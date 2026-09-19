package com.volmit.gsw.config;

import art.arcane.volmlib.util.config.ConfigFileSupport;
import art.arcane.volmlib.util.hotload.ConfigHotloadEngine;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class ConfigReloader implements AutoCloseable {
    private final Plugin plugin;
    private final ConfigService config;
    private final Runnable reload;
    private final File metricsFile;
    private final ScheduledExecutorService watcher;
    private final ConfigHotloadEngine engine;
    private boolean failed;

    public ConfigReloader(Dependencies dependencies) {
        plugin = dependencies.plugin();
        config = dependencies.config();
        reload = dependencies.reload();
        metricsFile = dependencies.metricsFile();
        engine = new ConfigHotloadEngine(this::managed, this::files,
                this::read, ConfigFileSupport::normalize);
        watcher = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "GamemodeSwitcher-Config-Watcher");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        engine.configure(1000, 500, files(),
                List.of(plugin.getDataFolder(), new File(plugin.getDataFolder(), "languages"), metricsFile.getParentFile()));
        config.setSelfWriteListener(this::noteSelfWrite);
        watcher.scheduleWithFixedDelay(this::poll, 1, 1, TimeUnit.SECONDS);
    }

    public void noteSelfWrite(File file, String source) {
        engine.noteSelfWrite(file, source);
    }

    @Override
    public void close() {
        config.setSelfWriteListener(null);
        watcher.shutdownNow();
        engine.clear();
    }

    private void poll() {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            Set<ConfigHotloadEngine.StableContentSnapshot> snapshots = engine.pollTouchedSnapshots();
            AtomicBoolean attempted = new AtomicBoolean();
            for (ConfigHotloadEngine.StableContentSnapshot snapshot : snapshots) {
                engine.processSnapshotChange(snapshot, ignored -> {
                    attempted.set(true);
                    return true;
                }, null);
            }
            if (attempted.get()) {
                reload.run();
            }
            failed = false;
        } catch (RuntimeException exception) {
            if (!failed) {
                plugin.getLogger().log(Level.SEVERE, "Could not watch GamemodeSwitcher configuration", exception);
                failed = true;
            }
        }
    }

    private boolean managed(File file) {
        Path path = file.toPath().toAbsolutePath().normalize();
        Path languages = plugin.getDataFolder().toPath().resolve("languages").toAbsolutePath().normalize();
        return path.equals(config.file().toAbsolutePath().normalize())
                || path.equals(metricsFile.toPath().toAbsolutePath().normalize())
                || (languages.equals(path.getParent()) && path.getFileName().toString().endsWith(".toml"));
    }

    private List<File> files() {
        ArrayList<File> files = new ArrayList<>();
        files.add(config.file().toFile());
        files.add(metricsFile);
        File[] languages = new File(plugin.getDataFolder(), "languages")
                .listFiles((directory, name) -> name.endsWith(".toml"));
        if (languages != null) {
            for (File language : languages) {
                files.add(language);
            }
        }
        return files;
    }

    private String read(File file) {
        if (!file.isFile() || file.length() > 2L * 1024 * 1024) {
            return null;
        }
        try {
            return Files.readString(file.toPath());
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read " + file, exception);
        }
    }

    public record Dependencies(Plugin plugin, ConfigService config, Runnable reload, File metricsFile) {
    }
}
