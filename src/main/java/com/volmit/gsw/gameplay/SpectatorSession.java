package com.volmit.gsw.gameplay;

import org.bukkit.GameMode;
import org.bukkit.Location;

import java.util.Objects;
import java.util.UUID;

public record SpectatorSession(UUID world, double x, double y, double z, float yaw, float pitch,
                               GameMode previousMode, boolean restorePreviousMode, boolean returnToOrigin) {
    public SpectatorSession {
        Objects.requireNonNull(world);
        Objects.requireNonNull(previousMode);
        if (previousMode == GameMode.SPECTATOR || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) {
            throw new IllegalArgumentException("Invalid Spectator session origin or previous mode");
        }
    }

    public static SpectatorSession capture(Location location, GameMode previousMode, boolean restorePreviousMode,
                                           boolean returnToOrigin) {
        return new SpectatorSession(Objects.requireNonNull(location.getWorld()).getUID(), location.getX(),
                location.getY(), location.getZ(), location.getYaw(), location.getPitch(), previousMode,
                restorePreviousMode, returnToOrigin);
    }

    public GameMode exitMode(GameMode fallback) {
        return restorePreviousMode ? previousMode : fallback;
    }
}
