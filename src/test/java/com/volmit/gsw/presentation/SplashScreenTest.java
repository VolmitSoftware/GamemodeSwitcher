package com.volmit.gsw.presentation;

import net.md_5.bungee.api.ChatColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SplashScreenTest {
    @Test
    void keepsRuntimeMetadataInOneAlignedColumn() {
        String banner = SplashScreen.render(new SplashScreen.BannerInfo("2.8.0", "Paper 26.3", 25, "2026-10-14"));
        List<String> rows = ChatColor.stripColor(banner).lines().skip(1).toList();
        assertEquals(6, rows.size());
        int column = rows.get(1).indexOf("GamemodeSwitcher");
        List<String> fields = List.of("GamemodeSwitcher", "Version:", "By:", "Server:", "Java:");
        for (int row = 1; row < rows.size(); row++) {
            assertEquals(column, rows.get(row).indexOf(fields.get(row - 1)));
        }
        assertTrue(rows.get(2).endsWith("Version: 2.8.0"));
        assertTrue(rows.get(4).endsWith("Server: Paper 26.3 | MC Support: 26.1-26.3"));
        assertTrue(rows.get(5).endsWith("Java: 25 | Date: 2026-10-14"));
    }

    @Test
    void usesBothBrandColorsAndResetsEveryRow() {
        String banner = SplashScreen.render(new SplashScreen.BannerInfo("2.8.0", "Folia 26.3", 25, "2026-10-14"));
        assertTrue(banner.contains(ChatColor.of(ChatMenuStyle.theme().primaryLeft()).toString()));
        assertTrue(banner.contains(ChatColor.of(ChatMenuStyle.theme().primaryRight()).toString()));
        assertEquals(6, banner.lines().filter(row -> row.endsWith(ChatColor.RESET.toString())).count());
    }
}
