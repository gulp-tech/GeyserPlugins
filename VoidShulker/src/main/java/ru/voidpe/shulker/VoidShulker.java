package ru.voidpe.shulker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The item in the player's inventory is authoritative, never an in-memory escrow.
 * All supported transfers are cancelled and applied on the server thread, with
 * the updated shulker and player slots written before the event returns. The GUI
 * is only a view. Cursor/drag/number-key operations are deliberately disabled.
 */
public class VoidShulker extends JavaPlugin implements Listener, CommandExecutor {
    private static final Pattern HEX = Pattern.compile("&#([0-9A-Fa-f]{6})");
    private static final int OFFHAND_SLOT = 40;
    private static final StackTransfer.Stacks<ItemStack> STACKS = new StackTransfer.Stacks<>() {
        public boolean empty(ItemStack stack) { return isEmpty(stack); }
        public int amount(ItemStack stack) { return stack.getAmount(); }
        public int max(ItemStack stack) { return stack.getMaxStackSize(); }
        public boolean similar(ItemStack a, ItemStack b) { return a.isSimilar(b); }
        public ItemStack copy(ItemStack stack, int amount) {
            ItemStack copy = stack.clone();
            copy.setAmount(amount);
            return copy;
        }
    };

    private static final class ShulkerHolder implements InventoryHolder {
        final UUID owner;
        final String token;
        final int anchorSlot;
        Inventory inventory;

        ShulkerHolder(UUID owner, int anchorSlot) {
            this.owner = owner;
            this.token = UUID.randomUUID().toString();
            this.anchorSlot = anchorSlot;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    private final Map<UUID, ShulkerHolder> sessions = new HashMap<>();
    private final Map<UUID, Long> lastOpen = new HashMap<>();
    private NamespacedKey sessionKey;

    @Override public void onEnable() {
        saveDefaultConfig();
        sessionKey = new NamespacedKey(this, "open_session");
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("voidshulker") != null) getCommand("voidshulker").setExecutor(this);
        // Online players are relevant to plugin reloads, not only fresh logins.
        for (Player player : Bukkit.getOnlinePlayers()) stripStaleMarkers(player);
        getLogger().info("VoidShulker: содержимое сохраняется в самом предмете после каждого переноса.");
    }

    @Override public void onDisable() {
        for (UUID uuid : new ArrayList<>(sessions.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            ShulkerHolder holder = sessions.get(uuid);
            if (player != null && holder != null) {
                player.closeInventory();
                finish(player, holder);
            } else if (holder != null) {
                getLogger().warning("Сессия игрока " + uuid + " закрыта без игрока; предмет остаётся в его инвентаре.");
            }
        }
        sessions.clear();
        lastOpen.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR || player.isSneaking()) return;
        EquipmentSlot hand = event.getHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) return;
        ItemStack item = hand == EquipmentSlot.HAND ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (!isShulker(item)) return;
        if (hand == EquipmentSlot.OFF_HAND && (!getConfig().getBoolean("offhand", true)
                || isShulker(player.getInventory().getItemInMainHand()))) return;
        if (!player.hasPermission("voidshulker.use")) {
            send(player, getConfig().getString("messages.no-permission", "&cНельзя открывать шалкер из руки."));
            return;
        }
        // Cancel placement before examining cooldown, game mode, or contents.
        event.setCancelled(true);
        if (player.getGameMode() == GameMode.CREATIVE) {
            send(player, getConfig().getString("messages.creative", "&cОткрытие шалкера в творческом режиме отключено."));
            return;
        }
        if (!isEmpty(player.getItemOnCursor())) {
            send(player, getConfig().getString("messages.cursor", "&cСначала убери предмет с курсора."));
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastOpen.getOrDefault(player.getUniqueId(), 0L) < 300L) return;
        lastOpen.put(player.getUniqueId(), now);
        if (item.getAmount() != 1) {
            send(player, getConfig().getString("messages.stacked", "&cСначала раздели шалкеры: открывать можно по одному."));
            return;
        }
        openShulker(player, hand, item);
    }

    private void openShulker(Player player, EquipmentSlot hand, ItemStack item) {
        ItemStack marked = item.clone();
        if (!(marked.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox box)) {
            getLogger().warning("Не удалось прочитать предмет шалкера игрока " + player.getUniqueId());
            return;
        }
        int slot = hand == EquipmentSlot.HAND ? player.getInventory().getHeldItemSlot() : OFFHAND_SLOT;
        ShulkerHolder holder = new ShulkerHolder(player.getUniqueId(), slot);
        holder.inventory = Bukkit.createInventory(holder, 27,
                component(getConfig().getString("title", "&#B388FFШалкер")));
        holder.inventory.setContents(copyContents(box.getSnapshotInventory().getContents()));
        meta.getPersistentDataContainer().set(sessionKey, PersistentDataType.STRING, holder.token);
        if (!marked.setItemMeta(meta)) {
            getLogger().severe("Не удалось пометить предмет шалкера " + player.getUniqueId());
            return;
        }
        // The original stays in player data. The marker only distinguishes this
        // exact item from another shulker that a plugin might put into the slot.
        player.getInventory().setItem(slot, marked);
        sessions.put(holder.owner, holder);
        try {
            if (player.openInventory(holder.inventory) == null
                    || player.getOpenInventory().getTopInventory().getHolder() != holder) {
                finish(player, holder); // opening cancelled or replaced by another plugin
                return;
            }
        } catch (RuntimeException e) {
            finish(player, holder);
            getLogger().severe("Ошибка открытия шалкера " + player.getUniqueId() + ": " + e);
            return;
        }
        play(player, "sound-open");
    }

    /** The marked item is the source of truth. Never reconstruct it from an old GUI copy. */
    private ItemStack anchor(Player player, ShulkerHolder holder) {
        ItemStack item = player.getInventory().getItem(holder.anchorSlot);
        if (!isShulker(item) || item.getAmount() != 1 || !hasToken(item, holder.token)) return null;
        return item;
    }

    private boolean hasToken(ItemStack item, String token) {
        if (isEmpty(item) || !item.hasItemMeta()) return false;
        return token.equals(item.getItemMeta().getPersistentDataContainer().get(sessionKey, PersistentDataType.STRING));
    }

    private ItemStack removeMarker(ItemStack stack) {
        if (isEmpty(stack) || !stack.hasItemMeta()) return stack;
        ItemMeta meta = stack.getItemMeta();
        if (!meta.getPersistentDataContainer().has(sessionKey, PersistentDataType.STRING)) return stack;
        ItemStack copy = stack.clone();
        meta.getPersistentDataContainer().remove(sessionKey);
        copy.setItemMeta(meta);
        return copy;
    }

    private void finish(Player player, ShulkerHolder holder) {
        if (!sessions.remove(holder.owner, holder)) return;
        PlayerInventory inv = player.getInventory();
        boolean found = false;
        // Search all slots if some other plugin moved the item. Do not return the
        // GUI contents or create a replacement; that could duplicate the item.
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (hasToken(contents[i], holder.token)) {
                inv.setItem(i, removeMarker(contents[i]));
                found = true;
            }
        }
        if (!found && !player.isDead()) {
            getLogger().warning("Шалкер сессии " + holder.owner + " перемещён/удалён извне; копии GUI не выдаются.");
        }
    }

    private void stripStaleMarkers(Player player) {
        if (sessions.containsKey(player.getUniqueId())) return;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (isShulker(stack) && stack.hasItemMeta()
                    && stack.getItemMeta().getPersistentDataContainer().has(sessionKey, PersistentDataType.STRING)) {
                player.getInventory().setItem(i, removeMarker(stack));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof ShulkerHolder holder
                && event.getPlayer() instanceof Player player && holder.owner.equals(player.getUniqueId())
                && sessions.get(holder.owner) == holder) {
            finish(player, holder);
            play(player, "sound-close");
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        ShulkerHolder holder = sessions.get(event.getPlayer().getUniqueId());
        if (holder != null) finish(event.getPlayer(), holder);
        lastOpen.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { stripStaleMarkers(event.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        ShulkerHolder holder = sessions.get(player.getUniqueId());
        if (holder == null) return;
        // Vanilla / other plugins decide keepInventory and drops; do not add a
        // second shulker. Only remove the temporary marker from existing drops.
        List<ItemStack> drops = event.getDrops();
        for (int i = 0; i < drops.size(); i++) {
            if (hasToken(drops.get(i), holder.token)) drops.set(i, removeMarker(drops.get(i)));
        }
        finish(player, holder);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())
                && Tag.SHULKER_BOXES.isTagged(event.getBlockPlaced().getType())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ShulkerHolder holder)) return;
        boolean wasCancelled = event.isCancelled();
        // Cancel every vanilla action; nothing in the GUI is independently owned.
        event.setCancelled(true);
        if (wasCancelled || !(event.getWhoClicked() instanceof Player player)
                || !holder.owner.equals(player.getUniqueId()) || sessions.get(holder.owner) != holder) return;
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT
                && click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT) return;
        if (!isEmpty(event.getCursor()) || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR) return;
        ItemStack item = anchor(player, holder);
        if (item == null || !(item.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox box)) {
            invalidate(player, holder, "предмет-якорь исчез или изменился");
            return;
        }
        ItemStack[] topBefore = copyContents(box.getSnapshotInventory().getContents());
        if (!Arrays.equals(topBefore, copyContents(holder.inventory.getContents()))) {
            invalidate(player, holder, "окно изменено извне");
            return;
        }
        ItemStack[] topAfter = copyContents(topBefore);
        PlayerInventory playerInv = player.getInventory();
        ItemStack[] bottomBefore = copyContents(playerInv.getContents());
        ItemStack[] bottomAfter = copyContents(bottomBefore);
        int slot = event.getSlot();
        int limit = click == ClickType.RIGHT ? 1 : Integer.MAX_VALUE;
        int moved;
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(holder.inventory)) {
            moved = StackTransfer.move(topAfter, slot, bottomAfter,
                    playerInv.getStorageContents().length, holder.anchorSlot, limit, playerInv.getMaxStackSize(), STACKS);
        } else if (event.getClickedInventory() != null && event.getClickedInventory().equals(playerInv)) {
            if (slot < 0 || slot >= playerInv.getStorageContents().length || slot == holder.anchorSlot
                    || isShulker(bottomAfter[slot])) return;
            moved = StackTransfer.move(bottomAfter, slot, topAfter,
                    topAfter.length, -1, limit, holder.inventory.getMaxStackSize(), slot, STACKS);
        } else return;
        if (moved == 0) return;
        try {
            box.getSnapshotInventory().setContents(topAfter);
            meta.setBlockState(box);
            ItemStack updated = item.clone();
            if (!updated.setItemMeta(meta) || holder.anchorSlot >= bottomAfter.length) {
                invalidate(player, holder, "не удалось сохранить содержимое");
                return;
            }
            bottomAfter[holder.anchorSlot] = updated;
            // Only changed slots are sent, not all equipment on every mobile tap.
            for (int i = 0; i < bottomAfter.length; i++) {
                if (!Objects.equals(bottomBefore[i], bottomAfter[i])) playerInv.setItem(i, bottomAfter[i]);
            }
            holder.inventory.setContents(topAfter);
            player.updateInventory();
        } catch (RuntimeException failure) {
            // No old escrow fallback. Restore the pre-click state if a setter failed.
            try {
                for (int i = 0; i < bottomBefore.length; i++) {
                    if (!Objects.equals(playerInv.getItem(i), bottomBefore[i])) playerInv.setItem(i, bottomBefore[i]);
                }
                holder.inventory.setContents(topBefore);
            } catch (RuntimeException rollback) {
                getLogger().severe("ОШИБКА отката инвентаря " + holder.owner + ": " + rollback);
            }
            getLogger().severe("ОШИБКА переноса в шалкере " + holder.owner + ": " + failure);
            invalidate(player, holder, "ошибка переноса");
        }
    }

    private void invalidate(Player player, ShulkerHolder holder, String reason) {
        getLogger().warning("Сессия шалкера " + holder.owner + " закрывается: " + reason);
        send(player, getConfig().getString("messages.invalid", "&cШалкер изменился извне, окно закрыто."));
        // Closing an inventory inside InventoryClickEvent is unsafe in Bukkit.
        Bukkit.getScheduler().runTask(this, () -> {
            if (sessions.get(holder.owner) == holder) {
                player.closeInventory();
                finish(player, holder);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShulkerHolder) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() != GameMode.CREATIVE && event.getNewGameMode() != GameMode.SPECTATOR) return;
        ShulkerHolder holder = sessions.get(event.getPlayer().getUniqueId());
        if (holder != null) invalidate(event.getPlayer(), holder, "режим игры изменился");
    }

    private static ItemStack[] copyContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) copy[i] = isEmpty(contents[i]) ? null : contents[i].clone();
        return copy;
    }
    private static boolean isEmpty(ItemStack item) { return item == null || item.getType().isAir() || item.getAmount() <= 0; }
    private static boolean isShulker(ItemStack item) { return !isEmpty(item) && Tag.SHULKER_BOXES.isTagged(item.getType()); }
    private void play(Player player, String configKey) {
        String sound = getConfig().getString(configKey, "");
        if (sound != null && !sound.isBlank()) player.playSound(player.getLocation(), sound, 0.6f, 1.0f);
    }
    private void send(CommandSender sender, String text) { sender.sendMessage(component(text == null ? "" : text)); }
    private Component component(String legacy) { return LegacyComponentSerializer.legacySection().deserialize(color(legacy)); }
    private String color(String text) {
        Matcher m = HEX.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            StringBuilder rep = new StringBuilder("§x");
            for (char c : m.group(1).toCharArray()) rep.append('§').append(c);
            m.appendReplacement(out, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(out);
        return out.toString().replace('&', '§');
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("voidshulker.admin")) { send(sender, "&cНет прав."); return true; }
            reloadConfig();
            send(sender, "&aVoidShulker перезагружен.");
        } else send(sender, "&7Использование: &f/voidshulker reload");
        return true;
    }
}
