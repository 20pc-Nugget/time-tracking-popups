package com.timetrackingpopups;

import com.timetrackingpopups.data.FarmingPatch;
import com.timetrackingpopups.data.FarmingRegion;
import com.timetrackingpopups.data.FarmingWorld;
import com.timetrackingpopups.data.PatchImplementation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.runelite.api.gameval.VarPlayerID;

/**
 * Every patch Time Tracking knows about, sorted into {@link Category categories}. The labels and
 * config keys built here have to match the ones in {@link TimeTrackingPopupsConfig} - that file is
 * generated from the same rules (see tools/generate_config.py).
 */
final class PatchCatalog
{
	private PatchCatalog()
	{
	}

	static List<PatchEntry> build()
	{
		List<PatchEntry> entries = new ArrayList<>();

		for (FarmingRegion region : new FarmingWorld().getAllRegions())
		{
			for (FarmingPatch patch : region.getPatches())
			{
				Category category = Category.of(patch.getImplementation());
				if (category == null)
				{
					continue;
				}
				String label = labelFor(category, region, patch);
				entries.add(new PatchEntry(category, category.getId() + "_" + slug(label), label, patch, -1));
			}
		}

		addBirdHouse(entries, "Mushroom Meadow (North)", VarPlayerID.BIRDHOUSE_TRANSMIT_A);
		addBirdHouse(entries, "Mushroom Meadow (South)", VarPlayerID.BIRDHOUSE_TRANSMIT_B);
		addBirdHouse(entries, "Verdant Valley (Northeast)", VarPlayerID.BIRDHOUSE_TRANSMIT_C);
		addBirdHouse(entries, "Verdant Valley (Southwest)", VarPlayerID.BIRDHOUSE_TRANSMIT_D);

		return Collections.unmodifiableList(entries);
	}

	private static void addBirdHouse(List<PatchEntry> entries, String label, int varp)
	{
		entries.add(new PatchEntry(Category.BIRDHOUSE, Category.BIRDHOUSE.getId() + "_" + slug(label), label, null, varp));
	}

	static String labelFor(Category category, FarmingRegion region, FarmingPatch patch)
	{
		String where = region.getName() + (patch.getName().isEmpty() ? "" : " " + patch.getName());
		if (!category.isMixed())
		{
			return where;
		}

		String type = typeName(patch.getImplementation());
		if (patch.getName().equalsIgnoreCase(type))
		{
			where = region.getName();
		}
		return type + " (" + where + ")";
	}

	private static String typeName(PatchImplementation implementation)
	{
		switch (implementation)
		{
			case MUSHROOM:
				return "Mushroom";
			case BELLADONNA:
				return "Belladonna";
			case ANIMA:
				return "Anima";
			case HESPORI:
				return "Hespori";
			case CELASTRUS:
				return "Celastrus";
			case CRYSTAL_TREE:
				return "Crystal tree";
			case HARDWOOD_TREE:
				return "Hardwood";
			case REDWOOD:
				return "Redwood";
			case COMPOST:
				return "Compost bin";
			case BIG_COMPOST:
				return "Giant compost bin";
			default:
				return implementation.name();
		}
	}

	static String slug(String label)
	{
		return label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}
}
