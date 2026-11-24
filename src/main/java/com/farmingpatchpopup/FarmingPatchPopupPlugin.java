package com.farmingpatchpopup;

import com.farmingpatchpopup.data.CropState;
import com.farmingpatchpopup.data.PatchPredictor;
import com.farmingpatchpopup.data.Produce;
import com.farmingpatchpopup.overlay.PopupData;
import com.farmingpatchpopup.overlay.PopupOverlay;
import com.farmingpatchpopup.overlay.PopupTier;
import com.google.inject.Provides;
import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
		name = "Time Tracking Popups",
		description = "Collection log style popup and sound when your farming patches, compost bins and bird houses are ready or diseased. Needs the Time Tracking plugin enabled",
		tags = {"farming", "time tracking", "herb", "coral", "patch", "popup", "notification", "reminder", "birdhouse"}
)
public class FarmingPatchPopupPlugin extends Plugin
{
	/** How often the patches are re-checked, in game ticks (0.6s each). */
	private static final int CHECK_EVERY_TICKS = 5;

	private static final String BIRD_HOUSE_KEY_PREFIX = "birdhouse.";
	private static final long BIRD_HOUSE_SECONDS = 50 * 60;
	private static final int BIRD_HOUSE_MAX_VARP = 9 * 3;
	private static final int[] BIRD_HOUSE_ITEMS = {
			ItemID.BIRDHOUSE_NORMAL, ItemID.BIRDHOUSE_OAK, ItemID.BIRDHOUSE_WILLOW, ItemID.BIRDHOUSE_TEAK, ItemID.BIRDHOUSE_MAPLE,
			ItemID.BIRDHOUSE_MAHOGANY, ItemID.BIRDHOUSE_YEW, ItemID.BIRDHOUSE_MAGIC, ItemID.BIRDHOUSE_REDWOOD
	};
	private static final String[] BIRD_HOUSE_NAMES = {
			"Bird house", "Oak bird house", "Willow bird house", "Teak bird house", "Maple bird house",
			"Mahogany bird house", "Yew bird house", "Magic bird house", "Redwood bird house"
	};

	// Markers kept in this plugin's own config group (per RuneScape profile) so each ready / diseased
	// spell is announced once. They are removed again as soon as the spell is over.
	private static final String READY_PREFIX = "ready_";
	private static final String DISEASED_PREFIX = "diseased_";
	private static final Color DISEASED_COLOR = new Color(0xE04040);
	private static final Color FARMING_GREEN = new Color(0x3BB54A);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private PopupOverlay overlay;

	@Inject
	private FarmingPatchPopupConfig config;

	private List<PatchEntry> entries = new ArrayList<>();
	private int tickCounter;
	/** True until the first successful check of a session; see {@link FarmingPatchPopupConfig#notifyAtLogin()}. */
	private boolean firstCheck = true;

	@Provides
	FarmingPatchPopupConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FarmingPatchPopupConfig.class);
	}

	@Override
	protected void startUp()
	{
		entries = PatchCatalog.build();
		firstCheck = true;
		tickCounter = 0;
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlay.clear();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// LOGGED_IN is also sent after every loading screen, so only the login itself counts as a new session.
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.LOGGING_IN)
		{
			firstCheck = true;
			overlay.clear();
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		firstCheck = true;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (++tickCounter % CHECK_EVERY_TICKS == 0)
		{
			check();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!FarmingPatchPopupConfig.GROUP.equals(event.getGroup()) || !"testPopup".equals(event.getKey()))
		{
			return;
		}

		if (config.testPopup())
		{
			clientThread.invokeLater(this::showTestPopup);
			// Un-tick the box again so it works like a button.
			configManager.setConfiguration(FarmingPatchPopupConfig.GROUP, "testPopup", false);
		}
	}

	// ------------------------------------------------------------------ checking

	/**
	 * What one enabled patch is doing right now. Empty and dead patches never get a Status, so they
	 * can't hold anything back.
	 */
	private static final class Status
	{
		private final PatchEntry entry;
		private final boolean done;
		private final boolean diseased;
		private final int itemId;
		private final String cropName;

		private Status(PatchEntry entry, boolean done, boolean diseased, int itemId, String cropName)
		{
			this.entry = entry;
			this.done = done;
			this.diseased = diseased;
			this.itemId = itemId;
			this.cropName = cropName;
		}
	}

	/** One patch type that has just become ready (or diseased), with the patches involved. */
	private static final class Event
	{
		private final Category category;
		private final List<Status> statuses;

		private Event(Category category, List<Status> statuses)
		{
			this.category = category;
			this.statuses = statuses;
		}
	}

	private void check()
	{
		if (client.getGameState() != GameState.LOGGED_IN || configManager.getRSProfileKey() == null)
		{
			return;
		}

		long now = Instant.now().getEpochSecond();
		boolean leagues = isLeaguesWorld();

		Map<Category, List<Status>> byCategory = new EnumMap<>(Category.class);
		for (PatchEntry entry : entries)
		{
			if (!isEnabled(entry))
			{
				continue;
			}
			Status status = statusOf(entry, now, leagues);
			if (status != null)
			{
				byCategory.computeIfAbsent(entry.getCategory(), c -> new ArrayList<>()).add(status);
			}
		}

		// Nothing is shown for things that were already true when the session started, if the user asked for that.
		boolean announce = !(firstCheck && !config.notifyAtLogin());

		List<Event> ready = config.notifyWhenReady() ? findNewlyReady(byCategory) : new ArrayList<>();
		List<Event> diseased = config.notifyWhenDiseased() ? findNewlyDiseased(byCategory) : new ArrayList<>();

		if (announce)
		{
			show(ready, false);
			show(diseased, true);
		}

		firstCheck = false;
	}

	/**
	 * A patch type is ready when it has at least one growing patch and every one of them is done - a
	 * single patch still growing (or diseased) holds the whole type back. It is announced once, and
	 * announced again only after it has stopped being ready (harvested, replanted).
	 */
	private List<Event> findNewlyReady(Map<Category, List<Status>> byCategory)
	{
		List<Event> events = new ArrayList<>();
		for (Category category : Category.values())
		{
			List<Status> statuses = byCategory.getOrDefault(category, new ArrayList<>());
			String markerKey = READY_PREFIX + category.getId();
			boolean ready = !statuses.isEmpty() && statuses.stream().allMatch(s -> s.done);

			if (!ready)
			{
				// Forget the last announcement so the next time this type finishes it is announced again.
				if (configManager.getRSProfileConfiguration(FarmingPatchPopupConfig.GROUP, markerKey) != null)
				{
					configManager.unsetRSProfileConfiguration(FarmingPatchPopupConfig.GROUP, markerKey);
				}
				continue;
			}

			// Harvesting some of the patches (so fewer remain) must not announce the type again.
			if (configManager.getRSProfileConfiguration(FarmingPatchPopupConfig.GROUP, markerKey) == null)
			{
				configManager.setRSProfileConfiguration(FarmingPatchPopupConfig.GROUP, markerKey, "1");
				events.add(new Event(category, statuses));
			}
		}
		return events;
	}

	/** Patches that have become diseased since they were last checked - each is announced once until cured. */
	private List<Event> findNewlyDiseased(Map<Category, List<Status>> byCategory)
	{
		Map<Category, List<Status>> fresh = new EnumMap<>(Category.class);
		Set<String> diseasedKeys = new LinkedHashSet<>();

		for (Category category : Category.values())
		{
			for (Status status : byCategory.getOrDefault(category, new ArrayList<>()))
			{
				if (!status.diseased)
				{
					continue;
				}
				String key = status.entry.getKey();
				diseasedKeys.add(key);
				String markerKey = DISEASED_PREFIX + key;
				if (configManager.getRSProfileConfiguration(FarmingPatchPopupConfig.GROUP, markerKey) == null)
				{
					configManager.setRSProfileConfiguration(FarmingPatchPopupConfig.GROUP, markerKey, "1");
					fresh.computeIfAbsent(category, c -> new ArrayList<>()).add(status);
				}
			}
		}

		// Cured, dead, harvested or switched off: forget it so a later outbreak is announced again.
		for (PatchEntry entry : entries)
		{
			String markerKey = DISEASED_PREFIX + entry.getKey();
			if (!diseasedKeys.contains(entry.getKey())
					&& configManager.getRSProfileConfiguration(FarmingPatchPopupConfig.GROUP, markerKey) != null)
			{
				configManager.unsetRSProfileConfiguration(FarmingPatchPopupConfig.GROUP, markerKey);
			}
		}

		List<Event> events = new ArrayList<>();
		fresh.forEach((category, statuses) -> events.add(new Event(category, statuses)));
		return events;
	}

	/** One popup per patch type, or a single "multiple resources" popup when several happen together. */
	private void show(List<Event> events, boolean diseased)
	{
		if (events.isEmpty())
		{
			return;
		}
		if (events.size() > 1 && config.combineMultiple())
		{
			overlay.enqueue(buildCombinedPopup(events, diseased));
			return;
		}
		for (Event event : events)
		{
			overlay.enqueue(buildPopup(event.category, event.statuses, diseased));
		}
	}

	private boolean isEnabled(PatchEntry entry)
	{
		Category category = entry.getCategory();
		Boolean master = configManager.getConfiguration(FarmingPatchPopupConfig.GROUP, category.masterKey(), Boolean.class);
		if (!(master != null ? master : category.isEnabledByDefault()))
		{
			return false;
		}
		Boolean individual = configManager.getConfiguration(FarmingPatchPopupConfig.GROUP, entry.getKey(), Boolean.class);
		return individual == null || individual;
	}

	private Status statusOf(PatchEntry entry, long now, boolean leagues)
	{
		return entry.isBirdHouse() ? birdHouseStatus(entry, now) : farmingStatus(entry, now, leagues);
	}

	private Status farmingStatus(PatchEntry entry, long now, boolean leagues)
	{
		PatchPredictor.Prediction prediction = PatchPredictor.predict(entry.getPatch(), configManager, leagues);
		if (prediction == null)
		{
			return null; // never seen by Time Tracking
		}

		Produce produce = prediction.getProduce();
		CropState cropState = prediction.getCropState();
		if (produce == Produce.WEEDS || produce == Produce.SCARECROW || produce.getItemID() < 0)
		{
			return null; // weeds or an unknown state
		}
		if (cropState == CropState.DISEASED)
		{
			// Not finished, and worth a warning - but it still counts as growing, so it holds its type back.
			return new Status(entry, false, true, produce.getItemID(), produce.getName());
		}
		if (cropState != CropState.GROWING && cropState != CropState.HARVESTABLE)
		{
			return null; // empty, filling or dead
		}

		boolean done;
		boolean estimated = prediction.getTickRate() > 0 && prediction.getObservedStage() < prediction.getStages() - 1;
		if (estimated && config.waitForAccurateTimers() && !PatchPredictor.isTimerCalibrated(configManager, prediction.getTickRate()))
		{
			done = false;
		}
		else
		{
			done = now >= prediction.getDoneEstimate();
		}

		return new Status(entry, done, false, produce.getItemID(), produce.getName());
	}

	private Status birdHouseStatus(PatchEntry entry, long now)
	{
		String stored = configManager.getRSProfileConfiguration(PatchPredictor.GROUP, BIRD_HOUSE_KEY_PREFIX + entry.getBirdHouseVarp());
		if (stored == null)
		{
			return null;
		}

		String[] parts = stored.split(":");
		if (parts.length != 2)
		{
			return null;
		}

		int varp;
		long timestamp;
		try
		{
			varp = Integer.parseInt(parts[0]);
			timestamp = Long.parseLong(parts[1]);
		}
		catch (NumberFormatException e)
		{
			return null;
		}

		// 0 is empty, 3n+1 / 3n+2 are built but not seeded, 3n is seeded (see Time Tracking's BirdHouseState)
		if (varp <= 0 || varp > BIRD_HOUSE_MAX_VARP || varp % 3 != 0)
		{
			return null;
		}

		int index = (varp - 1) / 3;
		boolean done = now >= timestamp + BIRD_HOUSE_SECONDS;
		return new Status(entry, done, false, BIRD_HOUSE_ITEMS[index], BIRD_HOUSE_NAMES[index]);
	}

	private boolean isLeaguesWorld()
	{
		EnumSet<WorldType> worldTypes = client.getWorldType();
		return worldTypes.contains(WorldType.SEASONAL) && !worldTypes.contains(WorldType.DEADMAN);
	}

	// ------------------------------------------------------------------ popup text

	/** The popup for one patch type. */
	private PopupData buildPopup(Category category, List<Status> statuses, boolean diseased)
	{
		List<Status> ordered = sorted(statuses);

		Set<String> crops = new LinkedHashSet<>();
		Set<Integer> icons = new LinkedHashSet<>();
		for (Status status : ordered)
		{
			crops.add(status.cropName);
			icons.add(status.itemId);
		}

		String title;
		if (crops.size() == 1)
		{
			title = crops.iterator().next();
		}
		else if (crops.size() == 2)
		{
			title = joinNames(new ArrayList<>(crops));
		}
		else
		{
			title = "Mixed " + category.getPopupName().toLowerCase(Locale.ROOT);
		}

		boolean single = ordered.size() == 1;
		return new PopupData(
				titleCase(category.getDisplayName()) + (diseased ? " Diseased" : " Ready"),
				title,
				new ArrayList<>(icons),
				category.getTier(),
				diseased ? DISEASED_COLOR : category.getColor(),
				single ? "Patch" : "Patches",
				single ? ordered.get(0).entry.getLabel() : String.valueOf(ordered.size()),
				diseased ? "Needs" : "Ready to",
				diseased ? "Curing" : category.getVerb());
	}

	/** The popup for several patch types at once: "Multiple resources ready". */
	private PopupData buildCombinedPopup(List<Event> events, boolean diseased)
	{
		Set<Integer> icons = new LinkedHashSet<>();
		int patches = 0;
		for (Event event : events)
		{
			patches += event.statuses.size();
			for (Status status : sorted(event.statuses))
			{
				icons.add(status.itemId);
			}
		}

		return new PopupData(
				diseased ? "Farming Alert" : "Farming Patches",
				diseased ? "Multiple Crops Diseased" : "Multiple Resources Ready",
				new ArrayList<>(icons),
				diseased ? PopupTier.RARE : PopupTier.UNCOMMON,
				diseased ? DISEASED_COLOR : FARMING_GREEN,
				"Patches",
				String.valueOf(patches),
				diseased ? "Needs" : "Types",
				diseased ? "Curing" : String.valueOf(events.size()));
	}

	private static List<Status> sorted(List<Status> statuses)
	{
		List<Status> ordered = new ArrayList<>(statuses);
		ordered.sort(Comparator.comparing((Status s) -> s.entry.getCategory().ordinal()).thenComparing(s -> s.entry.getLabel()));
		return ordered;
	}

	private static String joinNames(List<String> names)
	{
		return String.join(" & ", names);
	}

	private static String titleCase(String text)
	{
		StringBuilder builder = new StringBuilder();
		for (String word : text.split(" "))
		{
			if (builder.length() > 0)
			{
				builder.append(' ');
			}
			builder.append(word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
		}
		return builder.toString();
	}

	private void showTestPopup()
	{
		List<Integer> icons = Arrays.asList(Produce.RANARR.getItemID(), Produce.SNAPDRAGON.getItemID(), Produce.TORSTOL.getItemID());
		overlay.enqueue(new PopupData(
				titleCase(Category.HERB.getDisplayName()) + " Ready",
				"Mixed herbs",
				icons,
				Category.HERB.getTier(),
				Category.HERB.getColor(),
				"Patches",
				"9",
				"Ready to",
				Category.HERB.getVerb()));
		overlay.enqueue(new PopupData(
				titleCase(Category.HERB.getDisplayName()) + " Diseased",
				Produce.RANARR.getName(),
				Arrays.asList(Produce.RANARR.getItemID()),
				Category.HERB.getTier(),
				DISEASED_COLOR,
				"Patch",
				"Catherby",
				"Needs",
				"Curing"));
	}
}