package com.farmingpatchpopup;

import com.farmingpatchpopup.data.FarmingPatch;
import javax.annotation.Nullable;
import lombok.Value;

/** One individually toggleable patch (or bird house space). */
@Value
public class PatchEntry
{
	Category category;
	/** Config key of this patch's toggle, e.g. {@code herb_ardougne}. */
	String key;
	/** Text shown in the settings, e.g. "Ardougne". */
	String label;
	/** The Time Tracking patch, or null for a bird house. */
	@Nullable
	FarmingPatch patch;
	/** The bird house varp, or -1 for a farming patch. */
	int birdHouseVarp;

	public boolean isBirdHouse()
	{
		return patch == null;
	}
}
