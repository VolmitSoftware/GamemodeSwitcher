package com.volmit.gsw.debug;

import art.arcane.volmlib.util.diagnostics.DebugDumpContributor;
import art.arcane.volmlib.util.localization.LanguageUpstreamSync;
import com.volmit.gsw.GamemodeSwitcher;
import com.volmit.gsw.config.RuntimeConfig;
import com.volmit.gsw.localization.LanguageService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class SwitcherDebugContributor implements DebugDumpContributor {
    private final GamemodeSwitcher plugin;

    public SwitcherDebugContributor(GamemodeSwitcher plugin) {
        this.plugin = plugin;
    }

    @Override
    public Report capture() {
        LanguageService languages = plugin.getLanguageService();
        Snapshot snapshot = new Snapshot(plugin.schedulerName(), plugin.getConfigService().runtime(),
                languages.availableLocales(), languages.sourceDescription(), languageSourceState(languages),
                plugin.getSwitchService().stateCount(),
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
                + "Language source: " + snapshot.languageSource() + "\n"
                + "Language source state: " + snapshot.languageSourceState() + "\n"
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
                + "Spectator exit: " + config.spectatorExit() + "\n"
                + "Spectator previous-mode restoration: " + config.restorePreviousMode() + "\n"
                + "Spectator return to origin: " + config.returnToOrigin() + "\n"
                + "Spectator unsafe returns allowed: " + config.allowUnsafeReturn() + "\n";
    }

    private String languageSourceState(LanguageService languages) {
        Optional<Throwable> failure = languages.sourceFailure();
        if (failure.isPresent()) {
            return "unavailable (" + failure.get().getClass().getSimpleName() + ")";
        }
        Optional<LanguageUpstreamSync.Status> status = languages.upstreamStatus();
        if (status.isEmpty()) {
            return "unavailable";
        }
        LanguageUpstreamSync.Status current = status.get();
        StringBuilder state = new StringBuilder();
        state.append(current.revision().map(sha -> "revision " + sha.substring(0, 7)).orElse("not checked"));
        if (current.checkedMillis() > 0L) {
            state.append(", checked ").append(Instant.ofEpochMilli(current.checkedMillis()));
        }
        current.lastError().ifPresent(error -> state.append(", last error: ").append(error));
        return state.toString();
    }

    private record Snapshot(String scheduler, RuntimeConfig config, List<String> languages, String languageSource,
                            String languageSourceState, int states, boolean metricsInitialized,
                            boolean metricsReporting) {
        private Snapshot {
            languages = List.copyOf(languages);
        }
    }
}
