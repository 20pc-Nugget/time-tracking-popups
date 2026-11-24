/*
 * Based on SoundManager from Collection Log Popup Enhanced, Copyright (c) 2026, SnakeSteak, BSD 2-Clause.
 */
package com.farmingpatchpopup.sound;

import com.farmingpatchpopup.FarmingPatchPopupConfig;
import com.farmingpatchpopup.overlay.PopupTier;
import java.io.File;
import java.util.EnumMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.RuneLite;
import net.runelite.client.audio.AudioPlayer;

/**
 * Plays the popup sound: one of the game's own town crier sound effects (played through the client,
 * so it follows the game's sound effect volume), or one of the clips Collection Log Popup Enhanced uses.
 *
 * <p>Users can override any clip by dropping a same-named .wav in {@link #SOUNDS_DIR}.
 */
@Slf4j
@Singleton
public class SoundManager
{
	private static final File SOUNDS_DIR = new File(RuneLite.RUNELITE_DIR, "time-tracking-popups/sounds");
	private static final Map<PopupTier, String> FILENAMES = new EnumMap<>(PopupTier.class);

	static
	{
		FILENAMES.put(PopupTier.COMMON, "common.wav");
		FILENAMES.put(PopupTier.UNCOMMON, "uncommon.wav");
		FILENAMES.put(PopupTier.RARE, "rare.wav");
		FILENAMES.put(PopupTier.VERY_RARE, "very_rare.wav");
		FILENAMES.put(PopupTier.PET, "pet.wav");
	}

	// Volume range the client's playSoundEffect(id, volume) takes (see SoundEffectVolume).
	private static final int MAX_GAME_VOLUME = 127;

	private final Client client;
	private final AudioPlayer audioPlayer;
	private final FarmingPatchPopupConfig config;

	@Inject
	public SoundManager(Client client, AudioPlayer audioPlayer, FarmingPatchPopupConfig config)
	{
		this.client = client;
		this.audioPlayer = audioPlayer;
		this.config = config;

		if (!SOUNDS_DIR.mkdirs() && !SOUNDS_DIR.isDirectory())
		{
			log.warn("Failed to create custom sounds directory {}", SOUNDS_DIR);
		}
	}

	/** Must be called on the client thread (the overlay's render does). */
	public void play(PopupTier panelTier)
	{
		int volume = config.soundVolume();
		if (!config.soundEnabled() || volume <= 0)
		{
			return;
		}

		SoundChoice choice = config.soundChoice();
		Integer gameSoundId = choice.getGameSoundId();
		if (gameSoundId != null)
		{
			playGameSound(gameSoundId, volume);
			return;
		}

		playClip(choice.resolve(panelTier), volume);
	}

	private void playGameSound(int soundId, int volumePercent)
	{
		try
		{
			if (config.playWhenGameSoundsMuted())
			{
				// Uses the game's own sound effect volume, or this volume if the game's effects are muted.
				client.playSoundEffect(soundId, Math.max(1, Math.min(MAX_GAME_VOLUME, volumePercent * MAX_GAME_VOLUME / 100)));
			}
			else
			{
				client.playSoundEffect(soundId);
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to play game sound {}", soundId, e);
		}
	}

	private void playClip(PopupTier tier, int volume)
	{
		String filename = FILENAMES.get(tier);

		try
		{
			File override = new File(SOUNDS_DIR, filename);
			if (override.isFile())
			{
				audioPlayer.play(override, gain(volume));
				return;
			}

			String resource = "/sounds/" + filename;
			if (SoundManager.class.getResource(resource) == null)
			{
				log.debug("No sound bundled for {}", resource);
				return;
			}

			audioPlayer.play(SoundManager.class, resource, gain(volume));
		}
		catch (Exception e)
		{
			log.warn("Failed to play {}", filename, e);
		}
	}

	// AudioPlayer's gain is a dB attenuation from the clip's mastered volume (0 dB = unchanged),
	// not a 0-1 linear multiplier, so the linear 0-100 slider is log-scaled to match.
	private static float gain(int volumePercent)
	{
		return 20f * (float) Math.log10(Math.min(100, volumePercent) / 100f);
	}
}
