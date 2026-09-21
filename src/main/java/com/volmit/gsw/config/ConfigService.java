package com.volmit.gsw.config;

import art.arcane.volmlib.util.io.AtomicFileIO;
import art.arcane.volmlib.util.config.ConfigEditorDocument;
import com.moandjiezana.toml.Toml;
import org.bukkit.GameMode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

public final class ConfigService {
    private static final int MAXIMUM_BYTES = 256 * 1024;

    private final Path file;
    private String defaults;
    private volatile PreparedConfig active;
    private volatile BiConsumer<File, String> selfWrite = (file, source) -> { };

    public ConfigService(File dataFolder) {
        file = dataFolder.toPath().resolve("config.toml");
    }

    public synchronized void initialize() throws IOException {
        try (InputStream input = ConfigService.class.getResourceAsStream("/config.toml")) {
            if (input == null) {
                throw new IOException("Missing bundled config.toml");
            }
            defaults = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!Files.exists(file)) {
            AtomicFileIO.writeString(file, defaults);
        }
        reload();
    }

    public ConfigEditorDocument editorDocument(String source) throws IOException {
        return ConfigEditorDocument.fromTomlWithDefaults(source,
                Objects.requireNonNull(defaults, "Configuration has not initialized"));
    }

    public synchronized void reload() throws IOException {
        install(prepare());
    }

    public synchronized PreparedConfig prepare() throws IOException {
        if (Files.size(file) > MAXIMUM_BYTES) {
            throw new IOException("config.toml exceeds " + MAXIMUM_BYTES + " bytes");
        }
        String source = Files.readString(file, StandardCharsets.UTF_8);
        return new PreparedConfig(source, parse(source));
    }

    public synchronized void install(PreparedConfig prepared) {
        active = Objects.requireNonNull(prepared);
    }

    public synchronized RuntimeConfig save(String expected, String replacement) throws IOException {
        RuntimeConfig parsed = parse(replacement);
        if (!Files.readString(file, StandardCharsets.UTF_8).equals(expected)) {
            throw new IOException("config.toml changed on disk; reopen the editor before saving");
        }
        AtomicFileIO.writeString(file, replacement);
        active = new PreparedConfig(replacement, parsed);
        selfWrite.accept(file.toFile(), replacement);
        return parsed;
    }

    public void setSelfWriteListener(BiConsumer<File, String> listener) {
        selfWrite = listener == null ? (file, source) -> { } : listener;
    }

    public RuntimeConfig runtime() {
        return Objects.requireNonNull(active, "Configuration has not initialized").runtime();
    }

    public String source() {
        return Objects.requireNonNull(active, "Configuration has not initialized").source();
    }

    public Path file() {
        return file;
    }

    public static RuntimeConfig parse(String source) throws IOException {
        if (source.isBlank() || source.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_BYTES) {
            throw new IOException("config.toml is empty or exceeds " + MAXIMUM_BYTES + " bytes");
        }
        try {
            Toml toml = new Toml().read(source);
            String language = toml.getString("general.language", "en_US");
            if (!language.matches("[a-zA-Z]{2,3}[-_][a-zA-Z]{2,3}")) {
                throw new IllegalArgumentException("general.language must be a locale code such as en_US");
            }
            Set<String> disabledWorlds = new HashSet<>();
            List<?> worlds = toml.getList("restrictions.disabled-worlds", List.of());
            for (Object world : worlds) {
                if (!(world instanceof String name) || name.isBlank()) {
                    throw new IllegalArgumentException("restrictions.disabled-worlds must contain world names");
                }
                disabledWorlds.add(name.toLowerCase(Locale.ROOT));
            }
            return new RuntimeConfig(
                    toml.getBoolean("general.enabled", true), language,
                    toml.getBoolean("diagnostics.upload-enabled", true),
                    toml.getBoolean("metrics.enabled", true),
                    bounded(toml, "gestures.double-tap-millis", 600, 100, 3000),
                    bounded(toml, "gestures.sneak-tap-millis", 600, 100, 3000),
                    bounded(toml, "gestures.cooldown-millis", 500, 0, 60000),
                    toml.getBoolean("gestures.require-empty-hands", false),
                    toml.getBoolean("feedback.auto-fly-creative", true),
                    feedback(toml),
                    disabledWorlds, modes(toml, false), modes(toml, true),
                    GameMode.valueOf(toml.getString("gestures.spectator-exit", "CREATIVE").toUpperCase(Locale.ROOT)),
                    toml.getBoolean("spectator.restore-previous-mode", false),
                    toml.getBoolean("spectator.return-to-origin", false),
                    toml.getBoolean("spectator.allow-unsafe-return", true)
            );
        } catch (IllegalArgumentException | ClassCastException exception) {
            throw new IOException("Invalid config.toml: " + exception.getMessage(), exception);
        }
    }

    private static long bounded(Toml toml, String key, long fallback, long minimum, long maximum) {
        long value = toml.getLong(key, fallback);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static FeedbackConfig feedback(Toml toml) {
        String sound = toml.getString("feedback.sound", "minecraft:ui.button.click");
        if (sound.length() > 256 || !sound.matches("[a-z0-9._-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("feedback.sound must be a namespaced sound key of at most 256 characters, such as minecraft:ui.button.click");
        }
        return new FeedbackConfig(
                toml.getBoolean("feedback.chat-enabled", true),
                toml.getBoolean("feedback.action-bar-enabled", true),
                toml.getBoolean("feedback.title-enabled", true),
                bounded(toml, "feedback.popup-duration-ticks", 50, 1, 200),
                toml.getBoolean("feedback.sound-enabled", true), sound,
                decimal(toml, "feedback.sound-volume", 0.7F, 0, 1),
                decimal(toml, "feedback.sound-pitch", 1.2F, 0.5F, 2));
    }

    private static float decimal(Toml toml, String key, float fallback, float minimum, float maximum) {
        int separator = key.lastIndexOf('.');
        Toml section = toml.getTable(key.substring(0, separator));
        Object configured = section == null ? null : section.toMap().get(key.substring(separator + 1));
        if (configured == null) {
            return fallback;
        }
        if (!(configured instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < minimum || number.doubleValue() > maximum) {
            throw new IllegalArgumentException(key + " must be a number between " + minimum + " and " + maximum);
        }
        return number.floatValue();
    }

    private static Map<GameMode, GameMode> modes(Toml toml, boolean sneaking) {
        Map<GameMode, GameMode> modes = new EnumMap<>(GameMode.class);
        String prefix = sneaking ? "gestures.sneaking." : "gestures.normal.";
        modes.put(GameMode.SURVIVAL, mode(toml, prefix + "survival", sneaking ? "ADVENTURE" : "CREATIVE"));
        modes.put(GameMode.CREATIVE, mode(toml, prefix + "creative", sneaking ? "SPECTATOR" : "SURVIVAL"));
        modes.put(GameMode.ADVENTURE, mode(toml, prefix + "adventure", sneaking ? "SURVIVAL" : "CREATIVE"));
        return modes;
    }

    private static GameMode mode(Toml toml, String key, String fallback) {
        return GameMode.valueOf(toml.getString(key, fallback).toUpperCase(Locale.ROOT));
    }

    public record PreparedConfig(String source, RuntimeConfig runtime) {
    }
}
