package com.volmit.gsw;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SupportedRuntime {
    public static final int MINIMUM_JAVA = 25;

    private static final String METADATA = "gamemodeswitcher-build.properties";
    private static final Pattern RANGE = Pattern.compile("(\\d+)\\.(\\d+)-(\\d+)\\.(\\d+)");
    private static final Pattern MINECRAFT_VERSION = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.\\d+)?(?:\\.build\\.\\d+-(?:stable|beta|alpha)|-R\\d+\\.\\d+(?:-SNAPSHOT)?)?");
    private static final String MINECRAFT_RANGE = loadRange();

    private SupportedRuntime() {
    }

    public static String minecraftRange() {
        return MINECRAFT_RANGE;
    }

    static void requireSupported(String bukkitVersion, int javaFeature) {
        if (javaFeature < MINIMUM_JAVA || !supportsMinecraft(bukkitVersion)) {
            throw new IllegalStateException("GamemodeSwitcher requires Minecraft " + MINECRAFT_RANGE.replace("-", " through ")
                    + " (including patch releases) and Java " + MINIMUM_JAVA + " or newer; found Minecraft "
                    + bukkitVersion + " and Java " + javaFeature + ".");
        }
    }

    private static boolean supportsMinecraft(String bukkitVersion) {
        Matcher version = bukkitVersion == null ? null : MINECRAFT_VERSION.matcher(bukkitVersion);
        if (version == null || !version.matches()) {
            return false;
        }
        Matcher range = RANGE.matcher(MINECRAFT_RANGE);
        if (!range.matches()) {
            return false;
        }
        int major = Integer.parseInt(version.group(1));
        int minor = Integer.parseInt(version.group(2));
        return major == Integer.parseInt(range.group(1)) && major == Integer.parseInt(range.group(3))
                && minor >= Integer.parseInt(range.group(2)) && minor <= Integer.parseInt(range.group(4));
    }

    private static String loadRange() {
        try (InputStream input = SupportedRuntime.class.getClassLoader().getResourceAsStream(METADATA)) {
            if (input == null) {
                throw new IllegalStateException("GamemodeSwitcher build metadata is missing: " + METADATA);
            }
            Properties properties = new Properties();
            properties.load(input);
            String range = properties.getProperty("minecraftRange", "").strip();
            if (!RANGE.matcher(range).matches()) {
                throw new IllegalStateException("GamemodeSwitcher build metadata has an invalid Minecraft range: " + range);
            }
            return range;
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read GamemodeSwitcher build metadata", failure);
        }
    }
}
