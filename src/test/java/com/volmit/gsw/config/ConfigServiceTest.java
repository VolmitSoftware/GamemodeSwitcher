package com.volmit.gsw.config;

import org.bukkit.GameMode;
import art.arcane.volmlib.util.config.ConfigEditorDocument;
import art.arcane.volmlib.util.config.TomlDocumentEditor;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigServiceTest {
    @TempDir
    Path directory;

    @Test
    void omittedSettingsRemainEditableWithoutRewritingTheConfiguration() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        String source = "# Server settings\n[general]\nenabled = true\n";
        Files.writeString(directory.resolve("config.toml"), source);
        service.initialize();
        ConfigEditorDocument document = service.editorDocument(service.prepare().source());
        assertThat(document.value(List.of("feedback", "title-enabled")).getAsBoolean()).isTrue();
        assertThat(document.value(List.of("gestures", "sneaking", "survival")).getAsString()).isEqualTo("ADVENTURE");
        assertThat(Files.readString(service.file())).isEqualTo(source);
        String replacement = TomlDocumentEditor.set(document.source(), List.of("feedback", "title-enabled"), new JsonPrimitive(false));
        service.save(document.source(), replacement);
        assertThat(service.runtime().feedback().titleEnabled()).isFalse();
        assertThat(service.editorDocument(service.source()).value(List.of("feedback", "sound-enabled")).getAsBoolean()).isTrue();
        assertThatThrownBy(() -> service.save(document.source(), replacement)).isInstanceOf(IOException.class);
    }

    @Test
    void defaultsKeepOriginalDestinations() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.initialize();
        RuntimeConfig config = service.runtime();
        assertThat(config.target(GameMode.SURVIVAL, false)).isEqualTo(GameMode.CREATIVE);
        assertThat(config.target(GameMode.SURVIVAL, true)).isEqualTo(GameMode.ADVENTURE);
        assertThat(config.target(GameMode.CREATIVE, true)).isEqualTo(GameMode.SPECTATOR);
        assertThat(config.target(GameMode.SPECTATOR, false)).isEqualTo(GameMode.CREATIVE);
        assertThat(config.debugUpload()).isTrue();
        assertThat(config.metricsEnabled()).isTrue();
        assertThat(config.allowUnsafeReturn()).isTrue();
        assertThat(config.feedback()).isEqualTo(new FeedbackConfig(true, true, true, 50,
                true, "minecraft:ui.button.click", 0.7F, 1.2F));
    }

    @Test
    void unsafeReturnDefaultsOnAndCanBeDisabledByHotReload() throws IOException {
        assertThat(ConfigService.parse("[spectator]\nreturn-to-origin = true").allowUnsafeReturn()).isTrue();
        ConfigService service = new ConfigService(directory.toFile());
        service.initialize();
        Files.writeString(service.file(), service.source().replace("allow-unsafe-return = true", "allow-unsafe-return = false"));
        service.reload();
        assertThat(service.runtime().allowUnsafeReturn()).isFalse();
        assertThatThrownBy(() -> ConfigService.parse("[spectator]\nallow-unsafe-return = 'yes'"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void invalidReloadLeavesTheEntireLastGoodSnapshotActive() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.initialize();
        RuntimeConfig original = service.runtime();
        Files.writeString(service.file(), service.source().replace("double-tap-millis = 600", "double-tap-millis = -1"));
        assertThatThrownBy(service::reload).isInstanceOf(IOException.class);
        assertThat(service.runtime()).isSameAs(original);
    }

    @Test
    void editorRejectsStaleWritesAndDoesNotOverwriteExternalChanges() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.initialize();
        String original = service.source();
        String external = original.replace("cooldown-millis = 500", "cooldown-millis = 900");
        Files.writeString(service.file(), external);
        assertThatThrownBy(() -> service.save(original, original.replace("enabled = true", "enabled = false")))
                .isInstanceOf(IOException.class).hasMessageContaining("changed on disk");
        assertThat(Files.readString(service.file())).isEqualTo(external);
    }

    @Test
    void validatesTypesAndModeNamesInsteadOfGuessing() {
        assertThatThrownBy(() -> ConfigService.parse("[general]\nenabled = 'yes'"))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ConfigService.parse("[gestures]\nspectator-exit = 'builder'"))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ConfigService.parse("[restrictions]\ndisabled-worlds = [42]"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void acceptsResourcePackSoundKeysAndIntegerVolumes() throws IOException {
        RuntimeConfig config = ConfigService.parse("""
                [feedback]
                chat-enabled = false
                action-bar-enabled = true
                title-enabled = true
                popup-duration-ticks = 200
                sound = "server:gamemode/switch"
                sound-volume = 1
                sound-pitch = 0.5
                """);
        assertThat(config.feedback()).isEqualTo(new FeedbackConfig(false, true, true, 200,
                true, "server:gamemode/switch", 1, 0.5F));
    }

    @Test
    void rejectsInvalidFeedbackWithoutReplacingWorkingSettings() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.initialize();
        RuntimeConfig previous = service.runtime();
        for (String entry : new String[]{
                "sound = 'UI_BUTTON_CLICK'", "sound = 'minecraft:'", "sound = 'invalid key'",
                "sound-volume = -0.1", "sound-volume = 1.1", "sound-volume = '0.7'",
                "sound-pitch = 0.49", "sound-pitch = 2.1", "sound-pitch = true",
                "popup-duration-ticks = 0", "popup-duration-ticks = 201", "popup-duration-ticks = 1.5",
                "title-enabled = 'yes'", "action-bar-enabled = 1"
        }) {
            Files.writeString(service.file(), "[feedback]\n" + entry);
            assertThatThrownBy(service::reload).as(entry).isInstanceOf(IOException.class);
            assertThat(service.runtime()).isSameAs(previous);
        }
    }
}
