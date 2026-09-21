package com.volmit.gsw.gameplay;

import art.arcane.volmlib.util.io.AtomicFileIO;
import com.moandjiezana.toml.Toml;
import org.bukkit.GameMode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SpectatorSessions implements AutoCloseable {
    private final Path file;
    private final Logger logger;
    private final ExecutorService writer;
    private final Map<UUID, SpectatorSession> sessions;

    public SpectatorSessions(Path file, Logger logger) throws IOException {
        this.file = file;
        this.logger = logger;
        sessions = load(file);
        writer = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("GamemodeSwitcher-Spectator-Sessions").factory());
    }

    public synchronized SpectatorSession get(UUID player) {
        return sessions.get(player);
    }

    public synchronized CompletableFuture<Void> put(UUID player, SpectatorSession session) {
        sessions.put(player, session);
        return persist();
    }

    public synchronized CompletableFuture<Void> remove(UUID player) {
        if (sessions.remove(player) == null) {
            return CompletableFuture.completedFuture(null);
        }
        return persist();
    }

    public synchronized CompletableFuture<Void> remove(UUID player, SpectatorSession expected) {
        if (sessions.get(player) != expected) {
            return CompletableFuture.completedFuture(null);
        }
        return remove(player);
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.severe("Spectator session writes did not finish within five seconds during shutdown");
                writer.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(Level.SEVERE, "Interrupted while saving Spectator sessions during shutdown", exception);
        }
    }

    private CompletableFuture<Void> persist() {
        Map<UUID, SpectatorSession> snapshot = Map.copyOf(sessions);
        return CompletableFuture.runAsync(() -> {
            StringBuilder source = new StringBuilder();
            for (UUID player : snapshot.keySet().stream().sorted().toList()) {
                SpectatorSession session = snapshot.get(player);
                source.append("[sessions.").append(player).append("]\n")
                        .append("world = \"").append(session.world()).append("\"\n")
                        .append("previous-mode = \"").append(session.previousMode()).append("\"\n")
                        .append("restore-previous-mode = ").append(session.restorePreviousMode()).append('\n')
                        .append("return-to-origin = ").append(session.returnToOrigin()).append('\n')
                        .append("x = ").append(session.x()).append('\n')
                        .append("y = ").append(session.y()).append('\n')
                        .append("z = ").append(session.z()).append('\n')
                        .append("yaw = ").append(session.yaw()).append('\n')
                        .append("pitch = ").append(session.pitch()).append("\n\n");
            }
            try {
                AtomicFileIO.writeString(file, source.toString());
            } catch (IOException exception) {
                throw new UncheckedIOException("Could not save Spectator sessions", exception);
            }
        }, writer);
    }

    private static Map<UUID, SpectatorSession> load(Path file) throws IOException {
        Map<UUID, SpectatorSession> sessions = new HashMap<>();
        if (!Files.exists(file)) {
            return sessions;
        }
        try {
            Toml table = new Toml().read(Files.readString(file)).getTable("sessions");
            if (table == null) {
                return sessions;
            }
            for (String id : table.toMap().keySet()) {
                Toml session = table.getTable(id);
                sessions.put(UUID.fromString(id), new SpectatorSession(UUID.fromString(session.getString("world")),
                        number(session, "x"), number(session, "y"), number(session, "z"),
                        (float) number(session, "yaw"), (float) number(session, "pitch"),
                        GameMode.valueOf(session.getString("previous-mode")),
                        session.getBoolean("restore-previous-mode", false), session.getBoolean("return-to-origin", false)));
            }
        } catch (IllegalArgumentException | ClassCastException | NullPointerException exception) {
            throw new IOException("Invalid Spectator sessions at " + file, exception);
        }
        return sessions;
    }

    private static double number(Toml table, String key) {
        if (!(table.toMap().get(key) instanceof Number number)) {
            throw new IllegalArgumentException("Missing or invalid Spectator origin coordinate " + key);
        }
        return number.doubleValue();
    }
}
