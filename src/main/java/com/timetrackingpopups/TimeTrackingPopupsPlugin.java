package com.timetrackingpopups;

import com.timetrackingpopups.data.CropState;
import com.timetrackingpopups.data.PatchPredictor;
import com.timetrackingpopups.data.Produce;
import com.timetrackingpopups.overlay.PopupData;
import com.timetrackingpopups.overlay.PopupOverlay;
import com.timetrackingpopups.overlay.PopupTier;
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
public class TimeTrackingPopupsPlugin extends Plugin
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
	/** Stands in for "finish time not known" (diseased, or Time Tracking has not calibrated its timer yet). */
	private static final long UNKNOWN = Long.MAX_VALUE;
	private static final String SOON_PREFIX = "soon_";
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
	private TimeTrackingPopupsConfig config;

	private List<PatchEntry> entries = new ArrayList<>();
	private int tickCounter;
	/** True until the first successful check of a session; see {@link TimeTrackingPopupsConfig#notifyAtLogin()}. */
	private boolean firstCheck = true;

	@Provides
	TimeTrackingPopupsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TimeTrackingPopupsConfig.class);
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
		if (!TimeTrackingPopupsConfig.GROUP.equals(event.getGroup()) || !"testPopup".equals(event.getKey()))
		{
			return;
		}

		if (config.testPopup())
		{
			clientThread.invokeLater(this::showTestPopup);
			// Un-tick the box again so it works like a button.
			configManager.setConfiguration(TimeTrackingPopupsConfig.GROUP, "testPopup", false);
		}
	}

	// ------------------------------------------------------------------ checking

	/** The three things a popup can announce. */
	private enum Kind
	{
		READY, SOON, DISEASED
	}

	/**
	 * What one enabled patch is doing right now. Empty and dead patches never get a Status, so they
	 * can't hold anything back.
	 */
	private static final class Status
	{
		private final PatchEntry entry;
		private final long secondsLeft;
		private final boolean done;
		private final boolean diseased;
		private final int itemId;
		private final String cropName;

		/** @param secondsLeft seconds until it finishes, 0 if it already has, {@link #UNKNOWN} if that can't be told */
		private Status(PatchEntry entry, long secondsLeft, boolean diseased, int itemId, String cropName)
		{
			this.entry = entry;
			this.secondsLeft = secondsLeft;
			this.done = !diseased && secondsLeft == 0;
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

		List<Event> soon = config.notifyEarly() ? findNewlySoon(byCategory) : new ArrayList<>();
		List<Event> ready = config.notifyWhenReady() ? findNewlyReady(byCategory) : new ArrayList<>();
		List<Event> diseased = config.notifyWhenDiseased() ? findNewlyDiseased(byCategory) : new ArrayList<>();

		if (announce)
		{
			show(soon, Kind.SOON);
			show(ready, Kind.READY);
			show(diseased, Kind.DISEASED);
		}

		firstCheck = false;
	}

	/**
	 * The early warning: a patch type is "ready soon" once its last growing patch is within the chosen
	 * number of minutes of finishing. Like the ready popup it waits for every selected patch of the type,
	 * is announced once, and is announced again only after the type has stopped counting down
	 * (finished and been harvested, or replanted).
	 */
	private List<Event> findNewlySoon(Map<Category, List<Status>> byCategory)
	{
		long windowSeconds = config.earlyMinutes() * 60L;
		List<Event> events = new ArrayList<>();
		for (Category category : Category.values())
		{
			List<Status> statuses = byCategory.getOrDefault(category, new ArrayList<>());
			String markerKey = SOON_PREFIX + category.getId();
			// Every patch has a known finish time inside the window, and at least one is still growing.
			boolean soon = !statuses.isEmpty()
				&& statuses.stream().allMatch(st -> st.secondsLeft <= windowSeconds)
				&& statuses.stream().anyMatch(st -> st.secondsLeft > 0);

			if (!soon)
			{
				if (configManager.getRSProfileConfiguration(TimeTrackingPopupsConfig.GROUP, markerKey) != null)
				{
					configManager.unsetRSProfileConfiguration(TimeTrackingPopupsConfig.GROUP, markerKey);
				}
				continue;
			}

			if (configManager.getRSProfileConfiguration(TimeTrackingPopupsConfig.GROUP, markerKey) == null)
			{
				configManager.setRSProfileConfiguration(TimeTrackingPopupsConfig.GROUP, markerKey, "1");
				events.add(new Event(category, statuses));
			}
		}
		return events;
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
				if (configManager.getRSProfileConfiguration(TimeTrackingPopupsConfig.GROUP, markerKey) != null)
				{
					configManager.unsetRSProfileConfiguration(TimeTrackingPopupsConfig.GROUP, markerKey);
				}
				continue;
			}

			// Harvesting some of the patches (so fewer remain) must not announce the type again.
			if (configManager.getRSProfileConfiguration(TimeTrackingPopupsConfig.GROUP, markerKey) == null)
			{
				configManager.setRSProfileConfiguration(TimeTrackingPopupsConfig.GROUP, markerKey, "1");
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
				if (configManager.getRSProfileConfiguration(TimeTrackingPopupsConfig.GROUP, markerKey) == null)
				{
					configManager.setRSProfileConfiguration(TimeTrackingPopupsConfig.GROUP, markerKey, "1");
					fresh.computeIfAbsent(category, c -> new ArrayList<>()).add(status);
				}
			}
		}

		// Cured, dead, harvested or switched off: forget it so a later outbreak is announced again.
		for (PatchEntry entry : entries)
		{
			String markerKey = DISEASED_PREFIX + entry.getKey();
			if (!diseasedKeys.contains(entry.getKey())
				&& configManager.getRSProfileConfiguration(TimeTrackingPopupsConfig.GROUP, markerKey) != null)
			{
				configManager.unsetRSProfileConfiguration(TimeTrackingPopupsConfig.GROUP, markerKey);
			}
		}

		List<Event> events = new ArrayList<>();
		fresh.forEach((category, statuses) -> events.add(new Event(category, statuses)));
		return events;
	}

	/** One popup per patch type, or a single "multiple resources" popup when several happen together. */
	private void show(List<Event> events, Kind kind)
	{
		if (events.isEmpty())
		{
			return;
		}
		if (events.size() > 1 && config.combineMultiple())
		{
			overlay.enqueue(buildCombinedPopup(events, kind));
			return;
		}
		for (Event event : events)
		{
			overlay.enqueue(buildPopup(event.category, event.statuses, kind));
		}
	}

	private boolean isEnabled(PatchEntry entry)
	{
		Category category = entry.getCategory();
		Boolean master = configManager.getConfiguration(TimeTrackingPopupsConfig.GROUP, category.masterKey(), Boolean.class);
		if (!(master != null ? master : category.isEnabledByDefault()))
		{
			return false;
		}
		Boolean individual = configManager.getConfiguration(TimeTrackingPopupsConfig.GROUP, entry.getKey(), Boolean.class);
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
			return new Status(entry, UNKNOWN, true, produce.getItemID(), produce.getName());
		}
		if (cropState != CropState.GROWING && cropState != CropState.HARVESTABLE)
		{
			return null; // empty, filling or dead
		}

		long secondsLeft;
		boolean estimated = prediction.getTickRate() > 0 && prediction.getObservedStage() < prediction.getStages() - 1;
		if (estimated && config.waitForAccurateTimers() && !PatchPredictor.isTimerCalibrated(configManager, prediction.getTickRate()))
		{
			secondsLeft = UNKNOWN;
		}
		else
		{
			secondsLeft = Math.max(0, prediction.getDoneEstimate() - now);
		}

		return new Status(entry, secondsLeft, false, produce.getItemID(), produce.getName());
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
		long secondsLeft = Math.max(0, timestamp + BIRD_HOUSE_SECONDS - now);
		return new Status(entry, secondsLeft, false, BIRD_HOUSE_ITEMS[index], BIRD_HOUSE_NAMES[index]);
	}

	private boolean isLeaguesWorld()
	{
		EnumSet<WorldType> worldTypes = client.getWorldType();
		return worldTypes.contains(WorldType.SEASONAL) && !worldTypes.contains(WorldType.DEADMAN);
	}

	// ------------------------------------------------------------------ popup text

	/** The popup for one patch type. */
	private PopupData buildPopup(Category category, List<Status> statuses, Kind kind)
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
		String caption = titleCase(category.getDisplayName()) + (kind == Kind.DISEASED ? " Diseased" : kind == Kind.SOON ? " Ready Soon" : " Ready");
		String rightLabel = kind == Kind.DISEASED ? "Needs" : kind == Kind.SOON ? "Ready in" : "Ready to";
		String rightValue = kind == Kind.DISEASED ? "Curing" : kind == Kind.SOON ? formatTime(longestWait(ordered)) : category.getVerb();
		return new PopupData(
			caption,
			title,
			new ArrayList<>(icons),
			category.getTier(),
			kind == Kind.DISEASED ? DISEASED_COLOR : category.getColor(),
			single ? "Patch" : "Patches",
			single ? ordered.get(0).entry.getLabel() : String.valueOf(ordered.size()),
			rightLabel,
			rightValue);
	}

	/** The popup for several patch types at once: "Multiple resources ready". */
	private PopupData buildCombinedPopup(List<Event> events, Kind kind)
	{
		Set<Integer> icons = new LinkedHashSet<>();
		int patches = 0;
		long longestWait = 0;
		for (Event event : events)
		{
			patches += event.statuses.size();
			longestWait = Math.max(longestWait, longestWait(event.statuses));
			for (Status status : sorted(event.statuses))
			{
				icons.add(status.itemId);
			}
		}

		boolean diseased = kind == Kind.DISEASED;
		boolean soon = kind == Kind.SOON;
		return new PopupData(
			diseased ? "Farming Alert" : "Farming Patches",
			diseased ? "Multiple Crops Diseased" : soon ? "Multiple Resources Soon" : "Multiple Resources Ready",
			new ArrayList<>(icons),
			diseased ? PopupTier.RARE : PopupTier.UNCOMMON,
			diseased ? DISEASED_COLOR : FARMING_GREEN,
			"Patches",
			String.valueOf(patches),
			diseased ? "Needs" : soon ? "Ready in" : "Types",
			diseased ? "Curing" : soon ? formatTime(longestWait) : String.valueOf(events.size()));
	}

	/** Seconds until the last of these patches finishes. */
	private static long longestWait(List<Status> statuses)
	{
		return statuses.stream().mapToLong(st -> st.secondsLeft == UNKNOWN ? 0 : st.secondsLeft).max().orElse(0);
	}

	/** "12 min", "1 h 5 min" - rounded up, because the popup is about being there in time. */
	private static String formatTime(long seconds)
	{
		long minutes = Math.max(1, (seconds + 59) / 60);
		if (minutes < 60)
		{
			return minutes + " min";
		}
		return (minutes / 60) + " h " + (minutes % 60) + " min";
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
			titleCase(Category.HERB.getDisplayName()) + " Ready Soon",
			"Mixed herbs",
			icons,
			Category.HERB.getTier(),
			Category.HERB.getColor(),
			"Patches",
			"9",
			"Ready in",
			formatTime(config.earlyMinutes() * 60L)));
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
