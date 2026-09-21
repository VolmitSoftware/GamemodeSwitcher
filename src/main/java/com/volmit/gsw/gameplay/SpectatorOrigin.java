package com.volmit.gsw.gameplay;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.WorldChunks;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class SpectatorOrigin {
    private final Plugin plugin;

    public SpectatorOrigin(Plugin plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<Boolean> allowed(Location destination, Dimensions dimensions, boolean allowUnsafe) {
        if (allowUnsafe) {
            return CompletableFuture.completedFuture(true);
        }
        int minX = (int) Math.floor(destination.getX() - dimensions.width() / 2);
        int maxX = (int) Math.floor(destination.getX() + dimensions.width() / 2);
        int minZ = (int) Math.floor(destination.getZ() - dimensions.width() / 2);
        int maxZ = (int) Math.floor(destination.getZ() + dimensions.width() / 2);
        List<CompletableFuture<Boolean>> checks = new ArrayList<>();
        for (int x = minX >> 4; x <= maxX >> 4; x++) {
            for (int z = minZ >> 4; z <= maxZ >> 4; z++) {
                checks.add(checkChunk(destination, dimensions, new Footprint(minX, maxX, minZ, maxZ, x, z)));
            }
        }
        return CompletableFuture.allOf(checks.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> checks.stream().allMatch(CompletableFuture::join));
    }

    private CompletableFuture<Boolean> checkChunk(Location destination, Dimensions dimensions, Footprint footprint) {
        World world = destination.getWorld();
        return WorldChunks.loadExisting(plugin, world, footprint.chunkX(), footprint.chunkZ()).thenCompose(loaded -> {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            if (!loaded || !FoliaScheduler.runRegion(plugin, world, footprint.chunkX(), footprint.chunkZ(), () -> {
                try {
                    result.complete(inspect(destination, dimensions, footprint));
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            })) {
                result.complete(false);
            }
            return result;
        });
    }

    private boolean inspect(Location destination, Dimensions dimensions, Footprint footprint) {
        World world = destination.getWorld();
        int feet = destination.getBlockY();
        int head = (int) Math.floor(destination.getY() + dimensions.height());
        if (feet <= world.getMinHeight() || head >= world.getMaxHeight()
                || !world.isChunkLoaded(footprint.chunkX(), footprint.chunkZ())) {
            return false;
        }
        int startX = Math.max(footprint.minX(), footprint.chunkX() << 4);
        int endX = Math.min(footprint.maxX(), (footprint.chunkX() << 4) + 15);
        int startZ = Math.max(footprint.minZ(), footprint.chunkZ() << 4);
        int endZ = Math.min(footprint.maxZ(), (footprint.chunkZ() << 4) + 15);
        for (int x = startX; x <= endX; x++) {
            for (int z = startZ; z <= endZ; z++) {
                if (!world.getWorldBorder().isInside(new Location(world, x + 0.5, destination.getY(), z + 0.5))) {
                    return false;
                }
                Block floor = world.getBlockAt(x, feet - 1, z);
                Material type = floor.getType();
                if (!type.isOccluding() || type == Material.MAGMA_BLOCK || type == Material.CACTUS
                        || floor.getBoundingBox().getMaxY() > destination.getY()) {
                    return false;
                }
                for (int y = feet; y <= head; y++) {
                    if (!world.getBlockAt(x, y, z).getType().isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public record Dimensions(double width, double height) {
        public Dimensions {
            if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0 || width > 32 || height > 64) {
                throw new IllegalArgumentException("Invalid player dimensions for Spectator return");
            }
        }
    }

    private record Footprint(int minX, int maxX, int minZ, int maxZ, int chunkX, int chunkZ) {
    }
}
