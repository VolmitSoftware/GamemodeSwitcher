package com.volmit.gsw.gui;

import art.arcane.volmlib.util.config.ConfigEditorDocument;
import art.arcane.volmlib.util.config.TomlDocumentEditor;
import com.google.gson.JsonPrimitive;
import com.volmit.gsw.config.ConfigService;
import com.volmit.gsw.localization.SwitcherMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SoundPickerTest {
    @TempDir
    Path directory;

    @Test
    void presetsUseValidatedSoundKeysAndRetainOtherFeedbackSettings() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.initialize();
        assertThat(SoundPicker.PRESETS).extracting(SoundPicker.Preset::slot).doesNotHaveDuplicates();
        for (SoundPicker.Preset preset : SoundPicker.PRESETS) {
            assertThat(SwitcherMessages.catalog().require(preset.name().id())).isEqualTo(preset.name());
            String changed = TomlDocumentEditor.set(service.source(), SoundPicker.SOUND_PATH, new JsonPrimitive(preset.key()));
            assertThat(ConfigService.parse(changed).feedback().sound()).isEqualTo(preset.key());
            assertThat(ConfigService.parse(changed).feedback().soundVolume()).isEqualTo(service.runtime().feedback().soundVolume());
            assertThat(ConfigService.parse(changed).feedback().soundPitch()).isEqualTo(service.runtime().feedback().soundPitch());
        }
    }

    @Test
    void presetSelectionCannotOverwriteChangesMadeWhilePickerWasOpen() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.initialize();
        ConfigEditorDocument opened = service.editorDocument(service.source());
        String external = TomlDocumentEditor.set(service.source(), SoundPicker.SOUND_PATH, new JsonPrimitive("custom:menu.switch"));
        Files.writeString(service.file(), external);
        String selected = TomlDocumentEditor.set(opened.source(), SoundPicker.SOUND_PATH,
                new JsonPrimitive(SoundPicker.PRESETS.getFirst().key()));
        assertThatThrownBy(() -> service.save(opened.source(), selected)).isInstanceOf(IOException.class)
                .hasMessageContaining("changed on disk");
        assertThat(Files.readString(service.file())).isEqualTo(external);
    }
}
