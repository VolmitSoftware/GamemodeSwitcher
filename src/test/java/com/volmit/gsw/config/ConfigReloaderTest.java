package com.volmit.gsw.config;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigReloaderTest {
    @TempDir
    Path directory;

    @Test
    void watcherKeepsRunningAfterAReloadThrowsALinkageError() throws IOException, InterruptedException {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        ConfigService config = new ConfigService(directory.toFile());
        config.initialize();
        File metrics = directory.resolve("bStats/config.yml").toFile();
        Files.createDirectories(metrics.getParentFile().toPath());
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(2);
        Runnable reload = () -> {
            first.countDown();
            second.countDown();
            throw new NoClassDefFoundError("missing/Library");
        };
        try (ConfigReloader reloader = new ConfigReloader(new ConfigReloader.Dependencies(plugin, config, reload, metrics))) {
            reloader.start();
            String source = Files.readString(config.file());
            Files.writeString(config.file(), source + "\n# first edit\n");
            assertThat(first.await(15, TimeUnit.SECONDS)).isTrue();
            Files.writeString(config.file(), source + "\n# second edit\n");
            assertThat(second.await(15, TimeUnit.SECONDS)).isTrue();
        }
    }
}
