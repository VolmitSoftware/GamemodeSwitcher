package com.volmit.gsw.presentation;

import art.arcane.volmlib.util.plugin.ComponentLog;
import art.arcane.volmlib.util.plugin.SplashScreenSupport;
import com.volmit.gsw.GamemodeSwitcher;
import net.md_5.bungee.api.ChatColor;

import java.util.logging.Level;

public final class SplashScreen {
    private static final String[] ART = {
            " ██████╗ ███████╗██╗    ██╗",
            "██╔════╝ ██╔════╝██║    ██║",
            "██║  ███╗███████╗██║ █╗ ██║",
            "██║   ██║╚════██║██║███╗██║",
            "╚██████╔╝███████║╚███╔███╔╝",
            " ╚═════╝ ╚══════╝ ╚══╝╚══╝ "
    };

    private SplashScreen() {
    }

    public static void print(GamemodeSwitcher plugin) {
        try {
            String[] details = {
                    "",
                    "GamemodeSwitcher | Gestures and game modes",
                    "Version: " + plugin.getDescription().getVersion(),
                    "By: VolmitSoftware (Arcane Arts) | VolmitSoftware.com",
                    "Server: " + SplashScreenSupport.serverVersionWithoutMcSuffix() + " | MC Support: 1.20.1+",
                    "Java: " + SplashScreenSupport.javaMajorVersion() + " | " + plugin.schedulerName()
                            + " | Date: " + SplashScreenSupport.startupDate()
            };
            ChatColor gold = ChatColor.of(ChatMenuStyle.theme().primaryLeft());
            ChatColor mint = ChatColor.of(ChatMenuStyle.theme().primaryRight());
            ChatColor detail = ChatColor.of(ChatMenuStyle.theme().description());
            StringBuilder output = new StringBuilder("\n");
            for (int row = 0; row < ART.length; row++) {
                for (int index = 0; index < ART[row].length(); index++) {
                    char glyph = ART[row].charAt(index);
                    output.append(glyph == '█' ? gold : mint).append(glyph);
                }
                output.append(detail).append("   ").append(details[row]).append('\n');
            }
            ComponentLog.logLegacy(plugin, plugin.getLogger(), "", Level.INFO, output.toString(), null);
        } catch (RuntimeException | LinkageError failure) {
            plugin.getLogger().log(Level.WARNING, "GamemodeSwitcher startup banner could not be rendered", failure);
        }
    }
}
