package ru.voidpe.cases;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Directional;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Кейсы: загрузка из конфига, предметы-кейсы, рулетка-анимация, выдача призов и объявления. */
public final class CaseService implements Listener {

    public record PrizeDef(String id, String name, Material material, int weight,
                           boolean announce, List<String> commands) {}

    public record CaseDef(String id, String displayName, Material material,
                          List<String> itemLore, List<PrizeDef> prizes) {}

    /** Маркер меню титулов. */
    static final class TitlesHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    /** Меню титулов: что можно выбить из кейса титулов и с каким шансом. */
    public void openTitles(Player player) {
        CaseDef def = byId("titles");
        if (def == null) {
            msg(player, "case-not-found", null, null);
            return;
        }
        Inventory inv = Bukkit.createInventory(new TitlesHolder(), 27,
                component(TextUtil.color("&#FFD93DТитулы &8• &fтолько из кейса")));
        ItemStack filler = pane(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < 27; slot++) {
            inv.setItem(slot, filler);
        }
        int total = 0;
        for (PrizeDef prize : def.prizes()) {
            total += prize.weight();
        }
        int slot = 10;
        for (PrizeDef prize : def.prizes()) {
            if (slot > 16) {
                break;
            }
            ItemStack item = prizeItem(prize);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                double chance = total > 0 ? prize.weight() * 100.0 / total : 0;
                List<Component> lore = new ArrayList<>();
                lore.add(noItalic(component(TextUtil.color("&7Шанс: &f" + String.format(Locale.US, "%.1f", chance) + "%"))));
                lore.add(noItalic(component(TextUtil.color("&7Выбивается только из: &f" + def.displayName()))));
                lore.add(noItalic(component(TextUtil.color("&8Титул виден после ника в чате"))));
                meta.lore(lore);
                item.setItemMeta(meta);
            }
            inv.setItem(slot++, item);
        }
        inv.setItem(22, pane(Material.YELLOW_STAINED_GLASS_PANE, "&#FFD93DКейс: " + def.displayName()));
        player.openInventory(inv);
    }

    /** Маркер инвентаря рулетки. */
    static final class CaseHolder implements InventoryHolder {
        final String caseId;
        CaseHolder(String caseId) { this.caseId = caseId; }
        @Override public Inventory getInventory() { return null; }
    }

    private static final class Session {
        final CaseDef def;
        final Inventory inventory;
        boolean finished;
        Session(CaseDef def, Inventory inventory) { this.def = def; this.inventory = inventory; }
    }

    private final VoidCases plugin;
    private final NamespacedKey idKey;
    private final Map<String, CaseDef> cases = new LinkedHashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<String, String> placedCases = new LinkedHashMap<>(); // "world,x,y,z" -> id кейса
    private final java.io.File placedFile;

    public CaseService(VoidCases plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "case_id");
        this.placedFile = new java.io.File(plugin.getDataFolder(), "placed-cases.yml");
        load();
        loadPlaced();
    }

    // ------------------------------------------------------------------ загрузка
    public void load() {
        cases.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("cases");
        if (section == null) {
            plugin.getLogger().warning("В config.yml нет секции cases!");
            return;
        }
        for (String id : section.getKeys(false)) {
            String path = "cases." + id;
            Material material = Material.matchMaterial(plugin.getConfig().getString(path + ".material", "CHEST"));
            if (material == null) {
                plugin.getLogger().warning("Кейс " + id + ": неизвестный материал, пропускаю");
                continue;
            }
            List<PrizeDef> prizes = new ArrayList<>();
            for (Map<?, ?> raw : plugin.getConfig().getMapList(path + ".prizes")) {
                String prizeId = String.valueOf(raw.get("id"));
                Material prizeMaterial = Material.matchMaterial(String.valueOf(raw.get("material")));
                if (prizeMaterial == null) {
                    plugin.getLogger().warning("Кейс " + id + ", приз " + prizeId + ": неизвестный материал");
                    continue;
                }
                int weight = raw.get("weight") instanceof Number ? ((Number) raw.get("weight")).intValue() : 100;
                boolean announce = Boolean.TRUE.equals(raw.get("announce"));
                List<String> commands = new ArrayList<>();
                if (raw.get("commands") instanceof List) {
                    for (Object cmd : (List<?>) raw.get("commands")) {
                        commands.add(String.valueOf(cmd));
                    }
                }
                prizes.add(new PrizeDef(
                        prizeId,
                        raw.get("name") == null ? prizeId : String.valueOf(raw.get("name")),
                        prizeMaterial,
                        Math.max(1, weight),
                        announce,
                        commands));
            }
            if (prizes.isEmpty()) {
                plugin.getLogger().warning("Кейс " + id + ": нет призов, пропускаю");
                continue;
            }
            cases.put(id.toLowerCase(Locale.ROOT), new CaseDef(
                    id,
                    plugin.getConfig().getString(path + ".display-name", id),
                    material,
                    plugin.getConfig().getStringList(path + ".item-lore"),
                    prizes));
        }
        plugin.getLogger().info("Загружено кейсов: " + cases.size());
    }

    public CaseDef byId(String id) {
        return id == null ? null : cases.get(id.toLowerCase(Locale.ROOT));
    }

    public List<String> ids() {
        return new ArrayList<>(cases.keySet());
    }

    public List<CaseDef> all() {
        return new ArrayList<>(cases.values());
    }

    // ------------------------------------------------------------------ поставленные кейсы
    private static String key(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private void loadPlaced() {
        placedCases.clear();
        if (!placedFile.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(placedFile);
        for (String line : yaml.getStringList("cases")) {
            String[] parts = line.split(",", -1);
            if (parts.length == 5) {
                placedCases.put(parts[0] + "," + parts[1] + "," + parts[2] + "," + parts[3], parts[4]);
            }
        }
        plugin.getLogger().info("Поставленных кейсов: " + placedCases.size());
    }

    private void savePlaced() {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, String> e : placedCases.entrySet()) {
                lines.add(e.getKey() + "," + e.getValue());
            }
            yaml.set("cases", lines);
            yaml.save(placedFile);
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось сохранить поставленные кейсы: " + t.getMessage());
        }
    }

    /** Вид кейса в этой точке или null. */
    public String placedCaseAt(Location loc) {
        return loc == null || loc.getWorld() == null ? null : placedCases.get(key(loc));
    }

    /** Поставить кейс на блок: блок становится сундуком Края (материал — в конфиге), точка сохраняется. */
    public void setPlaced(Block block, CaseDef def, org.bukkit.block.BlockFace facing) {
        placedCases.put(key(block.getLocation()), def.id());
        String matName = plugin.getConfig().getString("placed-cases.set-block", "ENDER_CHEST");
        Material material = matName == null || matName.isEmpty() ? null : Material.matchMaterial(matName);
        if (material != null && material.isBlock()) {
            block.setType(material);
            if (block.getBlockData() instanceof Directional) {
                Directional directional = (Directional) block.getBlockData();
                try {
                    directional.setFacing(facing);
                    block.setBlockData(directional);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        savePlaced();
    }

    /** Убрать поставленный кейс в точке; вернёт id кейса или null, если там кейса нет. */
    public String removePlaced(Location loc) {
        String removed = placedCases.remove(key(loc));
        if (removed != null) {
            savePlaced();
        }
        return removed;
    }

    /** Списать один предмет-кейс указанного вида из инвентаря (включая руки). */
    private boolean consumeCaseFromInventory(Player player, String caseId) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (caseId.equals(identify(stack))) {
                stack.setAmount(stack.getAmount() - 1);
                if (stack.getAmount() <= 0) {
                    player.getInventory().setItem(i, null);
                }
                return true;
            }
        }
        return false;
    }

    /** Клик по поставленному кейсу: списывает кейс-предмет из инвентаря и запускает рулетку. */
    @EventHandler
    public void onPlacedUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        String caseId = placedCaseAt(block.getLocation());
        if (caseId == null) {
            return;
        }
        event.setCancelled(true); // сам сундук не открываем — открывается рулетка
        Player player = event.getPlayer();
        if (sessions.containsKey(player.getUniqueId())) {
            return; // рулетка уже крутится (возможно, её открыл onUse с кейсом в руке)
        }
        CaseDef def = byId(caseId);
        if (def == null) {
            return;
        }
        if (!consumeCaseFromInventory(player, caseId)) {
            msg(player, "need-case", def, null);
            return;
        }
        openCase(player, def);
    }

    // поставленный кейс нельзя сломать (админ убирает его командой /cases remove)
    @EventHandler
    public void onBreakPlaced(BlockBreakEvent event) {
        if (placedCaseAt(event.getBlock().getLocation()) == null) {
            return;
        }
        if (!event.getPlayer().hasPermission("voidcases.admin")) {
            event.setCancelled(true);
            msg(event.getPlayer(), "case-protected", null, null);
            return;
        }
        removePlaced(event.getBlock().getLocation());
        msg(event.getPlayer(), "set-removed", null, null);
    }

    @EventHandler
    public void onExplodePlaced(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> placedCaseAt(b.getLocation()) != null);
    }

    @EventHandler
    public void onExplodePlaced(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> placedCaseAt(b.getLocation()) != null);
    }

    // ------------------------------------------------------------------ предметы
    public ItemStack createCaseItem(CaseDef def, int amount) {
        ItemStack stack = new ItemStack(def.material(), Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(noItalic(component(TextUtil.color(def.displayName()))));
        List<Component> lore = new ArrayList<>();
        for (String line : def.itemLore()) {
            lore.add(noItalic(component(TextUtil.color(line))));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, def.id());
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack prizeItem(PrizeDef prize) {
        ItemStack stack = new ItemStack(prize.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(noItalic(component(TextUtil.color(prize.name()))));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public String identify(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------ открытие
    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        String id = identify(player.getInventory().getItemInMainHand());
        if (id == null) {
            return;
        }
        CaseDef def = byId(id);
        if (def == null) {
            return;
        }
        event.setCancelled(true);
        if (sessions.containsKey(player.getUniqueId())) {
            msg(player, "already-opening", def, null);
            return;
        }
        // расходуем один кейс и запускаем рулетку
        ItemStack hand = player.getInventory().getItemInMainHand();
        hand.setAmount(hand.getAmount() - 1);
        openCase(player, def);
    }

    private void openCase(Player player, CaseDef def) {
        Inventory inv = Bukkit.createInventory(new CaseHolder(def.id()), 27,
                component(TextUtil.color(def.displayName())));
        ItemStack filler = pane(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < 27; slot++) {
            if (slot < 9 || slot > 17) {
                inv.setItem(slot, filler);
            }
        }
        ItemStack pointer = pane(Material.YELLOW_STAINED_GLASS_PANE, "&#FFD93D▼");
        inv.setItem(4, pointer);
        inv.setItem(22, pointer);
        roll(inv, def);
        sessions.put(player.getUniqueId(), new Session(def, inv));
        player.openInventory(inv);
        playSound(player, cfg("animation.open-sound", "block.chest.open"), 1.0f);

        final int duration = Math.max(40, plugin.getConfig().getInt("animation.duration-ticks", 90));
        new BukkitRunnable() {
            int ticks = 0;
            int sinceUpdate = 0;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    sessions.remove(player.getUniqueId());
                    cancel();
                    return;
                }
                ticks++;
                sinceUpdate++;
                // рулетка постепенно замедляется: 2 -> 4 -> 7 -> 10 тиков между сменами
                int interval = ticks < duration * 0.45 ? 2
                        : ticks < duration * 0.72 ? 4
                        : ticks < duration * 0.9 ? 7 : 10;
                if (sinceUpdate >= interval && ticks < duration) {
                    sinceUpdate = 0;
                    roll(inv, def);
                    playSound(player, cfg("animation.spin-sound", "block.note_block.hat"),
                            0.8f + Math.min(0.7f, ticks / (float) duration * 0.7f));
                }
                if (ticks >= duration) {
                    cancel();
                    finish(player, def, inv);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void roll(Inventory inv, CaseDef def) {
        for (int slot = 9; slot <= 17; slot++) {
            inv.setItem(slot, prizeItem(randomPrize(def)));
        }
    }

    private void finish(Player player, CaseDef def, Inventory inv) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.finished = true;
        }
        PrizeDef prize = randomPrize(def);
        // финальный кадр: приз ровно по центру, где указатели
        for (int slot = 9; slot <= 17; slot++) {
            inv.setItem(slot, prizeItem(randomPrize(def)));
        }
        inv.setItem(13, prizeItem(prize));
        playSound(player, cfg("animation.win-sound", "entity.player.levelup"), 1.0f);

        // команды приза (консоль): {player} заменяется на ник
        for (String cmd : prize.commands()) {
            String prepared = cmd.replace("{player}", player.getName());
            if (prepared.startsWith("/")) {
                prepared = prepared.substring(1);
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), prepared);
        }

        msg(player, "win", def, prize);
        player.showTitle(Title.title(
                noItalic(component(TextUtil.color(prize.name()))),
                noItalic(component(TextUtil.color(def.displayName())))));
        if (prize.announce()) {
            String text = plugin.getConfig().getString("messages.win-broadcast", "");
            if (text != null && !text.isEmpty()) {
                Component broadcast = component(prefix() + TextUtil.color(text
                        .replace("{player}", player.getName())
                        .replace("{case}", def.displayName())
                        .replace("{prize}", prize.name())));
                Bukkit.broadcast(broadcast);
            }
        }
        if (plugin.getConfig().getBoolean("animation.firework", true)) {
            launchFirework(player);
        }

        // секунду показываем выигрыш в рулетке, потом закрываем
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            sessions.remove(player.getUniqueId());
            if (player.isOnline() && player.getOpenInventory().getTopInventory().equals(inv)) {
                player.closeInventory();
            }
        }, 20L);
    }

    private PrizeDef randomPrize(CaseDef def) {
        int total = 0;
        for (PrizeDef prize : def.prizes()) {
            total += prize.weight();
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (PrizeDef prize : def.prizes()) {
            roll -= prize.weight();
            if (roll < 0) {
                return prize;
            }
        }
        return def.prizes().get(def.prizes().size() - 1);
    }

    private void launchFirework(Player player) {
        try {
            Firework firework = player.getWorld().spawn(player.getLocation().add(0, 1, 0), Firework.class);
            FireworkMeta meta = firework.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .withColor(Color.ORANGE, Color.YELLOW)
                    .with(FireworkEffect.Type.BALL_LARGE)
                    .flicker(true)
                    .trail(true)
                    .build());
            meta.setPower(0);
            firework.setFireworkMeta(meta);
            firework.detonate();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ защита рулетки
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof CaseHolder
                || event.getInventory().getHolder() instanceof TitlesHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof CaseHolder
                || event.getInventory().getHolder() instanceof TitlesHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof CaseHolder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getPlayer();
        Session session = sessions.get(player.getUniqueId());
        // закрыл рулетку до конца анимации — открываем обратно, приз не потеряется
        if (session != null && !session.finished) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && sessions.containsKey(player.getUniqueId())) {
                    player.openInventory(session.inventory);
                }
            });
        }
    }

    // ------------------------------------------------------------------ утилиты
    private ItemStack pane(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(noItalic(component(TextUtil.color(name))));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private String cfg(String path, String def) {
        return plugin.getConfig().getString(path, def);
    }

    private String prefix() {
        return TextUtil.color(plugin.getConfig().getString("messages.prefix", "&#C86BFFVoidPE &8» &f"));
    }

    public void msg(Player player, String key, CaseDef def, PrizeDef prize) {
        String text = plugin.getConfig().getString("messages." + key, "");
        if (text == null || text.isEmpty()) {
            return;
        }
        if (def != null) {
            text = text.replace("{case}", def.displayName());
        }
        if (prize != null) {
            text = text.replace("{prize}", prize.name());
        }
        player.sendMessage(component(prefix() + TextUtil.color(text)));
    }

    private void playSound(Player player, String sound, float pitch) {
        if (sound == null || sound.isEmpty()) {
            return;
        }
        try {
            player.playSound(player.getLocation(), sound.toLowerCase(Locale.ROOT), 0.8f, pitch);
        } catch (Throwable ignored) {
        }
    }

    static Component component(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy == null ? "" : legacy);
    }

    static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }
}
