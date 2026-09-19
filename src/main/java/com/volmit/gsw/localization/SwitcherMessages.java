package com.volmit.gsw.localization;

import art.arcane.volmlib.util.config.BukkitConfigMessages;
import art.arcane.volmlib.util.diagnostics.BukkitDebugMessages;
import art.arcane.volmlib.util.director.DirectorMessages;
import art.arcane.volmlib.util.localization.BukkitLanguageMessages;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.TextKey;
import org.bukkit.GameMode;

import java.util.List;

public final class SwitcherMessages {
    public static final TextKey PREFIX = TextKey.of("runtime.prefix", "<gradient:#e9bd68:#85d6b0><bold>GamemodeSwitcher</bold></gradient> &8›&r ");
    public static final TextKey NO_PERMISSION = prefixed("runtime.permission.denied", "&cYou do not have permission to do that.&r");
    public static final TextKey PLAYER_ONLY = prefixed("runtime.player_only", "&cThis command can only be used by a player.&r");
    public static final TextKey HOT_RELOAD_SUCCESS = prefixed("runtime.hot_reload.success", "&aApplied configuration and language file changes.&r");
    public static final TextKey HOT_RELOAD_FAILED = prefixed("runtime.hot_reload.failed", "&cRejected file changes; the last known good settings remain active.&r");
    public static final TextKey COMMAND_CONFIG_OPENED = prefixed("command.feedback.config.opened", "&aOpened the configuration editor.&r");
    public static final TextKey COMMAND_FAILED = prefixed("command.feedback.failed", "&cThe command could not be completed. See the console for details.&r");
    public static final TextKey CONFIG_SAVED = prefixed("command.feedback.config.saved", "&a{setting}&r &7changed from &f{old}&r &7to &f{new}&r&7.&r");
    public static final TextKey CONFIG_SAVE_FAILED = prefixed("command.feedback.config.save_failed", "&cCould not apply &f{setting}&r&c: {reason}&r");
    public static final TextKey STATUS_HEADER = TextKey.of("command.status.header", "&6&lGamemodeSwitcher&r");
    public static final TextKey STATUS_CONFIG = TextKey.of("command.status.config", "&7Switching:&r {enabled} &8|&r &7Language:&r &f{language}&r &8|&r &7Hot reload:&r {hot_reload}");
    public static final TextKey STATUS_PLAYER = TextKey.of("command.status.player", "&7Gestures:&r {state} &8|&r &7Game mode:&r {mode}");
    public static final TextKey STATUS_COMPATIBILITY = TextKey.of("command.status.compatibility", "&7Artifact:&r &fJava 17 / Bukkit 1.20.1+&r &8|&r &7Scheduler:&r &f{scheduler}&r");
    public static final TextKey STATE_ENABLED = TextKey.of("state.enabled", "&aEnabled&r");
    public static final TextKey STATE_DISABLED = TextKey.of("state.disabled", "&cDisabled&r");
    public static final TextKey MODE_SURVIVAL = TextKey.of("mode.survival", "Survival");
    public static final TextKey MODE_CREATIVE = TextKey.of("mode.creative", "Creative");
    public static final TextKey MODE_ADVENTURE = TextKey.of("mode.adventure", "Adventure");
    public static final TextKey MODE_SPECTATOR = TextKey.of("mode.spectator", "Spectator");
    public static final TextKey SWITCH_SUCCESS = prefixed("switch.changed", "&aGame mode changed to &f{mode}&r&a.&r");
    public static final TextKey SWITCH_TITLE = TextKey.of("switch.title", "<gradient:#e9bd68:#85d6b0>{mode}</gradient>");
    public static final TextKey SWITCH_SUBTITLE = TextKey.of("switch.subtitle", "&7Game mode changed.&r");
    public static final TextKey SWITCH_OVERLAY = TextKey.of("switch.overlay", "&7Game mode: &f{mode}&r");
    public static final TextKey SWITCH_SPECTATOR_OVERLAY_HINT = TextKey.of("switch.spectator_overlay_hint", "&7Sneak three times to leave Spectator.&r");
    public static final TextKey SWITCH_DENIED = prefixed("switch.no_permission", "&cYou do not have permission to use that game mode.&r");
    public static final TextKey SWITCH_DISABLED = prefixed("switch.disabled", "&eGame mode switching is disabled.&r");
    public static final TextKey SWITCH_BLOCKED_WORLD = prefixed("switch.world_blocked", "&eGame mode switching is unavailable in &f{world}&r&e.&r");
    public static final TextKey SWITCH_COOLDOWN = prefixed("switch.cooldown", "&eWait &f{seconds}&r&e seconds before switching again.&r");
    public static final TextKey SWITCH_CANCELLED = prefixed("switch.cancelled", "&eAnother plugin prevented the game mode change.&r");
    public static final TextKey SWITCH_SPECTATOR_HINT = prefixed("switch.spectator_hint", "&7Use &f/gsw menu&r&7 or &f/gsw set survival&r&7 to leave spectator mode.&r");
    public static final TextKey SWITCH_EMPTY_HANDS = prefixed("switch.empty_hands", "&eEmpty both hands to use game mode gestures.&r");
    public static final TextKey SWITCH_ALREADY = prefixed("switch.already", "&7Your game mode is already &f{mode}&r&7.&r");
    public static final TextKey TOGGLE_ENABLED = prefixed("toggle.enabled", "&aGame mode gestures enabled.&r");
    public static final TextKey TOGGLE_DISABLED = prefixed("toggle.disabled", "&eGame mode gestures disabled. Commands and the menu remain available.&r");
    public static final TextKey PREFERENCES_FAILED = prefixed("preferences.failed", "&cCould not save your preference. See the console for details.&r");
    public static final TextKey GESTURE_HELP = TextKey.of("gesture.help", "&7Double-tap the swap-hands key (F by default) to switch modes. Sneak while doing this for the alternate action; triple-tap sneak to leave spectator. Destinations follow the server settings.&r");
    public static final TextKey GESTURE_NORMAL = TextKey.of("gesture.normal", "&7Double-tap Swap Item With Offhand: &f{mode}&r");
    public static final TextKey GESTURE_SNEAKING = TextKey.of("gesture.sneaking", "&7Hold Sneak + double-tap Swap Item With Offhand: &f{mode}&r");
    public static final TextKey GESTURE_SPECTATOR = TextKey.of("gesture.spectator", "&7Press Sneak three separate times: &f{mode}&r");
    public static final TextKey GESTURE_REBIND = TextKey.of("gesture.rebind", "&7Rebind Swap Item With Offhand (default F) and Sneak (default Left Shift) in Options > Controls > Key Binds.&r");
    public static final TextKey GESTURE_PERSONAL_DISABLED = TextKey.of("gesture.personal_disabled", "&eYour gestures are disabled. Enable them in this menu or with /gsw toggle true.&r");
    public static final TextKey GESTURE_EMPTY_HANDS = TextKey.of("gesture.empty_hands", "&7Both hands must be empty to use gestures.&r");
    public static final TextKey MENU_FEEDBACK = TextKey.of("menu.feedback", "&6Personal feedback&r");
    public static final TextKey MENU_FEEDBACK_HELP = TextKey.of("menu.feedback_help", "&7Mute switch messages, popups, or sounds for yourself.&r");
    public static final TextKey FEEDBACK_TITLE = TextKey.of("preferences.title", "<gradient:#634514:#2f523f>Personal Feedback</gradient>");
    public static final TextKey FEEDBACK_CHAT = TextKey.of("preferences.chat", "&6Chat messages&r");
    public static final TextKey FEEDBACK_ACTION_BAR = TextKey.of("preferences.action_bar", "&6Action bar&r");
    public static final TextKey FEEDBACK_SCREEN_TITLE = TextKey.of("preferences.screen_title", "&6Title popups&r");
    public static final TextKey FEEDBACK_SOUND = TextKey.of("preferences.sound", "&6Switch sounds&r");
    public static final TextKey FEEDBACK_MUTED = TextKey.of("preferences.muted", "&cMuted for you&r");
    public static final TextKey FEEDBACK_INHERIT = TextKey.of("preferences.inherit", "&7Following server settings: {state}&r");
    public static final TextKey FEEDBACK_TOGGLE = TextKey.of("preferences.toggle", "&7Click to switch between muted and server settings.&r");
    public static final TextKey FEEDBACK_SILENCE = TextKey.of("preferences.silence", "&6Silence all&r");
    public static final TextKey FEEDBACK_SILENCE_HELP = TextKey.of("preferences.silence_help", "&7Mute successful switch messages, popups, and sounds.&r");
    public static final TextKey FEEDBACK_RESET = TextKey.of("preferences.reset", "&aUse server settings&r");
    public static final TextKey FEEDBACK_RESET_HELP = TextKey.of("preferences.reset_help", "&7Remove your personal mutes for every channel.&r");
    public static final TextKey FEEDBACK_FAILURES = TextKey.of("preferences.failures", "&7Blocked switches still explain why in the action bar when chat is muted.&r");
    public static final TextKey MENU_BACK = TextKey.of("menu.back", "&7Back to game modes&r");
    public static final TextKey MENU_TITLE = TextKey.of("menu.title", "<gradient:#634514:#2f523f>Game Modes</gradient>");
    public static final TextKey MENU_CURRENT = TextKey.of("menu.current", "&7Current game mode: &f{mode}&r");
    public static final TextKey MENU_SELECT = TextKey.of("menu.select", "&7Click to select.&r");
    public static final TextKey MENU_SELECTED = TextKey.of("menu.selected", "&aSelected&r");
    public static final TextKey MENU_SURVIVAL_HELP = TextKey.of("menu.survival_help", "&7Gather resources and manage health and hunger.&r");
    public static final TextKey MENU_CREATIVE_HELP = TextKey.of("menu.creative_help", "&7Build with unlimited resources and Creative flight.&r");
    public static final TextKey MENU_ADVENTURE_HELP = TextKey.of("menu.adventure_help", "&7Explore maps with restricted block breaking and placement.&r");
    public static final TextKey MENU_SPECTATOR_HELP = TextKey.of("menu.spectator_help", "&7Observe the world and pass through blocks.&r");
    public static final TextKey MENU_GESTURES = TextKey.of("menu.gestures", "&6Gesture controls&r");
    public static final TextKey MENU_TOGGLE_HELP = TextKey.of("menu.toggle_help", "&7Click to toggle gestures.&r");
    public static final TextKey MENU_UNAVAILABLE = TextKey.of("menu.unavailable", "&cYou cannot select this game mode.&r");
    public static final TextKey MENU_TOGGLE_ENABLED = TextKey.of("menu.toggle_enabled", "&aGestures enabled&r");
    public static final TextKey MENU_TOGGLE_DISABLED = TextKey.of("menu.toggle_disabled", "&cGestures disabled&r");
    public static final TextKey MENU_CONFIG = TextKey.of("menu.config", "&6Configuration&r");
    public static final TextKey MENU_CONFIG_HELP = TextKey.of("menu.config_help", "&7Edit server settings. Changes apply immediately.&r");
    public static final TextKey MENU_LANGUAGE = TextKey.of("menu.language", "&aLanguage&r");
    public static final TextKey MENU_LANGUAGE_HELP = TextKey.of("menu.language_help", "&7Choose the language used for your messages and menus.&r");
    public static final TextKey MENU_CLOSE = TextKey.of("menu.close", "&cClose&r");
    public static final TextKey GUI_ROOT_TITLE = TextKey.of("gui.title.root", "<gradient:#634514:#2f523f>GSW</gradient> <color:#404040>Configuration</color>");
    public static final TextKey COMMAND_ROOT = TextKey.of("command.description.root", "Game mode switching, personal gestures, and configuration");
    public static final TextKey COMMAND_MENU = TextKey.of("command.description.menu", "Open the game mode selector");
    public static final TextKey COMMAND_SET = TextKey.of("command.description.set", "Change your game mode");
    public static final TextKey COMMAND_TOGGLE = TextKey.of("command.description.toggle", "Enable or disable your game mode gestures");
    public static final TextKey COMMAND_STATUS = TextKey.of("command.description.status", "Show switching settings and your game mode");
    public static final TextKey COMMAND_CONFIG = TextKey.of("command.description.config", "Open the configuration editor");
    public static final TextKey COMMAND_LANGUAGE = TextKey.of("command.description.language", "Choose your language or edit server translations");
    public static final TextKey COMMAND_DEBUG = TextKey.of("command.description.debug", "Inspect diagnostics");
    public static final TextKey COMMAND_VERSION = TextKey.of("command.description.version", "Show the GamemodeSwitcher version");
    public static final TextKey COMMAND_DEBUG_DUMP = TextKey.of("command.description.debug_dump", "Create a GamemodeSwitcher diagnostic report");
    public static final TextKey PARAMETER_MODE = TextKey.of("command.parameter.mode", "Survival, creative, adventure, or spectator");
    public static final TextKey PARAMETER_ENABLED = TextKey.of("command.parameter.enabled", "Enable or disable gestures; omit to toggle");
    public static final TextKey PARAMETER_UPLOAD = TextKey.of("command.parameter.upload", "Upload the report when public uploads are enabled");

    private SwitcherMessages() {
    }

    public static MessageCatalog catalog() {
        MessageCatalog.Builder builder = MessageCatalog.builder("en_US");
        builder.addAll(productKeys());
        builder.addAll(GuiMessages.keys());
        builder.addAll(DirectorMessages.keys());
        builder.addAll(BukkitLanguageMessages.keys());
        builder.addAll(BukkitDebugMessages.keys());
        builder.addAll(BukkitConfigMessages.keys());
        builder.addAll(BukkitConfigMessages.configuredLayoutKeys());
        return builder.build();
    }

    public static TextKey mode(GameMode mode) {
        return switch (mode) {
            case SURVIVAL -> MODE_SURVIVAL;
            case CREATIVE -> MODE_CREATIVE;
            case ADVENTURE -> MODE_ADVENTURE;
            case SPECTATOR -> MODE_SPECTATOR;
        };
    }

    private static TextKey prefixed(String id, String english) {
        return TextKey.ofOptional(id, "{prefix}" + english, "prefix");
    }

    private static List<TextKey> productKeys() {
        return List.of(PREFIX, NO_PERMISSION, PLAYER_ONLY, HOT_RELOAD_SUCCESS, HOT_RELOAD_FAILED,
                COMMAND_CONFIG_OPENED, COMMAND_FAILED, CONFIG_SAVED, CONFIG_SAVE_FAILED,
                STATUS_HEADER, STATUS_CONFIG, STATUS_PLAYER, STATUS_COMPATIBILITY,
                STATE_ENABLED, STATE_DISABLED, MODE_SURVIVAL, MODE_CREATIVE, MODE_ADVENTURE, MODE_SPECTATOR,
                SWITCH_SUCCESS, SWITCH_TITLE, SWITCH_SUBTITLE, SWITCH_OVERLAY, SWITCH_SPECTATOR_OVERLAY_HINT,
                SWITCH_DENIED, SWITCH_DISABLED, SWITCH_BLOCKED_WORLD, SWITCH_COOLDOWN,
                SWITCH_CANCELLED, SWITCH_SPECTATOR_HINT, SWITCH_EMPTY_HANDS, SWITCH_ALREADY, TOGGLE_ENABLED,
                TOGGLE_DISABLED, PREFERENCES_FAILED, GESTURE_HELP, GESTURE_NORMAL, GESTURE_SNEAKING, GESTURE_SPECTATOR,
                GESTURE_REBIND, GESTURE_PERSONAL_DISABLED, GESTURE_EMPTY_HANDS, MENU_FEEDBACK, MENU_FEEDBACK_HELP,
                FEEDBACK_TITLE, FEEDBACK_CHAT, FEEDBACK_ACTION_BAR, FEEDBACK_SCREEN_TITLE, FEEDBACK_SOUND,
                FEEDBACK_MUTED, FEEDBACK_INHERIT, FEEDBACK_TOGGLE, FEEDBACK_SILENCE, FEEDBACK_SILENCE_HELP,
                FEEDBACK_RESET, FEEDBACK_RESET_HELP, FEEDBACK_FAILURES, MENU_BACK, MENU_TITLE, MENU_CURRENT, MENU_SELECT,
                MENU_UNAVAILABLE, MENU_GESTURES, MENU_TOGGLE_HELP, MENU_TOGGLE_ENABLED, MENU_TOGGLE_DISABLED, MENU_CONFIG, MENU_LANGUAGE,
                MENU_SELECTED, MENU_SURVIVAL_HELP, MENU_CREATIVE_HELP, MENU_ADVENTURE_HELP, MENU_SPECTATOR_HELP,
                MENU_CONFIG_HELP, MENU_LANGUAGE_HELP, MENU_CLOSE, GUI_ROOT_TITLE, COMMAND_ROOT, COMMAND_MENU, COMMAND_SET, COMMAND_TOGGLE,
                COMMAND_STATUS, COMMAND_CONFIG, COMMAND_LANGUAGE, COMMAND_DEBUG, COMMAND_VERSION,
                COMMAND_DEBUG_DUMP, PARAMETER_MODE, PARAMETER_ENABLED, PARAMETER_UPLOAD);
    }
}
