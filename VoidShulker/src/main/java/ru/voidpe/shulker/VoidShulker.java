package ru.voidpe.shulker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
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
import org.bukkit.event.inventory.InventoryAction;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The item in the player's inventory is authoritative, never an in-memory escrow.
 * All supported transfers are cancelled and applied on the server thread, with
 * the updated shulker, player slots and cursor written before the event returns. The GUI
 * is only a view. Cursor/drag/number-key operations use the same transaction;
 * drops, moving the open anchor and nested shulkers remain disabled.
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
        // A client resurrect can land at close time; sweep the recent placements once more.
        if (isEnabled()) Bukkit.getScheduler().runTask(this, () -> resolveGhosts(player));
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
        if (sessions.containsKey(event.getPlayer().getUniqueId()) && isShulkerBlock(event.getBlockPlaced())) {
            event.setCancelled(true);
        }
    }

    /**
     * Bedrock race guard: one quick tap can open the GUI and place the box while
     * the item itself survives the placement (core-side ghost copy). One tick
     * later — and again at session close — the world and the inventory must not
     * both hold the same filled shulker; exactly one honest copy is kept.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlacedShulker(BlockPlaceEvent event) {
        if (!isShulkerBlock(event.getBlockPlaced())) return;
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        ItemStack placedItem = copyItem(event.getItemInHand());
        if (!isShulker(placedItem)) return;
        int slot = event.getHand() == EquipmentSlot.OFF_HAND ? OFFHAND_SLOT : player.getInventory().getHeldItemSlot();
        ghosts.add(new GhostRecord(player.getUniqueId(), event.getBlockPlaced().getLocation(), placedItem, slot,
                sessions.containsKey(player.getUniqueId()), System.currentTimeMillis()));
        Bukkit.getScheduler().runTask(this, () -> resolveGhosts(player));
    }

    private static final class GhostRecord {
        final UUID owner;
        final Location location;
        final ItemStack placedItem;
        final int slot;
        final boolean session;
        final long time;

        GhostRecord(UUID owner, Location location, ItemStack placedItem, int slot, boolean session, long time) {
            this.owner = owner;
            this.location = location;
            this.placedItem = placedItem;
            this.slot = slot;
            this.session = session;
            this.time = time;
        }
    }

    private final List<GhostRecord> ghosts = new ArrayList<>();

    /** Removes the ghost copy left by a placement race; the honest copy always stays. */
    private void resolveGhosts(Player player) {
        if (!player.isOnline()) return;
        long now = System.currentTimeMillis();
        Iterator<GhostRecord> iterator = ghosts.iterator();
        while (iterator.hasNext()) {
            GhostRecord ghost = iterator.next();
            if (now - ghost.time > 3000L) {
                iterator.remove();
                continue;
            }
            if (!ghost.owner.equals(player.getUniqueId())) continue;
            ItemStack held = player.getInventory().getItem(ghost.slot);
            if (isEmpty(held) || held.getType() != ghost.placedItem.getType()) continue;
            if (!(held.getItemMeta() instanceof BlockStateMeta heldMeta)
                    || !(heldMeta.getBlockState() instanceof ShulkerBox heldBox)
                    || !(ghost.placedItem.getItemMeta() instanceof BlockStateMeta placedMeta)
                    || !(placedMeta.getBlockState() instanceof ShulkerBox placedBox)
                    || !sameContents(heldBox, placedBox)) continue;
            Block block = ghost.location.getBlock();
            if (!(block.getState() instanceof ShulkerBox worldBox) || !sameContents(worldBox, placedBox)) {
                iterator.remove();
                continue;
            }
            iterator.remove();
            String ghostKind;
            if (ghost.session || sessions.containsKey(ghost.owner)) {
                // The tap only meant to open the GUI: the block is the ghost copy.
                block.setType(Material.AIR);
                ghostKind = "блок";
            } else {
                // The placement was intended: the surviving item is the ghost copy.
                player.getInventory().setItem(ghost.slot, null);
                ghostKind = "предмет";
            }
            getLogger().warning("Гонка постановки шалкера у " + ghost.owner + ": удалён призрачный "
                    + ghostKind + " " + ghost.location);
            send(player, getConfig().getString("messages.ghost", "&cОбнаружен дубликат шалкера, лишняя копия удалена."));
        }
    }

    /** Creates an all-or-nothing view of the real item, player inventory and cursor. */
    private Transaction transaction(Player player, ShulkerHolder holder, ItemStack cursor) {
        if (!Objects.equals(copyItem(player.getItemOnCursor()), copyItem(cursor))) {
            invalidate(player, holder, "курсор изменился извне");
            return null;
        }
        ItemStack item = anchor(player, holder);
        if (item == null || !(item.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox box)) {
            invalidate(player, holder, "предмет-якорь исчез или изменился");
            return null;
        }
        ItemStack[] topBefore = copyContents(box.getSnapshotInventory().getContents());
        if (!Arrays.equals(topBefore, copyContents(holder.inventory.getContents()))) {
            invalidate(player, holder, "окно изменено извне");
            return null;
        }
        return new Transaction(player, holder, item, meta, box, topBefore, cursor);
    }

    private final class Transaction {
        final Player player;
        final ShulkerHolder holder;
        final PlayerInventory playerInv;
        final ItemStack item;
        final BlockStateMeta meta;
        final ShulkerBox box;
        final ItemStack[] topBefore;
        final ItemStack[] topAfter;
        final ItemStack[] bottomBefore;
        final ItemStack[] bottomAfter;
        final ItemStack[] cursorBefore = new ItemStack[1];
        final ItemStack[] cursorAfter = new ItemStack[1];

        Transaction(Player player, ShulkerHolder holder, ItemStack item, BlockStateMeta meta,
                    ShulkerBox box, ItemStack[] topBefore, ItemStack cursor) {
            this.player = player;
            this.holder = holder;
            this.playerInv = player.getInventory();
            this.item = item;
            this.meta = meta;
            this.box = box;
            this.topBefore = topBefore;
            this.topAfter = copyContents(topBefore);
            this.bottomBefore = copyContents(playerInv.getContents());
            this.bottomAfter = copyContents(bottomBefore);
            this.cursorBefore[0] = copyItem(cursor);
            this.cursorAfter[0] = copyItem(cursor);
        }

        private boolean changed() {
            return !Arrays.equals(topBefore, topAfter) || !Arrays.equals(bottomBefore, bottomAfter)
                    || !Arrays.equals(cursorBefore, cursorAfter);
        }

        void commit() {
            if (!changed()) return;
            // The live inventory must still match the snapshot; a placement race
            // that consumed the anchor must never be resurrected from bottomAfter.
            if (!Objects.equals(playerInv.getItem(holder.anchorSlot), bottomBefore[holder.anchorSlot])) {
                invalidate(player, holder, "инвентарь изменён вне транзакции");
                return;
            }
            try {
                box.getSnapshotInventory().setContents(topAfter);
                meta.setBlockState(box);
                ItemStack updated = item.clone();
                if (holder.anchorSlot < 0 || holder.anchorSlot >= bottomAfter.length
                        || !updated.setItemMeta(meta)) {
                    throw new IllegalStateException("не удалось сохранить содержимое в предмете");
                }
                bottomAfter[holder.anchorSlot] = updated;
                for (int i = 0; i < bottomAfter.length; i++) {
                    if (!Objects.equals(bottomBefore[i], bottomAfter[i])) playerInv.setItem(i, bottomAfter[i]);
                }
                if (!Objects.equals(cursorBefore[0], cursorAfter[0])) {
                    player.setItemOnCursor(copyItem(cursorAfter[0]));
                }
                holder.inventory.setContents(topAfter);
                player.updateInventory();
            } catch (RuntimeException failure) {
                rollback();
                getLogger().severe("ОШИБКА переноса в шалкере " + holder.owner + ": " + failure);
                invalidate(player, holder, "ошибка переноса");
            }
        }

        private void rollback() {
            try {
                for (int i = 0; i < bottomBefore.length; i++) {
                    if (!Objects.equals(playerInv.getItem(i), bottomBefore[i])) playerInv.setItem(i, bottomBefore[i]);
                }
                if (!Objects.equals(player.getItemOnCursor(), cursorBefore[0])) {
                    player.setItemOnCursor(copyItem(cursorBefore[0]));
                }
                holder.inventory.setContents(topBefore);
            } catch (RuntimeException rollbackFailure) {
                getLogger().severe("ОШИБКА отката инвентаря " + holder.owner + ": " + rollbackFailure);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ShulkerHolder holder)) return;
        boolean wasCancelled = event.isCancelled();
        // Vanilla never owns a GUI copy: every accepted action is applied manually below.
        event.setCancelled(true);
        if (wasCancelled || !(event.getWhoClicked() instanceof Player player)
                || !holder.owner.equals(player.getUniqueId()) || sessions.get(holder.owner) != holder
                || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        boolean fromTop = event.getClickedInventory() != null && event.getClickedInventory().equals(holder.inventory);
        boolean fromBottom = event.getClickedInventory() != null && event.getClickedInventory().equals(player.getInventory());
        if (!fromTop && !fromBottom) return;
        Transaction tx = transaction(player, holder, event.getCursor());
        if (tx == null) return;
        int slot = event.getSlot();
        if (fromTop) {
            if (slot < 0 || slot >= tx.topAfter.length) return;
        } else if (slot < 0 || slot >= tx.playerInv.getStorageContents().length || slot == holder.anchorSlot) {
            return; // equipment/offhand and the open anchor are never transferable
        }

        ClickType click = event.getClick();
        InventoryAction action = event.getAction();
        if (click == ClickType.DOUBLE_CLICK || action == InventoryAction.COLLECT_TO_CURSOR) {
            if (isEmpty(tx.cursorAfter[0]) || isShulker(tx.cursorAfter[0])) return;
            int moved = 0;
            for (int i = 0; i < tx.topAfter.length; i++) {
                moved += StackTransfer.move(tx.topAfter, i, tx.cursorAfter, 1, -1,
                        Integer.MAX_VALUE, tx.playerInv.getMaxStackSize(), STACKS);
            }
            for (int i = 0; i < tx.playerInv.getStorageContents().length; i++) {
                if (i == holder.anchorSlot) continue;
                moved += StackTransfer.move(tx.bottomAfter, i, tx.cursorAfter, 1, -1,
                        Integer.MAX_VALUE, tx.playerInv.getMaxStackSize(), STACKS);
            }
            if (moved > 0) tx.commit();
            return;
        }
        if (click == ClickType.DROP || click == ClickType.CONTROL_DROP || click == ClickType.MIDDLE
                || action == InventoryAction.DROP_ALL_CURSOR || action == InventoryAction.DROP_ONE_CURSOR
                || action == InventoryAction.DROP_ALL_SLOT || action == InventoryAction.DROP_ONE_SLOT) return;
        if (action == InventoryAction.HOTBAR_SWAP || action == InventoryAction.HOTBAR_MOVE_AND_READD) {
            int button = event.getHotbarButton();
            if (button < 0 || button >= 9 || button == holder.anchorSlot || !isEmpty(tx.cursorAfter[0])) return;
            ItemStack hotbar = tx.bottomAfter[button];
            if (fromTop && isShulker(hotbar)) return;
            ItemStack[] slots = fromTop ? tx.topAfter : tx.bottomAfter;
            if (Objects.equals(slots[slot], hotbar)) return;
            ItemStack previous = slots[slot];
            slots[slot] = copyItem(hotbar);
            tx.bottomAfter[button] = copyItem(previous);
            tx.commit();
            return;
        }
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT
                || action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            if (!isEmpty(tx.cursorAfter[0])) return;
            int moved;
            if (fromTop) {
                moved = StackTransfer.move(tx.topAfter, slot, tx.bottomAfter,
                        tx.playerInv.getStorageContents().length, holder.anchorSlot,
                        Integer.MAX_VALUE, tx.playerInv.getMaxStackSize(), STACKS);
            } else {
                if (isShulker(tx.bottomAfter[slot])) return;
                moved = StackTransfer.move(tx.bottomAfter, slot, tx.topAfter,
                        tx.topAfter.length, -1, Integer.MAX_VALUE, holder.inventory.getMaxStackSize(), STACKS);
            }
            if (moved > 0) tx.commit();
            return;
        }
        if (click != ClickType.LEFT && click != ClickType.RIGHT) return;
        if (fromTop && isShulker(tx.cursorAfter[0])) return;
        ItemStack[] slots = fromTop ? tx.topAfter : tx.bottomAfter;
        int inventoryMax = fromTop ? holder.inventory.getMaxStackSize() : tx.playerInv.getMaxStackSize();
        if (StackTransfer.click(slots, slot, tx.cursorAfter, click == ClickType.RIGHT, inventoryMax, STACKS)) {
            tx.commit();
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
        if (!(event.getView().getTopInventory().getHolder() instanceof ShulkerHolder holder)) return;
        boolean wasCancelled = event.isCancelled();
        event.setCancelled(true);
        if (wasCancelled || !(event.getWhoClicked() instanceof Player player)
                || !holder.owner.equals(player.getUniqueId()) || sessions.get(holder.owner) != holder
                || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        Transaction tx = transaction(player, holder, event.getOldCursor());
        if (tx == null) return;
        ItemStack dragged = tx.cursorBefore[0];
        if (isEmpty(dragged) || event.getNewItems().isEmpty()) return;
        ItemStack resultCursor = copyItem(event.getCursor());
        if (!isEmpty(resultCursor) && !resultCursor.isSimilar(dragged)) return;
        if (amount(resultCursor) > tx.playerInv.getMaxStackSize()
                || (!isEmpty(resultCursor) && resultCursor.getAmount() > resultCursor.getMaxStackSize())) return;

        long before = amount(dragged);
        long after = amount(resultCursor);
        // Validate every slot before changing any array. Conservation includes the cursor.
        for (Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
            int raw = entry.getKey();
            if (raw < 0 || raw >= event.getView().countSlots()) return;
            boolean targetTop = raw < tx.topAfter.length;
            int slot = targetTop ? raw : event.getView().convertSlot(raw);
            if (targetTop) {
                if (slot < 0 || slot >= tx.topAfter.length || isShulker(dragged)) return;
            } else if (slot < 0 || slot >= tx.playerInv.getStorageContents().length || slot == holder.anchorSlot) {
                return;
            }
            ItemStack previous = targetTop ? tx.topAfter[slot] : tx.bottomAfter[slot];
            ItemStack replacement = entry.getValue();
            if (!isEmpty(previous) && !previous.isSimilar(dragged)) return;
            if (!isEmpty(replacement) && !replacement.isSimilar(dragged)) return;
            int inventoryMax = targetTop ? holder.inventory.getMaxStackSize() : tx.playerInv.getMaxStackSize();
            if (amount(replacement) > inventoryMax
                    || (!isEmpty(replacement) && replacement.getAmount() > replacement.getMaxStackSize())) return;
            before += amount(previous);
            after += amount(replacement);
        }
        if (before != after) {
            getLogger().warning("Отклонён drag с изменением числа предметов у " + holder.owner
                    + ": было " + before + ", стало " + after);
            return;
        }
        for (Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
            int raw = entry.getKey();
            boolean targetTop = raw < tx.topAfter.length;
            int slot = targetTop ? raw : event.getView().convertSlot(raw);
            if (targetTop) tx.topAfter[slot] = copyItem(entry.getValue());
            else tx.bottomAfter[slot] = copyItem(entry.getValue());
        }
        tx.cursorAfter[0] = resultCursor;
        tx.commit();
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
        for (int i = 0; i < contents.length; i++) copy[i] = copyItem(contents[i]);
        return copy;
    }
    private static ItemStack copyItem(ItemStack item) { return isEmpty(item) ? null : item.clone(); }
    private static int amount(ItemStack item) { return isEmpty(item) ? 0 : item.getAmount(); }
    private static boolean isEmpty(ItemStack item) { return item == null || item.getType().isAir() || item.getAmount() <= 0; }
    private static boolean isShulker(ItemStack item) {
        return !isEmpty(item) && (Tag.SHULKER_BOXES.isTagged(item.getType())
                || item.getType().name().contains("SHULKER_BOX"));
    }
    private static boolean isShulkerBlock(Block block) {
        return block != null && (block.getState() instanceof ShulkerBox
                || block.getType().name().contains("SHULKER_BOX"));
    }
    private static boolean sameContents(ShulkerBox a, ShulkerBox b) {
        return Arrays.equals(copyContents(a.getSnapshotInventory().getContents()),
                copyContents(b.getSnapshotInventory().getContents()));
    }
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
