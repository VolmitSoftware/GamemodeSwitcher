package com.volmit.gsw.gui;

import art.arcane.volmlib.util.bukkit.BukkitInventoryViews;
import art.arcane.volmlib.util.config.BukkitConfigMessages;
import art.arcane.volmlib.util.config.ConfigEditorDocument;
import art.arcane.volmlib.util.inventorygui.BukkitInventoryShutdown;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentText;
import art.arcane.volmlib.util.plugin.LegacyLoreLayout;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.volmit.gsw.GamemodeSwitcher;
import com.volmit.gsw.localization.SwitcherMessages;
import com.volmit.gsw.localization.WorldMessages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class WorldPicker implements Listener, AutoCloseable {
    static final List<String> WORLDS_PATH = List.of("restrictions", "disabled-worlds");
    static final List<Integer> WORLD_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43);
    private static final int SIZE = 54;
    private static final int HELP_SLOT = 4;
    private static final int BACK_SLOT = 45;
    private static final int PREVIOUS_SLOT = 48;
    private static final int NEXT_SLOT = 50;
    private static final int CLOSE_SLOT = 53;

    private final GamemodeSwitcher plugin;
    private final Map<UUID, BukkitInventoryShutdown.View> views = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public WorldPicker(GamemodeSwitcher plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player player) {
        FoliaScheduler.runEntity(plugin, player, () -> openOwned(player, 0));
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
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        FoliaScheduler.runEntity(plugin, player, () -> {
            if (allowed(player) && BukkitInventoryViews.top(player.getOpenInventory()) == holder.inventory) {
                click(player, holder, slot);
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

    static List<String> configuredKeys(ConfigEditorDocument document) {
        List<String> keys = new ArrayList<>();
        for (JsonElement element : document.value(WORLDS_PATH).getAsJsonArray()) {
            keys.add(element.getAsString().strip().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(keys);
    }

    static JsonArray toggled(List<String> configured, Choice choice) {
        Set<String> keys = new LinkedHashSet<>(configured);
        if (choice.disabled()) {
            keys.removeAll(choice.entries());
        } else {
            keys.add(choice.key());
        }
        JsonArray replacement = new JsonArray(keys.size());
        for (String value : keys) {
            replacement.add(value);
        }
        return replacement;
    }

    static List<Choice> choices(List<World> loaded, List<String> configured) {
        Map<String, Choice> choices = new TreeMap<>();
        Set<String> matched = new HashSet<>();
        for (World world : loaded) {
            String key = world.getKey().toString();
            List<String> entries = configured.stream()
                    .filter(entry -> entry.equals(key) || entry.equals(world.getName().toLowerCase(Locale.ROOT)))
                    .distinct().toList();
            matched.addAll(entries);
            choices.put(key, new Choice(key, world.getName(), icon(world.getEnvironment()), entries));
        }
        for (String entry : configured) {
            if (!matched.contains(entry)) {
                choices.putIfAbsent(entry, new Choice(entry, null, Material.PAPER, List.of(entry)));
            }
        }
        return List.copyOf(choices.values());
    }

    private void openOwned(Player player, int requestedPage) {
        if (!allowed(player)) {
            return;
        }
        ConfigEditorDocument document;
        try {
            document = plugin.getConfigService().editorDocument(plugin.getConfigService().source());
        } catch (IOException failure) {
            plugin.getLogger().log(Level.SEVERE, "Could not prepare disabled world configuration", failure);
            plugin.getLanguageService().sendPrefixed(player, SwitcherMessages.HOT_RELOAD_FAILED);
            return;
        }
        List<Choice> choices = choices(Bukkit.getWorlds(), configuredKeys(document));
        int pages = Math.max(1, (choices.size() + WORLD_SLOTS.size() - 1) / WORLD_SLOTS.size());
        int page = Math.clamp(requestedPage, 0, pages - 1);
        Holder holder = new Holder(this, player.getUniqueId(), document, page);
        holder.inventory = Bukkit.createInventory(holder, SIZE, text(player, WorldMessages.TITLE));
        ItemStack background = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            holder.inventory.setItem(slot, background);
        }
        holder.inventory.setItem(HELP_SLOT, item(Material.BOOK, text(player, WorldMessages.HELP),
                List.of(text(player, WorldMessages.HELP_DETAIL))));
        int first = page * WORLD_SLOTS.size();
        for (int index = 0; index < WORLD_SLOTS.size() && first + index < choices.size(); index++) {
            Choice choice = choices.get(first + index);
            holder.choices.put(WORLD_SLOTS.get(index), choice);
            holder.inventory.setItem(WORLD_SLOTS.get(index), choiceItem(player, choice));
        }
        if (page > 0) {
            holder.inventory.setItem(PREVIOUS_SLOT, item(Material.ARROW, text(player, BukkitConfigMessages.PREVIOUS), List.of()));
        }
        if (page < pages - 1) {
            holder.inventory.setItem(NEXT_SLOT, item(Material.ARROW, text(player, BukkitConfigMessages.NEXT), List.of()));
        }
        holder.inventory.setItem(BACK_SLOT, item(Material.ARROW, text(player, BukkitConfigMessages.BACK), List.of()));
        holder.inventory.setItem(CLOSE_SLOT, item(Material.BARRIER, text(player, SwitcherMessages.MENU_CLOSE), List.of()));
        player.openInventory(holder.inventory);
        if (closed && BukkitInventoryViews.top(player.getOpenInventory()) == holder.inventory) {
            player.closeInventory();
        } else if (!closed && BukkitInventoryViews.top(player.getOpenInventory()) == holder.inventory) {
            views.put(player.getUniqueId(), new BukkitInventoryShutdown.View(player, holder.inventory));
        }
    }

    private void click(Player player, Holder holder, int slot) {
        if (slot == BACK_SLOT) {
            plugin.getConfigEditor().open(player, player.hasPermission("gamemodeswitcher.use") ? plugin.getSelector()::open : null);
        } else if (slot == CLOSE_SLOT) {
            player.closeInventory();
        } else if (slot == PREVIOUS_SLOT || slot == NEXT_SLOT) {
            openOwned(player, holder.page + (slot == NEXT_SLOT ? 1 : -1));
        } else {
            Choice choice = holder.choices.get(slot);
            if (choice != null) {
                int page = holder.page;
                plugin.getConfigEditor().apply(player,
                        holder.document.edit(WORLDS_PATH, toggled(configuredKeys(holder.document), choice)),
                        returned -> openOwned(returned, page));
            }
        }
    }

    private ItemStack choiceItem(Player player, Choice choice) {
        List<String> lore = new ArrayList<>();
        lore.add(choice.folder() == null ? text(player, WorldMessages.UNLOADED)
                : text(player, WorldMessages.FOLDER, MessageArgs.builder().untrusted("folder", choice.folder()).build()));
        lore.add(text(player, choice.disabled() ? WorldMessages.DISABLED : WorldMessages.ENABLED));
        lore.add(text(player, choice.folder() == null ? WorldMessages.REMOVE
                : choice.disabled() ? WorldMessages.ENABLE : WorldMessages.DISABLE));
        ItemStack stack = item(choice.icon(), ComponentText.literal(choice.key()).legacy(), lore);
        if (choice.disabled()) {
            ItemMeta meta = stack.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static Material icon(World.Environment environment) {
        return switch (environment) {
            case NORMAL -> Material.GRASS_BLOCK;
            case NETHER -> Material.NETHERRACK;
            case THE_END -> Material.END_STONE;
            case CUSTOM -> Material.ENDER_EYE;
        };
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

    private String text(Player player, TextKey key, MessageArgs arguments) {
        return ComponentText.markup(plugin.getLanguageService().renderWithoutPrefix(player, key, arguments)).legacy();
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
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        stack.setItemMeta(meta);
        return stack;
    }

    record Choice(String key, String folder, Material icon, List<String> entries) {
        Choice {
            entries = List.copyOf(entries);
        }

        boolean disabled() {
            return !entries.isEmpty();
        }
    }

    private static final class Holder implements InventoryHolder {
        private final WorldPicker picker;
        private final UUID owner;
        private final ConfigEditorDocument document;
        private final int page;
        private final Map<Integer, Choice> choices = new HashMap<>(WORLD_SLOTS.size());
        private Inventory inventory;

        private Holder(WorldPicker picker, UUID owner, ConfigEditorDocument document, int page) {
            this.picker = picker;
            this.owner = owner;
            this.document = document;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
