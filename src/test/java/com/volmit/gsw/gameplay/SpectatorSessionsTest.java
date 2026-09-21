package com.volmit.gsw.gameplay;

import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpectatorSessionsTest {
    @TempDir
    Path directory;

    @Test
    void persistsOriginPoliciesAndModeAcrossRestart() throws Exception {
        Path file = directory.resolve("spectator-sessions.toml");
        UUID player = UUID.randomUUID();
        SpectatorSession session = new SpectatorSession(UUID.randomUUID(), -15.5, 70, 48.75,
                90, -20, GameMode.ADVENTURE, true, true);
        try (SpectatorSessions sessions = new SpectatorSessions(file, Logger.getAnonymousLogger())) {
            sessions.put(player, session).get(5, TimeUnit.SECONDS);
        }
        try (SpectatorSessions sessions = new SpectatorSessions(file, Logger.getAnonymousLogger())) {
            assertThat(sessions.get(player)).isEqualTo(session);
            assertThat(sessions.get(player).exitMode(GameMode.CREATIVE)).isEqualTo(GameMode.ADVENTURE);
            sessions.remove(player).get(5, TimeUnit.SECONDS);
        }
        try (SpectatorSessions sessions = new SpectatorSessions(file, Logger.getAnonymousLogger())) {
            assertThat(sessions.get(player)).isNull();
        }
    }

    @Test
    void queuedInvalidationCannotBeOverwrittenByAnEarlierEntrySave() throws Exception {
        Path file = directory.resolve("spectator-sessions.toml");
        UUID player = UUID.randomUUID();
        SpectatorSession session = new SpectatorSession(UUID.randomUUID(), 0, 64, 0,
                0, 0, GameMode.SURVIVAL, false, true);
        try (SpectatorSessions sessions = new SpectatorSessions(file, Logger.getAnonymousLogger())) {
            sessions.put(player, session);
            sessions.remove(player).get(5, TimeUnit.SECONDS);
            assertThat(sessions.get(player)).isNull();
        }
        try (SpectatorSessions sessions = new SpectatorSessions(file, Logger.getAnonymousLogger())) {
            assertThat(sessions.get(player)).isNull();
        }
        assertThat(session.exitMode(GameMode.CREATIVE)).isEqualTo(GameMode.CREATIVE);
    }

    @Test
    void rejectsInvalidOriginsAndSpectatorAsPreviousMode() {
        assertThatThrownBy(() -> new SpectatorSession(UUID.randomUUID(), Double.NaN, 64, 0,
                0, 0, GameMode.SURVIVAL, true, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpectatorSession(UUID.randomUUID(), 0, 64, 0,
                0, 0, GameMode.SPECTATOR, true, true)).isInstanceOf(IllegalArgumentException.class);
    }
}
