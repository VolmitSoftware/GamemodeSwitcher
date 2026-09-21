package com.volmit.gsw.gameplay;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.WorldChunks;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class SpectatorOriginTest {
    @Test
    void unsafeReturnSkipsChunkLoadingAndEnvironmentalInspection() {
        Plugin plugin = mock(Plugin.class);
        World world = mock(World.class);
        try (MockedStatic<WorldChunks> chunks = mockStatic(WorldChunks.class)) {
            assertThat(new SpectatorOrigin(plugin).allowed(new Location(world, 8, -100, 8),
                    new SpectatorOrigin.Dimensions(0.6, 1.8), true).join()).isTrue();
            chunks.verifyNoInteractions();
            verifyNoInteractions(world, plugin);
        }
    }

    @Test
    void loadsEveryFootprintChunkAndRejectsAnObstruction() {
        Plugin plugin = mock(Plugin.class);
        World world = mock(World.class);
        WorldBorder border = mock(WorldBorder.class);
        Block floor = mock(Block.class);
        Block air = mock(Block.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getWorldBorder()).thenReturn(border);
        when(border.isInside(any(Location.class))).thenReturn(true);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        Material solid = mock(Material.class);
        Material empty = mock(Material.class);
        when(solid.isOccluding()).thenReturn(true);
        when(empty.isAir()).thenReturn(true);
        when(floor.getType()).thenReturn(solid);
        when(floor.getBoundingBox()).thenReturn(new BoundingBox(15, 63, 15, 16, 64, 16));
        when(air.getType()).thenReturn(empty);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation ->
                invocation.getArgument(1, Integer.class) == 63 ? floor : air);
        Set<String> loaded = new HashSet<>();
        try (MockedStatic<WorldChunks> chunks = mockStatic(WorldChunks.class);
             MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            chunks.when(() -> WorldChunks.loadExisting(eq(plugin), eq(world), anyInt(), anyInt())).thenAnswer(invocation -> {
                loaded.add(invocation.getArgument(2) + ":" + invocation.getArgument(3));
                return CompletableFuture.completedFuture(true);
            });
            scheduler.when(() -> FoliaScheduler.runRegion(eq(plugin), eq(world), anyInt(), anyInt(), any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        invocation.getArgument(4, Runnable.class).run();
                        return true;
                    });
            SpectatorOrigin origin = new SpectatorOrigin(plugin);
            Location destination = new Location(world, 16, 64, 16);
            assertThat(origin.allowed(destination, new SpectatorOrigin.Dimensions(0.6, 1.8), false).join()).isTrue();
            assertThat(loaded).containsExactlyInAnyOrder("0:0", "0:1", "1:0", "1:1");
            when(world.getBlockAt(16, 65, 16)).thenReturn(floor);
            assertThat(origin.allowed(destination, new SpectatorOrigin.Dimensions(0.6, 1.8), false).join()).isFalse();
            assertThat(origin.allowed(destination, new SpectatorOrigin.Dimensions(0.6, 1.8), true).join()).isTrue();
        }
    }

    @Test
    void missingChunkFailsWithoutInspectingWorldBlocks() {
        Plugin plugin = mock(Plugin.class);
        World world = mock(World.class);
        try (MockedStatic<WorldChunks> chunks = mockStatic(WorldChunks.class)) {
            chunks.when(() -> WorldChunks.loadExisting(eq(plugin), eq(world), anyInt(), anyInt()))
                    .thenReturn(CompletableFuture.completedFuture(false));
            assertThat(new SpectatorOrigin(plugin).allowed(new Location(world, 8, 64, 8),
                    new SpectatorOrigin.Dimensions(0.6, 1.8), false).join()).isFalse();
        }
    }
}
