# GamemodeSwitcher

Switch gamemodes with double-tap gestures, a selector menu, or `/gsw set <mode>` on Spigot 1.20.1 and newer compatible servers, including Paper, Purpur, Leaf, Folia, and Canvas. The shaded plugin requires Java 17 or newer; the server may require a newer Java runtime.

Use `/gsw` for Director command help, `/gsw menu` for the selector, `/gsw config` for the TOML editor, and `/gsw language` for personal languages and translation editing. Mode changes require `gamemodeswitcher.use` plus the destination's `gamemodeswitcher.mode.<mode>` permission. These permissions default to operators.

Product documentation lives in the [central wiki source](https://github.com/VolmitSoftware/docs/blob/main/gamemodeswitcher.md).

Build with JDK 25 and `./gradlew build` (PowerShell: `.\gradlew.bat build`). Compilation targets Java 17. The build checks the shaded jar's bytecode and compiles against the baseline Spigot API and current Spigot/Paper APIs. Artifacts are written to `build/libs/` and `../BUILDS/GamemodeSwitcher.jar`; override the staging directory with `-PbuildsDirectory=<path>`.

Configuration and installed language files apply changes automatically. English is generated locally; the other 17 language catalogs download from this repository when selected. Language files explain formatting and available variables in their header comments. Diagnostic reports upload by default; use `/gsw debug dump upload=false` for a local report. The startup splash always prints.

bStats uses plugin ID `33967`. The `metrics.enabled` setting and the shared `plugins/bStats/config.yml` opt-out apply automatically when edited.

The build discovers a neighboring VolmLib composite build by walking up parent directories. Set `-PlocalVolmLibDirectory=<path>` for another checkout, or `-PuseLocalVolmLib=false` to intentionally resolve the published dependency. Runtime dependencies and English defaults are bundled in the jar, so first startup works offline. The language source manifest points to `main/src/main/resources/languages/` on GitHub; those catalogs must be published there for downloads to succeed.

The reusable gameplay scenario is `tools/gameplay/gamemode-switcher.mjs`. Run it through the workspace's Multiplexor harness against an isolated instance. Generated QA reports remain under `build/qa/`.
