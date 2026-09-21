package com.volmit.gsw.gameplay;

import art.arcane.volmlib.util.config.TomlDocumentEditor;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.google.gson.JsonPrimitive;
import com.volmit.gsw.config.ConfigService;
import com.volmit.gsw.localization.LanguageService;
import com.volmit.gsw.localization.SwitcherMessages;
import com.volmit.gsw.presentation.SwitchFeedback;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SwitchServiceTest {
    @TempDir
    Path directory;

    @Test
    void previousModeReturnRechecksPermissionAndHonorsExplicitTarget() throws Exception {
        Fixture fixture = fixture();
        acceptChanges(fixture);
        fixture.service().close();
        try (SpectatorSessions sessions = new SpectatorSessions(directory.resolve("data/spectator-sessions.toml"), Logger.getAnonymousLogger())) {
            sessions.put(fixture.player().getUniqueId(), new SpectatorSession(fixture.player().getWorld().getUID(),
                    0, 64, 0, 0, 0, GameMode.ADVENTURE, true, false)).get(5, TimeUnit.SECONDS);
        }
        SwitchFeedback feedback = new SwitchFeedback(new SwitchFeedback.Dependencies(fixture.plugin(), fixture.config(), fixture.language()));
        try (SwitchService service = new SwitchService(new SwitchService.Dependencies(fixture.plugin(), fixture.config(), fixture.language(), feedback))) {
            fixture.mode().set(GameMode.SPECTATOR);
            assertThat(service.spectatorExit(fixture.player())).isEqualTo(GameMode.ADVENTURE);
            when(fixture.player().hasPermission(SwitchService.permission(GameMode.ADVENTURE))).thenReturn(false);
            assertThat(service.returnFromSpectator(fixture.player())).isFalse();
            assertThat(fixture.mode().get()).isEqualTo(GameMode.SPECTATOR);
            assertThat(service.switchMode(fixture.player(), GameMode.SURVIVAL)).isTrue();
            assertThat(fixture.mode().get()).isEqualTo(GameMode.SURVIVAL);
            assertThat(service.spectatorExit(fixture.player())).isEqualTo(GameMode.CREATIVE);
        }
    }

    @Test
    void deathDiscardsRememberedModeWithoutRestoringIt() throws Exception {
        Fixture fixture = fixture();
        fixture.service().close();
        try (SpectatorSessions sessions = new SpectatorSessions(directory.resolve("data/spectator-sessions.toml"), Logger.getAnonymousLogger())) {
            sessions.put(fixture.player().getUniqueId(), new SpectatorSession(fixture.player().getWorld().getUID(),
                    0, 64, 0, 0, 0, GameMode.ADVENTURE, true, false)).get(5, TimeUnit.SECONDS);
        }
        SwitchFeedback feedback = new SwitchFeedback(new SwitchFeedback.Dependencies(fixture.plugin(), fixture.config(), fixture.language()));
        try (SwitchService service = new SwitchService(new SwitchService.Dependencies(fixture.plugin(), fixture.config(), fixture.language(), feedback))) {
            fixture.mode().set(GameMode.SPECTATOR);
            PlayerDeathEvent event = mock(PlayerDeathEvent.class);
            when(event.getEntity()).thenReturn(fixture.player());
            service.onDeath(event);
            assertThat(service.returnFromSpectator(fixture.player())).isFalse();
            assertThat(service.spectatorExit(fixture.player())).isEqualTo(GameMode.CREATIVE);
            verify(fixture.player(), never()).setGameMode(any(GameMode.class));
        }
    }

    @Test
    void eligibilityIsReadOnlyAndExplainsTheSameRestrictionsAsSwitching() throws Exception {
        Fixture fixture = fixture();
        acceptChanges(fixture);
        try (SwitchService service = fixture.service()) {
            assertThat(service.eligibility(fixture.player(), GameMode.CREATIVE).allowed()).isTrue();
            verify(fixture.player(), never()).setGameMode(any(GameMode.class));
            assertThat(service.eligibility(fixture.player(), GameMode.SURVIVAL).reason()).isEqualTo(SwitcherMessages.SWITCH_ALREADY);
            assertThat(service.switchMode(fixture.player(), GameMode.CREATIVE)).isTrue();
            assertThat(service.eligibility(fixture.player(), GameMode.SURVIVAL).reason()).isEqualTo(SwitcherMessages.SWITCH_COOLDOWN);
            String source = fixture.config().source();
            fixture.config().save(source, source.replace("disabled-worlds = []", "disabled-worlds = [\"WORLD\"]"));
            assertThat(service.eligibility(fixture.player(), GameMode.SURVIVAL).reason()).isEqualTo(SwitcherMessages.SWITCH_BLOCKED_WORLD);
            source = fixture.config().source();
            fixture.config().save(source, source.replace("enabled = true", "enabled = false"));
            assertThat(service.eligibility(fixture.player(), GameMode.SURVIVAL).reason()).isEqualTo(SwitcherMessages.SWITCH_DISABLED);
            when(fixture.player().hasPermission(SwitchService.permission(GameMode.SURVIVAL))).thenReturn(false);
            assertThat(service.eligibility(fixture.player(), GameMode.SURVIVAL).reason()).isEqualTo(SwitcherMessages.SWITCH_DENIED);
        }
    }

    @Test
    void gestureHelpUsesCurrentModeAndHotReloadedDestinations() throws Exception {
        Fixture fixture = fixture();
        when(fixture.language().renderWithoutPrefix(eq(fixture.player()), any(TextKey.class), any(MessageArgs.class)))
                .thenAnswer(invocation -> {
                    TextKey key = invocation.getArgument(1);
                    MessageArgs args = invocation.getArgument(2);
                    return key.english().contains("{mode}") ? key.english().replace("{mode}", args.require("mode").value().toString()) : key.english();
                });
        try (SwitchService service = fixture.service()) {
            assertThat(service.gestureHelp(fixture.player())).anyMatch(line -> line.contains("Hold Sneak") && line.contains("Adventure"));
            String source = fixture.config().source();
            fixture.config().save(source, TomlDocumentEditor.set(source, List.of("gestures", "sneaking", "survival"), new JsonPrimitive("spectator")));
            assertThat(service.gestureHelp(fixture.player())).anyMatch(line -> line.contains("Hold Sneak") && line.contains("Spectator"));
            fixture.mode().set(GameMode.SPECTATOR);
            assertThat(service.gestureHelp(fixture.player())).anyMatch(line -> line.contains("three separate times") && line.contains("Creative"))
                    .noneMatch(line -> line.contains("Double-tap"));
        }
    }

    @Test
    void cancelledModeChangeDoesNotReportSuccessPlaySoundOrConsumeCooldown() throws IOException {
        Fixture fixture = fixture();
        try (SwitchService service = fixture.service()) {
            assertThat(service.switchMode(fixture.player(), GameMode.CREATIVE)).isFalse();
            verify(fixture.language()).send(eq(fixture.player()), eq(SwitcherMessages.SWITCH_CANCELLED), any(MessageArgs.class));
            verify(fixture.language(), never()).send(eq(fixture.player()), eq(SwitcherMessages.SWITCH_SUCCESS), any(MessageArgs.class));
            verify(fixture.player(), never()).playSound(any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
            verify(fixture.player(), never()).setFlying(true);
            assertThat(service.stateCount()).isZero();

            acceptChanges(fixture);
            assertThat(service.switchMode(fixture.player(), GameMode.CREATIVE)).isTrue();
            verify(fixture.language()).send(eq(fixture.player()), eq(SwitcherMessages.SWITCH_SUCCESS), any(MessageArgs.class));
            verify(fixture.player()).playSound(any(Location.class), eq("minecraft:ui.button.click"), eq(SoundCategory.PLAYERS), eq(0.7F), eq(1.2F));
            verify(fixture.language(), never()).send(eq(fixture.player()), eq(SwitcherMessages.SWITCH_COOLDOWN), any(MessageArgs.class));
        }
    }

    @ParameterizedTest
    @EnumSource(GameMode.class)
    void eachModeRequiresItsOwnPermission(GameMode target) throws IOException {
        Fixture fixture = fixture();
        when(fixture.player().hasPermission(SwitchService.permission(target))).thenReturn(false);
        try (SwitchService service = fixture.service()) {
            assertThat(service.switchMode(fixture.player(), target)).isFalse();
            verify(fixture.language()).send(eq(fixture.player()), eq(SwitcherMessages.SWITCH_DENIED), any(MessageArgs.class));
            verify(fixture.player(), never()).setGameMode(any(GameMode.class));
            verify(fixture.player(), never()).playSound(any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
            assertThat(service.stateCount()).isZero();
        }
    }

    @Test
    void personalToggleDisablesGesturesWhileCommandsRemainSubjectToTheGlobalSwitch() throws Exception {
        Fixture fixture = fixture();
        acceptChanges(fixture);
        try (SwitchService service = fixture.service()) {
            service.toggle(fixture.player(), false).get(5, TimeUnit.SECONDS);
            assertThat(service.enabled(fixture.player())).isFalse();
            service.onSwap(swap(fixture.player()));
            service.onSwap(swap(fixture.player()));
            assertThat(service.stateCount()).isZero();
            assertThat(service.switchMode(fixture.player(), GameMode.CREATIVE)).isTrue();

            String previous = fixture.config().source();
            fixture.config().save(previous, TomlDocumentEditor.set(previous, List.of("general", "enabled"), new JsonPrimitive(false)));
            assertThat(service.switchMode(fixture.player(), GameMode.SURVIVAL)).isFalse();
            verify(fixture.language()).send(eq(fixture.player()), eq(SwitcherMessages.SWITCH_DISABLED), any(MessageArgs.class));
            verify(fixture.player(), never()).setGameMode(GameMode.SURVIVAL);
        }
    }

    @Test
    void emptyHandsRequirementRejectsEitherOccupiedHandAndRechecksBeforeDispatch() throws IOException {
        ItemStack occupied = heldItem(false);
        ItemStack empty = heldItem(true);
        Fixture fixture = fixture();
        String previous = fixture.config().source();
        fixture.config().save(previous, previous.replace("require-empty-hands = false", "require-empty-hands = true"));
        List<Runnable> scheduled = new ArrayList<>();
        try (SwitchService service = fixture.service(); MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.runEntity(eq(fixture.plugin()), eq(fixture.player()), any(Runnable.class), eq(1L)))
                    .thenAnswer(invocation -> {
                        scheduled.add(invocation.getArgument(2, Runnable.class));
                        return true;
                    });
            when(fixture.inventory().getItemInMainHand()).thenReturn(occupied);
            service.onSwap(swap(fixture.player()));
            service.onSwap(swap(fixture.player()));
            assertThat(scheduled).isEmpty();
            assertThat(service.stateCount()).isZero();

            when(fixture.inventory().getItemInMainHand()).thenReturn(empty);
            when(fixture.inventory().getItemInOffHand()).thenReturn(occupied);
            service.onSwap(swap(fixture.player()));
            service.onSwap(swap(fixture.player()));
            assertThat(scheduled).isEmpty();

            when(fixture.inventory().getItemInOffHand()).thenReturn(empty);
            service.onSwap(swap(fixture.player()));
            service.onSwap(swap(fixture.player()));
            assertThat(scheduled).hasSize(1);
            when(fixture.inventory().getItemInOffHand()).thenReturn(occupied);
            scheduled.get(0).run();
            verify(fixture.player(), never()).setGameMode(any(GameMode.class));
        }
    }

    @Test
    void lateCancelledSwapDoesNotApplyTheDeferredModeChange() throws IOException {
        Fixture fixture = fixture();
        List<Runnable> scheduled = new ArrayList<>();
        try (SwitchService service = fixture.service(); MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.runEntity(eq(fixture.plugin()), eq(fixture.player()), any(Runnable.class), eq(1L)))
                    .thenAnswer(invocation -> {
                        scheduled.add(invocation.getArgument(2, Runnable.class));
                        return true;
                    });
            PlayerSwapHandItemsEvent second = swap(fixture.player());
            service.onSwap(swap(fixture.player()));
            service.onSwap(second);
            assertThat(scheduled).hasSize(1);
            second.setCancelled(true);
            scheduled.get(0).run();

            verify(fixture.player(), never()).setGameMode(any(GameMode.class));
            verify(fixture.language(), never()).send(eq(fixture.player()), eq(SwitcherMessages.SWITCH_SUCCESS), any(MessageArgs.class));
            assertThat(service.stateCount()).isZero();
        }
    }

    @Test
    void configurationResetInvalidatesAlreadyQueuedGestures() throws IOException {
        Fixture fixture = fixture();
        List<Runnable> scheduled = new ArrayList<>();
        try (SwitchService service = fixture.service(); MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.runEntity(eq(fixture.plugin()), eq(fixture.player()), any(Runnable.class), eq(1L)))
                    .thenAnswer(invocation -> {
                        scheduled.add(invocation.getArgument(2, Runnable.class));
                        return true;
                    });
            service.onSwap(swap(fixture.player()));
            service.onSwap(swap(fixture.player()));
            assertThat(scheduled).hasSize(1);
            service.clear();
            scheduled.get(0).run();

            verify(fixture.player(), never()).setGameMode(any(GameMode.class));
        }
    }

    @Test
    void spectatorExitDoesNotFollowThePlayerIntoAnotherWorld() throws IOException {
        Fixture fixture = fixture();
        fixture.mode().set(GameMode.SPECTATOR);
        World origin = fixture.player().getWorld();
        World destination = mock(World.class);
        when(destination.getName()).thenReturn("destination");
        when(destination.getUID()).thenReturn(UUID.randomUUID());
        List<Runnable> scheduled = new ArrayList<>();
        try (SwitchService service = fixture.service(); MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.runEntity(eq(fixture.plugin()), eq(fixture.player()), any(Runnable.class), eq(1L)))
                    .thenAnswer(invocation -> {
                        scheduled.add(invocation.getArgument(2, Runnable.class));
                        return true;
                    });
            service.onSneak(new PlayerToggleSneakEvent(fixture.player(), true));
            service.onSneak(new PlayerToggleSneakEvent(fixture.player(), true));
            service.onSneak(new PlayerToggleSneakEvent(fixture.player(), true));
            assertThat(scheduled).hasSize(1);
            when(fixture.player().getWorld()).thenReturn(destination);
            service.onWorldChange(new PlayerChangedWorldEvent(fixture.player(), origin));
            scheduled.get(0).run();

            verify(fixture.player(), never()).setGameMode(any(GameMode.class));
        }
    }

    @Test
    void coloredModeLabelsBecomePlainMessageArguments() throws IOException {
        Fixture fixture = fixture();
        when(fixture.language().renderWithoutPrefix(eq(fixture.player()), eq(SwitcherMessages.MODE_CREATIVE), any(MessageArgs.class)))
                .thenReturn("&6Builder&r");
        acceptChanges(fixture);
        try (SwitchService service = fixture.service()) {
            assertThat(service.switchMode(fixture.player(), GameMode.CREATIVE)).isTrue();
            ArgumentCaptor<MessageArgs> arguments = ArgumentCaptor.forClass(MessageArgs.class);
            verify(fixture.language()).send(eq(fixture.player()), eq(SwitcherMessages.SWITCH_SUCCESS), arguments.capture());
            assertThat(arguments.getValue().require("mode").value()).isEqualTo("Builder");
        }
    }

    @Test
    void queuedGestureIsDiscardedAfterDeathEvenIfThePlayerRespawnsInTheSameState() throws IOException {
        Fixture fixture = fixture();
        List<Runnable> scheduled = new ArrayList<>();
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(fixture.player());
        try (SwitchService service = fixture.service(); MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.runEntity(eq(fixture.plugin()), eq(fixture.player()), any(Runnable.class), eq(1L)))
                    .thenAnswer(invocation -> {
                        scheduled.add(invocation.getArgument(2, Runnable.class));
                        return true;
                    });
            service.onSwap(swap(fixture.player()));
            service.onSwap(swap(fixture.player()));
            assertThat(scheduled).hasSize(1);
            when(fixture.player().isDead()).thenReturn(true);
            service.onDeath(death);
            when(fixture.player().isDead()).thenReturn(false);
            scheduled.get(0).run();

            verify(fixture.player(), never()).setGameMode(any(GameMode.class));
            assertThat(service.stateCount()).isZero();
        }
    }

    @Test
    void queuedGestureIsDiscardedAfterAnExternalModeChangeEvenWhenTheOriginalModeReturns() throws IOException {
        Fixture fixture = fixture();
        List<Runnable> scheduled = new ArrayList<>();
        try (SwitchService service = fixture.service(); MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.runEntity(eq(fixture.plugin()), eq(fixture.player()), any(Runnable.class), eq(1L)))
                    .thenAnswer(invocation -> {
                        scheduled.add(invocation.getArgument(2, Runnable.class));
                        return true;
                    });
            service.onSwap(swap(fixture.player()));
            service.onSwap(swap(fixture.player()));
            assertThat(scheduled).hasSize(1);
            service.onModeChange(new PlayerGameModeChangeEvent(fixture.player(), GameMode.ADVENTURE));
            fixture.mode().set(GameMode.ADVENTURE);
            service.onModeChange(new PlayerGameModeChangeEvent(fixture.player(), GameMode.SURVIVAL));
            fixture.mode().set(GameMode.SURVIVAL);
            scheduled.get(0).run();

            verify(fixture.player(), never()).setGameMode(any(GameMode.class));
            assertThat(service.stateCount()).isZero();
        }
    }

    private Fixture fixture() throws IOException {
        Plugin plugin = mock(Plugin.class);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        World world = mock(World.class);
        LanguageService language = mock(LanguageService.class);
        AtomicReference<GameMode> mode = new AtomicReference<>(GameMode.SURVIVAL);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(player.getGameMode()).thenAnswer(ignored -> mode.get());
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.getAllowFlight()).thenReturn(true);
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(new ItemStack(Material.AIR));
        when(inventory.getItemInOffHand()).thenReturn(new ItemStack(Material.AIR));
        when(language.render(eq(player), any(TextKey.class))).thenReturn("Creative");
        when(language.renderWithoutPrefix(eq(player), any(TextKey.class), any(MessageArgs.class))).thenReturn("Creative");
        ConfigService config = new ConfigService(directory.toFile());
        config.initialize();
        String source = config.source();
        String chatOnly = TomlDocumentEditor.set(source, List.of("feedback", "action-bar-enabled"), new JsonPrimitive(false));
        chatOnly = TomlDocumentEditor.set(chatOnly, List.of("feedback", "title-enabled"), new JsonPrimitive(false));
        config.save(source, chatOnly);
        SwitchFeedback feedback = new SwitchFeedback(new SwitchFeedback.Dependencies(plugin, config, language));
        SwitchService service = new SwitchService(new SwitchService.Dependencies(plugin, config, language, feedback));
        return new Fixture(plugin, player, inventory, config, language, service, mode);
    }

    private void acceptChanges(Fixture fixture) {
        doAnswer(invocation -> {
            fixture.mode().set(invocation.getArgument(0, GameMode.class));
            return null;
        }).when(fixture.player()).setGameMode(any(GameMode.class));
    }

    private PlayerSwapHandItemsEvent swap(Player player) {
        return new PlayerSwapHandItemsEvent(player, new ItemStack(Material.AIR), new ItemStack(Material.AIR));
    }

    private record Fixture(Plugin plugin, Player player, PlayerInventory inventory, ConfigService config,
                           LanguageService language, SwitchService service, AtomicReference<GameMode> mode) {
    }
    private ItemStack heldItem(boolean empty) {
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(empty);
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        return item;
    }

}
