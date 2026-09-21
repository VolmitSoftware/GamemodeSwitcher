package com.volmit.gsw.config;

import org.bukkit.GameMode;

import java.util.Map;
import java.util.Set;

public record RuntimeConfig(
        boolean enabled,
        String language,
        boolean debugUpload,
        boolean metricsEnabled,
        long doubleTapMillis,
        long sneakTapMillis,
        long cooldownMillis,
        boolean requireEmptyHands,
        boolean autoFlyCreative,
        FeedbackConfig feedback,
        Set<String> disabledWorlds,
        Map<GameMode, GameMode> normalModes,
        Map<GameMode, GameMode> sneakingModes,
        GameMode spectatorExit,
        boolean restorePreviousMode,
        boolean returnToOrigin,
        boolean allowUnsafeReturn
) {
    public RuntimeConfig {
        disabledWorlds = Set.copyOf(disabledWorlds);
        normalModes = Map.copyOf(normalModes);
        sneakingModes = Map.copyOf(sneakingModes);
    }

    public GameMode target(GameMode current, boolean sneaking) {
        if (current == GameMode.SPECTATOR) {
            return spectatorExit;
        }
        return (sneaking ? sneakingModes : normalModes).get(current);
    }
}
