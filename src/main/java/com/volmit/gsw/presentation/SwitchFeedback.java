package com.volmit.gsw.presentation;

import art.arcane.volmlib.util.hud.HudActionBar;
import art.arcane.volmlib.util.hud.HudPriority;
import art.arcane.volmlib.util.hud.HudSegment;
import art.arcane.volmlib.util.hud.HudSlot;
import art.arcane.volmlib.util.hud.HudTitleClaim;
import art.arcane.volmlib.util.hud.HudTitleService;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.gsw.config.ConfigService;
import com.volmit.gsw.config.FeedbackConfig;
import com.volmit.gsw.gameplay.PersonalFeedback;
import com.volmit.gsw.localization.LanguageService;
import com.volmit.gsw.localization.SwitcherMessages;
import org.bukkit.GameMode;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class SwitchFeedback implements AutoCloseable {
    private static final String PURPOSE = "gamemodeswitcher:switch";
    private static final long TICK_MILLIS = 50L;
    private static final long TITLE_FADE_IN_TICKS = 5L;
    private static final long TITLE_FADE_OUT_TICKS = 10L;
    private static final List<HudSlot> ACTION_BAR_SLOTS = List.of(HudSlot.RIGHT, HudSlot.LEFT);

    private final Plugin plugin;
    private final ConfigService config;
    private final LanguageService language;
    private final HudActionBar actionBar;
    private final HudTitleService titles;
    private final ConcurrentHashMap<UUID, ActiveHud> active = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong sessionIds = new AtomicLong();
    private volatile boolean closed;

    public SwitchFeedback(Dependencies dependencies) {
        Objects.requireNonNull(dependencies, "dependencies");
        plugin = Objects.requireNonNull(dependencies.plugin(), "plugin");
        config = Objects.requireNonNull(dependencies.config(), "config");
        language = Objects.requireNonNull(dependencies.language(), "language");
        actionBar = new HudActionBar(plugin);
        titles = new HudTitleService(plugin);
    }

    public void success(Player player, GameMode mode, MessageArgs arguments, PersonalFeedback preference) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(arguments, "arguments");
        if (closed) {
            return;
        }
        long expectedGeneration = generation.get();
        FeedbackConfig settings = preference.apply(config.runtime().feedback());
        reset(player);
        if (settings.chatEnabled()) {
            language.send(player, SwitcherMessages.SWITCH_SUCCESS, arguments);
            if (mode == GameMode.SPECTATOR) {
                language.send(player, SwitcherMessages.SWITCH_SPECTATOR_HINT, MessageArgs.empty());
            }
        }
        if (settings.actionBarEnabled() || settings.titleEnabled()) {
            showSuccess(player, mode, arguments, settings, expectedGeneration);
        }
        if (closed || expectedGeneration != generation.get()) {
            return;
        }
        if (settings.soundEnabled()) {
            player.playSound(player.getLocation(), settings.sound(), SoundCategory.PLAYERS,
                    settings.soundVolume(), settings.soundPitch());
        }
    }

    public void failure(Player player, TextKey message, MessageArgs arguments, PersonalFeedback preference) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(arguments, "arguments");
        if (closed) {
            return;
        }
        long expectedGeneration = generation.get();
        FeedbackConfig settings = preference.apply(config.runtime().feedback());
        reset(player);
        if (settings.chatEnabled()) {
            language.send(player, message, arguments);
            return;
        }
        publishActionBar(player, language.renderWithoutPrefix(player, message, arguments), settings.popupDurationTicks());
        track(new ActiveHud(sessionIds.incrementAndGet(), expectedGeneration, player.getUniqueId(), player, true, null),
                settings.popupDurationTicks(), expectedGeneration);
    }

    public void reset(Player player) {
        Objects.requireNonNull(player, "player");
        ActiveHud previous = active.get(player.getUniqueId());
        if (previous != null) {
            clearOwned(previous);
        }
    }

    public void clear() {
        long retiredGeneration = generation.getAndIncrement();
        List<ActiveHud> previous = new ArrayList<>();
        for (ActiveHud hud : active.values()) {
            if (hud.generation() <= retiredGeneration) {
                previous.add(hud);
            }
        }
        if (previous.isEmpty()) {
            return;
        }
        if (!FoliaScheduler.runGlobal(plugin, () -> scheduleCleanup(previous))) {
            for (ActiveHud hud : previous) {
                cleanupUnavailable(hud);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        clear();
    }

    private void showSuccess(Player player, GameMode mode, MessageArgs arguments, FeedbackConfig settings,
                             long expectedGeneration) {
        String spectatorHint = mode == GameMode.SPECTATOR
                ? language.renderWithoutPrefix(player, SwitcherMessages.SWITCH_SPECTATOR_OVERLAY_HINT, MessageArgs.empty())
                : null;
        if (settings.actionBarEnabled()) {
            String overlay = language.renderWithoutPrefix(player, SwitcherMessages.SWITCH_OVERLAY, arguments);
            publishActionBar(player, spectatorHint == null ? overlay : overlay + "  " + spectatorHint,
                    settings.popupDurationTicks());
        }
        HudTitleClaim claim = settings.titleEnabled()
                ? showTitle(player, arguments, spectatorHint, settings.popupDurationTicks()) : null;
        if (settings.actionBarEnabled() || claim != null) {
            track(new ActiveHud(sessionIds.incrementAndGet(), expectedGeneration, player.getUniqueId(), player,
                    settings.actionBarEnabled(), claim),
                    claim == null ? settings.popupDurationTicks() : titleDuration(settings.popupDurationTicks()),
                    expectedGeneration);
        }
    }

    private HudTitleClaim showTitle(Player player, MessageArgs arguments, String spectatorHint, long durationTicks) {
        HudTitleClaim claim = titles.open(player, PURPOSE, HudPriority.NOTICE, titleDuration(durationTicks) * TICK_MILLIS);
        if (!claim.resolve()) {
            claim.release();
            return null;
        }
        ComponentMessenger.showTitleMarkup(player,
                language.renderWithoutPrefix(player, SwitcherMessages.SWITCH_TITLE, arguments),
                spectatorHint == null
                        ? language.renderWithoutPrefix(player, SwitcherMessages.SWITCH_SUBTITLE, MessageArgs.empty())
                        : spectatorHint,
                Duration.ofMillis(TITLE_FADE_IN_TICKS * TICK_MILLIS),
                Duration.ofMillis(durationTicks * TICK_MILLIS),
                Duration.ofMillis(TITLE_FADE_OUT_TICKS * TICK_MILLIS));
        return claim;
    }

    private long titleDuration(long stayTicks) {
        return stayTicks + TITLE_FADE_IN_TICKS + TITLE_FADE_OUT_TICKS;
    }

    private void publishActionBar(Player player, String markup, long durationTicks) {
        actionBar.publish(player, new HudSegment(PURPOSE, HudPriority.NOTICE, durationTicks * TICK_MILLIS,
                ACTION_BAR_SLOTS, ComponentText.markup(markup).legacy()));
    }

    private void track(ActiveHud hud, long durationTicks, long expectedGeneration) {
        active.put(hud.playerId(), hud);
        if (closed || expectedGeneration != generation.get()) {
            clearOwned(hud);
            return;
        }
        if (!FoliaScheduler.runEntity(plugin, hud.player(), () -> clearOwned(hud), durationTicks, () -> retire(hud))) {
            clearOwned(hud);
        }
    }

    private void scheduleCleanup(List<ActiveHud> previous) {
        for (ActiveHud hud : previous) {
            if (!FoliaScheduler.runEntity(plugin, hud.player(), () -> clearOwned(hud), 0L, () -> retire(hud))) {
                cleanupUnavailable(hud);
            }
        }
    }

    private void cleanupUnavailable(ActiveHud hud) {
        if (FoliaScheduler.isOwnedByCurrentRegion(hud.player())) {
            clearOwned(hud);
        } else {
            retire(hud);
        }
    }

    private void clearOwned(ActiveHud hud) {
        if (!active.remove(hud.playerId(), hud)) {
            return;
        }
        if (hud.actionBar()) {
            actionBar.clear(hud.player(), PURPOSE);
        }
        if (hud.title() != null) {
            hud.title().dismiss();
        }
    }

    private void retire(ActiveHud hud) {
        if (!active.remove(hud.playerId(), hud)) {
            return;
        }
        if (hud.actionBar()) {
            actionBar.retire(hud.playerId(), PURPOSE);
        }
        if (hud.title() != null) {
            hud.title().retire();
        }
    }

    public record Dependencies(Plugin plugin, ConfigService config, LanguageService language) {
    }

    private record ActiveHud(long sessionId, long generation, UUID playerId, Player player, boolean actionBar,
                             HudTitleClaim title) {
    }
}
