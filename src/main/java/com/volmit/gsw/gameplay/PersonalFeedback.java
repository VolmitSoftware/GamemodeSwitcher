package com.volmit.gsw.gameplay;

import com.volmit.gsw.config.FeedbackConfig;

public record PersonalFeedback(boolean chatMuted, boolean actionBarMuted, boolean titleMuted, boolean soundMuted) {
    public static final PersonalFeedback DEFAULT = new PersonalFeedback(false, false, false, false);
    public static final PersonalFeedback SILENT = new PersonalFeedback(true, true, true, true);

    public FeedbackConfig apply(FeedbackConfig settings) {
        return new FeedbackConfig(settings.chatEnabled() && !chatMuted, settings.actionBarEnabled() && !actionBarMuted,
                settings.titleEnabled() && !titleMuted, settings.popupDurationTicks(), settings.soundEnabled() && !soundMuted,
                settings.sound(), settings.soundVolume(), settings.soundPitch());
    }

    public PersonalFeedback toggle(Channel channel) {
        return switch (channel) {
            case CHAT -> new PersonalFeedback(!chatMuted, actionBarMuted, titleMuted, soundMuted);
            case ACTION_BAR -> new PersonalFeedback(chatMuted, !actionBarMuted, titleMuted, soundMuted);
            case TITLE -> new PersonalFeedback(chatMuted, actionBarMuted, !titleMuted, soundMuted);
            case SOUND -> new PersonalFeedback(chatMuted, actionBarMuted, titleMuted, !soundMuted);
        };
    }

    public boolean muted(Channel channel) {
        return switch (channel) {
            case CHAT -> chatMuted;
            case ACTION_BAR -> actionBarMuted;
            case TITLE -> titleMuted;
            case SOUND -> soundMuted;
        };
    }

    public enum Channel {
        CHAT, ACTION_BAR, TITLE, SOUND
    }
}
