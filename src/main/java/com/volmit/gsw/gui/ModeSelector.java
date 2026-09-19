package com.volmit.gsw.gui;

import art.arcane.volmlib.util.bukkit.BukkitInventoryViews;
import art.arcane.volmlib.util.inventorygui.BukkitInventoryShutdown;
import art.arcane.volmlib.util.localization.LanguageAudience;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.plugin.LegacyLoreLayout;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.gsw.GamemodeSwitcher;
import com.volmit.gsw.config.FeedbackConfig;
import com.volmit.gsw.gameplay.PersonalFeedback;
import com.volmit.gsw.gameplay.SwitchService;
import com.volmit.gsw.localization.SwitcherMessages;
import com.volmit.gsw.presentation.ChatMenuStyle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

public final class ModeSelector implements Listener, AutoCloseable {
    private static final int SIZE = 54;
    private static final int STATUS_SLOT = 13;
    private static final int TOGGLE_SLOT = 28;
    private static final int GESTURES_SLOT = 30;
    private static final int LANGUAGE_SLOT = 32;
    private static final int CONFIG_SLOT = 34;
    private static final int FEEDBACK_SLOT = 40;
    private static final int CLOSE_SLOT = 53;
    private static final Map<Integer, GameMode> MODES = Map.of(
            19, GameMode.SURVIVAL, 21, GameMode.CREATIVE, 23, GameMode.ADVENTURE, 25, GameMode.SPECTATOR);

    private final GamemodeSwitcher plugin;
    private final Map<UUID, BukkitInventoryShutdown.View> openInventories = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public ModeSelector(GamemodeSwitcher plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player player) {
        FoliaScheduler.runEntity(plugin, player, () -> openOwned(player));
    }

    @Override
    public void close() {
        closed = true;
        BukkitInventoryShutdown.drain(plugin, openInventories.values());
        openInventories.clear();
        HandlerList.unregisterAll(this);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(BukkitInventoryViews.top(event.getView()).getHolder() instanceof Holder holder) || holder.selector != this) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.getUniqueId().equals(holder.owner)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        FoliaScheduler.runEntity(plugin, player, () -> {
            if (closed || BukkitInventoryViews.top(player.getOpenInventory()).getHolder() != holder) {
                return;
            }
            LanguageAudience.call(player.getUniqueId(), () -> {
                if (!holder.saving) {
                    if (holder.preferences) {
                        clickFeedback(player, holder, slot);
                    } else {
                        clickOwned(player, slot);
                    }
                }
                return true;
            });
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (BukkitInventoryViews.top(event.getView()).getHolder() instanceof Holder holder && holder.selector == this) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        UUID owner = event.getPlayer().getUniqueId();
        BukkitInventoryShutdown.View view = openInventories.get(owner);
        if (view != null && view.inventory() == event.getInventory()) {
            openInventories.remove(owner, view);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        openInventories.remove(event.getPlayer().getUniqueId());
    }

    private void openOwned(Player player) {
        if (closed || !player.isOnline()) {
            return;
        }
        if (!player.hasPermission("gamemodeswitcher.use")) {
            plugin.getLanguageService().sendPrefixed(player, SwitcherMessages.NO_PERMISSION);
            return;
        }
        Holder holder = new Holder(this, player.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, SIZE, text(player, SwitcherMessages.MENU_TITLE));
        ItemStack background = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            holder.inventory.setItem(slot, background);
        }
        List<String> gestures = gestureLore(player);
        holder.inventory.setItem(STATUS_SLOT, item(Material.COMPASS,
                text(player, SwitcherMessages.MENU_CURRENT, MessageArgs.builder()
                        .untrusted("mode", plugin.modeName(player, player.getGameMode())).build()),
                gestures));
        for (Map.Entry<Integer, GameMode> entry : MODES.entrySet()) {
            holder.inventory.setItem(entry.getKey(), modeItem(player, entry.getValue()));
        }
        boolean enabled = plugin.getSwitchService().enabled(player);
        holder.inventory.setItem(TOGGLE_SLOT, item(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                text(player, enabled ? SwitcherMessages.MENU_TOGGLE_ENABLED : SwitcherMessages.MENU_TOGGLE_DISABLED),
                List.of(text(player, SwitcherMessages.MENU_TOGGLE_HELP))));
        holder.inventory.setItem(GESTURES_SLOT, item(Material.BOOK, text(player, SwitcherMessages.MENU_GESTURES),
                gestures));
        holder.inventory.setItem(FEEDBACK_SLOT, item(Material.NOTE_BLOCK, text(player, SwitcherMessages.MENU_FEEDBACK),
                List.of(text(player, SwitcherMessages.MENU_FEEDBACK_HELP))));
        holder.inventory.setItem(LANGUAGE_SLOT, item(Material.WRITABLE_BOOK, text(player, SwitcherMessages.MENU_LANGUAGE),
                List.of(text(player, SwitcherMessages.MENU_LANGUAGE_HELP))));
        if (player.hasPermission("gamemodeswitcher.config")) {
            holder.inventory.setItem(CONFIG_SLOT, item(Material.COMPARATOR, text(player, SwitcherMessages.MENU_CONFIG),
                    List.of(text(player, SwitcherMessages.MENU_CONFIG_HELP))));
        }
        holder.inventory.setItem(CLOSE_SLOT, item(Material.BARRIER, text(player, SwitcherMessages.MENU_CLOSE), List.of()));
        player.openInventory(holder.inventory);
        if (BukkitInventoryViews.top(player.getOpenInventory()) == holder.inventory) {
            openInventories.put(player.getUniqueId(), new BukkitInventoryShutdown.View(player, holder.inventory));
            refreshLater(player, holder);
        }
    }

    private void clickOwned(Player player, int slot) {
        GameMode mode = MODES.get(slot);
        if (mode != null) {
            Inventory inventory = BukkitInventoryViews.top(player.getOpenInventory());
            plugin.getSwitchService().switchMode(player, mode);
            if (!closed && BukkitInventoryViews.top(player.getOpenInventory()) == inventory) {
                openOwned(player);
            }
            return;
        }
        switch (slot) {
            case TOGGLE_SLOT -> {
                Inventory inventory = BukkitInventoryViews.top(player.getOpenInventory());
                plugin.getSwitchService().toggle(player, !plugin.getSwitchService().enabled(player))
                        .whenComplete((ignored, failure) -> FoliaScheduler.runEntity(plugin, player, () -> {
                            if (!closed && BukkitInventoryViews.top(player.getOpenInventory()) == inventory) {
                                openOwned(player);
                            }
                        }));
            }
            case GESTURES_SLOT -> {
                for (String line : plugin.getSwitchService().gestureHelp(player)) {
                    player.sendMessage(ComponentText.markup(line).legacy());
                }
            }
            case FEEDBACK_SLOT -> openFeedback(player);
            case LANGUAGE_SLOT -> {
                player.closeInventory();
                plugin.getLanguageSwitcher().open(player);
            }
            case CONFIG_SLOT -> {
                if (player.hasPermission("gamemodeswitcher.config")) {
                    plugin.getConfigEditor().open(player, this::open);
                }
            }
            case CLOSE_SLOT -> player.closeInventory();
            default -> {
            }
        }
    }

    private ItemStack modeItem(Player player, GameMode mode) {
        SwitchService.Eligibility eligibility = plugin.getSwitchService().eligibility(player, mode);
        Material material = switch (mode) {
            case SURVIVAL -> Material.IRON_PICKAXE;
            case CREATIVE -> Material.GRASS_BLOCK;
            case ADVENTURE -> Material.MAP;
            case SPECTATOR -> Material.ENDER_EYE;
        };
        ArrayList<String> lore = new ArrayList<>();
        TextKey description = switch (mode) {
            case SURVIVAL -> SwitcherMessages.MENU_SURVIVAL_HELP;
            case CREATIVE -> SwitcherMessages.MENU_CREATIVE_HELP;
            case ADVENTURE -> SwitcherMessages.MENU_ADVENTURE_HELP;
            case SPECTATOR -> SwitcherMessages.MENU_SPECTATOR_HELP;
        };
        lore.add(text(player, description));
        lore.add("");
        boolean selected = player.getGameMode() == mode;
        if (selected) {
            lore.add(text(player, SwitcherMessages.MENU_SELECTED));
        }
        if (eligibility.allowed()) {
            lore.add(text(player, SwitcherMessages.MENU_SELECT));
        } else if (!selected || eligibility.reason() != SwitcherMessages.SWITCH_ALREADY) {
            lore.add(text(player, eligibility.reason(), eligibility.arguments()));
        }
        String color = selected ? ChatMenuStyle.theme().primaryRight() : ChatMenuStyle.theme().primaryLeft();
        String name = ComponentText.component(Component.text((selected ? "✓ " : "") + plugin.modeName(player, mode))
                .color(TextColor.fromHexString(color))).legacy();
        return item(material, name, lore);
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        ArrayList<String> wrappedLore = new ArrayList<>();
        for (String line : lore) {
            wrappedLore.addAll(LegacyLoreLayout.wrap(line, 44));
        }
        meta.setLore(wrappedLore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        stack.setItemMeta(meta);
        return stack;
    }

    private List<String> gestureLore(Player player) {
        List<String> lore = new ArrayList<>();
        for (String line : plugin.getSwitchService().gestureHelp(player)) {
            lore.add(ComponentText.markup(line).legacy());
        }
        return lore;
    }

    private void refreshLater(Player player, Holder holder) {
        FoliaScheduler.runEntity(plugin, player, () -> {
            if (closed || !player.isOnline() || BukkitInventoryViews.top(player.getOpenInventory()) != holder.inventory) {
                return;
            }
            if (holder.preferences) {
                renderFeedback(player, holder);
            } else {
                for (Map.Entry<Integer, GameMode> entry : MODES.entrySet()) {
                    holder.inventory.setItem(entry.getKey(), modeItem(player, entry.getValue()));
                }
                List<String> gestures = gestureLore(player);
                holder.inventory.setItem(STATUS_SLOT, item(Material.COMPASS,
                        text(player, SwitcherMessages.MENU_CURRENT, MessageArgs.builder()
                                .untrusted("mode", plugin.modeName(player, player.getGameMode())).build()), gestures));
                holder.inventory.setItem(GESTURES_SLOT, item(Material.BOOK, text(player, SwitcherMessages.MENU_GESTURES), gestures));
            }
            refreshLater(player, holder);
        }, 10L);
    }

    private void openFeedback(Player player) {
        Holder holder = new Holder(this, player.getUniqueId());
        holder.preferences = true;
        holder.inventory = Bukkit.createInventory(holder, SIZE, text(player, SwitcherMessages.FEEDBACK_TITLE));
        ItemStack background = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            holder.inventory.setItem(slot, background);
        }
        renderFeedback(player, holder);
        player.openInventory(holder.inventory);
        if (BukkitInventoryViews.top(player.getOpenInventory()) == holder.inventory) {
            openInventories.put(player.getUniqueId(), new BukkitInventoryShutdown.View(player, holder.inventory));
            refreshLater(player, holder);
        }
    }

    private void renderFeedback(Player player, Holder holder) {
        PersonalFeedback choice = plugin.getSwitchService().personalFeedback(player);
        FeedbackConfig settings = plugin.getConfigService().runtime().feedback();
        holder.inventory.setItem(13, item(Material.BOOK, text(player, SwitcherMessages.MENU_FEEDBACK),
                List.of(text(player, SwitcherMessages.MENU_FEEDBACK_HELP), text(player, SwitcherMessages.FEEDBACK_FAILURES))));
        holder.inventory.setItem(19, feedbackItem(player, SwitcherMessages.FEEDBACK_CHAT, choice.chatMuted(), settings.chatEnabled()));
        holder.inventory.setItem(21, feedbackItem(player, SwitcherMessages.FEEDBACK_ACTION_BAR, choice.actionBarMuted(), settings.actionBarEnabled()));
        holder.inventory.setItem(23, feedbackItem(player, SwitcherMessages.FEEDBACK_SCREEN_TITLE, choice.titleMuted(), settings.titleEnabled()));
        holder.inventory.setItem(25, feedbackItem(player, SwitcherMessages.FEEDBACK_SOUND, choice.soundMuted(), settings.soundEnabled()));
        holder.inventory.setItem(30, item(Material.GRAY_DYE, text(player, SwitcherMessages.FEEDBACK_SILENCE),
                List.of(text(player, SwitcherMessages.FEEDBACK_SILENCE_HELP))));
        holder.inventory.setItem(32, item(Material.LIME_DYE, text(player, SwitcherMessages.FEEDBACK_RESET),
                List.of(text(player, SwitcherMessages.FEEDBACK_RESET_HELP))));
        holder.inventory.setItem(45, item(Material.ARROW, text(player, SwitcherMessages.MENU_BACK), List.of()));
        holder.inventory.setItem(CLOSE_SLOT, item(Material.BARRIER, text(player, SwitcherMessages.MENU_CLOSE), List.of()));
    }

    private ItemStack feedbackItem(Player player, TextKey name, boolean muted, boolean serverEnabled) {
        String state = muted ? text(player, SwitcherMessages.FEEDBACK_MUTED)
                : text(player, SwitcherMessages.FEEDBACK_INHERIT, MessageArgs.builder().untrusted("state",
                        ComponentText.markup(plugin.getLanguageService().renderWithoutPrefix(player,
                                serverEnabled ? SwitcherMessages.STATE_ENABLED : SwitcherMessages.STATE_DISABLED,
                                MessageArgs.empty())).plain()).build());
        return item(muted ? Material.GRAY_DYE : Material.LIME_DYE, text(player, name),
                List.of(state, text(player, SwitcherMessages.FEEDBACK_TOGGLE)));
    }

    private void clickFeedback(Player player, Holder holder, int slot) {
        if (slot == 45) {
            openOwned(player);
            return;
        }
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        PersonalFeedback.Channel channel = switch (slot) {
            case 19 -> PersonalFeedback.Channel.CHAT;
            case 21 -> PersonalFeedback.Channel.ACTION_BAR;
            case 23 -> PersonalFeedback.Channel.TITLE;
            case 25 -> PersonalFeedback.Channel.SOUND;
            default -> null;
        };
        if (channel == null && slot != 30 && slot != 32) {
            return;
        }
        holder.saving = true;
        CompletableFuture<Void> save = channel != null ? plugin.getSwitchService().togglePersonalFeedback(player, channel)
                : plugin.getSwitchService().setPersonalFeedback(player, slot == 30 ? PersonalFeedback.SILENT : PersonalFeedback.DEFAULT);
        save.whenComplete((ignored, failure) ->
                FoliaScheduler.runEntity(plugin, player, () -> {
                    holder.saving = false;
                    if (!closed && BukkitInventoryViews.top(player.getOpenInventory()) == holder.inventory) {
                        renderFeedback(player, holder);
                    }
                }));
    }

    private String text(Player player, TextKey key) {
        return text(player, key, MessageArgs.empty());
    }

    private String text(Player player, TextKey key, MessageArgs arguments) {
        return ComponentText.markup(plugin.getLanguageService().renderWithoutPrefix(player, key, arguments)).legacy();
    }

    private static final class Holder implements InventoryHolder {
        private final ModeSelector selector;
        private final UUID owner;
        private Inventory inventory;
        private boolean preferences;
        private boolean saving;

        private Holder(ModeSelector selector, UUID owner) {
            this.selector = selector;
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
