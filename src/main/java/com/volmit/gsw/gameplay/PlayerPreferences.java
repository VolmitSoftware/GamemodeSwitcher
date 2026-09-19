package com.volmit.gsw.gameplay;

import art.arcane.volmlib.util.io.AtomicFileIO;
import com.moandjiezana.toml.Toml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PlayerPreferences implements AutoCloseable {
    private final Path file;
    private final Logger logger;
    private final ExecutorService writer;
    private volatile Snapshot snapshot;

    public PlayerPreferences(Path file, Logger logger) throws IOException {
        this.file = file;
        this.logger = logger;
        snapshot = load(file);
        writer = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "GamemodeSwitcher-Player-Preferences");
            thread.setDaemon(true);
            return thread;
        });
    }

    public boolean enabled(UUID player) {
        return !snapshot.disabled().contains(player);
    }

    public PersonalFeedback feedback(UUID player) {
        return snapshot.feedback().getOrDefault(player, PersonalFeedback.DEFAULT);
    }

    public CompletableFuture<Void> setFeedback(UUID player, PersonalFeedback feedback) {
        return CompletableFuture.runAsync(() -> saveFeedback(player, feedback), writer);
    }

    public CompletableFuture<Void> toggleFeedback(UUID player, PersonalFeedback.Channel channel) {
        return CompletableFuture.runAsync(() -> saveFeedback(player, feedback(player).toggle(channel)), writer);
    }

    public CompletableFuture<Void> setEnabled(UUID player, boolean enabled) {
        return CompletableFuture.runAsync(() -> {
            Set<UUID> replacement = new HashSet<>(snapshot.disabled());
            if (enabled) {
                replacement.remove(player);
            } else {
                replacement.add(player);
            }
            save(new Snapshot(Set.copyOf(replacement), snapshot.feedback()));
        }, writer);
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.severe("Player preference writes did not finish within five seconds during shutdown");
                writer.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.log(Level.SEVERE, "Interrupted while saving player preferences during shutdown", exception);
        }
    }

    private void saveFeedback(UUID player, PersonalFeedback feedback) {
        Map<UUID, PersonalFeedback> replacement = new HashMap<>(snapshot.feedback());
        if (feedback.equals(PersonalFeedback.DEFAULT)) {
            replacement.remove(player);
        } else {
            replacement.put(player, feedback);
        }
        save(new Snapshot(snapshot.disabled(), Map.copyOf(replacement)));
    }

    private void save(Snapshot replacement) {
        StringBuilder source = new StringBuilder("disabled = [");
        List<String> values = replacement.disabled().stream().map(UUID::toString).sorted().toList();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                source.append(", ");
            }
            source.append('"').append(values.get(index)).append('"');
        }
        source.append("]\n");
        List<UUID> players = replacement.feedback().keySet().stream().sorted().toList();
        for (UUID player : players) {
            PersonalFeedback feedback = replacement.feedback().get(player);
            source.append("\n[feedback.").append(player).append("]\n")
                    .append("chat-muted = ").append(feedback.chatMuted()).append('\n')
                    .append("action-bar-muted = ").append(feedback.actionBarMuted()).append('\n')
                    .append("title-muted = ").append(feedback.titleMuted()).append('\n')
                    .append("sound-muted = ").append(feedback.soundMuted()).append('\n');
        }
        try {
            AtomicFileIO.writeString(file, source.toString());
            snapshot = replacement;
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not save player preferences", exception);
        }
    }

    private static Snapshot load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new Snapshot(Set.of(), Map.of());
        }
        try {
            Toml toml = new Toml().read(Files.readString(file, StandardCharsets.UTF_8));
            List<?> values = toml.getList("disabled", List.of());
            Set<UUID> disabled = new HashSet<>();
            for (Object value : values) {
                if (!(value instanceof String text)) {
                    throw new IllegalArgumentException("disabled must contain UUID strings");
                }
                disabled.add(UUID.fromString(text));
            }
            Map<UUID, PersonalFeedback> feedback = new HashMap<>();
            Toml table = toml.getTable("feedback");
            if (table != null) {
                for (String key : table.toMap().keySet()) {
                    UUID player = UUID.fromString(key);
                    Toml choices = table.getTable(key);
                    if (choices == null) {
                        throw new IllegalArgumentException("Feedback preferences must be a table for " + key);
                    }
                    feedback.put(player, new PersonalFeedback(choices.getBoolean("chat-muted", false),
                            choices.getBoolean("action-bar-muted", false), choices.getBoolean("title-muted", false),
                            choices.getBoolean("sound-muted", false)));
                }
            }
            return new Snapshot(Set.copyOf(disabled), Map.copyOf(feedback));
        } catch (IllegalArgumentException | ClassCastException exception) {
            throw new IOException("Invalid player preferences at " + file, exception);
        }
    }

    private record Snapshot(Set<UUID> disabled, Map<UUID, PersonalFeedback> feedback) {
    }
}
