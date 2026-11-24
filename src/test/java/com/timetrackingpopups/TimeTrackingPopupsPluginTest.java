package com.timetrackingpopups;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Launches RuneLite in developer mode with this plugin loaded: {@code ./gradlew run}-style. */
public class TimeTrackingPopupsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(TimeTrackingPopupsPlugin.class);
		RuneLite.main(args);
	}
}
