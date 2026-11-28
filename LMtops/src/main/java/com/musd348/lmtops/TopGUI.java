package com.musd348.lmtops;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

public class TopGUI implements Listener {
    private static final class MenuHolder implements InventoryHolder {
        private Inventory inventory;
        private final boolean main;
        MenuHolder(boolean main) { this.main = main; }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final LMtops plugin;

    public TopGUI(LMtops plugin) {
        this.plugin = plugin;
    }

    public void openMainMenu(Player player) {
        String title = ColorUtil.color(plugin.getConfig().getString("gui.main-title", "&#FFD700✦ Топы Сервера ✦"));
        MenuHolder holder = new MenuHolder(true);
        Inventory inv = Bukkit.createInventory(holder, 27, title);
        holder.inventory = inv;

        inv.setItem(11, createGuiItem(Material.GOLD_INGOT, plugin.getConfig().getString("gui.balance-title", "&#FFD700⭐ Топ по Балансу"), List.of("&7Нажмите, чтобы открыть")));
        inv.setItem(12, createGuiItem(Material.DIAMOND_SWORD, plugin.getConfig().getString("gui.kills-title", "&#FF4444⚔ Топ по Убийствам"), List.of("&7Нажмите, чтобы открыть")));
        inv.setItem(13, createGuiItem(Material.SKELETON_SKULL, plugin.getConfig().getString("gui.deaths-title", "&#777777☠ Топ по Смертям"), List.of("&7Нажмите, чтобы открыть")));
        inv.setItem(14, createGuiItem(Material.NETHER_STAR, plugin.getConfig().getString("gui.shards-title", "&#AA55FF💎 Топ по Осколкам"), List.of("&7Нажмите, чтобы открыть")));
        inv.setItem(15, createGuiItem(Material.DIAMOND_PICKAXE, plugin.getConfig().getString("gui.blocks-title", "&#55CCFF⛏ Топ по Блокам"), List.of("&7Нажмите, чтобы открыть")));
        inv.setItem(16, createGuiItem(Material.CLOCK, plugin.getConfig().getString("gui.playtime-title", "&#FFAA00⏰ Топ по Времени"), List.of("&7Нажмите, чтобы открыть")));

        if (plugin.getConfig().getBoolean("sounds.open-menu", true)) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
        }
        player.openInventory(inv);
    }

    public void openTopList(Player player, TopType type) {
        String configKey = switch (type) {
            case BALANCE -> "gui.balance-title";
            case KILLS -> "gui.kills-title";
            case DEATHS -> "gui.deaths-title";
            case SHARDS -> "gui.shards-title";
            case BLOCKS -> "gui.blocks-title";
            case PLAYTIME -> "gui.playtime-title";
        };

        String title = ColorUtil.color(plugin.getConfig().getString(configKey, "Топ"));
        MenuHolder holder = new MenuHolder(false);
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.inventory = inv;

        List<TopEntry> entries = plugin.getTopManager().getTop(type.name().toLowerCase());
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21};

        for (int i = 0; i < Math.min(entries.size(), slots.length); i++) {
            TopEntry entry = entries.get(i);
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            if (meta != null) {
                if (entry.uuid() != null) meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.uuid()));
                meta.setDisplayName(ColorUtil.color("&e#" + (i + 1) + " &f" + entry.name()));
                meta.setLore(List.of(ColorUtil.color("&7Результат: &a" + formatValue(type, entry.value()))));
                skull.setItemMeta(meta);
            }
            inv.setItem(slots[i], skull);
        }

        inv.setItem(49, createGuiItem(Material.BARRIER, "&cНазад", List.of("&7Вернуться в меню")));

        if (plugin.getConfig().getBoolean("sounds.open-menu", true)) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
        }
        player.openInventory(inv);
    }

    public void openSubTop(Player player, String type, String title) {
        TopType topType = TopType.fromString(type);
        if (topType != null) {
            openTopList(player, topType);
        } else {
            openMainMenu(player);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        boolean cancelled = event.isCancelled();
        event.setCancelled(true);
        if (cancelled || !(event.getWhoClicked() instanceof Player player)) return;
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= event.getView().getTopInventory().getSize()) return;
        if (holder.main) {
            TopType type = switch (raw) {
                case 11 -> TopType.BALANCE; case 12 -> TopType.KILLS; case 13 -> TopType.DEATHS;
                case 14 -> TopType.SHARDS; case 15 -> TopType.BLOCKS; case 16 -> TopType.PLAYTIME;
                default -> null;
            };
            if (type != null) Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == holder) openTopList(player, type); });
        } else if (raw == 49) {
            Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == holder) openMainMenu(player); });
        }
    }
    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
    }

    private ItemStack createGuiItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.color(name));
            List<String> coloredLore = new ArrayList<>();
            for (String l : lore) coloredLore.add(ColorUtil.color(l));
            meta.setLore(coloredLore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String formatValue(TopType type, double value) {
        return switch (type) {
            case BALANCE -> String.format("%.2f %s", value, plugin.getConfig().getString("currency.balance-symbol", "$"));
            case KILLS, DEATHS, BLOCKS -> String.format("%,d", (long) value);
            case SHARDS -> String.format("%,d %s", (long) value, plugin.getConfig().getString("currency.shards-name", "оск."));
            case PLAYTIME -> {
                long totalSeconds = (long) value;
                long hours = totalSeconds / 3600;
                long minutes = (totalSeconds % 3600) / 60;
                yield String.format("%dч %dмин", hours, minutes);
            }
        };
    }
}