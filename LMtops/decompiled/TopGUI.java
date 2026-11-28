/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.Material
 *  org.bukkit.Sound
 *  org.bukkit.entity.Player
 *  org.bukkit.inventory.Inventory
 *  org.bukkit.inventory.ItemStack
 *  org.bukkit.inventory.meta.ItemMeta
 *  org.bukkit.inventory.meta.SkullMeta
 */
package com.musd348.lmtops;

import com.musd348.lmtops.ColorUtil;
import com.musd348.lmtops.LMtops;
import com.musd348.lmtops.TopEntry;
import com.musd348.lmtops.TopType;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

public class TopGUI {
    private final LMtops plugin;

    public TopGUI(LMtops plugin) {
        this.plugin = plugin;
    }

    public void openMainMenu(Player player) {
        String title = ColorUtil.color(this.plugin.getConfig().getString("gui.main-title", "&#FFD700\u2726 \u0422\u043e\u043f\u044b \u0421\u0435\u0440\u0432\u0435\u0440\u0430 \u2726"));
        Inventory inv = Bukkit.createInventory(null, (int)27, (String)title);
        inv.setItem(11, this.createGuiItem(Material.GOLD_INGOT, this.plugin.getConfig().getString("gui.balance-title", "&#FFD700\u2b50 \u0422\u043e\u043f \u043f\u043e \u0411\u0430\u043b\u0430\u043d\u0441\u0443"), List.of("&7\u041d\u0430\u0436\u043c\u0438\u0442\u0435, \u0447\u0442\u043e\u0431\u044b \u043e\u0442\u043a\u0440\u044b\u0442\u044c")));
        inv.setItem(12, this.createGuiItem(Material.DIAMOND_SWORD, this.plugin.getConfig().getString("gui.kills-title", "&#FF4444\u2694 \u0422\u043e\u043f \u043f\u043e \u0423\u0431\u0438\u0439\u0441\u0442\u0432\u0430\u043c"), List.of("&7\u041d\u0430\u0436\u043c\u0438\u0442\u0435, \u0447\u0442\u043e\u0431\u044b \u043e\u0442\u043a\u0440\u044b\u0442\u044c")));
        inv.setItem(13, this.createGuiItem(Material.SKELETON_SKULL, this.plugin.getConfig().getString("gui.deaths-title", "&#777777\u2620 \u0422\u043e\u043f \u043f\u043e \u0421\u043c\u0435\u0440\u0442\u044f\u043c"), List.of("&7\u041d\u0430\u0436\u043c\u0438\u0442\u0435, \u0447\u0442\u043e\u0431\u044b \u043e\u0442\u043a\u0440\u044b\u0442\u044c")));
        inv.setItem(14, this.createGuiItem(Material.NETHER_STAR, this.plugin.getConfig().getString("gui.shards-title", "&#AA55FF\ud83d\udc8e \u0422\u043e\u043f \u043f\u043e \u041e\u0441\u043a\u043e\u043b\u043a\u0430\u043c"), List.of("&7\u041d\u0430\u0436\u043c\u0438\u0442\u0435, \u0447\u0442\u043e\u0431\u044b \u043e\u0442\u043a\u0440\u044b\u0442\u044c")));
        inv.setItem(15, this.createGuiItem(Material.DIAMOND_PICKAXE, this.plugin.getConfig().getString("gui.blocks-title", "&#55CCFF\u26cf \u0422\u043e\u043f \u043f\u043e \u0411\u043b\u043e\u043a\u0430\u043c"), List.of("&7\u041d\u0430\u0436\u043c\u0438\u0442\u0435, \u0447\u0442\u043e\u0431\u044b \u043e\u0442\u043a\u0440\u044b\u0442\u044c")));
        inv.setItem(16, this.createGuiItem(Material.CLOCK, this.plugin.getConfig().getString("gui.playtime-title", "&#FFAA00\u23f0 \u0422\u043e\u043f \u043f\u043e \u0412\u0440\u0435\u043c\u0435\u043d\u0438"), List.of("&7\u041d\u0430\u0436\u043c\u0438\u0442\u0435, \u0447\u0442\u043e\u0431\u044b \u043e\u0442\u043a\u0440\u044b\u0442\u044c")));
        if (this.plugin.getConfig().getBoolean("sounds.open-menu", true)) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
        }
        player.openInventory(inv);
    }

    public void openTopList(Player player, TopType type) {
        String configKey = switch (type) {
            default -> throw new MatchException(null, null);
            case TopType.BALANCE -> "gui.balance-title";
            case TopType.KILLS -> "gui.kills-title";
            case TopType.DEATHS -> "gui.deaths-title";
            case TopType.SHARDS -> "gui.shards-title";
            case TopType.BLOCKS -> "gui.blocks-title";
            case TopType.PLAYTIME -> "gui.playtime-title";
        };
        String title = ColorUtil.color(this.plugin.getConfig().getString(configKey, "\u0422\u043e\u043f"));
        Inventory inv = Bukkit.createInventory(null, (int)54, (String)title);
        List<TopEntry> entries = this.plugin.getTopManager().getTop(type.name().toLowerCase());
        int[] slots = new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21};
        for (int i = 0; i < Math.min(entries.size(), slots.length); ++i) {
            TopEntry entry = entries.get(i);
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta)skull.getItemMeta();
            if (meta != null) {
                meta.setOwner(entry.name());
                meta.setDisplayName(ColorUtil.color("&e#" + (i + 1) + " &f" + entry.name()));
                meta.setLore(List.of(ColorUtil.color("&7\u0420\u0435\u0437\u0443\u043b\u044c\u0442\u0430\u0442: &a" + this.formatValue(type, entry.value()))));
                skull.setItemMeta((ItemMeta)meta);
            }
            inv.setItem(slots[i], skull);
        }
        inv.setItem(49, this.createGuiItem(Material.BARRIER, "&c\u041d\u0430\u0437\u0430\u0434", List.of("&7\u0412\u0435\u0440\u043d\u0443\u0442\u044c\u0441\u044f \u0432 \u043c\u0435\u043d\u044e")));
        if (this.plugin.getConfig().getBoolean("sounds.open-menu", true)) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
        }
        player.openInventory(inv);
    }

    public void openSubTop(Player player, String type, String title) {
        TopType topType = TopType.fromString(type);
        if (topType != null) {
            this.openTopList(player, topType);
        } else {
            this.openMainMenu(player);
        }
    }

    private ItemStack createGuiItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.color(name));
            ArrayList<String> coloredLore = new ArrayList<String>();
            for (String l : lore) {
                coloredLore.add(ColorUtil.color(l));
            }
            meta.setLore(coloredLore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String formatValue(TopType type, double value) {
        return switch (type) {
            default -> throw new MatchException(null, null);
            case TopType.BALANCE -> String.format("%.2f %s", value, this.plugin.getConfig().getString("currency.balance-symbol", "$"));
            case TopType.KILLS, TopType.DEATHS, TopType.BLOCKS -> String.format("%,d", (long)value);
            case TopType.SHARDS -> String.format("%,d %s", (long)value, this.plugin.getConfig().getString("currency.shards-name", "\u043e\u0441\u043a."));
            case TopType.PLAYTIME -> {
                long totalSeconds = (long)value;
                long hours = totalSeconds / 3600L;
                long minutes = totalSeconds % 3600L / 60L;
                yield String.format("%d\u0447 %d\u043c\u0438\u043d", hours, minutes);
            }
        };
    }
}

