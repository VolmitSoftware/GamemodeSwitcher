package com.volmit.gsw.localization;

import art.arcane.volmlib.util.localization.TextKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GuiMessages {
    public static final TextKey DISABLED_WORLDS_INPUT = TextKey.of("gui.config.restrictions.disabled-worlds.input",
            "Use a list of world names, such as [\"world_nether\", \"world_the_end\"]. Use [] for no world restrictions.");
    public static final TextKey SOUND_INPUT = TextKey.of("gui.config.feedback.sound.input",
            "Enter a namespaced sound key, such as minecraft:ui.button.click or minecraft:entity.experience_orb.pickup. Custom resource-pack keys are allowed.");
    private static final Map<String, Entry> ENTRIES = entries();

    private GuiMessages() {
    }

    public static TextKey name(String path) {
        return entry(path).name();
    }

    public static TextKey description(String path) {
        return entry(path).description();
    }

    public static List<TextKey> keys() {
        List<TextKey> keys = new ArrayList<>(ENTRIES.size() * 2 + 2);
        for (Entry entry : ENTRIES.values()) {
            keys.add(entry.name());
            keys.add(entry.description());
        }
        keys.add(DISABLED_WORLDS_INPUT);
        keys.add(SOUND_INPUT);
        return List.copyOf(keys);
    }

    private static Entry entry(String path) {
        return Objects.requireNonNull(ENTRIES.get(path), "Unknown configuration path: " + path);
    }

    private static Map<String, Entry> entries() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        add(entries, "general", "General", "Enable game mode switching and choose the server language.");
        add(entries, "metrics", "Statistics", "Control anonymous bStats reporting.");
        add(entries, "gestures", "Gesture controls", "Set tap timing, cooldowns, and gesture destinations.");
        add(entries, "gestures.normal", "Double-tap destinations", "Choose where double-tapping the swap-hands key takes each game mode.");
        add(entries, "gestures.sneaking", "Sneaking destinations", "Choose where sneaking while double-tapping the swap-hands key takes each game mode.");
        add(entries, "restrictions", "World restrictions", "Choose worlds where game mode switching is unavailable.");
        add(entries, "feedback", "Switch feedback", "Choose switch messages, popups, sounds, and Creative flight behavior.");
        add(entries, "diagnostics", "Diagnostics", "Control public diagnostic report uploads.");
        add(entries, "languages", "Languages", "Edit server translations and their formatting.");
        add(entries, "general.enabled", "Game mode switching", "Allow switching through gestures, commands, and the menu.");
        add(entries, "general.language", "Server language", "Default language for players without a personal choice. Missing translations use English.");
        add(entries, "metrics.enabled", "Anonymous statistics", "Send anonymous bStats statistics. The global bStats opt-out also applies.");
        add(entries, "gestures.double-tap-millis", "Double-tap window", "Maximum time between swap-hands taps in milliseconds. Range: 100 to 3000.");
        add(entries, "gestures.sneak-tap-millis", "Spectator tap window", "Maximum time between spectator sneak presses in milliseconds. Range: 100 to 3000.");
        add(entries, "gestures.cooldown-millis", "Switch cooldown", "Wait time after a successful mode change in milliseconds. Range: 0 to 60000; 0 removes the wait.");
        add(entries, "gestures.require-empty-hands", "Require empty hands", "Require both hands to be empty before a gesture can change your mode.");
        add(entries, "gestures.spectator-exit", "Spectator exit mode", "Destination after three distinct sneak presses in Spectator.");
        add(entries, "gestures.normal.survival", "From Survival", "Destination when you double-tap the swap-hands key in Survival.");
        add(entries, "gestures.normal.creative", "From Creative", "Destination when you double-tap the swap-hands key in Creative.");
        add(entries, "gestures.normal.adventure", "From Adventure", "Destination when you double-tap the swap-hands key in Adventure.");
        add(entries, "gestures.sneaking.survival", "From Survival", "Destination when you sneak and double-tap the swap-hands key in Survival.");
        add(entries, "gestures.sneaking.creative", "From Creative", "Destination when you sneak and double-tap the swap-hands key in Creative.");
        add(entries, "gestures.sneaking.adventure", "From Adventure", "Destination when you sneak and double-tap the swap-hands key in Adventure.");
        add(entries, "restrictions.disabled-worlds", "Disabled worlds", "World names where gestures, commands, and the menu cannot change game modes. Names ignore letter case.");
        add(entries, "feedback.auto-fly-creative", "Fly on entering Creative", "Start flying after entering Creative when the player is allowed to fly.");
        add(entries, "feedback.chat-enabled", "Chat messages", "Show successful changes and Spectator hints in chat. When off, failure reasons appear in the action bar.");
        add(entries, "feedback.action-bar-enabled", "Action bar popup", "Show the new mode above the hotbar, with an exit hint in Spectator.");
        add(entries, "feedback.title-enabled", "Title popup", "Show the new mode in the center of the screen, with an exit hint in Spectator.");
        add(entries, "feedback.popup-duration-ticks", "Popup duration", "Title and action bar duration in ticks; 20 ticks = 1 second. Range: 1 to 200.");
        add(entries, "feedback.sound-enabled", "Switch sound", "Play a confirmation sound after a successful game mode change.");
        add(entries, "feedback.sound", "Sound key", "Sound to play after a successful game mode change.");
        add(entries, "feedback.sound-volume", "Sound volume", "Volume of the switch sound. Range: 0 to 1; 0 is silent.");
        add(entries, "feedback.sound-pitch", "Sound pitch", "Pitch of the switch sound. Range: 0.5 to 2; 1 uses the original pitch.");
        add(entries, "diagnostics.upload-enabled", "Public debug uploads", "Allow diagnostic reports to upload publicly. Use upload=false to save a report locally.");
        return Collections.unmodifiableMap(entries);
    }

    private static void add(Map<String, Entry> entries, String path, String name, String description) {
        String prefix = "gui.config." + path;
        entries.put(path, new Entry(TextKey.of(prefix + ".name", name), TextKey.of(prefix + ".description", description)));
    }

    private record Entry(TextKey name, TextKey description) {
    }
}
