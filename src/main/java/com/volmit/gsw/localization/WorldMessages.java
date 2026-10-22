package com.volmit.gsw.localization;

import art.arcane.volmlib.util.localization.TextKey;

import java.util.List;

public final class WorldMessages {
    public static final TextKey TITLE = TextKey.of("world.menu.title", "<gradient:#634514:#2f523f>Disabled worlds</gradient>");
    public static final TextKey HELP = TextKey.of("world.menu.help", "<gold>Disabled worlds</gold>");
    public static final TextKey HELP_DETAIL = TextKey.of("world.menu.help-detail", "<gray>Click a world to turn game mode switching off or on there. Worlds are saved by dimension key, such as minecraft:the_nether.</gray>");
    public static final TextKey FOLDER = TextKey.of("world.menu.folder", "<gray>World folder: <white>{folder}</white></gray>");
    public static final TextKey UNLOADED = TextKey.of("world.menu.unloaded", "<gray>Not loaded on this server</gray>");
    public static final TextKey DISABLED = TextKey.of("world.menu.disabled", "<red>Switching off</red>");
    public static final TextKey ENABLED = TextKey.of("world.menu.enabled", "<green>Switching on</green>");
    public static final TextKey DISABLE = TextKey.of("world.menu.disable", "<gray>Click to turn switching off here.</gray>");
    public static final TextKey ENABLE = TextKey.of("world.menu.enable", "<gray>Click to turn switching back on here.</gray>");
    public static final TextKey REMOVE = TextKey.of("world.menu.remove", "<gray>Click to remove it from the list.</gray>");

    private WorldMessages() {
    }

    public static List<TextKey> keys() {
        return List.of(TITLE, HELP, HELP_DETAIL, FOLDER, UNLOADED, DISABLED, ENABLED, DISABLE, ENABLE, REMOVE);
    }
}
