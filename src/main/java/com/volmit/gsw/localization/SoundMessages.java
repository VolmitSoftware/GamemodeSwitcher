package com.volmit.gsw.localization;

import art.arcane.volmlib.util.localization.TextKey;

import java.util.List;

public final class SoundMessages {
    public static final TextKey TITLE = TextKey.of("sound.menu.title", "<gradient:#634514:#2f523f>Switch sound</gradient>");
    public static final TextKey CURRENT = TextKey.of("sound.menu.current", "<gray>Current sound: <white>{sound}</white></gray>");
    public static final TextKey SELECT = TextKey.of("sound.menu.select", "<gray>Left-click to use. Right-click to preview.</gray>");
    public static final TextKey SELECTED = TextKey.of("sound.menu.selected", "<green>Selected sound</green>");
    public static final TextKey PREVIEW = TextKey.of("sound.menu.preview", "<gold>Preview current sound</gold>");
    public static final TextKey PREVIEW_HELP = TextKey.of("sound.menu.preview-help", "<gray>Hear this sound at the server's current volume and pitch. Preview works even when switch sounds are muted.</gray>");
    public static final TextKey CUSTOM = TextKey.of("sound.menu.custom", "<gold>Custom sound key</gold>");
    public static final TextKey CUSTOM_HELP = TextKey.of("sound.menu.custom-help", "<gray>Enter a Minecraft or resource-pack sound key.</gray>");
    public static final TextKey CLICK = TextKey.of("sound.preset.click", "<gold>Button click</gold>");
    public static final TextKey CHIME = TextKey.of("sound.preset.chime", "<gold>Chime</gold>");
    public static final TextKey PLING = TextKey.of("sound.preset.pling", "<gold>Pling</gold>");
    public static final TextKey EXPERIENCE = TextKey.of("sound.preset.experience", "<gold>Experience pickup</gold>");
    public static final TextKey BELL = TextKey.of("sound.preset.bell", "<gold>Bell</gold>");

    private SoundMessages() {
    }

    public static List<TextKey> keys() {
        return List.of(TITLE, CURRENT, SELECT, SELECTED, PREVIEW, PREVIEW_HELP, CUSTOM, CUSTOM_HELP,
                CLICK, CHIME, PLING, EXPERIENCE, BELL);
    }
}
