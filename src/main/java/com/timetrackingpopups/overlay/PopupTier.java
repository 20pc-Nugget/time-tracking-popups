package com.timetrackingpopups.overlay;

public enum PopupTier
{
	COMMON("Common"),
	UNCOMMON("Uncommon"),
	RARE("Rare"),
	VERY_RARE("Very Rare"),
	PET("Pet");

	private final String label;

	PopupTier(String label)
	{
		this.label = label;
	}

	public String getLabel()
	{
		return label;
	}
}
