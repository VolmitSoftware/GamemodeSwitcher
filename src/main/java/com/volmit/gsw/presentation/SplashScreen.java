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
            BannerInfo info = new BannerInfo(plugin.getDescription().getVersion(),
                    SplashScreenSupport.serverVersionWithoutMcSuffix(plugin.getServer().getVersion()),
                    SplashScreenSupport.javaMajorVersion(),
                    SplashScreenSupport.startupDate());
            ComponentLog.logLegacy(plugin, plugin.getLogger(), "", Level.INFO, render(info), null);
        } catch (RuntimeException | LinkageError failure) {
            plugin.getLogger().log(Level.WARNING, "GamemodeSwitcher startup banner could not be rendered", failure);
        }
    }

    static String render(BannerInfo info) {
        ChatColor gold = ChatColor.of(ChatMenuStyle.theme().primaryLeft());
        ChatColor mint = ChatColor.of(ChatMenuStyle.theme().primaryRight());
        ChatColor text = ChatColor.of(ChatMenuStyle.theme().description());
        String separator = text + " | ";
        String[] details = {
                "",
                gold + "GamemodeSwitcher" + text + ", Gestures and game modes",
                text + "Version: " + mint + info.version(),
                text + "By: " + mint + "VolmitSoftware (Arcane Arts)" + separator + mint + "VolmitSoftware.com",
                text + "Server: " + mint + info.server() + separator + "MC Support: " + mint + "26.1-26.3",
                text + "Java: " + mint + info.javaVersion() + separator + "Date: " + mint + info.date()
        };
        int width = 0;
        for (String row : ART) {
            width = Math.max(width, row.length());
        }
        StringBuilder output = new StringBuilder("\n");
        for (int row = 0; row < ART.length; row++) {
            ChatColor previous = null;
            for (int index = 0; index < ART[row].length(); index++) {
                char glyph = ART[row].charAt(index);
                if (glyph != ' ') {
                    ChatColor color = glyph == '█' ? gold : mint;
                    if (color != previous) {
                        output.append(color);
                        previous = color;
                    }
                }
                output.append(glyph);
            }
            if (!details[row].isEmpty()) {
                output.append(" ".repeat(width - ART[row].length() + 3)).append(details[row]);
            }
            output.append(ChatColor.RESET).append('\n');
        }
        return output.toString();
    }

    record BannerInfo(String version, String server, int javaVersion, String date) {
    }
}
