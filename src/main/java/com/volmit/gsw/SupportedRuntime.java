package com.volmit.gsw;

import java.util.regex.Pattern;

final class SupportedRuntime {
    private static final Pattern MINECRAFT_VERSION = Pattern.compile("26\\.[1-3](?:\\.\\d+)?(?:\\.build\\.\\d+-(?:stable|beta|alpha)|-R\\d+\\.\\d+(?:-SNAPSHOT)?)?");

    private SupportedRuntime() {
    }

    static void requireSupported(String bukkitVersion, int javaFeature) {
        if (javaFeature != 25 || bukkitVersion == null || !MINECRAFT_VERSION.matcher(bukkitVersion).matches()) {
            throw new IllegalStateException("GamemodeSwitcher requires Minecraft 26.1 through 26.3 (including patch releases) and Java 25; found Minecraft "
                    + bukkitVersion + " and Java " + javaFeature + ".");
        }
    }
}
