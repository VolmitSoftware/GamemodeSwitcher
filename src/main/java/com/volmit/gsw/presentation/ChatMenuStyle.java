package com.volmit.gsw.presentation;

import art.arcane.volmlib.util.director.help.DirectorMiniMenu;

public final class ChatMenuStyle {
    private static final DirectorMiniMenu.Theme THEME = new DirectorMiniMenu.Theme(
            "#e9bd68", "#85d6b0", "#534023", "#527b63",
            "#e5e9df", "#ff8080", "#d5d5ba", "#adbbaa");

    private ChatMenuStyle() {
    }

    public static DirectorMiniMenu.Theme theme() {
        return THEME;
    }
}
