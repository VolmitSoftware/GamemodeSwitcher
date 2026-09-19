package com.volmit.gsw.presentation;

import art.arcane.volmlib.util.hud.HudActionBar;
import art.arcane.volmlib.util.hud.HudPriority;
import art.arcane.volmlib.util.hud.HudSegment;
import art.arcane.volmlib.util.hud.HudTitleClaim;
import art.arcane.volmlib.util.hud.HudTitleService;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.gsw.config.ConfigService;
import com.volmit.gsw.gameplay.PersonalFeedback;
import com.volmit.gsw.localization.LanguageService;
import com.volmit.gsw.localization.SwitcherMessages;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SwitchFeedbackTest {
    @Test
    void personalSilenceSuppressesEverySuccessChannelButKeepsRejectionVisible() throws IOException {
        try (Fixture fixture = new Fixture("chat-enabled = true\naction-bar-enabled = true\ntitle-enabled = true\nsound-enabled = true")) {
            fixture.feedback.success(fixture.player, GameMode.CREATIVE, MODE, PersonalFeedback.SILENT);
            verify(fixture.language, never()).send(eq(fixture.player), any(TextKey.class), any(MessageArgs.class));
            verify(fixture.player, never()).playSound(any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
            verifyNoInteractions(fixture.actionBar(), fixture.titles());
            fixture.feedback.failure(fixture.player, SwitcherMessages.SWITCH_DENIED, MODE, PersonalFeedback.SILENT);
            verify(fixture.actionBar()).publish(eq(fixture.player), any(HudSegment.class));
            verify(fixture.language, never()).send(eq(fixture.player), any(TextKey.class), any(MessageArgs.class));
        }
    }

    private static final MessageArgs MODE = MessageArgs.builder().untrusted("mode", "Spectator").build();

    @Test
    void defaultsSendChatTitleActionBarAndSoundWithoutOpeningInventory() throws IOException {
        try (Fixture fixture = new Fixture("")) {
            fixture.feedback.success(fixture.player, GameMode.SPECTATOR, MODE, PersonalFeedback.DEFAULT);

            verify(fixture.language).send(fixture.player, SwitcherMessages.SWITCH_SUCCESS, MODE);
            verify(fixture.language).send(eq(fixture.player), eq(SwitcherMessages.SWITCH_SPECTATOR_HINT), any(MessageArgs.class));
            verify(fixture.player).playSound(fixture.location, "minecraft:ui.button.click", SoundCategory.PLAYERS, 0.7F, 1.2F);
            verify(fixture.actionBar()).publish(eq(fixture.player), any(HudSegment.class));
            verify(fixture.titles()).open(fixture.player, "gamemodeswitcher:switch", HudPriority.NOTICE, 3_250L);
            fixture.messenger.verify(() -> ComponentMessenger.showTitleMarkup(eq(fixture.player), anyString(),
                    eq(SwitcherMessages.SWITCH_SPECTATOR_OVERLAY_HINT.english()),
                    eq(Duration.ofMillis(250L)), eq(Duration.ofMillis(2_500L)), eq(Duration.ofMillis(500L))));
            verify(fixture.player, never()).openInventory(any(Inventory.class));
            assertThat(fixture.entityTasks).singleElement().extracting(Scheduled::delayTicks).isEqualTo(65L);
        }
    }

    @Test
    void enabledChannelsIncludeSpectatorGuidanceAndConfiguredSound() throws IOException {
        try (Fixture fixture = new Fixture("""
                chat-enabled = false
                action-bar-enabled = true
                title-enabled = true
                popup-duration-ticks = 40
                sound = "custom:mode.changed"
                sound-volume = 0.25
                sound-pitch = 1.75
                """)) {
            fixture.feedback.success(fixture.player, GameMode.SPECTATOR, MODE, PersonalFeedback.DEFAULT);

            ArgumentCaptor<HudSegment> segment = ArgumentCaptor.forClass(HudSegment.class);
            verify(fixture.actionBar()).publish(eq(fixture.player), segment.capture());
            assertThat(ComponentText.legacy(segment.getValue().text()).plain())
                    .isEqualTo("Game mode: Spectator  Sneak three times to leave Spectator.");
            assertThat(segment.getValue().ttlMillis()).isEqualTo(2_000L);
            verify(fixture.titles()).open(fixture.player, "gamemodeswitcher:switch", HudPriority.NOTICE, 2_750L);
            fixture.messenger.verify(() -> ComponentMessenger.showTitleMarkup(fixture.player,
                    SwitcherMessages.SWITCH_TITLE.english().replace("{mode}", "Spectator"),
                    SwitcherMessages.SWITCH_SPECTATOR_OVERLAY_HINT.english(),
                    Duration.ofMillis(250L), Duration.ofMillis(2_000L), Duration.ofMillis(500L)));
            assertThat(fixture.entityTasks).singleElement().extracting(Scheduled::delayTicks).isEqualTo(55L);
            verify(fixture.player).playSound(fixture.location, "custom:mode.changed", SoundCategory.PLAYERS, 0.25F, 1.75F);
            verify(fixture.language, never()).send(eq(fixture.player), any(TextKey.class), any(MessageArgs.class));
            verify(fixture.player, never()).openInventory(any(Inventory.class));
        }
    }

    @Test
    void configChangesApplyToTheNextSwitchAndReleasePreviousHud() throws IOException {
        try (Fixture fixture = new Fixture("action-bar-enabled = true\ntitle-enabled = true")) {
            fixture.feedback.success(fixture.player, GameMode.CREATIVE, MODE, PersonalFeedback.DEFAULT);
            verify(fixture.language, never()).send(eq(fixture.player), eq(SwitcherMessages.SWITCH_SPECTATOR_HINT), any(MessageArgs.class));
            fixture.messenger.verify(() -> ComponentMessenger.showTitleMarkup(eq(fixture.player), anyString(),
                    eq(SwitcherMessages.SWITCH_SUBTITLE.english()), any(Duration.class), any(Duration.class), any(Duration.class)));
            fixture.configure("chat-enabled = false\naction-bar-enabled = false\ntitle-enabled = false\nsound-enabled = false");
            clearInvocations(fixture.language, fixture.player);

            fixture.feedback.success(fixture.player, GameMode.SURVIVAL, MODE, PersonalFeedback.DEFAULT);

            verify(fixture.actionBar()).clear(fixture.player, "gamemodeswitcher:switch");
            verify(fixture.titleClaims.get(0)).dismiss();
            verify(fixture.language, never()).send(eq(fixture.player), any(TextKey.class), any(MessageArgs.class));
            verify(fixture.player, never()).playSound(any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
            verify(fixture.player, never()).openInventory(any(Inventory.class));
            assertThat(fixture.entityTasks).hasSize(1);
        }
    }

    @Test
    void rejectedSwitchRemainsVisibleWhenAllNormalMessageChannelsAreDisabled() throws IOException {
        try (Fixture fixture = new Fixture("chat-enabled = false\naction-bar-enabled = false\ntitle-enabled = false")) {
            fixture.feedback.failure(fixture.player, SwitcherMessages.SWITCH_CANCELLED, MessageArgs.empty(), PersonalFeedback.DEFAULT);

            ArgumentCaptor<HudSegment> segment = ArgumentCaptor.forClass(HudSegment.class);
            verify(fixture.actionBar()).publish(eq(fixture.player), segment.capture());
            assertThat(ComponentText.legacy(segment.getValue().text()).plain())
                    .isEqualTo(ComponentText.markup(SwitcherMessages.SWITCH_CANCELLED.english()).plain());
            verify(fixture.language, never()).send(eq(fixture.player), any(TextKey.class), any(MessageArgs.class));
            verify(fixture.player, never()).playSound(any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
            verifyNoInteractions(fixture.titles());
            verify(fixture.player, never()).openInventory(any(Inventory.class));

            fixture.configure("chat-enabled = true");
            fixture.feedback.failure(fixture.player, SwitcherMessages.SWITCH_DENIED, MODE, PersonalFeedback.DEFAULT);
            verify(fixture.language).send(fixture.player, SwitcherMessages.SWITCH_DENIED, MODE);
            verify(fixture.actionBar()).clear(fixture.player, "gamemodeswitcher:switch");
        }
    }

    @Test
    void delayedCleanupAndRetirementCannotRemoveNewerActionBarOnlyFeedback() throws IOException {
        try (Fixture fixture = new Fixture("action-bar-enabled = true\ntitle-enabled = false")) {
            fixture.feedback.success(fixture.player, GameMode.CREATIVE, MODE, PersonalFeedback.DEFAULT);
            Scheduled originalExpiry = fixture.entityTasks.get(0);
            fixture.feedback.clear();
            assertThat(fixture.entityTasks).hasSize(1);
            fixture.globalTasks.get(0).run();
            Scheduled reloadCleanup = fixture.entityTasks.get(1);
            assertThat(reloadCleanup.delayTicks()).isZero();

            fixture.feedback.success(fixture.player, GameMode.SURVIVAL, MODE, PersonalFeedback.DEFAULT);
            Scheduled currentExpiry = fixture.entityTasks.get(2);
            clearInvocations(fixture.actionBar());
            originalExpiry.action().run();
            originalExpiry.retired().run();
            reloadCleanup.action().run();
            reloadCleanup.retired().run();
            verifyNoInteractions(fixture.actionBar());

            currentExpiry.action().run();
            verify(fixture.actionBar()).clear(fixture.player, "gamemodeswitcher:switch");
            verifyNoInteractions(fixture.titles());
            verify(fixture.player, never()).openInventory(any(Inventory.class));
        }
    }

    @Test
    void reloadDuringHudPublicationDiscardsTheOldGeneration() throws IOException {
        try (Fixture fixture = new Fixture("action-bar-enabled = true\ntitle-enabled = false")) {
            doAnswer(invocation -> {
                fixture.feedback.clear();
                return null;
            }).when(fixture.actionBar()).publish(eq(fixture.player), any(HudSegment.class));

            fixture.feedback.success(fixture.player, GameMode.CREATIVE, MODE, PersonalFeedback.DEFAULT);

            verify(fixture.actionBar()).clear(fixture.player, "gamemodeswitcher:switch");
            assertThat(fixture.entityTasks).isEmpty();
            verify(fixture.player, never()).openInventory(any(Inventory.class));
            verify(fixture.player, never()).playSound(any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
        }
    }

    @Test
    void titleClaimDenialPreservesTheOtherPluginsTitle() throws IOException {
        try (Fixture fixture = new Fixture("title-enabled = true\naction-bar-enabled = false")) {
            fixture.grantTitles = false;
            fixture.feedback.success(fixture.player, GameMode.CREATIVE, MODE, PersonalFeedback.DEFAULT);

            fixture.messenger.verifyNoInteractions();
            verify(fixture.titleClaims.get(0)).release();
            assertThat(fixture.entityTasks).isEmpty();
            verifyNoInteractions(fixture.actionBar());
            verify(fixture.player, never()).openInventory(any(Inventory.class));
        }
    }

    @Test
    void closeClearsHudOnThePlayerTaskAndPreventsFurtherFeedback() throws IOException {
        try (Fixture fixture = new Fixture("action-bar-enabled = true\ntitle-enabled = true")) {
            fixture.feedback.success(fixture.player, GameMode.CREATIVE, MODE, PersonalFeedback.DEFAULT);
            fixture.feedback.close();
            verify(fixture.actionBar(), never()).clear(eq(fixture.player), anyString());
            fixture.globalTasks.get(0).run();
            fixture.entityTasks.get(1).action().run();
            verify(fixture.actionBar()).clear(fixture.player, "gamemodeswitcher:switch");
            verify(fixture.titleClaims.get(0)).dismiss();
            clearInvocations(fixture.player, fixture.language, fixture.actionBar(), fixture.titles());

            fixture.feedback.success(fixture.player, GameMode.SURVIVAL, MODE, PersonalFeedback.DEFAULT);
            fixture.feedback.failure(fixture.player, SwitcherMessages.SWITCH_DENIED, MODE, PersonalFeedback.DEFAULT);

            verifyNoInteractions(fixture.player, fixture.language, fixture.actionBar(), fixture.titles());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Plugin plugin = mock(Plugin.class);
        private final Player player = mock(Player.class);
        private final ConfigService config = mock(ConfigService.class);
        private final LanguageService language = mock(LanguageService.class);
        private final Location location = new Location(null, 0, 64, 0);
        private final List<Scheduled> entityTasks = new ArrayList<>();
        private final List<Runnable> globalTasks = new ArrayList<>();
        private final List<HudTitleClaim> titleClaims = new ArrayList<>();
        private final MockedConstruction<HudActionBar> actionBars;
        private final MockedConstruction<HudTitleService> titleServices;
        private final MockedStatic<FoliaScheduler> scheduler;
        private final MockedStatic<ComponentMessenger> messenger;
        private final SwitchFeedback feedback;
        private boolean grantTitles = true;

        private Fixture(String settings) throws IOException {
            when(plugin.getName()).thenReturn("GamemodeSwitcher");
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.getLocation()).thenReturn(location);
            when(language.renderWithoutPrefix(eq(player), any(TextKey.class), any(MessageArgs.class)))
                    .thenAnswer(invocation -> invocation.getArgument(1, TextKey.class).english().replace("{mode}", "Spectator"));
            configure(settings);
            actionBars = mockConstruction(HudActionBar.class);
            titleServices = mockConstruction(HudTitleService.class, (service, context) ->
                    when(service.open(eq(player), anyString(), anyInt(), anyLong())).thenAnswer(invocation -> {
                        HudTitleClaim claim = mock(HudTitleClaim.class);
                        when(claim.resolve()).thenAnswer(ignored -> grantTitles);
                        titleClaims.add(claim);
                        return claim;
                    }));
            scheduler = mockStatic(FoliaScheduler.class);
            scheduler.when(() -> FoliaScheduler.runEntity(eq(plugin), eq(player), any(Runnable.class), anyLong(), any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        entityTasks.add(new Scheduled(invocation.getArgument(2, Runnable.class),
                                invocation.getArgument(3, Long.class), invocation.getArgument(4, Runnable.class)));
                        return true;
                    });
            scheduler.when(() -> FoliaScheduler.runGlobal(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
                globalTasks.add(invocation.getArgument(1, Runnable.class));
                return true;
            });
            messenger = mockStatic(ComponentMessenger.class);
            feedback = new SwitchFeedback(new SwitchFeedback.Dependencies(plugin, config, language));
        }

        @Override
        public void close() {
            feedback.close();
            messenger.close();
            scheduler.close();
            titleServices.close();
            actionBars.close();
        }

        private void configure(String settings) throws IOException {
            when(config.runtime()).thenReturn(ConfigService.parse("[feedback]\n" + settings));
        }

        private HudActionBar actionBar() {
            return actionBars.constructed().get(0);
        }

        private HudTitleService titles() {
            return titleServices.constructed().get(0);
        }
    }

    private record Scheduled(Runnable action, long delayTicks, Runnable retired) {
    }
}
