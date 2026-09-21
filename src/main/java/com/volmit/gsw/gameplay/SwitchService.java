package com.volmit.gsw.gameplay;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.util.scheduling.EntityTeleports;
import com.volmit.gsw.config.ConfigService;
import com.volmit.gsw.config.RuntimeConfig;
import com.volmit.gsw.localization.LanguageService;
import com.volmit.gsw.localization.SwitcherMessages;
import com.volmit.gsw.presentation.SwitchFeedback;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final SpectatorSessions sessions;
    private final SpectatorOrigin origins;
    private final Map<UUID, Object> transitions = new ConcurrentHashMap<>();
    private final Map<UUID, SpectatorSession> entering = new ConcurrentHashMap<>();
    private final Set<UUID> changingMode = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Location> returnTeleports = new ConcurrentHashMap<>();
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
        sessions = new SpectatorSessions(plugin.getDataFolder().toPath().resolve("data/spectator-sessions.toml"), plugin.getLogger());
        origins = new SpectatorOrigin(plugin);
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
                    switchMode(player, spectatorExit(player));
                }
            }, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        invalidateTransition(event.getPlayer().getUniqueId());
        feedback.reset(event.getPlayer());
        reset(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (entering.containsKey(event.getPlayer().getUniqueId())) {
            invalidateTransition(event.getPlayer().getUniqueId());
        }
        feedback.reset(event.getPlayer());
        reset(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        discardSession(event.getEntity().getUniqueId());
        invalidateTransition(event.getEntity().getUniqueId());
        feedback.reset(event.getEntity());
        reset(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onModeChange(PlayerGameModeChangeEvent event) {
        resetGestures(event.getPlayer().getUniqueId());
        UUID id = event.getPlayer().getUniqueId();
        if (!changingMode.contains(id)) {
            invalidateTransition(id);
            SpectatorSession session = sessions.get(id);
            if (session != null) {
                FoliaScheduler.runEntity(plugin, event.getPlayer(), () -> {
                    if (!event.isCancelled()) {
                        discardSessionIfSame(id, session);
                    }
                }, 1L);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location expected = returnTeleports.get(event.getPlayer().getUniqueId());
        if (expected == null || event.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN
                || event.getTo() == null || !event.getTo().equals(expected)) {
            invalidateTransition(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (event.getPlayer().getGameMode() != GameMode.SPECTATOR || event.getPlayer().isDead()) {
            discardSession(event.getPlayer().getUniqueId());
        }
    }

    public boolean returnFromSpectator(Player player) {
        if (player.getGameMode() != GameMode.SPECTATOR || sessions.get(player.getUniqueId()) == null) {
            fail(player, SwitcherMessages.SPECTATOR_NONE);
            return false;
        }
        return switchMode(player, spectatorExit(player));
    }

    public GameMode spectatorExit(Player player) {
        SpectatorSession session = sessions.get(player.getUniqueId());
        return session == null ? config.runtime().spectatorExit() : session.exitMode(config.runtime().spectatorExit());
    }

    public boolean switchMode(Player player, GameMode target) {
        RuntimeConfig settings = config.runtime();
        Eligibility eligibility = eligibility(player, target, settings);
        if (!eligibility.allowed()) {
            feedback.failure(player, eligibility.reason(), eligibility.arguments(), preferences.feedback(player.getUniqueId()));
            return false;
        }
        SpectatorSession session = sessions.get(player.getUniqueId());
        if (player.getGameMode() == GameMode.SPECTATOR && session != null && session.returnToOrigin()) {
            returnToOrigin(player, target, session);
            return false;
        }
        if (target == GameMode.SPECTATOR && (settings.restorePreviousMode() || settings.returnToOrigin())) {
            enterSpectator(player, settings);
            return false;
        }
        return applyMode(player, target);
    }

    private boolean applyMode(Player player, GameMode target) {
        RuntimeConfig settings = config.runtime();
        changingMode.add(player.getUniqueId());
        try {
            player.setGameMode(target);
        } finally {
            changingMode.remove(player.getUniqueId());
        }
        if (player.getGameMode() != target) {
            feedback.failure(player, SwitcherMessages.SWITCH_CANCELLED, MessageArgs.empty(), preferences.feedback(player.getUniqueId()));
            return false;
        }
        if (target != GameMode.SPECTATOR) {
            discardSession(player.getUniqueId());
        }
        lastSwitch.put(player.getUniqueId(), System.nanoTime());
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
            addGestureHelp(lines, player, SwitcherMessages.GESTURE_SPECTATOR, spectatorExit(player));
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
        for (UUID id : entering.keySet()) {
            invalidateTransition(id);
        }
        transitions.clear();
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
        sessions.close();
    }

    public static String permission(GameMode mode) {
        return "gamemodeswitcher.mode." + mode.name().toLowerCase(Locale.ROOT);
    }

    private Eligibility eligibility(Player player, GameMode target, RuntimeConfig settings) {
        return eligibility(player, target, settings, false);
    }

    private Eligibility eligibility(Player player, GameMode target, RuntimeConfig settings, boolean transition) {
        if (!transition && transitions.containsKey(player.getUniqueId())) {
            return new Eligibility(SwitcherMessages.SPECTATOR_BUSY, MessageArgs.empty());
        }
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

    private void enterSpectator(Player player, RuntimeConfig settings) {
        UUID id = player.getUniqueId();
        Object token = new Object();
        transitions.put(id, token);
        long expectedGeneration = generation.get();
        SpectatorSession session = SpectatorSession.capture(player.getLocation(), player.getGameMode(),
                settings.restorePreviousMode(), settings.returnToOrigin());
        entering.put(id, session);
        sessions.put(id, session).whenComplete((ignored, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not persist Spectator entry for " + id, failure);
            }
            boolean scheduled = FoliaScheduler.runEntity(plugin, player, () -> {
                if (!transitionActive(player, token, expectedGeneration) || player.getGameMode() != session.previousMode()
                        || !player.getWorld().getUID().equals(session.world())) {
                    transitions.remove(id, token);
                    discardSessionIfSame(id, session);
                    return;
                }
                transitions.remove(id, token);
                Eligibility current = eligibility(player, GameMode.SPECTATOR);
                if (failure != null || !current.allowed()) {
                    discardSessionIfSame(id, session);
                    if (failure != null) {
                        fail(player, SwitcherMessages.SPECTATOR_SAVE_FAILED);
                    } else {
                        feedback.failure(player, current.reason(), current.arguments(), preferences.feedback(id));
                    }
                    return;
                }
                entering.remove(id, session);
                if (!applyMode(player, GameMode.SPECTATOR)) {
                    discardSessionIfSame(id, session);
                }
            }, 0L, () -> {
                transitions.remove(id, token);
                discardSessionIfSame(id, session);
            });
            if (!scheduled) {
                transitions.remove(id, token);
                discardSessionIfSame(id, session);
            }
        });
    }

    private void returnToOrigin(Player player, GameMode target, SpectatorSession session) {
        World world = plugin.getServer().getWorld(session.world());
        if (world == null || config.runtime().disabledWorlds().contains(world.getName().toLowerCase(Locale.ROOT))) {
            fail(player, SwitcherMessages.SPECTATOR_RETURN_FAILED);
            return;
        }
        Location destination = new Location(world, session.x(), session.y(), session.z(), session.yaw(), session.pitch());
        UUID id = player.getUniqueId();
        Object token = new Object();
        transitions.put(id, token);
        long expectedGeneration = generation.get();
        origins.allowed(destination, dimensions(player), config.runtime().allowUnsafeReturn()).whenComplete((safe, checkFailure) -> {
            if (checkFailure != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not inspect Spectator origin for " + id, checkFailure);
            }
            boolean entityScheduled = FoliaScheduler.runEntity(plugin, player, () -> {
                if (!transitionActive(player, token, expectedGeneration) || player.getGameMode() != GameMode.SPECTATOR
                        || sessions.get(id) != session) {
                    transitions.remove(id, token);
                    return;
                }
                Eligibility current = eligibility(player, target, config.runtime(), true);
                if (!Boolean.TRUE.equals(safe) || checkFailure != null || !current.allowed()
                        || config.runtime().disabledWorlds().contains(world.getName().toLowerCase(Locale.ROOT))) {
                    transitions.remove(id, token);
                    if (!current.allowed()) {
                        feedback.failure(player, current.reason(), current.arguments(), preferences.feedback(id));
                    } else {
                        fail(player, SwitcherMessages.SPECTATOR_RETURN_FAILED);
                    }
                    return;
                }
                returnTeleports.put(id, destination);
                EntityTeleports.teleport(plugin, player, destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
                        .whenComplete((success, failure) -> {
                            returnTeleports.remove(id, destination);
                            finishReturn(player, target, session,
                                    new ReturnResult(token, expectedGeneration, destination, Boolean.TRUE.equals(success), failure));
                        });
            }, 0L, () -> transitions.remove(id, token));
            if (!entityScheduled) {
                transitions.remove(id, token);
            }
        });
    }

    private void finishReturn(Player player, GameMode target, SpectatorSession session, ReturnResult result) {
        UUID id = player.getUniqueId();
        if (result.failure() != null) {
            plugin.getLogger().log(Level.SEVERE, "Spectator return teleport failed for " + id, result.failure());
        }
        boolean scheduled = FoliaScheduler.runEntity(plugin, player, () -> {
            if (!transitionActive(player, result.token(), result.generation()) || player.getGameMode() != GameMode.SPECTATOR
                    || sessions.get(id) != session) {
                transitions.remove(id, result.token());
                return;
            }
            Location actual = player.getLocation();
            if (!result.success() || result.failure() != null || !actual.getWorld().getUID().equals(session.world())
                    || actual.distanceSquared(result.destination()) > 0.01) {
                transitions.remove(id, result.token());
                fail(player, SwitcherMessages.SPECTATOR_RETURN_FAILED);
                return;
            }
            verifyReturn(player, target, session, result);
        }, 0L, () -> transitions.remove(id, result.token()));
        if (!scheduled) {
            transitions.remove(id, result.token());
        }
    }

    private boolean transitionActive(Player player, Object token, long expectedGeneration) {
        return transitions.get(player.getUniqueId()) == token && generation.get() == expectedGeneration
                && player.isOnline() && !player.isDead();
    }

    private void verifyReturn(Player player, GameMode target, SpectatorSession session, ReturnResult result) {
        UUID id = player.getUniqueId();
        SpectatorOrigin.Dimensions expectedDimensions = dimensions(player);
        origins.allowed(result.destination(), expectedDimensions, config.runtime().allowUnsafeReturn()).whenComplete((safe, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not verify Spectator return location for " + id, failure);
            }
            boolean scheduled = FoliaScheduler.runEntity(plugin, player, () -> {
                if (!transitionActive(player, result.token(), result.generation())
                        || player.getGameMode() != GameMode.SPECTATOR || sessions.get(id) != session) {
                    transitions.remove(id, result.token());
                    return;
                }
                transitions.remove(id, result.token());
                Location actual = player.getLocation();
                if (!Boolean.TRUE.equals(safe) || failure != null
                        || !actual.getWorld().getUID().equals(session.world())
                        || actual.distanceSquared(result.destination()) > 0.01
                        || (!config.runtime().allowUnsafeReturn() && !dimensions(player).equals(expectedDimensions))) {
                    fail(player, SwitcherMessages.SPECTATOR_RETURN_FAILED);
                    return;
                }
                Eligibility current = eligibility(player, target);
                if (!current.allowed()) {
                    feedback.failure(player, current.reason(), current.arguments(), preferences.feedback(id));
                    return;
                }
                applyMode(player, target);
            }, 0L, () -> transitions.remove(id, result.token()));
            if (!scheduled) {
                transitions.remove(id, result.token());
            }
        });
    }

    private SpectatorOrigin.Dimensions dimensions(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.SCALE);
        double scale = attribute == null ? 1 : attribute.getValue();
        return new SpectatorOrigin.Dimensions(Math.max(player.getWidth(), 0.6 * scale),
                Math.max(player.getHeight(), 1.8 * scale));
    }

    private void fail(Player player, TextKey key) {
        feedback.failure(player, key, MessageArgs.empty(), preferences.feedback(player.getUniqueId()));
    }

    private void discardSessionIfSame(UUID id, SpectatorSession session) {
        entering.remove(id, session);
        observeSessionRemoval(id, sessions.remove(id, session));
    }

    private void discardSession(UUID id) {
        observeSessionRemoval(id, sessions.remove(id));
    }

    private void invalidateTransition(UUID id) {
        transitions.remove(id);
        SpectatorSession pending = entering.remove(id);
        if (pending != null) {
            discardSessionIfSame(id, pending);
        }
    }

    private void observeSessionRemoval(UUID id, CompletableFuture<Void> removal) {
        removal.whenComplete((ignored, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not clear Spectator session for " + id, failure);
            }
        });
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

    private record ReturnResult(Object token, long generation, Location destination, boolean success, Throwable failure) {
    }

    public record Dependencies(Plugin plugin, ConfigService config, LanguageService language, SwitchFeedback feedback) {
    }

    public record Eligibility(TextKey reason, MessageArgs arguments) {
        public boolean allowed() {
            return reason == null;
        }
    }
}
