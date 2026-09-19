package com.volmit.gsw.config;

public record FeedbackConfig(
        boolean chatEnabled,
        boolean actionBarEnabled,
        boolean titleEnabled,
        long popupDurationTicks,
        boolean soundEnabled,
        String sound,
        float soundVolume,
        float soundPitch
) {
}
