package ru.voidpe.gifts;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Admin-only virtual inventories. All inventory moves are cancelled. */
final class GiftLootMenu implements Listener {
    private static final int PAGE_SIZE = 45;
    private final VoidGifts plugin;
    private final GiftLootStore store;
    private enum View { TYPES, LOOT, CHANCE }

    private static final class Holder implements InventoryHolder {
        final View view;
        final String type;
        final String rewardId;
        final int page;
        final Map<Integer, String> slots = new HashMap<>();
        Inventory inventory;
        Holder(View view, String type, String rewardId, int page) {
            this.view = view; this.type = type; this.rewardId = rewardId; this.page = page;
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    GiftLootMenu(VoidGifts plugin, GiftLootStore store) { this.plugin = plugin; this.store = store; }

    void openTypes(Player player, int requestedPage) {
        if (!allowed(player)) return;
        List<String> types = plugin.configuredGiftTypes();
        int page = page(requestedPage, types.size());
        Holder holder = new Holder(View.TYPES, null, null, page);
        Inventory inventory = inventory(holder, 54, "&8Подарки: выбери тип");
        int start = page * PAGE_SIZE;
        for (int i = start; i < Math.min(types.size(), start + PAGE_SIZE); i++) {
            String type = types.get(i);
            int slot = i - start;
            holder.slots.put(slot, type);
            inventory.setItem(slot, button(Material.CHEST, plugin.menuGiftName(type),
                    "&7Тип: &f" + type, "&eНажми для настройки лута"));
        }
        if (page > 0) inventory.setItem(45, button(Material.ARROW, "&eПредыдущая страница"));
        inventory.setItem(49, button(Material.BOOK, "&eВыбери подарок", "&7Настройка доступна только администраторам"));
        if ((page + 1) * PAGE_SIZE < types.size()) inventory.setItem(53, button(Material.ARROW, "&eСледующая страница"));
        player.openInventory(inventory);
    }

    private void openLoot(Player player, String type, int requestedPage) {
        if (!allowed(player) || !plugin.hasGiftType(type)) { openTypes(player, 0); return; }
        List<GiftLootStore.Reward> rewards = store.rewards(type);
        int page = page(requestedPage, rewards.size());
        Holder holder = new Holder(View.LOOT, type, null, page);
        Inventory inventory = inventory(holder, 54, "&8Лут подарка");
        int start = page * PAGE_SIZE;
        for (int i = start; i < Math.min(rewards.size(), start + PAGE_SIZE); i++) {
            GiftLootStore.Reward reward = rewards.get(i);
            int slot = i - start;
            holder.slots.put(slot, reward.id());
            inventory.setItem(slot, rewardIcon(reward));
        }
        inventory.setItem(45, button(Material.ARROW, "&eНазад к подаркам"));
        if (page > 0) inventory.setItem(48, button(Material.ARROW, "&eПредыдущая страница"));
        inventory.setItem(49, button(Material.BOOK, "&eКак добавить предмет", "&7Нажми на предмет в своём инвентаре.",
                "&7Он будет скопирован вместе с количеством.", "&7Нажми на награду для настройки шанса."));
        if ((page + 1) * PAGE_SIZE < rewards.size()) inventory.setItem(50, button(Material.ARROW, "&eСледующая страница"));
        player.openInventory(inventory);
    }

    private void openChance(Player player, String type, String id, int page) {
        if (!allowed(player) || !plugin.hasGiftType(type)) { openTypes(player, 0); return; }
        GiftLootStore.Reward reward = store.reward(type, id);
        if (reward == null) { openLoot(player, type, page); return; }
        Holder holder = new Holder(View.CHANCE, type, id, page);
        Inventory inventory = inventory(holder, 36, "&8Шанс награды");
        inventory.setItem(10, button(Material.RED_STAINED_GLASS_PANE, "&c-10%"));
        inventory.setItem(11, button(Material.RED_STAINED_GLASS_PANE, "&c-1%"));
        inventory.setItem(13, rewardIcon(reward));
        inventory.setItem(15, button(Material.LIME_STAINED_GLASS_PANE, "&a+1%"));
        inventory.setItem(16, button(Material.LIME_STAINED_GLASS_PANE, "&a+10%"));
        inventory.setItem(22, button(Material.BARRIER, "&cУдалить награду", "&7Удаляется только запись из лута."));
        inventory.setItem(31, button(Material.ARROW, "&eНазад к луту"));
        player.openInventory(inventory);
    }

    private boolean allowed(Player player) {
        if (!player.hasPermission("voidgifts.admin")) { player.sendMessage(color("&cНет прав для настройки подарков.")); return false; }
        if (!store.isReady()) { player.sendMessage(color("&cЛут недоступен. Проверьте loot.yml и лог сервера.")); return false; }
        return true;
    }

    private Inventory inventory(Holder holder, int size, String title) {
        holder.inventory = Bukkit.createInventory(holder, size, color(title));
        return holder.inventory;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        HumanEntity who = event.getWhoClicked();
        if (!(who instanceof Player player) || !allowed(player)) return;
        if (holder.type != null && !plugin.hasGiftType(holder.type)) {
            later(player, holder, () -> openTypes(player, 0)); return;
        }
        Inventory clicked = event.getClickedInventory();
        if (holder.view == View.LOOT && clicked instanceof PlayerInventory && clicked.getHolder() == player) {
            ItemStack item = event.getCurrentItem();
            if (item == null || item.getType().isAir()) return;
            if (!store.add(holder.type, item)) { saveFailed(player); return; }
            player.sendMessage(color("&aПредмет добавлен в лут с шансом 100%. Оригинал остался у тебя."));
            int newPage = (store.rewards(holder.type).size() - 1) / PAGE_SIZE;
            later(player, holder, () -> openLoot(player, holder.type, newPage));
            return;
        }
        if (clicked != top) return;
        int slot = event.getRawSlot();
        if (holder.view == View.TYPES) {
            String type = holder.slots.get(slot);
            if (type != null) later(player, holder, () -> openLoot(player, type, 0));
            else if (slot == 45 && holder.page > 0) later(player, holder, () -> openTypes(player, holder.page - 1));
            else if (slot == 53 && (holder.page + 1) * PAGE_SIZE < plugin.configuredGiftTypes().size()) later(player, holder, () -> openTypes(player, holder.page + 1));
        } else if (holder.view == View.LOOT) {
            String id = holder.slots.get(slot);
            if (id != null) later(player, holder, () -> openChance(player, holder.type, id, holder.page));
            else if (slot == 45) later(player, holder, () -> openTypes(player, 0));
            else if (slot == 48 && holder.page > 0) later(player, holder, () -> openLoot(player, holder.type, holder.page - 1));
            else if (slot == 50 && (holder.page + 1) * PAGE_SIZE < store.rewards(holder.type).size()) later(player, holder, () -> openLoot(player, holder.type, holder.page + 1));
        } else {
            if (slot == 31) { later(player, holder, () -> openLoot(player, holder.type, holder.page)); return; }
            GiftLootStore.Reward reward = store.reward(holder.type, holder.rewardId);
            if (reward == null) { later(player, holder, () -> openLoot(player, holder.type, holder.page)); return; }
            if (slot == 22) {
                if (!store.remove(holder.type, holder.rewardId)) { saveFailed(player); return; }
                player.sendMessage(color("&eНаграда удалена."));
                later(player, holder, () -> openLoot(player, holder.type, holder.page));
                return;
            }
            double delta = switch (slot) { case 10 -> -10; case 11 -> -1; case 15 -> 1; case 16 -> 10; default -> 0; };
            if (delta != 0) {
                if (!store.setChance(holder.type, holder.rewardId, LootChance.clamp(reward.chance() + delta))) { saveFailed(player); return; }
                later(player, holder, () -> openChance(player, holder.type, holder.rewardId, holder.page));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    /** Bukkit forbids opening another inventory synchronously inside InventoryClickEvent. */
    private void later(Player player, Holder current, Runnable next) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == current) next.run();
        });
    }

    private static int page(int requested, int size) { return Math.max(0, Math.min(requested, Math.max(0, (size - 1) / PAGE_SIZE))); }
    private static void saveFailed(Player player) { player.sendMessage(color("&cИзменение не сохранено. Проверьте лог сервера.")); }
    private static String color(String value) { return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value); }
    private static String percent(double chance) { return String.format(Locale.ROOT, chance == Math.rint(chance) ? "%.0f%%" : "%.1f%%", chance); }

    private static ItemStack rewardIcon(GiftLootStore.Reward reward) {
        ItemStack icon = reward.item();
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(color("&8────────────"));
            lore.add(color("&eШанс: &f" + percent(reward.chance())));
            lore.add(color("&7Нажми для настройки или удаления"));
            meta.setLore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private static ItemStack button(Material material, String name, String... description) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            List<String> lore = new ArrayList<>();
            for (String line : description) lore.add(color(line));
            if (!lore.isEmpty()) meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
