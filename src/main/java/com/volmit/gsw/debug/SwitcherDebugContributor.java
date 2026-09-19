package com.volmit.gsw.debug;

import art.arcane.volmlib.util.diagnostics.DebugDumpContributor;
import com.volmit.gsw.GamemodeSwitcher;
import com.volmit.gsw.config.RuntimeConfig;

import java.util.List;

public final class SwitcherDebugContributor implements DebugDumpContributor {
    private final GamemodeSwitcher plugin;

    public SwitcherDebugContributor(GamemodeSwitcher plugin) {
        this.plugin = plugin;
    }

    @Override
    public Report capture() {
        Snapshot snapshot = new Snapshot(plugin.schedulerName(), plugin.getConfigService().runtime(),
                plugin.getLanguageService().availableLocales(), plugin.getSwitchService().stateCount(),
                plugin.getMetricsService().initialized(), plugin.getMetricsService().reportingEnabled());
        return () -> render(snapshot);
    }

    private String render(Snapshot snapshot) {
        RuntimeConfig config = snapshot.config();
        return "GamemodeSwitcher runtime\n"
                + "Scheduler: " + snapshot.scheduler() + "\n"
                + "Enabled: " + config.enabled() + "\n"
                + "Language: " + config.language() + "\n"
                + "Available languages: " + snapshot.languages() + "\n"
                + "Hot reload: always enabled\n"
                + "Debug uploads enabled: " + config.debugUpload() + "\n"
                + "bStats enabled: " + config.metricsEnabled() + "\n"
                + "bStats initialized: " + snapshot.metricsInitialized() + "\n"
                + "bStats reporting enabled: " + snapshot.metricsReporting() + "\n"
                + "Transient gesture/cooldown entries: " + snapshot.states() + "\n"
                + "Double tap interval (ms): " + config.doubleTapMillis() + "\n"
                + "Sneak tap interval (ms): " + config.sneakTapMillis() + "\n"
                + "Switch cooldown (ms): " + config.cooldownMillis() + "\n"
                + "Require empty hands: " + config.requireEmptyHands() + "\n"
                + "Creative flight: " + config.autoFlyCreative() + "\n"
                + "Switch feedback: " + config.feedback() + "\n"
                + "Disabled worlds count: " + config.disabledWorlds().size() + "\n"
                + "Normal transitions: " + config.normalModes() + "\n"
                + "Sneaking transitions: " + config.sneakingModes() + "\n"
                + "Spectator exit: " + config.spectatorExit() + "\n";
    }

    private record Snapshot(String scheduler, RuntimeConfig config, List<String> languages, int states,
                            boolean metricsInitialized, boolean metricsReporting) {
        private Snapshot {
            languages = List.copyOf(languages);
        }
    }
}
