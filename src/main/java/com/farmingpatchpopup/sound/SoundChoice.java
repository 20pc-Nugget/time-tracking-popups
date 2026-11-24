package com.farmingpatchpopup.sound;

import com.farmingpatchpopup.overlay.PopupTier;
import javax.annotation.Nullable;

/**
 * The sound the popup plays: one of the game's own town crier sound effects, or one of the clips
 * bundled from Collection Log Popup Enhanced.
 */
public enum SoundChoice
{
	// The game's own sound effects (net.runelite.api.gameval.SoundEffectID), played through the client.
	TOWN_CRIER_RING_BELL("Town crier: ring bell", 3814),
	TOWN_CRIER_BELL_UP("Town crier: bell up", 3813),
	TOWN_CRIER_BELL_DOWN("Town crier: bell down", 3817),
	TOWN_CRIER_SCRATCH_HEAD("Town crier: scratch head", 3816),
	TOWN_CRIER_BELL_UP_OLD("Town crier: bell up (old)", 3815),
	TOWN_CRIER_BELL_DOWN_OLD("Town crier: bell down (old)", 3818),

	// Collection Log Popup Enhanced's clips.
	MATCH("Clip: match popup colour", null, null),
	COMMON("Clip: common", null, PopupTier.COMMON),
	UNCOMMON("Clip: uncommon", null, PopupTier.UNCOMMON),
	RARE("Clip: rare", null, PopupTier.RARE),
	VERY_RARE("Clip: very rare", null, PopupTier.VERY_RARE),
	PET("Clip: pet", null, PopupTier.PET);

	private final String label;
	@Nullable
	private final Integer gameSoundId;
	@Nullable
	private final PopupTier tier;

	SoundChoice(String label, int gameSoundId)
	{
		this(label, gameSoundId, null);
	}

	SoundChoice(String label, @Nullable Integer gameSoundId, @Nullable PopupTier tier)
	{
		this.label = label;
		this.gameSoundId = gameSoundId;
		this.tier = tier;
	}

	/** The game sound effect id, or null when this choice is one of the bundled clips. */
	@Nullable
	public Integer getGameSoundId()
	{
		return gameSoundId;
	}

	/** The bundled clip's tier, given the tier of the panel being shown. Only meaningful for clip choices. */
	public PopupTier resolve(PopupTier panelTier)
	{
		return tier != null ? tier : panelTier;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
