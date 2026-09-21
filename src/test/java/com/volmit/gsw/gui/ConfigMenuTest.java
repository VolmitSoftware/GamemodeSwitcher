package com.volmit.gsw.gui;

import art.arcane.volmlib.util.config.BukkitConfigEditor;
import art.arcane.volmlib.util.config.ConfigEditorDocument;
import art.arcane.volmlib.util.config.TomlDocumentEditor;
import com.volmit.gsw.config.ConfigService;
import com.volmit.gsw.localization.SwitcherMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigMenuTest {
    @TempDir
    Path directory;

    @Test
    void everyRuntimeSettingHasLocalizedEditorMetadata() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.initialize();
        ConfigEditorDocument document = ConfigEditorDocument.fromToml(service.source());
        Map<String, BukkitConfigEditor.EntryPresentation> metadata = ConfigMenu.entries(null);
        ArrayList<String> paths = new ArrayList<>();
        collectPaths(document, List.of(), paths);

        assertThat(metadata.keySet()).containsExactlyInAnyOrderElementsOf(paths);
        for (BukkitConfigEditor.EntryPresentation entry : metadata.values()) {
            assertThat(SwitcherMessages.catalog().require(entry.name().id())).isEqualTo(entry.name());
            assertThat(SwitcherMessages.catalog().require(entry.description().id())).isEqualTo(entry.description());
            if (entry.inputGuidance() != null) {
                assertThat(SwitcherMessages.catalog().require(entry.inputGuidance().id())).isEqualTo(entry.inputGuidance());
            }
        }
        List<String> roots = metadata.entrySet().stream()
                .filter(entry -> !entry.getKey().contains("."))
                .sorted(Comparator.comparingInt(entry -> entry.getValue().order()))
                .map(Map.Entry::getKey).toList();
        assertThat(roots).containsExactly("general", "metrics", "gestures", "restrictions", "feedback", "spectator", "diagnostics");
    }

    @Test
    void numericControlsUseValidRuntimeBounds() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.initialize();
        ConfigEditorDocument document = ConfigEditorDocument.fromToml(service.source());
        Map<String, BukkitConfigEditor.EntryPresentation> metadata = ConfigMenu.entries(null);
        for (Map.Entry<String, BukkitConfigEditor.EntryPresentation> entry : metadata.entrySet()) {
            BukkitConfigEditor.NumericControl control = entry.getValue().numeric();
            if (control == null) {
                continue;
            }
            List<String> path = List.of(entry.getKey().split("\\."));
            double current = document.value(path).getAsDouble();
            assertThat(control.step()).isPositive();
            assertThat(current).isBetween(control.minimum(), control.maximum());
            for (double boundary : new double[]{control.minimum(), control.maximum()}) {
                String input = boundary == (long) boundary ? Long.toString((long) boundary) : Double.toString(boundary);
                String replacement = TomlDocumentEditor.set(service.source(), path, document.parseValue(path, input));
                assertThat(ConfigService.parse(replacement)).isNotNull();
            }
        }
    }

    private void collectPaths(ConfigEditorDocument document, List<String> path, List<String> paths) {
        for (ConfigEditorDocument.Entry entry : document.entries(path)) {
            paths.add(String.join(".", entry.path()));
            if (entry.kind() == ConfigEditorDocument.Kind.TABLE) {
                collectPaths(document, entry.path(), paths);
            }
        }
    }
}
