package com.volmit.gsw.command;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentText;
import com.volmit.gsw.GamemodeSwitcher;
import com.volmit.gsw.config.RuntimeConfig;
import com.volmit.gsw.localization.LanguageService;
import com.volmit.gsw.localization.SwitcherMessages;
import com.volmit.gsw.presentation.ChatMenuStyle;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;

@Director(name = "gsw", aliases = {"gamemodeswitcher"}, description = "Game mode switching, personal gestures, and configuration", descriptionKey = "command.description.root")
public final class SwitcherCommands {
    private final GamemodeSwitcher plugin;
    private SwitcherDebugCommands debug;

    public SwitcherCommands(GamemodeSwitcher plugin) {
        this.plugin = plugin;
        debug = new SwitcherDebugCommands(plugin);
    }

    @Director(name = "version", hidden = true, description = "Show the GamemodeSwitcher version", descriptionKey = "command.description.version")
    public void version(@Param(name = "sender", contextual = true) CommandSender sender) {
        debug.version(sender);
    }

    @Director(name = "menu", sync = true, description = "Open the game mode selector", descriptionKey = "command.description.menu")
    public void menu(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (sender instanceof Player player) {
            plugin.getSelector().open(player);
        } else {
            plugin.getLanguageService().sendPrefixed(sender, SwitcherMessages.PLAYER_ONLY);
        }
    }

    @Director(name = "set", sync = true, description = "Change your game mode", descriptionKey = "command.description.set")
    public void set(
            @Param(name = "mode", description = "Survival, creative, adventure, or spectator", descriptionKey = "command.parameter.mode") GameMode mode,
            @Param(name = "sender", contextual = true) CommandSender sender
    ) {
        if (sender instanceof Player player) {
            plugin.getSwitchService().switchMode(player, mode);
        } else {
            plugin.getLanguageService().sendPrefixed(sender, SwitcherMessages.PLAYER_ONLY);
        }
    }

    @Director(name = "return", sync = true, description = "Return from your Spectator session", descriptionKey = "command.description.return")
    public void returnFromSpectator(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (sender instanceof Player player) {
            plugin.getSwitchService().returnFromSpectator(player);
        } else {
            plugin.getLanguageService().sendPrefixed(sender, SwitcherMessages.PLAYER_ONLY);
        }
    }

    @Director(name = "toggle", sync = true, description = "Enable or disable your game mode gestures", descriptionKey = "command.description.toggle")
    public void toggle(
            @Param(name = "enabled", defaultValue = "true", description = "Enable or disable gestures; omit to toggle", descriptionKey = "command.parameter.enabled") boolean enabled,
            @Param(name = "sender", contextual = true) CommandSender sender
    ) {
        if (sender instanceof Player player) {
            plugin.getSwitchService().toggle(player, enabled);
        } else {
            plugin.getLanguageService().sendPrefixed(sender, SwitcherMessages.PLAYER_ONLY);
        }
    }

    @Director(name = "config", sync = true, description = "Open the configuration editor", descriptionKey = "command.description.config")
    public void config(@Param(name = "sender", contextual = true) CommandSender sender) {
        plugin.getConfigEditor().open(sender,
                sender.hasPermission("gamemodeswitcher.use") ? plugin.getSelector()::open : null);
    }

    @Director(name = "language", sync = true, description = "Choose your language or edit server translations", descriptionKey = "command.description.language")
    public void language(@Param(name = "sender", contextual = true) CommandSender sender) {
        plugin.getLanguageSwitcher().open(sender);
    }

    @Director(name = "status", sync = true, description = "Show switching settings and your game mode", descriptionKey = "command.description.status")
    public void status(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!permitted(sender, "gamemodeswitcher.command")) {
            return;
        }
        LanguageService language = plugin.getLanguageService();
        RuntimeConfig config = plugin.getConfigService().runtime();
        ArrayList<String> entries = new ArrayList<>();
        entries.add(statusEntry(sender, SwitcherMessages.STATUS_CONFIG, MessageArgs.builder()
                .untrusted("enabled", state(sender, config.enabled()))
                .untrusted("language", config.language())
                .untrusted("hot_reload", state(sender, true)).build()));
        if (sender instanceof Player player) {
            entries.add(statusEntry(sender, SwitcherMessages.STATUS_PLAYER, MessageArgs.builder()
                    .untrusted("state", state(sender, plugin.getSwitchService().enabled(player)))
                    .untrusted("mode", plugin.modeName(sender, player.getGameMode())).build()));
            for (String line : plugin.getSwitchService().gestureHelp(player)) {
                entries.add(ComponentText.markup(line).miniMessage());
            }
        }
        entries.add(statusEntry(sender, SwitcherMessages.STATUS_COMPATIBILITY,
                MessageArgs.builder().untrusted("scheduler", plugin.schedulerName()).build()));
        if (!(sender instanceof Player)) {
            entries.add(statusEntry(sender, SwitcherMessages.GESTURE_HELP, MessageArgs.empty()));
        }
        String title = ComponentText.markup(language.renderWithoutPrefix(sender, SwitcherMessages.STATUS_HEADER,
                MessageArgs.empty())).plain();
        DirectorMiniMenu.deliverContent(sender, new DirectorMiniMenu.ContentMenu(
                title, "/gsw status", "/gsw", entries, "", 1, entries.size()),
                ChatMenuStyle.theme(), language.directorResolver());
    }

    private String state(CommandSender sender, boolean enabled) {
        TextKey key = enabled ? SwitcherMessages.STATE_ENABLED : SwitcherMessages.STATE_DISABLED;
        return ComponentText.markup(plugin.getLanguageService().renderWithoutPrefix(sender, key, MessageArgs.empty())).plain();
    }

    private String statusEntry(CommandSender sender, TextKey key, MessageArgs arguments) {
        return ComponentText.markup(plugin.getLanguageService().renderWithoutPrefix(sender, key, arguments)).miniMessage();
    }

    private boolean permitted(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        plugin.getLanguageService().sendPrefixed(sender, SwitcherMessages.NO_PERMISSION);
        return false;
    }
}
