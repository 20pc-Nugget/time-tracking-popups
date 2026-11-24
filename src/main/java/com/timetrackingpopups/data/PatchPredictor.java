/*
 * Prediction logic adapted from RuneLite's Time Tracking plugin (FarmingTracker#predictPatch and
 * #getTickTime), Copyright (c) 2018 Abex and the RuneLite contributors, BSD 2-Clause.
 */
package com.timetrackingpopups.data;

import java.time.Instant;
import javax.annotation.Nullable;
import lombok.Value;
import net.runelite.client.config.ConfigManager;

/**
 * Works out what a farming patch is doing from the data Time Tracking has stored in the config
 * ({@code timetracking.<profile>.<regionID>.<varbitID> = <varbitValue>:<unixTime>}).
 */
public final class PatchPredictor
{
	public static final String GROUP = "timetracking";
	private static final String FARM_TICK_OFFSET = "farmTickOffset";
	private static final String FARM_TICK_OFFSET_PRECISION = "farmTickOffsetPrecision";

	private PatchPredictor()
	{
	}

	@Value
	public static class Prediction
	{
		Produce produce;
		CropState cropState;
		/** Unix seconds at which the patch finishes growing; 0 if it is already done. */
		long doneEstimate;
		int stage;
		int stages;
		/** The growth tick length in minutes (after the leagues adjustment). */
		int tickRate;
		/** The stage the patch was in when Time Tracking last saw it, before any growth since. */
		int observedStage;
	}

	@Nullable
	public static Prediction predict(FarmingPatch patch, ConfigManager configManager, boolean leaguesWorld)
	{
		long unixNow = Instant.now().getEpochSecond();

		String storedValue = configManager.getRSProfileConfiguration(GROUP, patch.configKey());
		if (storedValue == null)
		{
			return null;
		}

		long unixTime = 0;
		int value = 0;
		String[] parts = storedValue.split(":");
		if (parts.length == 2)
		{
			try
			{
				value = Integer.parseInt(parts[0]);
				unixTime = Long.parseLong(parts[1]);
			}
			catch (NumberFormatException e)
			{
				return null;
			}
		}

		if (unixTime <= 0)
		{
			return null;
		}

		PatchState state = patch.getImplementation().forVarbitValue(value);
		if (state == null)
		{
			return null;
		}

		int observedStage = state.getStage();
		int stage = observedStage;
		int stages = state.getStages();
		int tickrate = state.getTickRate();

		// Farming ticks on leagues worlds are 1 minute instead of 5
		if (leaguesWorld)
		{
			tickrate = tickrate / 5;
		}

		long doneEstimate = 0;
		if (tickrate > 0)
		{
			long tickNow = getTickTime(configManager, tickrate, 0, unixNow);
			long tickTime = getTickTime(configManager, tickrate, 0, unixTime);
			int delta = (int) (tickNow - tickTime) / (tickrate * 60);

			doneEstimate = getTickTime(configManager, tickrate, stages - 1 - stage, tickTime);

			stage += delta;
			if (stage >= stages)
			{
				stage = stages - 1;
			}
		}

		return new Prediction(state.getProduce(), state.getCropState(), doneEstimate, stage, stages, tickrate, observedStage);
	}

	/**
	 * Whether Time Tracking has observed a growth tick precisely enough to trust finish times for a
	 * crop with this tick length - the same rule Time Tracking applies before sending its own
	 * reminders. Crops that are already harvestable never depend on this.
	 */
	public static boolean isTimerCalibrated(ConfigManager configManager, int tickRate)
	{
		Integer precision = configManager.getRSProfileConfiguration(GROUP, FARM_TICK_OFFSET_PRECISION, int.class);
		Integer offset = configManager.getRSProfileConfiguration(GROUP, FARM_TICK_OFFSET, int.class);
		return precision != null && offset != null && (precision >= tickRate || precision >= 40);
	}

	private static long getTickTime(ConfigManager configManager, int tickRate, int ticks, long requestedTime)
	{
		Integer offsetPrecisionMins = configManager.getRSProfileConfiguration(GROUP, FARM_TICK_OFFSET_PRECISION, int.class);
		Integer offsetTimeMins = configManager.getRSProfileConfiguration(GROUP, FARM_TICK_OFFSET, int.class);

		// All offsets are negative but are stored as positive
		long calculatedOffsetTime = 0L;
		if (offsetPrecisionMins != null && offsetTimeMins != null && (offsetPrecisionMins >= tickRate || offsetPrecisionMins >= 40))
		{
			calculatedOffsetTime = (offsetTimeMins % tickRate) * 60;
		}

		// Calculate "now" as +offset seconds in the future so we calculate the correct ticks
		long unixNow = requestedTime + calculatedOffsetTime;

		// The time that the tick requested will happen
		long timeOfCurrentTick = unixNow - (unixNow % (tickRate * 60L));
		long timeOfGoalTick = timeOfCurrentTick + (ticks * tickRate * 60L);

		// Move ourselves back to real time
		return timeOfGoalTick - calculatedOffsetTime;
	}
}
