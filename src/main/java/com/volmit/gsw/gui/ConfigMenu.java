package com.volmit.gsw.gui;

import art.arcane.volmlib.util.config.BukkitConfigEditor;
import com.volmit.gsw.GamemodeSwitcher;
import com.volmit.gsw.localization.GuiMessages;
import com.volmit.gsw.localization.SwitcherMessages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class ConfigMenu {
    private ConfigMenu() {
    }

    public static void configure(GamemodeSwitcher plugin) {
        Map<String, BukkitConfigEditor.EntryPresentation> entries = entries(plugin);
        BukkitConfigEditor.RootShortcut languages = new BukkitConfigEditor.RootShortcut(
                GuiMessages.name("languages"), GuiMessages.description("languages"), Material.WRITABLE_BOOK,
                player -> plugin.getLanguageSwitcher().openEditor(player, returned ->
                        plugin.getConfigEditor().open(returned, returnAction(plugin, returned))));
        plugin.getConfigEditor().configureLayout(new BukkitConfigEditor.EditorLayout(
                SwitcherMessages.GUI_ROOT_TITLE, path -> entries.get(String.join(".", path)), List.of(languages)));
    }

    static Map<String, BukkitConfigEditor.EntryPresentation> entries(GamemodeSwitcher plugin) {
        Map<String, BukkitConfigEditor.EntryPresentation> entries = new LinkedHashMap<>();
        add(entries, "general", Material.COMPARATOR, 0);
        add(entries, "metrics", Material.SPYGLASS, 1);
        add(entries, "gestures", Material.CLOCK, 2);
        add(entries, "restrictions", Material.IRON_DOOR, 3);
        add(entries, "feedback", Material.NOTE_BLOCK, 4);
        add(entries, "spectator", Material.ENDER_EYE, 5);
        add(entries, "diagnostics", Material.PAPER, 6);
        add(entries, "spectator.restore-previous-mode", Material.ENDER_EYE, 0);
        add(entries, "spectator.return-to-origin", Material.COMPASS, 1);
        add(entries, "spectator.allow-unsafe-return", Material.ENDER_PEARL, 2);
        add(entries, "general.enabled", Material.LEVER, 0);
        entries.put("general.language", new BukkitConfigEditor.EntryPresentation(
                GuiMessages.name("general.language"), GuiMessages.description("general.language"),
                Material.WRITABLE_BOOK, 1, null, null,
                player -> plugin.getLanguageSwitcher().command(player, new String[]{"server"})));
        add(entries, "metrics.enabled", Material.LEVER, 0);
        addNumber(entries, "gestures.double-tap-millis", Material.CLOCK, 0, new BukkitConfigEditor.NumericControl(50, 100, 3000));
        addNumber(entries, "gestures.sneak-tap-millis", Material.CLOCK, 1, new BukkitConfigEditor.NumericControl(50, 100, 3000));
        addNumber(entries, "gestures.cooldown-millis", Material.CLOCK, 2, new BukkitConfigEditor.NumericControl(50, 0, 60000));
        add(entries, "gestures.require-empty-hands", Material.LEVER, 3);
        add(entries, "gestures.spectator-exit", Material.ENDER_EYE, 4);
        add(entries, "gestures.normal", Material.IRON_PICKAXE, 5);
        add(entries, "gestures.sneaking", Material.LEATHER_BOOTS, 6);
        for (String group : List.of("normal", "sneaking")) {
            add(entries, "gestures." + group + ".survival", Material.IRON_PICKAXE, 0);
            add(entries, "gestures." + group + ".creative", Material.GRASS_BLOCK, 1);
            add(entries, "gestures." + group + ".adventure", Material.MAP, 2);
        }
        entries.put("restrictions.disabled-worlds", new BukkitConfigEditor.EntryPresentation(
                GuiMessages.name("restrictions.disabled-worlds"), GuiMessages.description("restrictions.disabled-worlds"),
                Material.FILLED_MAP, 0, null, GuiMessages.DISABLED_WORLDS_INPUT, null));
        add(entries, "feedback.chat-enabled", Material.PAPER, 0);
        add(entries, "feedback.action-bar-enabled", Material.NAME_TAG, 1);
        add(entries, "feedback.title-enabled", Material.OAK_SIGN, 2);
        addNumber(entries, "feedback.popup-duration-ticks", Material.CLOCK, 3, new BukkitConfigEditor.NumericControl(10, 1, 200));
        add(entries, "feedback.sound-enabled", Material.NOTE_BLOCK, 4);
        entries.put("feedback.sound", new BukkitConfigEditor.EntryPresentation(
                GuiMessages.name("feedback.sound"), GuiMessages.description("feedback.sound"),
                Material.JUKEBOX, 5, null, GuiMessages.SOUND_INPUT, player -> plugin.getSoundPicker().open(player)));
        addNumber(entries, "feedback.sound-volume", Material.NOTE_BLOCK, 6, new BukkitConfigEditor.NumericControl(0.1, 0, 1));
        addNumber(entries, "feedback.sound-pitch", Material.NOTE_BLOCK, 7, new BukkitConfigEditor.NumericControl(0.1, 0.5, 2));
        add(entries, "feedback.auto-fly-creative", Material.FEATHER, 8);
        add(entries, "diagnostics.upload-enabled", Material.PAPER, 0);
        return Map.copyOf(entries);
    }

    private static void add(Map<String, BukkitConfigEditor.EntryPresentation> entries,
                            String path, Material material, int order) {
        entries.put(path, new BukkitConfigEditor.EntryPresentation(
                GuiMessages.name(path), GuiMessages.description(path), material, order, null, null, null));
    }

    private static void addNumber(Map<String, BukkitConfigEditor.EntryPresentation> entries,
                                  String path, Material material, int order, BukkitConfigEditor.NumericControl control) {
        entries.put(path, new BukkitConfigEditor.EntryPresentation(
                GuiMessages.name(path), GuiMessages.description(path), material, order, control, null, null));
    }

    private static Consumer<Player> returnAction(GamemodeSwitcher plugin, Player player) {
        return player.hasPermission("gamemodeswitcher.use") ? plugin.getSelector()::open : null;
    }
}
