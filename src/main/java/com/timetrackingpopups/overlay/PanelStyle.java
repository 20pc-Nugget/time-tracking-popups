package com.timetrackingpopups.overlay;

/**
 * Which bundled panel art the popup draws. Colourful art is recoloured to the per-tier colours;
 * neutral art is drawn exactly as bundled. Sound only draws nothing and just plays the sound.
 */
public enum PanelStyle
{
	COLORFUL("Colourful", "Colorful"),
	NEUTRAL("Neutral", "Neutral"),
	// Never drawn - the directory only keeps the art loading path uniform.
	SOUND_ONLY("Sound only", "Colorful");

	private final String label;
	private final String resourceDirectory;

	PanelStyle(String label, String resourceDirectory)
	{
		this.label = label;
		this.resourceDirectory = resourceDirectory;
	}

	String resource(String name)
	{
		return resourceDirectory + "/" + name;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
