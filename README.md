# GamemodeSwitcher

Switch between Survival, Creative, Adventure, and Spectator with gestures, `/gsw menu`, or `/gsw set <mode>`.

Requires **Minecraft 26.1–26.3** (including patch releases) and **Java 25**. Targets Spigot, Paper, Purpur, Leaf, Folia, and Canvas.

Run `/gsw` for help. Configuration and installed translations reload automatically. First startup downloads runtime libraries; VolmLib is included and needs no separate installation.

[Wiki](https://docs.volmitsoftware.com/gamemodeswitcher) · [Support](https://volmitsoftware.com/discord)

## Build

```bash
./gradlew build
```

Use JDK 25 and `.\gradlew.bat build` on Windows. Output: `build/libs/` and `../BUILDS/GamemodeSwitcher.jar`.
