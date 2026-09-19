package com.volmit.gsw.gameplay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerPreferencesTest {
    @TempDir
    Path directory;

    @Test
    void serializesConcurrentPlayersAndKeepsOptOutAcrossRestart() throws Exception {
        Path file = directory.resolve("preferences.toml");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (PlayerPreferences preferences = new PlayerPreferences(file, Logger.getAnonymousLogger())) {
            preferences.setEnabled(first, false);
            preferences.setEnabled(second, false);
            preferences.setEnabled(first, true).get(5, TimeUnit.SECONDS);
            assertThat(preferences.enabled(first)).isTrue();
            assertThat(preferences.enabled(second)).isFalse();
        }
        try (PlayerPreferences reloaded = new PlayerPreferences(file, Logger.getAnonymousLogger())) {
            assertThat(reloaded.enabled(first)).isTrue();
            assertThat(reloaded.enabled(second)).isFalse();
        }
    }

    @Test
    void feedbackMutesAndGestureChoicesSurviveInterleavedWritesAndRestart() throws Exception {
        Path file = directory.resolve("preferences.toml");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        PersonalFeedback chatOnly = PersonalFeedback.DEFAULT.toggle(PersonalFeedback.Channel.CHAT);
        try (PlayerPreferences preferences = new PlayerPreferences(file, Logger.getAnonymousLogger())) {
            preferences.setFeedback(first, PersonalFeedback.SILENT);
            preferences.setEnabled(first, false);
            preferences.setFeedback(second, chatOnly).get(5, TimeUnit.SECONDS);
            assertThat(preferences.feedback(first)).isEqualTo(PersonalFeedback.SILENT);
            assertThat(preferences.enabled(first)).isFalse();
        }
        try (PlayerPreferences preferences = new PlayerPreferences(file, Logger.getAnonymousLogger())) {
            assertThat(preferences.feedback(first)).isEqualTo(PersonalFeedback.SILENT);
            assertThat(preferences.feedback(second)).isEqualTo(chatOnly);
            assertThat(preferences.enabled(first)).isFalse();
            preferences.setFeedback(first, PersonalFeedback.DEFAULT).get(5, TimeUnit.SECONDS);
        }
        try (PlayerPreferences preferences = new PlayerPreferences(file, Logger.getAnonymousLogger())) {
            assertThat(preferences.feedback(first)).isEqualTo(PersonalFeedback.DEFAULT);
            assertThat(preferences.feedback(second)).isEqualTo(chatOnly);
            assertThat(preferences.enabled(first)).isFalse();
        }
    }

    @Test
    void queuedChannelTogglesUseTheLatestPersistedPreference() throws Exception {
        UUID player = UUID.randomUUID();
        try (PlayerPreferences preferences = new PlayerPreferences(directory.resolve("preferences.toml"), Logger.getAnonymousLogger())) {
            preferences.toggleFeedback(player, PersonalFeedback.Channel.CHAT);
            preferences.toggleFeedback(player, PersonalFeedback.Channel.SOUND);
            preferences.toggleFeedback(player, PersonalFeedback.Channel.TITLE);
            preferences.toggleFeedback(player, PersonalFeedback.Channel.ACTION_BAR).get(5, TimeUnit.SECONDS);
            assertThat(preferences.feedback(player)).isEqualTo(PersonalFeedback.SILENT);
            preferences.setFeedback(player, PersonalFeedback.DEFAULT);
            preferences.toggleFeedback(player, PersonalFeedback.Channel.SOUND).get(5, TimeUnit.SECONDS);
            assertThat(preferences.feedback(player)).isEqualTo(PersonalFeedback.DEFAULT.toggle(PersonalFeedback.Channel.SOUND));
        }
    }
}
