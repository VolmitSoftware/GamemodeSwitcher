package com.volmit.gsw.gui;

import art.arcane.volmlib.util.bukkit.BukkitInventoryViews;
import art.arcane.volmlib.util.config.ConfigEditorDocument;
import art.arcane.volmlib.util.config.BukkitConfigMessages;
import art.arcane.volmlib.util.inventorygui.BukkitInventoryShutdown;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.plugin.LegacyLoreLayout;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.google.gson.JsonPrimitive;
import com.volmit.gsw.GamemodeSwitcher;
import com.volmit.gsw.config.FeedbackConfig;
import com.volmit.gsw.localization.SoundMessages;
import com.volmit.gsw.localization.SwitcherMessages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class SoundPicker implements Listener, AutoCloseable {
    static final List<String> SOUND_PATH = List.of("feedback", "sound");
    static final List<Preset> PRESETS = List.of(
            new Preset(19, "minecraft:ui.button.click", SoundMessages.CLICK),
            new Preset(21, "minecraft:block.note_block.chime", SoundMessages.CHIME),
            new Preset(23, "minecraft:block.note_block.pling", SoundMessages.PLING),
            new Preset(25, "minecraft:entity.experience_orb.pickup", SoundMessages.EXPERIENCE),
            new Preset(30, "minecraft:block.bell.use", SoundMessages.BELL));
    private static final int SIZE = 54;
    private final GamemodeSwitcher plugin;
    private final Map<UUID, BukkitInventoryShutdown.View> views = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public SoundPicker(GamemodeSwitcher plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player player) {
        FoliaScheduler.runEntity(plugin, player, () -> openOwned(player));
    }

    @Override
    public void close() {
        closed = true;
        BukkitInventoryShutdown.drain(plugin, views.values());
        views.clear();
        HandlerList.unregisterAll(this);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(BukkitInventoryViews.top(event.getView()).getHolder() instanceof Holder holder) || holder.picker != this) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.getUniqueId().equals(holder.owner)) {
            return;
        }
        int slot = event.getRawSlot();
        boolean preview = event.isRightClick();
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        FoliaScheduler.runEntity(plugin, player, () -> {
            if (allowed(player) && BukkitInventoryViews.top(player.getOpenInventory()) == holder.inventory) {
                click(player, holder, slot, preview);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (BukkitInventoryViews.top(event.getView()).getHolder() instanceof Holder holder && holder.picker == this) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        UUID owner = event.getPlayer().getUniqueId();
        BukkitInventoryShutdown.View view = views.get(owner);
        if (view != null && view.inventory() == event.getInventory()) {
            views.remove(owner, view);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        views.remove(event.getPlayer().getUniqueId());
    }

    private void openOwned(Player player) {
        if (!allowed(player)) {
            return;
        }
        ConfigEditorDocument document;
        try {
            document = plugin.getConfigService().editorDocument(plugin.getConfigService().source());
        } catch (IOException failure) {
            plugin.getLogger().log(Level.SEVERE, "Could not prepare switch sound configuration", failure);
            plugin.getLanguageService().sendPrefixed(player, SwitcherMessages.HOT_RELOAD_FAILED);
            return;
        }
        Holder holder = new Holder(this, player.getUniqueId(), document);
        holder.inventory = Bukkit.createInventory(holder, SIZE, text(player, SoundMessages.TITLE));
        ItemStack background = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            holder.inventory.setItem(slot, background);
        }
        String current = document.value(SOUND_PATH).getAsString();
        holder.inventory.setItem(13, item(Material.NOTE_BLOCK, text(player, SoundMessages.PREVIEW),
                List.of(text(player, SoundMessages.CURRENT, MessageArgs.builder().untrusted("sound", current).build()),
                        text(player, SoundMessages.PREVIEW_HELP))));
        for (Preset preset : PRESETS) {
            List<String> lore = new ArrayList<>();
            lore.add(ComponentText.literal(preset.key()).legacy());
            if (current.equals(preset.key())) {
                lore.add(text(player, SoundMessages.SELECTED));
            }
            lore.add(text(player, SoundMessages.SELECT));
            holder.inventory.setItem(preset.slot(), item(Material.NOTE_BLOCK, text(player, preset.name()), lore));
        }
        holder.inventory.setItem(32, item(Material.WRITABLE_BOOK, text(player, SoundMessages.CUSTOM),
                List.of(text(player, SoundMessages.CUSTOM_HELP))));
        holder.inventory.setItem(45, item(Material.ARROW, text(player, BukkitConfigMessages.BACK), List.of()));
        holder.inventory.setItem(53, item(Material.BARRIER, text(player, SwitcherMessages.MENU_CLOSE), List.of()));
        player.openInventory(holder.inventory);
        if (closed && BukkitInventoryViews.top(player.getOpenInventory()) == holder.inventory) {
            player.closeInventory();
        } else if (!closed && BukkitInventoryViews.top(player.getOpenInventory()) == holder.inventory) {
            views.put(player.getUniqueId(), new BukkitInventoryShutdown.View(player, holder.inventory));
        }
    }

    private void click(Player player, Holder holder, int slot, boolean preview) {
        if (slot == 13) {
            preview(player, plugin.getConfigService().runtime().feedback().sound());
        } else if (slot == 32) {
            plugin.getConfigEditor().edit(player, SOUND_PATH, this::open);
        } else if (slot == 45) {
            plugin.getConfigEditor().open(player, player.hasPermission("gamemodeswitcher.use") ? plugin.getSelector()::open : null);
        } else if (slot == 53) {
            player.closeInventory();
        } else {
            for (Preset preset : PRESETS) {
                if (preset.slot() == slot) {
                    if (preview) {
                        preview(player, preset.key());
                    } else {
                        plugin.getConfigEditor().apply(player,
                                holder.document.edit(SOUND_PATH, new JsonPrimitive(preset.key())), this::open);
                    }
                    return;
                }
            }
        }
    }

    private void preview(Player player, String key) {
        FeedbackConfig feedback = plugin.getConfigService().runtime().feedback();
        player.playSound(player.getLocation(), key, SoundCategory.PLAYERS, feedback.soundVolume(), feedback.soundPitch());
    }

    private boolean allowed(Player player) {
        if (closed || !plugin.isEnabled() || !player.isOnline()) {
            return false;
        }
        if (!player.hasPermission("gamemodeswitcher.config")) {
            plugin.getLanguageService().sendPrefixed(player, SwitcherMessages.NO_PERMISSION);
            return false;
        }
        return true;
    }

    private String text(Player player, TextKey key) {
        return text(player, key, MessageArgs.empty());
    }

    private String text(Player player, TextKey key, MessageArgs args) {
        return ComponentText.markup(plugin.getLanguageService().renderWithoutPrefix(player, key, args)).legacy();
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        List<String> wrapped = new ArrayList<>();
        for (String line : lore) {
            wrapped.addAll(LegacyLoreLayout.wrap(line, 44));
        }
        meta.setLore(wrapped);
        stack.setItemMeta(meta);
        return stack;
    }

    record Preset(int slot, String key, TextKey name) {
    }

    private static final class Holder implements InventoryHolder {
        private final SoundPicker picker;
        private final UUID owner;
        private final ConfigEditorDocument document;
        private Inventory inventory;

        private Holder(SoundPicker picker, UUID owner, ConfigEditorDocument document) {
            this.picker = picker;
            this.owner = owner;
            this.document = document;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
