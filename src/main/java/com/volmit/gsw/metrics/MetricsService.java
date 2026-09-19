package com.volmit.gsw.metrics;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;
import java.util.logging.Level;

public final class MetricsService implements AutoCloseable {
    private static final int BSTATS_PLUGIN_ID = 33967;

    private final Plugin plugin;
    private final File configFile;
    private Metrics metrics;
    private String activeSource;
    private boolean activeEnabled;
    private boolean globalEnabled;
    private boolean loaded;
    private boolean closed;

    public MetricsService(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        configFile = new File(plugin.getDataFolder().getParentFile(), "bStats/config.yml");
    }

    public File configFile() {
        return configFile;
    }

    public synchronized boolean reload(boolean enabled) {
        if (closed) {
            return false;
        }
        if (!enabled) {
            Metrics previous = metrics;
            metrics = null;
            activeEnabled = false;
            loaded = false;
            shutdown(previous);
            return true;
        }
        Metrics replacement = null;
        try {
            ConfigSnapshot snapshot = readConfig();
            if (loaded && activeEnabled == enabled && Objects.equals(activeSource, snapshot.source())) {
                return true;
            }
            replacement = new Metrics(plugin, BSTATS_PLUGIN_ID);
            snapshot = readConfig();
            Metrics previous = metrics;
            metrics = replacement;
            replacement = null;
            activeSource = snapshot.source();
            activeEnabled = enabled;
            globalEnabled = snapshot.enabled();
            loaded = true;
            shutdown(previous);
            return true;
        } catch (IOException | InvalidConfigurationException | RuntimeException | LinkageError failure) {
            shutdown(replacement);
            plugin.getLogger().log(Level.WARNING,
                    "Could not apply GamemodeSwitcher bStats settings; previous settings remain active", failure);
            return false;
        }
    }

    public synchronized boolean initialized() {
        return metrics != null;
    }

    public synchronized boolean reportingEnabled() {
        return metrics != null && globalEnabled;
    }

    @Override
    public synchronized void close() {
        closed = true;
        Metrics previous = metrics;
        metrics = null;
        shutdown(previous);
    }

    private ConfigSnapshot readConfig() throws IOException, InvalidConfigurationException {
        if (!Files.exists(configFile.toPath())) {
            return new ConfigSnapshot(null, true);
        }
        String source = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(source);
        requireBoolean(config, "enabled");
        requireBoolean(config, "logFailedRequests");
        requireBoolean(config, "logSentData");
        requireBoolean(config, "logResponseStatusText");
        Object serverUuid = config.get("serverUuid");
        if (serverUuid != null && (!(serverUuid instanceof String uuid) || uuid.isBlank())) {
            throw new InvalidConfigurationException("bStats serverUuid must be a nonempty string");
        }
        return new ConfigSnapshot(source, config.getBoolean("enabled", true));
    }

    private void requireBoolean(YamlConfiguration config, String key) throws InvalidConfigurationException {
        Object value = config.get(key);
        if (value != null && !(value instanceof Boolean)) {
            throw new InvalidConfigurationException("bStats " + key + " must be a boolean");
        }
    }

    private void shutdown(Metrics active) {
        if (active == null) {
            return;
        }
        try {
            active.shutdown();
        } catch (RuntimeException | LinkageError failure) {
            plugin.getLogger().log(Level.WARNING, "Could not stop GamemodeSwitcher bStats metrics", failure);
        }
    }

    private record ConfigSnapshot(String source, boolean enabled) {
    }
}
