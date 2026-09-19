# GamemodeSwitcher

Switch gamemodes with double-tap gestures, an in-game selector, or `/gsw set <mode>`. Gesture bindings, cooldowns, and feedback are configurable, and players can choose their own language and feedback preferences.

Supports Spigot 1.20.1 and newer compatible servers, including Paper, Purpur, Leaf, Folia, and Canvas. The plugin requires Java 17 or newer, though your server may require a newer version.

Product documentation lives in the [central wiki source](https://github.com/VolmitSoftware/docs/blob/main/gamemodeswitcher.md).

## Commands

- `/gsw` shows help
- `/gsw menu` opens the gamemode selector
- `/gsw set <mode>` changes your gamemode
- `/gsw config` opens the settings editor
- `/gsw language` opens language settings

Switching requires `gamemodeswitcher.use` and the destination's `gamemodeswitcher.mode.<mode>` permission. These permissions default to operators. Configuration and installed language files reload automatically when saved.

## Build

Build with JDK 25:

```bash
./gradlew build
```

On Windows, use `.\gradlew.bat build`. The plugin targets Java 17. The jar is written to `build/libs/` and copied to `../BUILDS/GamemodeSwitcher.jar`.
