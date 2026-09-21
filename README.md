# GamemodeSwitcher

Switch gamemodes with double-tap gestures, an in-game selector, or `/gsw set <mode>`. Gesture bindings, cooldowns, and feedback are configurable, and players can choose their own language and feedback preferences.

Requires Minecraft 26.1 or newer and Java 25 or newer. Targets Spigot and compatible Paper, Purpur, Leaf, Folia, and Canvas servers.

## Commands

- `/gsw` shows help
- `/gsw menu` opens the gamemode selector
- `/gsw set <mode>` changes your gamemode
- `/gsw return` exits a tracked Spectator session
- `/gsw config` opens the settings editor
- `/gsw language` opens language settings

Switching requires `gamemodeswitcher.use` and the destination's `gamemodeswitcher.mode.<mode>` permission. These permissions default to operators. Configuration and installed language files reload automatically when saved.

## Build

Build with JDK 25:

```bash
./gradlew build
```

On Windows, use `.\gradlew.bat build`. The plugin targets Java 25. The jar is written to `build/libs/` and copied to `../BUILDS/GamemodeSwitcher.jar`.
