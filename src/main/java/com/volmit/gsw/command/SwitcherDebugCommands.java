package com.volmit.gsw.command;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import com.volmit.gsw.GamemodeSwitcher;
import com.volmit.gsw.localization.SwitcherMessages;
import com.volmit.gsw.presentation.ChatMenuStyle;
import org.bukkit.command.CommandSender;

@Director(name = "debug", description = "Inspect diagnostics", descriptionKey = "command.description.debug")
public final class SwitcherDebugCommands {
    private final GamemodeSwitcher plugin;

    public SwitcherDebugCommands(GamemodeSwitcher plugin) {
        this.plugin = plugin;
    }

    @Director(name = "version", description = "Show the GamemodeSwitcher version", descriptionKey = "command.description.version")
    public void version(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("gamemodeswitcher.command")) {
            plugin.getLanguageService().sendPrefixed(sender, SwitcherMessages.NO_PERMISSION);
            return;
        }
        ComponentMessenger.sendMarkup(sender, DirectorMiniMenu.version("GamemodeSwitcher",
                plugin.getDescription().getVersion(), ChatMenuStyle.theme()));
    }

    @Director(name = "dump", sync = true, description = "Create a GamemodeSwitcher diagnostic report", descriptionKey = "command.description.debug_dump")
    public void dump(
            @Param(name = "upload", defaultValue = "true", description = "Upload the report when public uploads are enabled", descriptionKey = "command.parameter.upload") boolean upload,
            @Param(name = "sender", contextual = true) CommandSender sender
    ) {
        if (!sender.hasPermission("gamemodeswitcher.debug")) {
            plugin.getLanguageService().sendPrefixed(sender, SwitcherMessages.NO_PERMISSION);
            return;
        }
        plugin.getDebugDump().request(sender, upload);
    }
}
