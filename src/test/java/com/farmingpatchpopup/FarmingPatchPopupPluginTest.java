package com.farmingpatchpopup;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Launches RuneLite in developer mode with this plugin loaded: {@code ./gradlew run}-style. */
public class FarmingPatchPopupPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(FarmingPatchPopupPlugin.class);
		RuneLite.main(args);
	}
}
