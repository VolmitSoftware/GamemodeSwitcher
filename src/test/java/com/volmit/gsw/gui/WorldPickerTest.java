package com.volmit.gsw.gui;

import art.arcane.volmlib.util.config.ConfigEditorDocument;
import art.arcane.volmlib.util.config.TomlDocumentEditor;
import com.google.gson.JsonArray;
import com.volmit.gsw.config.ConfigService;
import com.volmit.gsw.localization.SwitcherMessages;
import com.volmit.gsw.localization.WorldMessages;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorldPickerTest {
    @TempDir
    Path directory;

    @Test
    void togglingAddsAndRemovesDimensionKeysAndSavesValidConfiguration() throws IOException {
        ConfigService service = new ConfigService(directory.toFile());
        service.initialize();
        ConfigEditorDocument document = service.editorDocument(service.source());
        assertThat(WorldPicker.configuredKeys(document)).isEmpty();
        World nether = world("world_nether", NamespacedKey.minecraft("the_nether"), World.Environment.NETHER);
        JsonArray added = WorldPicker.toggled(WorldPicker.configuredKeys(document),
                WorldPicker.choices(List.of(nether), WorldPicker.configuredKeys(document)).getFirst());
        String saved = TomlDocumentEditor.set(document.source(), WorldPicker.WORLDS_PATH, added);
        service.save(document.source(), saved);
        assertThat(service.runtime().disabledWorlds()).containsExactly("minecraft:the_nether");
        ConfigEditorDocument reopened = service.editorDocument(service.source());
        assertThat(WorldPicker.configuredKeys(reopened)).containsExactly("minecraft:the_nether");
        JsonArray removed = WorldPicker.toggled(WorldPicker.configuredKeys(reopened),
                WorldPicker.choices(List.of(nether), WorldPicker.configuredKeys(reopened)).getFirst());
        service.save(reopened.source(), TomlDocumentEditor.set(reopened.source(), WorldPicker.WORLDS_PATH, removed));
        assertThat(service.runtime().disabledWorlds()).isEmpty();
    }

    @Test
    void choicesListLoadedWorldsByKeyAndKeepConfiguredUnloadedWorlds() {
        World nether = world("world_nether", NamespacedKey.minecraft("the_nether"), World.Environment.NETHER);
        World overworld = world("world", NamespacedKey.minecraft("overworld"), World.Environment.NORMAL);
        List<WorldPicker.Choice> choices = WorldPicker.choices(List.of(nether, overworld),
                List.of("minecraft:the_nether", "minecraft:creative_lobby"));
        assertThat(choices).containsExactly(
                new WorldPicker.Choice("minecraft:creative_lobby", null, Material.PAPER, List.of("minecraft:creative_lobby")),
                new WorldPicker.Choice("minecraft:overworld", "world", Material.GRASS_BLOCK, List.of()),
                new WorldPicker.Choice("minecraft:the_nether", "world_nether", Material.NETHERRACK, List.of("minecraft:the_nether")));
    }

    @Test
    void legacyFolderNamesMatchLoadedWorldsAndAreReplacedWhenToggled() {
        World nether = world("World_Nether", NamespacedKey.minecraft("the_nether"), World.Environment.NETHER);
        List<String> configured = List.of("world_nether", "minecraft:the_nether", "old_lobby");
        List<WorldPicker.Choice> choices = WorldPicker.choices(List.of(nether), configured);
        assertThat(choices).containsExactly(
                new WorldPicker.Choice("minecraft:the_nether", "World_Nether", Material.NETHERRACK,
                        List.of("world_nether", "minecraft:the_nether")),
                new WorldPicker.Choice("old_lobby", null, Material.PAPER, List.of("old_lobby")));
        assertThat(WorldPicker.toggled(configured, choices.getFirst()).asList())
                .extracting(element -> element.getAsString()).containsExactly("old_lobby");
        assertThat(WorldPicker.toggled(List.of(), new WorldPicker.Choice("minecraft:the_nether", "World_Nether",
                Material.NETHERRACK, List.of())).asList()).extracting(element -> element.getAsString())
                .containsExactly("minecraft:the_nether");
    }

    @Test
    void pickerMessagesAreInTheCatalogAndSlotsFitTheMenu() {
        WorldMessages.keys().forEach(key -> assertThat(SwitcherMessages.catalog().require(key.id())).isEqualTo(key));
        assertThat(WorldPicker.WORLD_SLOTS).doesNotHaveDuplicates().allMatch(slot -> slot > 8 && slot < 45);
    }

    private static World world(String name, NamespacedKey key, World.Environment environment) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        when(world.getKey()).thenReturn(key);
        when(world.getEnvironment()).thenReturn(environment);
        return world;
    }
}
