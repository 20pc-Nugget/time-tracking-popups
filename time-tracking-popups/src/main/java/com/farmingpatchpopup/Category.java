package com.farmingpatchpopup;

import com.farmingpatchpopup.data.PatchImplementation;
import com.farmingpatchpopup.overlay.PopupTier;
import java.awt.Color;
import lombok.Getter;
import net.runelite.api.gameval.ItemID;

/**
 * A group of patches the user can switch on or off as a whole (and then pick apart in its own
 * settings section). Herb and coral patches are the two that start enabled.
 */
@Getter
public enum Category
{
	HERB("herb", "Herb patches", "Herbs", true, "Harvest", PopupTier.UNCOMMON, 0x3BB54A, ItemID.UNIDENTIFIED_RANARR, false,
		PatchImplementation.HERB),
	CORAL("coral", "Coral patches", "Coral", true, "Collect", PopupTier.RARE, 0xFF6B8B, ItemID.CORAL_ELKHORN, false,
		PatchImplementation.CORAL),
	ALLOTMENT("allotment", "Allotment patches", "Allotments", false, "Harvest", PopupTier.COMMON, 0x8DBF3A, ItemID.CABBAGE, false,
		PatchImplementation.ALLOTMENT),
	FLOWER("flower", "Flower patches", "Flowers", false, "Pick", PopupTier.UNCOMMON, 0xE05CC0, ItemID.FLOWERS_WATERFALL_QUEST_RED, false,
		PatchImplementation.FLOWER),
	HOPS("hops", "Hops patches", "Hops", false, "Harvest", PopupTier.COMMON, 0xD9A441, ItemID.BARLEY, false,
		PatchImplementation.HOPS),
	BUSH("bush", "Bush patches", "Bushes", false, "Pick", PopupTier.RARE, 0xB5385A, ItemID.POISONIVY_BERRIES, false,
		PatchImplementation.BUSH),
	GRAPES("grapes", "Grape vines", "Grapes", false, "Pick", PopupTier.RARE, 0x8E5BD6, ItemID.GRAPES, false,
		PatchImplementation.GRAPES),
	TREE("tree", "Tree patches", "Trees", false, "Chop", PopupTier.COMMON, 0x9A7B4F, ItemID.YEW_LOGS, false,
		PatchImplementation.TREE),
	FRUIT_TREE("fruittree", "Fruit tree patches", "Fruit trees", false, "Pick", PopupTier.UNCOMMON, 0xF08A24, ItemID.PINEAPPLE, false,
		PatchImplementation.FRUIT_TREE),
	HARDWOOD("hardwood", "Hardwood & redwood trees", "Hardwood trees", false, "Chop", PopupTier.VERY_RARE, 0xB8863B, ItemID.TEAK_LOGS, true,
		PatchImplementation.HARDWOOD_TREE, PatchImplementation.REDWOOD),
	SPIRIT_TREE("spirittree", "Spirit tree patches", "Spirit trees", false, "Chop", PopupTier.RARE, 0x5FD4C8, ItemID.SPIRIT_TREE_DUMMY, false,
		PatchImplementation.SPIRIT_TREE),
	CALQUAT("calquat", "Calquat patches", "Calquats", false, "Pick", PopupTier.UNCOMMON, 0xF0A030, ItemID.CALQUAT_FRUIT, false,
		PatchImplementation.CALQUAT),
	CACTUS("cactus", "Cactus patches", "Cacti", false, "Harvest", PopupTier.COMMON, 0x4DB36B, ItemID.CACTUS_SPINE, false,
		PatchImplementation.CACTUS),
	SEAWEED("seaweed", "Seaweed patches", "Seaweed", false, "Collect", PopupTier.VERY_RARE, 0x2E9BD6, ItemID.GIANT_SEAWEED, false,
		PatchImplementation.SEAWEED),
	SPECIAL("special", "Special patches", "Special patches", false, "Harvest", PopupTier.PET, 0xDC2367, ItemID.BITTERCAP_MUSHROOM, true,
		PatchImplementation.MUSHROOM, PatchImplementation.BELLADONNA, PatchImplementation.ANIMA, PatchImplementation.HESPORI,
		PatchImplementation.CELASTRUS, PatchImplementation.CRYSTAL_TREE),
	COMPOST("compost", "Compost bins", "Compost", false, "Collect", PopupTier.COMMON, 0x8B6B4A, ItemID.BUCKET_COMPOST, true,
		PatchImplementation.COMPOST, PatchImplementation.BIG_COMPOST),
	BIRDHOUSE("birdhouse", "Bird houses", "Bird houses", false, "Collect", PopupTier.UNCOMMON, 0x56A8E0, ItemID.BIRDHOUSE_OAK, false);

	/** Config key prefix, e.g. {@code herb} for {@code herb_enabled}. */
	private final String id;
	/** Name used for the settings section and the master toggle. */
	private final String displayName;
	/** Short plural used in popup text ("Herbs"). */
	private final String popupName;
	private final boolean enabledByDefault;
	/** What you do once it's ready: Harvest, Collect, Pick, Chop. */
	private final String verb;
	/** Which of the bundled panel designs this category uses. */
	private final PopupTier tier;
	private final Color color;
	/** Icon shown when the exact crop can't be worked out. */
	private final int fallbackItemId;
	/** Mixed categories name the patch type in each toggle ("Anima (Farming Guild)"). */
	private final boolean mixed;
	private final PatchImplementation[] implementations;

	Category(String id, String displayName, String popupName, boolean enabledByDefault, String verb, PopupTier tier,
		int rgb, int fallbackItemId, boolean mixed, PatchImplementation... implementations)
	{
		this.id = id;
		this.displayName = displayName;
		this.popupName = popupName;
		this.enabledByDefault = enabledByDefault;
		this.verb = verb;
		this.tier = tier;
		this.color = new Color(rgb);
		this.fallbackItemId = fallbackItemId;
		this.mixed = mixed;
		this.implementations = implementations;
	}

	public String masterKey()
	{
		return id + "_enabled";
	}

	public static Category of(PatchImplementation implementation)
	{
		for (Category category : values())
		{
			for (PatchImplementation candidate : category.implementations)
			{
				if (candidate == implementation)
				{
					return category;
				}
			}
		}
		return null;
	}
}
