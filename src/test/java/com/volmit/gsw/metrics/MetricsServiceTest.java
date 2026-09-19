package com.volmit.gsw.metrics;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetricsServiceTest {
    @TempDir
    Path directory;

    @Test
    void createsStandardGlobalConfigAndClosesTheMetricsLifecycle() {
        try (MetricsService service = service()) {
            assertThat(service.reload(true)).isTrue();
            assertThat(service.initialized()).isTrue();
            assertThat(service.reportingEnabled()).isTrue();
            assertThat(service.configFile().toPath()).isEqualTo(directory.resolve("bStats/config.yml"));
            YamlConfiguration config = YamlConfiguration.loadConfiguration(service.configFile());
            assertThat(config.getBoolean("enabled")).isTrue();
            assertThat(UUID.fromString(config.getString("serverUuid"))).isNotNull();
            assertThat(config.getBoolean("logFailedRequests")).isFalse();
        }
    }

    @Test
    void retainsGlobalOptOutWhenInstallingMissingGlobalDefaults() throws IOException {
        Files.createDirectories(directory.resolve("bStats"));
        Files.writeString(directory.resolve("bStats/config.yml"), "enabled: false\n");
        try (MetricsService service = service()) {
            assertThat(service.reload(true)).isTrue();
            YamlConfiguration config = YamlConfiguration.loadConfiguration(service.configFile());
            assertThat(config.getBoolean("enabled")).isFalse();
            assertThat(service.reportingEnabled()).isFalse();
            assertThat(UUID.fromString(config.getString("serverUuid"))).isNotNull();
        }
    }

    @Test
    void doesNotRestartMetricsForUnchangedSettingsAndReplacesItForGlobalChanges() throws IOException {
        writeConfig(true);
        try (MockedConstruction<Metrics> construction = mockConstruction(Metrics.class, (metrics, context) ->
                assertThat(context.arguments().get(1)).isEqualTo(33967));
             MetricsService service = service()) {
            assertThat(service.reload(true)).isTrue();
            assertThat(service.reload(true)).isTrue();
            assertThat(construction.constructed()).hasSize(1);
            Metrics first = construction.constructed().get(0);
            verify(first, never()).shutdown();

            writeConfig(false);
            assertThat(service.reload(true)).isTrue();
            assertThat(construction.constructed()).hasSize(2);
            assertThat(service.reportingEnabled()).isFalse();
            verify(first).shutdown();
        }
    }

    @Test
    void invalidGlobalConfigurationRetainsPreviousSettingsButCannotBlockPluginOptOut() throws IOException {
        writeConfig(true);
        try (MockedConstruction<Metrics> construction = mockConstruction(Metrics.class);
             MetricsService service = service()) {
            assertThat(service.reload(true)).isTrue();
            Metrics active = construction.constructed().get(0);
            Files.writeString(service.configFile().toPath(), "enabled: certainly\n");
            assertThat(service.reload(true)).isFalse();
            assertThat(service.initialized()).isTrue();
            verify(active, never()).shutdown();

            assertThat(service.reload(false)).isTrue();
            assertThat(service.initialized()).isFalse();
            assertThat(service.reportingEnabled()).isFalse();
            verify(active).shutdown();
        }
    }

    @Test
    void shutdownPreventsMetricsFromRestarting() throws IOException {
        writeConfig(true);
        try (MockedConstruction<Metrics> construction = mockConstruction(Metrics.class)) {
            MetricsService service = service();
            assertThat(service.reload(true)).isTrue();
            Metrics active = construction.constructed().get(0);
            service.close();
            assertThat(service.initialized()).isFalse();
            assertThat(service.reload(true)).isFalse();
            assertThat(construction.constructed()).hasSize(1);
            verify(active).shutdown();
        }
    }

    private MetricsService service() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.resolve("GamemodeSwitcher").toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("GamemodeSwitcher-Metrics-Test"));
        when(plugin.isEnabled()).thenReturn(true);
        return new MetricsService(plugin);
    }

    private void writeConfig(boolean enabled) throws IOException {
        Files.createDirectories(directory.resolve("bStats"));
        Files.writeString(directory.resolve("bStats/config.yml"), "enabled: " + enabled
                + "\nserverUuid: 96453a26-2c5e-43dc-8a97-b0b814afdd29\n");
    }
}
