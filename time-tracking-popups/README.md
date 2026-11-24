# Time Tracking Popups

A RuneLite plugin that shows a **Collection Log Popup Enhanced style popup** (with the game's town crier
sound by default) when the farming patches, compost bins and bird houses you pick are ready or diseased. It reads the data RuneLite's **Time Tracking** plugin already stores, so
Time Tracking must be enabled (the plugin turns it on for you).

## What triggers a popup

| Event | When |
| --- | --- |
| **Ready** | Every *selected* patch of a type has finished growing. One patch still growing (or diseased) holds that whole type back. Empty and dead patches are ignored. |
| **Diseased** | A selected crop becomes diseased. Announced once until it is cured. |
| **Multiple resources** | Several types become ready (or diseased) at the same moment: one "Multiple Resources Ready" popup instead of one each. |

Each popup is announced once, and again only after the type has stopped being ready
(replanted, or nothing left growing). Harvesting some of the patches does not announce it again.

## Settings

Everything is a toggle. Sections: **Notifications**, **Popup**, **Sound**, then one section per
patch type (herbs, coral, allotments, flowers, hops, bushes, grapes, trees, fruit trees,
hardwood/redwood, spirit trees, calquat, cactus, seaweed, special patches, compost bins, bird houses).

Inside each patch-type section there is a master switch ("Notify for herb patches") and, once it is on,
a toggle for every individual patch ("• Ardougne", "• Catherby", ...). **Herb and coral patches are on by default.**

Notifications: notify when ready, notify when diseased, combine multiple types, wait for accurate
timers, notify at login. Popup: style (colourful / neutral / sound only), layout (extended /
compact), display time, size, text rendering, and a **Show test popup** tick-box. Sound: on/off,
which sound, volume. The default is the game's own **town crier ring bell**; you can pick any of the
town crier sound effects (ring bell, bell up, bell down, scratch head, and the old bell up/down), or the
Collection Log Popup Enhanced clips. Game sounds follow your in-game sound effect volume, and can still play when
effects are muted. Custom clip sounds: put `common.wav`, `uncommon.wav`, `rare.wav`, `very_rare.wav`
or `pet.wav` in `~/.runelite/time-tracking-popups/sounds`.

The icon on the popup is the crop that finished (ranarr, elkhorn coral, ...). When different crops
finish together the icon cycles through them.

## Notes and limits

* Time Tracking only records a patch while you are near it, so disease is only noticed then, and a patch
  you have never visited with Time Tracking on is not tracked.
* **Wait for accurate timers** (on by default) mirrors Time Tracking's own reminders: finish times are
  trusted once Time Tracking has seen a growth tick. Patches already showing as harvestable are not affected.
* Patch data in `src/main/java/com/farmingpatchpopup/data` is a copy of Time Tracking's tables. When
  RuneLite adds patches or crops, re-copy them and run the generator below.

## Developing

```
python3 tools/generate_config.py   # regenerates FarmingPatchPopupConfig.java (the per-patch toggles)
```

`./gradlew build` builds and tests it, and `./gradlew run` starts a RuneLite developer client with the
plugin loaded (the Gradle files come from RuneLite's example plugin). `ConfigConsistencyTest` fails if the
generated config and the patch list drift apart.

Credits: popup art, sounds and rendering from Collection Log Popup Enhanced by SnakeSteak; farming
data from RuneLite Time Tracking. All BSD 2-Clause - see `LICENSE`.
