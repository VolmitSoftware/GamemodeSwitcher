package com.volmit.gsw;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportedRuntimeTest {
    @Test
    void acceptsSupportedReleaseAndBukkitVersions() {
        for (String version : List.of("26.1", "26.1.2-R0.1-SNAPSHOT", "26.2-R0.1-SNAPSHOT", "26.3-R0.1-SNAPSHOT", "26.3.1",
                "26.1.2.build.74-stable", "26.3.build.178-stable", "26.3.build.157-beta")) {
            assertDoesNotThrow(() -> SupportedRuntime.requireSupported(version, 25));
        }
    }

    @Test
    void rejectsVersionsOutsideTheSupportedRange() {
        for (String version : List.of("1.20.1-R0.1-SNAPSHOT", "1.21.11", "26.0", "26.4", "26.4.build.1-stable", "27.1", "26.30", "26.3-pre1", "unknown")) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> SupportedRuntime.requireSupported(version, 25));
            assertTrue(failure.getMessage().contains(version));
        }
        assertThrows(IllegalStateException.class, () -> SupportedRuntime.requireSupported(null, 25));
    }

    @Test
    void requiresJava25() {
        for (int javaFeature : List.of(17, 21, 24, 26)) {
            assertThrows(IllegalStateException.class, () -> SupportedRuntime.requireSupported("26.3", javaFeature));
        }
    }
}
