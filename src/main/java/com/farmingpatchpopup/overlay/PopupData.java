package com.farmingpatchpopup.overlay;

import java.awt.Color;
import java.util.List;
import lombok.Value;

/** Everything one popup shows. */
@Value
public class PopupData
{
	/** Small heading across the top, e.g. "Herb Patches Ready". */
	String caption;
	/** Big centred line, e.g. the crop name. */
	String title;
	/** Item ids to show in the icon slot. More than one cycles through them. */
	List<Integer> itemIds;
	/** Which bundled panel design to draw. */
	PopupTier tier;
	/** The colourful style's tint. */
	Color color;
	String leftLabel;
	String leftValue;
	String rightLabel;
	String rightValue;
}
