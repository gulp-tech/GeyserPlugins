package ru.voidpe.shulker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
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
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Открытие шалкера из руки. На время сессии предмет находится в памяти плагина,
 * а не в инвентаре игрока, поэтому его нельзя одновременно открыть и поставить. */
public final class VoidShulker extends JavaPlugin implements Listener, CommandExecutor {

    private static final Pattern HEX = Pattern.compile("&#([0-9A-Fa-f]{6})");

    /** Открытая сессия шалкера: окно + рука + физический слот предмета-якоря. */
    private static final class ShulkerHolder implements InventoryHolder {
        private final UUID owner;
        private final EquipmentSlot hand;
        private final int anchorSlot; // 0-8 главная рука, 40 — вторая
        private final ItemStack escrow;
        private Inventory inventory;

        private ShulkerHolder(UUID owner, EquipmentSlot hand, int anchorSlot, ItemStack escrow) {
            this.owner = owner;
            this.hand = hand;
            this.anchorSlot = anchorSlot;
            this.escrow = escrow;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Map<UUID, ShulkerHolder> sessions = new HashMap<>();
    private final Map<UUID, Long> lastOpen = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("voidshulker") != null) {
            getCommand("voidshulker").setExecutor(this);
        }
        getLogger().info("VoidShulker включён: шалкер открывается из руки по ПКМ.");
    }

    @Override
    public void onDisable() {
        // Закрыть все окна: InventoryCloseEvent сохранит содержимое обратно.
        for (UUID uuid : new java.util.ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.closeInventory();
                ShulkerHolder remaining = sessions.get(uuid);
                if (remaining != null) {
                    save(p, remaining);
                }
            } else {
                getLogger().severe("Не удалось вернуть открытый шалкер отключившемуся игроку " + uuid);
            }
        }
        sessions.clear();
    }

    // ------------------------------------------------------------- открытие

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (sessions.containsKey(player.getUniqueId())) {
            // Никакая рука и никакой shift не должны поставить предмет, пока открыто окно.
            event.setCancelled(true);
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        if (player.isSneaking()) {
            return; // Shift+ПКМ — обычная установка шалкера на землю
        }
        // Какая рука сработала: событие приходит отдельно на каждую руку.
        EquipmentSlot hand = event.getHand();
        if (hand == null) {
            return;
        }
        ItemStack item = hand == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (!isShulker(item)) {
            return; // не наша рука/предмет (второе событие пары само обработается)
        }
        if (hand == EquipmentSlot.OFF_HAND && !getConfig().getBoolean("offhand", true)) {
            return;
        }
        // Главная рука в приоритете: если шалкер в главной, событие второй игнорируем.
        if (hand == EquipmentSlot.OFF_HAND && isShulker(player.getInventory().getItemInMainHand())) {
            return;
        }
        if (!player.hasPermission("voidshulker.use")) {
            send(player, getConfig().getString("messages.no-permission", "&cНельзя."));
            return;
        }
        event.setCancelled(true); // не ставим блок и не трогаем блок под курсором
        // Антиспам открытия (событие может продублироваться ядром).
        long now = System.currentTimeMillis();
        if (now - lastOpen.getOrDefault(player.getUniqueId(), 0L) < 300L) {
            return;
        }
        lastOpen.put(player.getUniqueId(), now);
        if (item.getAmount() > 1) {
            send(player, getConfig().getString("messages.stacked", "&cПо одному."));
            return;
        }
        openShulker(player, hand, item);
    }

    private void openShulker(Player player, EquipmentSlot hand, ItemStack item) {
        if (!(item.getItemMeta() instanceof BlockStateMeta meta)) {
            return;
        }
        if (!(meta.getBlockState() instanceof ShulkerBox box)) {
            return;
        }
        int anchorSlot = hand == EquipmentSlot.HAND
                ? player.getInventory().getHeldItemSlot() : 40;
        ShulkerHolder holder = new ShulkerHolder(player.getUniqueId(), hand, anchorSlot, item.clone());
        Inventory inv = Bukkit.createInventory(holder, 27,
                component(getConfig().getString("title", "&#B388FFШалкер")));
        holder.inventory = inv;
        // Копии предметов: GUI не делит стаки с предметом-якорем.
        ItemStack[] contents = box.getSnapshotInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null) {
                inv.setItem(i, contents[i].clone());
            }
        }
        // Изъять оригинал ДО открытия GUI: его нельзя поставить или переместить,
        // пока содержимое редактируется в копии инвентаря.
        player.getInventory().setItem(anchorSlot, null);
        sessions.put(player.getUniqueId(), holder);
        try {
            if (player.openInventory(inv) == null) {
                save(player, holder); // другой плагин отменил открытие
                return;
            }
        } catch (RuntimeException ex) {
            save(player, holder);
            throw ex;
        }
        String sound = getConfig().getString("sound-open", "");
        if (sound != null && !sound.isBlank()) {
            player.playSound(player.getLocation(), sound, 0.6f, 1.0f);
        }
    }

    // ------------------------------------------------------------- сохранение

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof ShulkerHolder holder
                && event.getPlayer() instanceof Player player
                && holder.owner.equals(player.getUniqueId())) {
            save(player, holder);
            String sound = getConfig().getString("sound-close", "");
            if (sound != null && !sound.isBlank()) {
                player.playSound(player.getLocation(), sound, 0.6f, 1.0f);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ShulkerHolder holder = sessions.get(event.getPlayer().getUniqueId());
        if (holder != null) {
            save(event.getPlayer(), holder);
        }
        lastOpen.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        ShulkerHolder holder = sessions.get(player.getUniqueId());
        if (holder == null) {
            return;
        }
        ItemStack result = finish(holder);
        if (result == null) {
            return;
        }
        if (event.getKeepInventory()) {
            returnItem(player, holder.anchorSlot, result);
        } else {
            // Список дропа уже сформирован, поэтому добавление в инвентарь здесь небезопасно.
            event.getDrops().add(result);
        }
    }

    /** Завершить именно эту сессию один раз и записать GUI в изъятый предмет. */
    private ItemStack finish(ShulkerHolder holder) {
        if (!sessions.remove(holder.owner, holder)) {
            return null;
        }
        ItemStack result = holder.escrow.clone();
        if (!(result.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox box)) {
            getLogger().severe("Не удалось сохранить шалкер игрока " + holder.owner);
            return result;
        }
        Inventory boxInv = box.getSnapshotInventory();
        boxInv.setContents(holder.inventory.getContents());
        meta.setBlockState(box);
        result.setItemMeta(meta);
        return result;
    }

    private void save(Player player, ShulkerHolder holder) {
        ItemStack result = finish(holder);
        if (result != null) {
            returnItem(player, holder.anchorSlot, result);
        }
    }

    /** Не перезаписывать чужой предмет, если слот был занят извне. */
    private void returnItem(Player player, int slot, ItemStack item) {
        ItemStack existing = player.getInventory().getItem(slot);
        if (existing == null || existing.getType().isAir()) {
            player.getInventory().setItem(slot, item);
            return;
        }
        Map<Integer, ItemStack> rest = player.getInventory().addItem(item);
        for (ItemStack drop : rest.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())
                && Tag.SHULKER_BOXES.isTagged(event.getBlockPlaced().getType())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------- антидюп

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ShulkerHolder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        Inventory clicked = event.getClickedInventory();

        // 1) Якорный слот заморожен полностью: клики, цифровые свапы на него,
        //    свап рук с ним и «собрать всё двойным кликом».
        if (clicked != null && clicked.equals(player.getInventory()) && event.getSlot() == holder.anchorSlot) {
            event.setCancelled(true);
            return;
        }
        if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() == holder.anchorSlot) {
            event.setCancelled(true);
            return;
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }

        // 2) Шалкер в шалкер не кладётся (как в ванилле): ни курсором,
        //    ни shift-кликом снизу, ни цифровой клавишей, ни свапом рук.
        if (clicked != null && clicked.equals(top)) {
            if (isShulker(event.getCursor())) {
                event.setCancelled(true);
                return;
            }
            if (event.getClick() == ClickType.NUMBER_KEY
                    && isShulker(player.getInventory().getItem(event.getHotbarButton()))) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                && clicked != null && clicked.equals(player.getInventory())
                && isShulker(event.getCurrentItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ShulkerHolder holder)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (event.getView().getInventory(raw) != null
                    && event.getView().getInventory(raw).equals(event.getWhoClicked().getInventory())
                    && event.getView().convertSlot(raw) == holder.anchorSlot) {
                event.setCancelled(true);
                return;
            }
            // Перетаскивание шалкера в верхнее окно — то же вложение, запрет.
            if (raw < topSize && isShulker(event.getOldCursor())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true); // якорь может быть в любой руке
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())
                && isShulker(event.getItemDrop().getItemStack())) {
            event.setCancelled(true); // не выбросить открытый/любой шалкер, пока окно открыто
        }
    }

    // ------------------------------------------------------------- служебное

    private boolean isShulker(ItemStack item) {
        return item != null && Tag.SHULKER_BOXES.isTagged(item.getType());
    }

    private void send(Player player, String text) {
        player.sendMessage(component(text == null ? "" : text));
    }

    private Component component(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(color(legacy));
    }

    /** &#RRGGBB и &коды -> секционные коды (как в остальных плагинах VoidPE). */
    private String color(String text) {
        Matcher m = HEX.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String hex = m.group(1);
            StringBuilder rep = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                rep.append('§').append(c);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(out);
        return out.toString().replace('&', '§');
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("voidshulker.admin")) {
                sender.sendMessage(component("&cНет прав."));
                return true;
            }
            reloadConfig();
            sender.sendMessage(component("&aVoidShulker перезагружен."));
            return true;
        }
        sender.sendMessage(component("&7Использование: &f/voidshulker reload"));
        return true;
    }
}
