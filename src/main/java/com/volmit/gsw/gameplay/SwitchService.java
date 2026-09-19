package com.volmit.gsw.gameplay;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.gsw.config.ConfigService;
import com.volmit.gsw.config.RuntimeConfig;
import com.volmit.gsw.localization.LanguageService;
import com.volmit.gsw.localization.SwitcherMessages;
import com.volmit.gsw.presentation.SwitchFeedback;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class SwitchService implements Listener, AutoCloseable {
    private final Plugin plugin;
    private final ConfigService config;
    private final LanguageService language;
    private final SwitchFeedback feedback;
    private final PlayerPreferences preferences;
    private final TapTracker swaps = new TapTracker();
    private final TapTracker sneaks = new TapTracker();
    private final Map<UUID, Long> lastSwitch = new ConcurrentHashMap<>();
    private final Map<UUID, PendingGesture> pendingGestures = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    public SwitchService(Dependencies dependencies) throws IOException {
        plugin = dependencies.plugin();
        config = dependencies.config();
        language = dependencies.language();
        feedback = dependencies.feedback();
        preferences = new PlayerPreferences(plugin.getDataFolder().toPath().resolve("data/player-preferences.toml"), plugin.getLogger());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        long expectedGeneration = generation.get();
        Player player = event.getPlayer();
        RuntimeConfig settings = config.runtime();
        if (!canGesture(player, settings) || player.getGameMode() == GameMode.SPECTATOR) {
            resetGestures(player.getUniqueId());
            return;
        }
        GameMode current = player.getGameMode();
        boolean sneaking = player.isSneaking();
        String action = current.name() + (sneaking ? ":sneaking" : ":normal");
        if (swaps.tap(player.getUniqueId(), action, System.nanoTime(), settings.doubleTapMillis() * 1_000_000L, 2)) {
            scheduleGesture(player, settings.target(current, sneaking), current, event, expectedGeneration);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        long expectedGeneration = generation.get();
        Player player = event.getPlayer();
        RuntimeConfig settings = config.runtime();
        if (!canGesture(player, settings) || player.getGameMode() != GameMode.SPECTATOR) {
            sneaks.remove(player.getUniqueId());
            return;
        }
        if (event.isSneaking() && sneaks.tap(player.getUniqueId(), "spectator", System.nanoTime(),
                settings.sneakTapMillis() * 1_000_000L, 3)) {
            UUID world = player.getWorld().getUID();
            PendingGesture pending = new PendingGesture();
            pendingGestures.put(player.getUniqueId(), pending);
            FoliaScheduler.runEntity(plugin, player, () -> {
                if (pendingGestures.remove(player.getUniqueId(), pending) && !event.isCancelled() && expectedGeneration == generation.get()
                        && player.getWorld().getUID().equals(world)
                        && player.getGameMode() == GameMode.SPECTATOR && canGesture(player, config.runtime())) {
                    switchMode(player, config.runtime().spectatorExit());
                }
            }, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        feedback.reset(event.getPlayer());
        reset(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        feedback.reset(event.getPlayer());
        reset(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        feedback.reset(event.getEntity());
        reset(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onModeChange(PlayerGameModeChangeEvent event) {
        resetGestures(event.getPlayer().getUniqueId());
    }

    public boolean switchMode(Player player, GameMode target) {
        RuntimeConfig settings = config.runtime();
        Eligibility eligibility = eligibility(player, target, settings);
        if (!eligibility.allowed()) {
            feedback.failure(player, eligibility.reason(), eligibility.arguments(), preferences.feedback(player.getUniqueId()));
            return false;
        }
        long now = System.nanoTime();
        player.setGameMode(target);
        if (player.getGameMode() != target) {
            feedback.failure(player, SwitcherMessages.SWITCH_CANCELLED, MessageArgs.empty(), preferences.feedback(player.getUniqueId()));
            return false;
        }
        lastSwitch.put(player.getUniqueId(), now);
        resetGestures(player.getUniqueId());
        if (target == GameMode.CREATIVE && settings.autoFlyCreative() && player.getAllowFlight()) {
            player.setFlying(true);
        }
        feedback.success(player, target, modeArgs(player, target), preferences.feedback(player.getUniqueId()));
        return true;
    }

    public Eligibility eligibility(Player player, GameMode target) {
        return eligibility(player, target, config.runtime());
    }

    public List<String> gestureHelp(Player player) {
        RuntimeConfig settings = config.runtime();
        List<String> lines = new ArrayList<>();
        if (!enabled(player)) {
            lines.add(language.renderWithoutPrefix(player, SwitcherMessages.GESTURE_PERSONAL_DISABLED, MessageArgs.empty()));
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            addGestureHelp(lines, player, SwitcherMessages.GESTURE_SPECTATOR, settings.spectatorExit());
        } else {
            addGestureHelp(lines, player, SwitcherMessages.GESTURE_NORMAL, settings.target(player.getGameMode(), false));
            addGestureHelp(lines, player, SwitcherMessages.GESTURE_SNEAKING, settings.target(player.getGameMode(), true));
        }
        if (settings.requireEmptyHands()) {
            lines.add(language.renderWithoutPrefix(player, SwitcherMessages.GESTURE_EMPTY_HANDS, MessageArgs.empty()));
        }
        lines.add(language.renderWithoutPrefix(player, SwitcherMessages.GESTURE_REBIND, MessageArgs.empty()));
        return List.copyOf(lines);
    }

    public PersonalFeedback personalFeedback(Player player) {
        return preferences.feedback(player.getUniqueId());
    }

    public CompletableFuture<Void> setPersonalFeedback(Player player, PersonalFeedback choice) {
        if (!player.hasPermission("gamemodeswitcher.use")) {
            language.send(player, SwitcherMessages.SWITCH_DENIED);
            return CompletableFuture.completedFuture(null);
        }
        return observeFeedbackSave(player, preferences.setFeedback(player.getUniqueId(), choice));
    }

    public CompletableFuture<Void> togglePersonalFeedback(Player player, PersonalFeedback.Channel channel) {
        if (!player.hasPermission("gamemodeswitcher.use")) {
            language.send(player, SwitcherMessages.SWITCH_DENIED);
            return CompletableFuture.completedFuture(null);
        }
        return observeFeedbackSave(player, preferences.toggleFeedback(player.getUniqueId(), channel));
    }

    public CompletableFuture<Void> toggle(Player player, boolean enabled) {
        if (!player.hasPermission("gamemodeswitcher.use")) {
            language.send(player, SwitcherMessages.SWITCH_DENIED);
            return CompletableFuture.completedFuture(null);
        }
        UUID playerId = player.getUniqueId();
        resetGestures(playerId);
        return preferences.setEnabled(playerId, enabled).whenComplete((ignored, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not save GamemodeSwitcher preference for " + playerId, failure);
            }
            FoliaScheduler.runEntity(plugin, player, () -> {
                resetGestures(playerId);
                language.send(player, failure != null ? SwitcherMessages.PREFERENCES_FAILED
                        : enabled ? SwitcherMessages.TOGGLE_ENABLED : SwitcherMessages.TOGGLE_DISABLED);
            });
        });
    }

    public boolean enabled(Player player) {
        return preferences.enabled(player.getUniqueId());
    }

    public int stateCount() {
        return swaps.size() + sneaks.size() + lastSwitch.size() + pendingGestures.size();
    }

    public void clear() {
        generation.incrementAndGet();
        swaps.clear();
        sneaks.clear();
        lastSwitch.clear();
        pendingGestures.clear();
        feedback.clear();
    }

    @Override
    public void close() {
        clear();
        feedback.close();
        preferences.close();
    }

    public static String permission(GameMode mode) {
        return "gamemodeswitcher.mode." + mode.name().toLowerCase(Locale.ROOT);
    }

    private Eligibility eligibility(Player player, GameMode target, RuntimeConfig settings) {
        if (!player.hasPermission("gamemodeswitcher.use") || !player.hasPermission(permission(target))) {
            return new Eligibility(SwitcherMessages.SWITCH_DENIED, MessageArgs.empty());
        }
        if (!settings.enabled()) {
            return new Eligibility(SwitcherMessages.SWITCH_DISABLED, MessageArgs.empty());
        }
        if (settings.disabledWorlds().contains(player.getWorld().getName().toLowerCase(Locale.ROOT))) {
            return new Eligibility(SwitcherMessages.SWITCH_BLOCKED_WORLD,
                    MessageArgs.builder().untrusted("world", player.getWorld().getName()).build());
        }
        if (player.getGameMode() == target) {
            return new Eligibility(SwitcherMessages.SWITCH_ALREADY, modeArgs(player, target));
        }
        long now = System.nanoTime();
        Long previous = lastSwitch.get(player.getUniqueId());
        long remaining = previous == null ? 0 : settings.cooldownMillis() * 1_000_000L - (now - previous);
        if (remaining > 0) {
            return new Eligibility(SwitcherMessages.SWITCH_COOLDOWN,
                    MessageArgs.builder().untrusted("seconds", String.format(Locale.ROOT, "%.1f", remaining / 1_000_000_000.0)).build());
        }
        return new Eligibility(null, MessageArgs.empty());
    }

    private CompletableFuture<Void> observeFeedbackSave(Player player, CompletableFuture<Void> save) {
        UUID playerId = player.getUniqueId();
        return save.whenComplete((ignored, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not save GamemodeSwitcher feedback preference for " + playerId, failure);
            }
            FoliaScheduler.runEntity(plugin, player, () -> {
                feedback.reset(player);
                if (failure != null) {
                    language.send(player, SwitcherMessages.PREFERENCES_FAILED);
                }
            });
        });
    }

    private void scheduleGesture(Player player, GameMode target, GameMode expected, PlayerSwapHandItemsEvent event, long expectedGeneration) {
        UUID world = player.getWorld().getUID();
        PendingGesture pending = new PendingGesture();
        pendingGestures.put(player.getUniqueId(), pending);
        FoliaScheduler.runEntity(plugin, player, () -> {
            if (pendingGestures.remove(player.getUniqueId(), pending) && !event.isCancelled()
                    && expectedGeneration == generation.get() && player.getGameMode() == expected
                    && player.getWorld().getUID().equals(world) && canGesture(player, config.runtime())) {
                switchMode(player, target);
            }
        }, 1L);
    }

    private boolean canGesture(Player player, RuntimeConfig settings) {
        return settings.enabled() && enabled(player) && player.hasPermission("gamemodeswitcher.use")
                && !player.isDead() && !settings.disabledWorlds().contains(player.getWorld().getName().toLowerCase(Locale.ROOT))
                && (!settings.requireEmptyHands() || (player.getInventory().getItemInMainHand().getType().isAir()
                && player.getInventory().getItemInOffHand().getType().isAir()));
    }

    private MessageArgs modeArgs(Player player, GameMode mode) {
        String name = ComponentText.markup(language.renderWithoutPrefix(player, SwitcherMessages.mode(mode), MessageArgs.empty())).plain();
        return MessageArgs.builder().untrusted("mode", name).build();
    }

    private void addGestureHelp(List<String> lines, Player player, TextKey key, GameMode target) {
        lines.add(language.renderWithoutPrefix(player, key, modeArgs(player, target)));
        Eligibility eligibility = eligibility(player, target);
        if (!eligibility.allowed()) {
            lines.add(language.renderWithoutPrefix(player, eligibility.reason(), eligibility.arguments()));
        }
    }

    private void reset(UUID player) {
        resetGestures(player);
        lastSwitch.remove(player);
    }

    private void resetGestures(UUID player) {
        swaps.remove(player);
        sneaks.remove(player);
        pendingGestures.remove(player);
    }

    private static final class PendingGesture {
    }

    public record Dependencies(Plugin plugin, ConfigService config, LanguageService language, SwitchFeedback feedback) {
    }

    public record Eligibility(TextKey reason, MessageArgs arguments) {
        public boolean allowed() {
            return reason == null;
        }
    }
}
