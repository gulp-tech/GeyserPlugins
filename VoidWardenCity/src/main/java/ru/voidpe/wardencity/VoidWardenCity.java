package ru.voidpe.wardencity;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * VoidWardenCity — «Город Вардена» как на FunTime, для Bedrock-сервера VoidPE.
 *
 * <p>Данж живёт в обычном древнем городе (Ancient City): админ один раз
 * указывает центр командой {@code /wcity setcenter}, дальше плагин сам:</p>
 * <ul>
 *   <li>наполняет сундуки города лутом из конфига и обновляет его по кулдауну;</li>
 *   <li>спавнит варденов рядом с игроками в городе и платит за их убийство
 *       командами из конфига (монеты, осколки, предметы — что напишешь);</li>
 *   <li>даёт игрокам телепорт в город {@code /wcity} за осколки и с кулдауном;</li>
 *   <li>по желанию защищает блоки города от разрушения.</li>
 * </ul>
 *
 * <p>Сам город плагин не строит: это ванильная структура Minecraft.
 * Найди древний город (/locate structure ancient_city), встань в его центре
 * и выполни /wcity setcenter и /wcity setspawn.</p>
 */
public class VoidWardenCity extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Random random = new Random();
    private NamespacedKey chestMarker;
    private boolean chestDataReady = true;
    private boolean customLootReady = true;
    private boolean chanceDataReady = true;
    private static final String CITY_TABLE = "chests/ancient_city";
    private static final String ICE_TABLE = "chests/ancient_city_ice_box";
    private WorldGuardCityRegion worldGuardRegion;
    private String regionStatus = "регион ещё не проверен";
    /** Время последнего наполнения сундука: "world;x;y;z" -> millis. */
    private final Map<String, Long> chestRefills = new HashMap<>();
    /** Сундуки, поставленные игроками в городе — им лут НЕ выдаётся (баг: стак сундуков = ферма лута). */
    private final java.util.Set<String> playerChests = new java.util.HashSet<>();
    /** Кулдауны телепорта игроков. */
    private final Map<UUID, Long> tpCooldowns = new HashMap<>();
    /** Идущие прогревы телепорта (/wcity): игрок -> задача. */
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> tpWarmups = new HashMap<>();
    /** Голограммы над сундуками: ключ сундука -> UUID стенда. */
    private final Map<String, UUID> holograms = new HashMap<>();

    private record ChanceEntry(LootEntry entry, ItemStack customItem) { }

    private record LootEntry(Material material, String command, int chance, int min, int max,
                             String name, int customModelData, String itemModel,
                             Map<String, Integer> enchantments, String potion, String sig) {
    }

    /** Предметы, добавленные админом через GUI (/wcity loot) — дополнение к ванильному луту. */
    private final List<ItemStack> customLoot = new ArrayList<>();
    /** Кто сейчас находится в городе (для приветствия при входе). */
    private final java.util.Set<UUID> insideCity = new java.util.HashSet<>();

    /** Держатель GUI настройки лута, чтобы отличать его от обычных инвентарей. */
    private static final class LootGuiHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Шансы, изменённые админом в меню /wcity chances (файл loot-chances.yml). */
    private final Map<String, Integer> chanceOverrides = new HashMap<>();
    /** Точки города (центр и спавн) живут в отдельном файле points.yml,
     *  чтобы не слетать при обновлении/удалении config.yml. */
    private File pointsFile;
    private YamlConfiguration points;
    /** Переопределённый шанс доп. лута из GUI; null — брать из конфига. */
    private Integer customChanceOverride;

    /** Список наград со шансами (меню /wcity chances), страница page. */
    private static final class ChancesGuiHolder implements InventoryHolder {
        private Inventory inventory;
        private final int page;

        private ChancesGuiHolder(int page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Редактор шанса одной награды (или доп. лута, если custom). */
    private static final class ChanceEditHolder implements InventoryHolder {
        private Inventory inventory;
        private final String sig;
        private final int page;
        private final boolean custom;

        private ChanceEditHolder(String sig, int page, boolean custom) {
            this.sig = sig;
            this.page = page;
            this.custom = custom;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        chestMarker = new NamespacedKey(this, "event_chest_table");
        loadPoints();
        loadChestData();
        loadCustomLoot();
        loadChanceOverrides();
        getServer().getPluginManager().registerEvents(this, this);
        syncWorldGuardRegion();
        if (getCommand("wcity") != null) {
            getCommand("wcity").setExecutor(this);
            getCommand("wcity").setTabCompleter(this);
        }
        // Identify unopened natural city chests before players can open them.
        scanChests(false);
        long chestPeriod = Math.max(20L, getConfig().getLong("chests.scan-seconds", 60L) * 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> scanChests(false), chestPeriod, chestPeriod);
        long wardenPeriod = Math.max(20L, getConfig().getLong("warden.check-seconds", 30L) * 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::spawnWardens, wardenPeriod, wardenPeriod);
        long holoPeriod = Math.max(20L, getConfig().getLong("hologram.update-seconds", 10L) * 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::updateHolograms, holoPeriod, holoPeriod);
        if (cityCenter() == null) {
            getLogger().warning("Город Вардена не настроен: мир из city.world не найден. "
                    + "Встань в центре древнего города и выполни /wcity setcenter.");
        }
    }

    @Override
    public void onDisable() {
        saveChestData();
        for (UUID id : holograms.values()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
        holograms.clear();
    }

    // ------------------------------------------------------------- регион

    private boolean syncWorldGuardRegion() {
        if (!getConfig().getBoolean("protection.worldguard.auto-region", true)) {
            regionStatus = "Автосоздание региона выключено; старый регион не удаляется.";
            getLogger().warning(regionStatus + " HyperTNT изменяет блоки напрямую и требует отдельной проверки!");
            return false;
        }
        org.bukkit.plugin.Plugin wg = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (wg == null || !wg.isEnabled()) {
            regionStatus = "WorldGuard не включён: автоматический регион не создан.";
            getLogger().severe(regionStatus + " Не включайте HyperTNT до исправления.");
            return false;
        }
        try {
            if (worldGuardRegion == null) worldGuardRegion = new WorldGuardCityRegion(this);
            boolean ok = worldGuardRegion.sync(cityCenter());
            regionStatus = worldGuardRegion.status();
            return ok;
        } catch (RuntimeException | LinkageError e) {
            regionStatus = "Ошибка подключения WorldGuard: " + e;
            getLogger().severe(regionStatus + ". Не включайте HyperTNT до исправления.");
            return false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(org.bukkit.event.world.WorldLoadEvent event) {
        if (points != null && event.getWorld().getName().equals(points.getString("center.world", ""))) {
            // WorldGuard and world-loading plugins may still be processing the load event.
            Bukkit.getScheduler().runTask(this, this::syncWorldGuardRegion);
        }
    }

    // ------------------------------------------------------------- точки города

    /**
     * Точки (центр и спавн) хранятся в points.yml, а не в config.yml:
     * конфиг при обновлениях плагина удаляют/перезаписывают, и точки
     * «пропадали после перезагрузки». Файл данных от этого не страдает.
     * При первом запуске значения мигрируют из config.yml (старые точки
     * и дефолты), дальше источником правды является только points.yml.
     */
    private void loadPoints() {
        pointsFile = new File(getDataFolder(), "points.yml");
        points = YamlConfiguration.loadConfiguration(pointsFile);
        boolean migrated = false;
        if (!points.contains("center.world")) {
            points.set("center.world", getConfig().getString("city.world", "world"));
            points.set("center.x", getConfig().getDouble("city.x", 0));
            points.set("center.y", getConfig().getDouble("city.y", -51));
            points.set("center.z", getConfig().getDouble("city.z", 0));
            migrated = true;
        }
        if (!points.contains("spawn.world")) {
            points.set("spawn.world", getConfig().getString("teleport.world",
                    getConfig().getString("city.world", "world")));
            points.set("spawn.x", getConfig().getDouble("teleport.x", getConfig().getDouble("city.x", 0)));
            points.set("spawn.y", getConfig().getDouble("teleport.y", getConfig().getDouble("city.y", -51)));
            points.set("spawn.z", getConfig().getDouble("teleport.z", getConfig().getDouble("city.z", 0)));
            points.set("spawn.yaw", getConfig().getDouble("teleport.yaw", 0));
            points.set("spawn.pitch", getConfig().getDouble("teleport.pitch", 0));
            migrated = true;
        }
        if (migrated && !savePoints()) {
            getLogger().severe("Не удалось создать points.yml — точки города не будут сохраняться!");
        }
        Location center = savedCenter();
        Location spawn = savedSpawn();
        if (center != null) {
            getLogger().info("Город Вардена загружен (points.yml): центр "
                    + center.getWorld().getName() + " " + center.getBlockX() + " " + center.getBlockY()
                    + " " + center.getBlockZ() + "; точка появления "
                    + (spawn == null ? "не задана"
                            : spawn.getWorld().getName() + " " + spawn.getBlockX() + " " + spawn.getBlockY()
                                    + " " + spawn.getBlockZ()) + ".");
        }
    }

    /** Сохраняет points.yml; false — запись на диск не удалась (уже залогировано). */
    private boolean savePoints() {
        try {
            points.save(pointsFile);
            return true;
        } catch (IOException e) {
            getLogger().severe("Не удалось сохранить points.yml (" + pointsFile.getAbsolutePath()
                    + "): " + e.getMessage());
            return false;
        }
    }

    /** Перечитывает points.yml с диска и сверяет точку: сохранение должно быть честным. */
    private boolean verifyPoint(String section, String world, double x, double y, double z) {
        YamlConfiguration check = YamlConfiguration.loadConfiguration(pointsFile);
        return world.equals(check.getString(section + ".world", ""))
                && Math.abs(check.getDouble(section + ".x", Double.NaN) - x) < 0.01
                && Math.abs(check.getDouble(section + ".y", Double.NaN) - y) < 0.01
                && Math.abs(check.getDouble(section + ".z", Double.NaN) - z) < 0.01;
    }

    /** Центр города из points.yml. */
    private Location savedCenter() {
        if (points == null) {
            World world = Bukkit.getWorld(getConfig().getString("city.world", "world"));
            return world == null ? null : new Location(world,
                    getConfig().getDouble("city.x", 0),
                    getConfig().getDouble("city.y", -51),
                    getConfig().getDouble("city.z", 0));
        }
        World world = Bukkit.getWorld(points.getString("center.world", "world"));
        if (world == null) {
            return null;
        }
        return new Location(world,
                points.getDouble("center.x", 0),
                points.getDouble("center.y", -51),
                points.getDouble("center.z", 0));
    }

    /** Точка появления игроков из points.yml; null — мир точки не загружен. */
    private Location savedSpawn() {
        if (points == null) {
            return null;
        }
        World world = Bukkit.getWorld(points.getString("spawn.world", ""));
        if (world == null) {
            return null;
        }
        return new Location(world,
                points.getDouble("spawn.x", 0),
                points.getDouble("spawn.y", -51),
                points.getDouble("spawn.z", 0),
                (float) points.getDouble("spawn.yaw", 0),
                (float) points.getDouble("spawn.pitch", 0));
    }

    private Location cityCenter() {
        return savedCenter();
    }

    private boolean inCity(Location loc) {
        Location center = cityCenter();
        if (center == null || loc.getWorld() == null || !center.getWorld().equals(loc.getWorld())) {
            return false;
        }
        double radius = getConfig().getDouble("city.radius", 120);
        double dx = loc.getX() - center.getX();
        double dz = loc.getZ() - center.getZ();
        if (dx * dx + dz * dz > radius * radius) {
            return false;
        }
        return loc.getY() >= center.getY() - getConfig().getDouble("city.y-below", 45)
                && loc.getY() <= center.getY() + getConfig().getDouble("city.y-above", 45);
    }

    /**
     * Текущий интервал обновления сундуков, в минутах: зависит от онлайна.
     * 25+ игроков — 5 мин, 10+ — 10 мин, меньше — 20 мин (chests.online-tiers).
     */
    private long currentRefillMinutes() {
        int online = Bukkit.getOnlinePlayers().size();
        long best = 20;
        long bestMin = -1;
        for (Map<?, ?> tier : getConfig().getMapList("chests.online-tiers")) {
            int minOnline = tier.get("min-online") instanceof Number n ? n.intValue() : 0;
            long minutes = tier.get("minutes") instanceof Number n ? n.longValue() : 20;
            if (online >= minOnline && minOnline >= bestMin) {
                bestMin = minOnline;
                best = minutes;
            }
        }
        return Math.max(1L, best);
    }

    private long currentRefillMillis() {
        return currentRefillMinutes() * 60_000L;
    }

    // ------------------------------------------------------------- сундуки

    private boolean dataReady() { return chestDataReady && customLootReady && chanceDataReady; }

    /** A marked single chest is the only inventory this plugin may refill. */
    private boolean singleChest(Chest chest) {
        return chest.getBlockData() instanceof org.bukkit.block.data.type.Chest data
                && data.getType() == org.bukkit.block.data.type.Chest.Type.SINGLE;
    }

    private String markedTable(Chest chest) {
        String table = chest.getPersistentDataContainer().get(chestMarker, PersistentDataType.STRING);
        return CITY_TABLE.equals(table) || ICE_TABLE.equals(table) ? table : null;
    }

    private String naturalTable(Chest chest) {
        LootTable table = chest.getLootTable();
        if (table == null || !table.getKey().getNamespace().equals("minecraft")) return null;
        String path = table.getKey().getKey();
        return CITY_TABLE.equals(path) || ICE_TABLE.equals(path) ? path : null;
    }

    /** Mark before opening inventory. Never trust old coordinate-only records. */
    private boolean markChest(Chest chest, String table) {
        if (!dataReady() || !singleChest(chest) || !inCity(chest.getLocation())) return false;
        chest.getPersistentDataContainer().set(chestMarker, PersistentDataType.STRING, table);
        chest.setLootTable(null); // prevent an extra native fill on first inventory access
        if (!chest.update(true, false)) return false;
        playerChests.remove(key(chest.getLocation()));
        return true;
    }

    private boolean isManaged(Chest chest) {
        return dataReady() && ChestPolicy.canUse(singleChest(chest), inCity(chest.getLocation()),
                playerChests.contains(key(chest.getLocation())), markedTable(chest) != null);
    }

    private boolean discoverNatural(Chest chest) {
        String table = naturalTable(chest);
        if (!ChestPolicy.canDiscover(singleChest(chest), inCity(chest.getLocation()),
                playerChests.contains(key(chest.getLocation())), table != null)) return false;
        return markChest(chest, table);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChestOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !(event.getInventory().getHolder() instanceof Chest chest)) return;
        if (!isManaged(chest)) return; // Includes all double chests.
        String key = key(chest.getLocation());
        long now = System.currentTimeMillis();
        Long last = chestRefills.get(key);
        if (last == null || now - last >= currentRefillMillis()) {
            if (!refill(event.getInventory(), player, chest)) return;
            chestRefills.put(key, now);
            saveChestData();
        }
        ensureHologram(chest.getLocation());
    }

    /** Newly loaded unopened natural chests are marked before players can open them. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        discoverChunk(event.getChunk());
    }

    private void discoverChunk(org.bukkit.Chunk chunk) {
        Location center = cityCenter();
        if (center == null || center.getWorld() != chunk.getWorld()) return;
        double radius = getConfig().getDouble("city.radius", 120);
        if (Math.hypot(chunk.getX() * 16 + 8 - center.getX(), chunk.getZ() * 16 + 8 - center.getZ()) > radius + 24) return;
        for (org.bukkit.block.BlockState state : chunk.getTileEntities()) {
            if (state instanceof Chest chest) discoverNatural(chest);
        }
    }

    private void scanChests() { scanChests(false); }

    private int scanChests(boolean force) {
        Location center = cityCenter();
        if (center == null || center.getWorld() == null) return 0;
        long now = System.currentTimeMillis();
        boolean changed = false;
        int refilled = 0;
        for (org.bukkit.Chunk chunk : center.getWorld().getLoadedChunks()) {
            double radius = getConfig().getDouble("city.radius", 120);
            if (Math.hypot(chunk.getX() * 16 + 8 - center.getX(), chunk.getZ() * 16 + 8 - center.getZ()) > radius + 24) continue;
            for (org.bukkit.block.BlockState state : chunk.getTileEntities()) {
                if (!(state instanceof Chest chest) || !inCity(chest.getLocation())) continue;
                if (!isManaged(chest) && !discoverNatural(chest)) continue;
                // Refresh the state after setting persistent data.
                if (!(chest.getBlock().getState() instanceof Chest live) || !isManaged(live)) continue;
                String key = key(live.getLocation());
                Long last = chestRefills.get(key);
                if (force || last == null || now - last >= currentRefillMillis()) {
                    if (refill(live.getBlockInventory(), null, live)) {
                        chestRefills.put(key, now);
                        changed = true;
                        refilled++;
                    }
                }
                ensureHologram(live.getLocation());
            }
        }
        if (changed) saveChestData();
        return refilled;
    }

    private boolean refill(Inventory inv, Player opener, Chest chest) {
        String tableKey = markedTable(chest);
        LootTable table = tableKey == null ? null : Bukkit.getLootTable(NamespacedKey.minecraft(tableKey));
        if (table == null) {
            getLogger().warning("Таблица лута " + tableKey + " недоступна: сундук не очищен.");
            return false;
        }
        List<ItemStack> vanilla;
        try {
            LootContext context = new LootContext.Builder(chest.getLocation()).build();
            vanilla = new ArrayList<>(table.populateLoot(random, context));
        } catch (RuntimeException ex) {
            getLogger().warning("Не удалось создать ванильный лут: " + ex.getMessage() + "; сундук не очищен.");
            return false;
        }
        List<ItemStack> selected = filterVanilla(vanilla);
        addExtraLoot(selected, opener, inv.getSize());
        selected = limitDrinkablePotions(selected);
        inv.clear();
        for (ItemStack item : selected) {
            int slot = freeSlot(inv);
            if (slot < 0) break;
            inv.setItem(slot, item);
        }
        return true;
    }

    /** Cap potion quantity without changing their potion data, names or models. */
    private List<ItemStack> limitDrinkablePotions(List<ItemStack> selected) {
        int remaining = Math.max(0, getConfig().getInt("chests.max-drinkable-potions", 1));
        List<ItemStack> limited = new ArrayList<>(selected.size());
        for (ItemStack item : selected) {
            if (item == null || item.getType() != Material.POTION) {
                limited.add(item);
                continue;
            }
            int count = Math.min(remaining, Math.max(0, item.getAmount()));
            if (count > 0) {
                ItemStack kept = item.clone();
                kept.setAmount(count);
                limited.add(kept);
                remaining -= count;
            }
        }
        return limited;
    }

    private List<ItemStack> filterVanilla(java.util.Collection<ItemStack> source) {
        List<ItemStack> selected = new ArrayList<>();
        for (ItemStack stack : source) {
            if (stack == null || stack.getType().isAir()) continue;
            if (random.nextInt(100) < overrideChance(vanillaSig(stack), 100)) selected.add(stack);
        }
        return selected;
    }

    private void addExtraLoot(List<ItemStack> selected, Player opener, int capacity) {
        // Reserve remaining space for the original config and the saved GUI items.
        List<LootEntry> loot = parseLoot();
        int rollsMin = Math.max(0, getConfig().getInt("chests.rolls-min", 2));
        int rollsMax = Math.max(rollsMin, getConfig().getInt("chests.rolls-max", 4));
        int rolls = rollsMin + (rollsMax > rollsMin ? random.nextInt(rollsMax - rollsMin + 1) : 0);
        int placed = 0;
        for (int pass = 0; pass < 3 && placed < rolls && selected.size() < capacity; pass++) {
            List<LootEntry> shuffled = new ArrayList<>(loot);
            java.util.Collections.shuffle(shuffled, random);
            for (LootEntry e : shuffled) {
                if (placed >= rolls || selected.size() >= capacity) break;
                if (random.nextInt(100) >= e.chance()) continue;
                if (e.command() != null) {
                    if (opener != null) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), e.command().replace("%player%", opener.getName()));
                        placed++;
                    }
                    continue;
                }
                int amount = e.min() + (e.max() > e.min() ? random.nextInt(e.max() - e.min() + 1) : 0);
                if (amount <= 0) continue;
                selected.add(buildItem(e, amount));
                placed++;
            }
        }
        for (ItemStack saved : customLoot) {
            if (selected.size() >= capacity) break;
            if (random.nextInt(100) >= effectiveItemChance(saved)) continue;
            ItemStack clone = saved.clone();
            clone.setAmount(1 + random.nextInt(Math.max(1, saved.getAmount())));
            selected.add(clone);
        }
    }

    /** Handle a natural table generated before the chunk scanner reached it. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNaturalLoot(org.bukkit.event.world.LootGenerateEvent event) {
        if (!dataReady() || event.isPlugin() || !(event.getInventoryHolder() instanceof Chest chest)) return;
        NamespacedKey lootKey = event.getLootTable().getKey();
        String table = lootKey.getNamespace().equals("minecraft") ? lootKey.getKey() : "";
        if ((!CITY_TABLE.equals(table) && !ICE_TABLE.equals(table))
                || !ChestPolicy.canDiscover(singleChest(chest), inCity(chest.getLocation()),
                        playerChests.contains(key(chest.getLocation())), true)) return;
        List<ItemStack> selected = filterVanilla(event.getLoot());
        Player opener = event.getEntity() instanceof Player p ? p : null;
        addExtraLoot(selected, opener, 27);
        event.setLoot(limitDrinkablePotions(selected));
        Location loc = chest.getLocation().clone();
        Bukkit.getScheduler().runTask(this, () -> {
            if (event.isCancelled() || !(loc.getBlock().getState() instanceof Chest current)
                    || playerChests.contains(key(loc)) || !singleChest(current)) return;
            if (markChest(current, table)) {
                chestRefills.put(key(loc), System.currentTimeMillis());
                saveChestData();
                ensureHologram(loc);
            }
        });
    }

    private ItemStack buildItem(LootEntry e, int amount) {
        ItemStack item = new ItemStack(e.material(), amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        if (e.name() != null && !e.name().isBlank()) {
            meta.displayName(comp(e.name()).decoration(TextDecoration.ITALIC, false));
        }
        if (e.customModelData() > 0) {
            meta.setCustomModelData(e.customModelData());
        }
        if (e.itemModel() != null && !e.itemModel().isBlank()) {
            NamespacedKey key = NamespacedKey.fromString(e.itemModel());
            if (key != null) {
                meta.setItemModel(key);
            }
        }
        if (e.potion() != null && meta instanceof PotionMeta potionMeta) {
            try {
                potionMeta.setBasePotionType(PotionType.valueOf(e.potion().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // неверное имя зелья в конфиге — оставим обычную бутылку
            }
        }
        if (e.enchantments() != null) {
            for (Map.Entry<String, Integer> en : e.enchantments().entrySet()) {
                if (en.getKey().equalsIgnoreCase("random")) {
                    applyRandomEnchant(meta);
                    continue;
                }
                Enchantment enchantment = org.bukkit.Registry.ENCHANTMENT.get(
                        NamespacedKey.minecraft(en.getKey().toLowerCase(Locale.ROOT)));
                if (enchantment == null) {
                    continue;
                }
                if (meta instanceof EnchantmentStorageMeta storage) {
                    storage.addStoredEnchant(enchantment, en.getValue(), true);
                } else {
                    meta.addEnchant(enchantment, en.getValue(), true);
                }
            }
        }
        item.setItemMeta(meta);
        return item;
    }

    /** Случайное зачарование для ванильной «книги со случайным зачарованием». */
    private void applyRandomEnchant(ItemMeta meta) {
        List<Enchantment> all = new ArrayList<>();
        for (Enchantment ench : org.bukkit.Registry.ENCHANTMENT) {
            if (ench != null) {
                all.add(ench);
            }
        }
        if (all.isEmpty()) {
            return;
        }
        Enchantment picked = all.get(random.nextInt(all.size()));
        int level = 1 + random.nextInt(Math.max(1, Math.min(3, picked.getMaxLevel())));
        if (meta instanceof EnchantmentStorageMeta storage) {
            storage.addStoredEnchant(picked, level, true);
        } else {
            meta.addEnchant(picked, level, true);
        }
    }

    private int freeSlot(Inventory inv) {
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                free.add(i);
            }
        }
        return free.isEmpty() ? -1 : free.get(random.nextInt(free.size()));
    }

    private List<LootEntry> parseLoot() {
        List<LootEntry> out = new ArrayList<>();
        for (Map<?, ?> raw : getConfig().getMapList("chests.loot")) {
            int chance = raw.get("chance") instanceof Number n ? n.intValue() : 50;
            int min = raw.get("min") instanceof Number n ? n.intValue() : 1;
            int max = raw.get("max") instanceof Number n ? n.intValue() : min;
            String name = raw.get("name") == null ? null : String.valueOf(raw.get("name"));
            int cmd = raw.get("custom-model-data") instanceof Number n ? n.intValue() : 0;
            String model = raw.get("item-model") == null ? null : String.valueOf(raw.get("item-model"));
            Map<String, Integer> enchants = new HashMap<>();
            if (raw.get("enchantments") instanceof Map<?, ?> enMap) {
                for (Map.Entry<?, ?> en : enMap.entrySet()) {
                    if (en.getValue() instanceof Number n) {
                        enchants.put(String.valueOf(en.getKey()), n.intValue());
                    }
                }
            }
            String potion = raw.get("potion") == null ? null : String.valueOf(raw.get("potion"));
            if (raw.get("command") != null) {
                String command = String.valueOf(raw.get("command"));
                String sig = "cmd:" + command;
                out.add(new LootEntry(null, command, overrideChance(sig, chance), min, max,
                        name, cmd, model, enchants, potion, sig));
            } else if (raw.get("material") != null) {
                Material mat = Material.matchMaterial(String.valueOf(raw.get("material")));
                if (mat != null) {
                    // Подпись награды для меню шансов: материал + зачарования,
                    // чтобы две зачарованные книги (swift_sneak и random) не путались.
                    String sig = mat.name();
                    if (!enchants.isEmpty()) {
                        List<String> keys = new ArrayList<>(enchants.keySet());
                        java.util.Collections.sort(keys);
                        sig += ":" + String.join("+", keys);
                    }
                    out.add(new LootEntry(mat, null, overrideChance(sig, chance), min, max,
                            name, cmd, model, enchants, potion, sig));
                }
            }
        }
        return out;
    }

    private String key(Location loc) {
        return loc.getWorld().getName() + ";" + loc.getBlockX() + ";" + loc.getBlockY() + ";" + loc.getBlockZ();
    }

    private YamlConfiguration loadDataSafely(File file) {
        YamlConfiguration data = new YamlConfiguration();
        try {
            data.load(file);
            return data;
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException ex) {
            getLogger().severe("Не удалось прочитать " + file.getName()
                    + "; файл сохранён без изменений: " + ex.getMessage());
            return null;
        }
    }

    private void atomicSave(YamlConfiguration data, File target) throws IOException {
        java.nio.file.Path dest = target.toPath().toAbsolutePath();
        java.nio.file.Files.createDirectories(dest.getParent());
        java.nio.file.Path temp = java.nio.file.Files.createTempFile(dest.getParent(), ".warden-", ".tmp");
        try {
            data.save(temp.toFile());
            try {
                java.nio.file.Files.move(temp, dest, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                java.nio.file.Files.move(temp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            java.nio.file.Files.deleteIfExists(temp);
        }
    }

    private File chestFile() {
        return new File(getDataFolder(), "chests.yml");
    }

    private void loadChestData() {
        chestDataReady = true;
        File file = chestFile();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration data = loadDataSafely(file);
        if (data == null) { chestDataReady = false; return; }
        if (data.isConfigurationSection("chests")) {
            for (String k : data.getConfigurationSection("chests").getKeys(false)) {
                chestRefills.put(k, data.getLong("chests." + k));
            }
        }
        if (data.isConfigurationSection("player-placed")) {
            playerChests.addAll(data.getConfigurationSection("player-placed").getKeys(false));
        }
        playerChests.addAll(data.getStringList("player-placed-list"));
    }

    private void saveChestData() {
        if (!chestDataReady) return;
        YamlConfiguration data = new YamlConfiguration();
        for (Map.Entry<String, Long> e : chestRefills.entrySet()) {
            data.set("chests." + e.getKey(), e.getValue());
        }
        data.set("player-placed-list", new ArrayList<>(playerChests));
        try {
            atomicSave(data, chestFile());
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить chests.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------- голограммы

    /** Ставит (или находит) голограмму над сундуком и обновляет её текст. */
    private void ensureHologram(Location chestLoc) {
        if (!getConfig().getBoolean("hologram.enabled", true) || chestLoc.getWorld() == null) {
            return;
        }
        String key = key(chestLoc);
        UUID id = holograms.get(key);
        Entity existing = id == null ? null : Bukkit.getEntity(id);
        if (existing != null && !existing.isDead()) {
            existing.customName(comp(hologramText(chestLoc)));
            return;
        }
        double height = getConfig().getDouble("hologram.height", 1.3);
        Location loc = chestLoc.clone().add(0.5, height, 0.5);
        ArmorStand stand = chestLoc.getWorld().spawn(loc, ArmorStand.class, st -> {
            st.setVisible(false);
            st.setGravity(false);
            st.setMarker(true);
            st.setInvulnerable(true);
            st.setPersistent(false);
            st.setCustomNameVisible(true);
            st.customName(comp(hologramText(chestLoc)));
        });
        holograms.put(key, stand.getUniqueId());
    }

    private void removeHologram(String key) {
        UUID id = holograms.remove(key);
        if (id != null) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.remove();
            }
        }
    }

    /** Раз в несколько секунд: текст-таймер у голограмм и чистка сломанных сундуков. */
    private void updateHolograms() {
        if (!getConfig().getBoolean("hologram.enabled", true)) {
            return;
        }
        for (String key : new ArrayList<>(chestRefills.keySet())) {
            Location loc = parseKey(key);
            if (loc == null || loc.getWorld() == null) {
                continue;
            }
            if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                removeHologram(key); // чанк выгрузился — стенд всё равно не сохранится
                continue;
            }
            if (loc.getBlock().getType() != Material.CHEST) {
                // сундук сломали — убираем голограмму и запись
                removeHologram(key);
                chestRefills.remove(key);
                saveChestData();
                continue;
            }
            if (!(loc.getBlock().getState() instanceof Chest chest) || !isManaged(chest)) {
                removeHologram(key);
                continue;
            }
            ensureHologram(loc);
        }
    }

    private String hologramText(Location chestLoc) {
        String raw = getConfig().getString("hologram.text", "&#FFD93DСундук города &8• &f{timer}");
        Long last = chestRefills.get(key(chestLoc));
        long cooldown = currentRefillMillis();
        String timer;
        if (last == null) {
            timer = getConfig().getString("hologram.ready", "&aможно лутать");
        } else {
            long left = cooldown - (System.currentTimeMillis() - last);
            if (left <= 0) {
                timer = getConfig().getString("hologram.ready", "&aможно лутать");
            } else {
                long sec = left / 1000;
                timer = String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
            }
        }
        return raw.replace("{timer}", timer == null ? "" : timer);
    }

    private Location parseKey(String key) {
        String[] parts = key.split(";");
        if (parts.length != 4) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        try {
            return new Location(world, Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------- GUI настройки лута

    private File customLootFile() {
        return new File(getDataFolder(), "loot-items.yml");
    }

    private void loadCustomLoot() {
        customLootReady = true;
        customLoot.clear();
        File file = customLootFile();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration data = loadDataSafely(file);
        if (data == null) { customLootReady = false; return; }
        List<?> raw = data.getList("items");
        if (raw != null) {
            for (Object o : raw) {
                if (o instanceof ItemStack stack && !stack.getType().isAir()) {
                    customLoot.add(stack.clone());
                }
            }
        }
        getLogger().info("Дополнительного лута из GUI загружено предметов: " + customLoot.size());
    }

    private boolean saveCustomLoot() {
        if (!customLootReady) return false;
        YamlConfiguration data = new YamlConfiguration();
        data.set("items", customLoot);
        try {
            atomicSave(data, customLootFile());
            return true;
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить loot-items.yml: " + e.getMessage());
            return false;
        }
    }

    private void openLootGui(Player player) {
        if (!customLootReady) { send(player, "&cНе удалось прочитать loot-items.yml. Исправь файл и перезапусти сервер."); return; }
        LootGuiHolder holder = new LootGuiHolder();
        Inventory gui = Bukkit.createInventory(holder, 54, comp(msg("gui-title")));
        holder.inventory = gui;
        int slot = 0;
        for (ItemStack saved : customLoot) {
            if (slot >= gui.getSize()) {
                break;
            }
            gui.setItem(slot++, saved.clone());
        }
        player.openInventory(gui);
        send(player, msg("gui-open"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLootGuiClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof LootGuiHolder)) {
            return;
        }
        if (!customLootReady) return;
        customLoot.clear();
        for (ItemStack item : event.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
                customLoot.add(item.clone());
            }
        }
        boolean saved = saveCustomLoot();
        if (event.getPlayer() instanceof Player player) {
            if (saved) send(player, msg("gui-saved").replace("{count}", String.valueOf(customLoot.size())));
            else send(player, "&cНе удалось сохранить loot-items.yml! Исправь ошибку диска и снова открой/закрой меню до перезапуска.");
        }
    }

    // ------------------------------------------------- меню шансов (/wcity chances)

    private File chanceFile() {
        return new File(getDataFolder(), "loot-chances.yml");
    }

    private void loadChanceOverrides() {
        chanceDataReady = true;
        chanceOverrides.clear();
        customChanceOverride = null;
        File file = chanceFile();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration data = loadDataSafely(file);
        if (data == null) { chanceDataReady = false; return; }
        for (Map<?, ?> raw : data.getMapList("chances")) {
            Object sig = raw.get("sig");
            if (sig != null && raw.get("chance") instanceof Number n) {
                chanceOverrides.put(String.valueOf(sig), clampChance(n.intValue()));
            }
        }
        if (data.contains("custom-chance-percent")) {
            customChanceOverride = clampChance(data.getInt("custom-chance-percent"));
        }
    }

    private boolean saveChanceOverrides() {
        if (!chanceDataReady) return false;
        YamlConfiguration data = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Integer> e : chanceOverrides.entrySet()) {
            Map<String, Object> row = new HashMap<>();
            row.put("sig", e.getKey());
            row.put("chance", e.getValue());
            list.add(row);
        }
        data.set("chances", list);
        if (customChanceOverride != null) {
            data.set("custom-chance-percent", customChanceOverride);
        }
        try {
            atomicSave(data, chanceFile());
            return true;
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить loot-chances.yml: " + e.getMessage());
            return false;
        }
    }

    private static int clampChance(int chance) {
        return Math.max(0, Math.min(100, chance));
    }

    private int overrideChance(String sig, int configChance) {
        Integer override = chanceOverrides.get(sig);
        return override == null ? configChance : override;
    }

    private int effectiveCustomChance() {
        return customChanceOverride != null
                ? customChanceOverride
                : getConfig().getInt("custom-loot.chance-percent", 30);
    }

    private static String itemSig(ItemStack item) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(item.serializeAsBytes());
            return "customitem:" + java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private int effectiveItemChance(ItemStack item) {
        return overrideChance(itemSig(item), clampChance(effectiveCustomChance()));
    }

    private String vanillaSig(ItemStack stack) {
        if (stack.getType() == Material.ENCHANTED_BOOK
                && stack.getItemMeta() instanceof EnchantmentStorageMeta meta
                && meta.hasStoredEnchant(Enchantment.SWIFT_SNEAK)) return "vanilla:SWIFT_SNEAK";
        return "vanilla:" + stack.getType().name();
    }

    private List<ChanceEntry> chanceEntries() {
        List<ChanceEntry> entries = new ArrayList<>();
        // Material list for the two vanilla Ancient City loot tables. The actual
        // loot is generated by the server, not by this display list.
        String[] names = {"ENCHANTED_GOLDEN_APPLE", "MUSIC_DISC_OTHERSIDE", "COMPASS",
                "SCULK_CATALYST", "DIAMOND_HOE", "LEAD", "DIAMOND_HORSE_ARMOR",
                "LEATHER", "MUSIC_DISC_13", "MUSIC_DISC_CAT", "DIAMOND_LEGGINGS",
                "SCULK", "SCULK_SENSOR", "CANDLE", "AMETHYST_SHARD", "EXPERIENCE_BOTTLE",
                "GLOW_BERRIES", "IRON_LEGGINGS", "CHISELED_SOULSHALE", "ECHO_SHARD",
                "DISC_FRAGMENT_5", "POTION", "ENCHANTED_BOOK", "BOOK", "BONE", "SOUL_TORCH",
                "COAL", "WARD_ARMOR_TRIM_SMITHING_TEMPLATE", "SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE",
                "SUSPICIOUS_STEW", "GOLDEN_CARROT", "BAKED_POTATO", "PACKED_ICE", "SNOWBALL"};
        for (String name : names) {
            Material mat = Material.matchMaterial(name);
            if (mat == null) continue; // newer vanilla items may not exist in older servers
            String sig = "vanilla:" + name;
            LootEntry row = new LootEntry(mat, null, overrideChance(sig, 100), 1, 1,
                    null, 0, null, Map.of(), null, sig);
            entries.add(new ChanceEntry(row, null));
        }
        LootEntry swift = new LootEntry(Material.ENCHANTED_BOOK, null, overrideChance("vanilla:SWIFT_SNEAK", 100),
                1, 1, "&eКнига Swift Sneak", 0, null, Map.of(), null, "vanilla:SWIFT_SNEAK");
        entries.add(new ChanceEntry(swift, null));
        for (LootEntry row : parseLoot()) entries.add(new ChanceEntry(row, null));
        for (ItemStack item : customLoot) {
            String sig = itemSig(item);
            LootEntry row = new LootEntry(item.getType(), null, effectiveItemChance(item), 1,
                    item.getAmount(), null, 0, null, Map.of(), null, sig);
            entries.add(new ChanceEntry(row, item));
        }
        return entries;
    }

    private ItemStack chanceEntryIcon(ChanceEntry entry) {
        if (entry.customItem() == null) return chanceIcon(entry.entry());
        ItemStack icon = entry.customItem().clone();
        icon.setAmount(1);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(comp("&7Дополнительный предмет"));
            lore.add(comp("&7Шанс: &f" + entry.entry().chance() + "%"));
            lore.add(comp("&eНажми, чтобы изменить"));
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private void openChancesGui(Player player, int page) {
        if (!chanceDataReady || !customLootReady) { send(player, "&cНе удалось прочитать данные лута или шансов. Проверь файлы и перезапусти сервер."); return; }
        List<ChanceEntry> entries = chanceEntries();
        int perPage = 45;
        int pages = Math.max(1, (entries.size() + perPage - 1) / perPage);
        int current = Math.max(0, Math.min(page, pages - 1));
        ChancesGuiHolder holder = new ChancesGuiHolder(current);
        Inventory gui = Bukkit.createInventory(holder, 54, comp(msg("chances-title")));
        holder.inventory = gui;
        int from = current * perPage;
        for (int i = 0; i < perPage && from + i < entries.size(); i++) {
            gui.setItem(i, chanceEntryIcon(entries.get(from + i)));
        }
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 45; slot < 54; slot++) {
            if (slot != 46) {
                gui.setItem(slot, filler);
            }
        }
        if (current > 0) {
            gui.setItem(45, button(Material.ARROW, "&e← Назад (стр. " + current + ")"));
        }
        gui.setItem(46, customChanceIcon());
        if (current < pages - 1) {
            gui.setItem(53, button(Material.ARROW, "&eВперёд (стр. " + (current + 2) + ") →"));
        }
        player.openInventory(gui);
    }

    private ItemStack chanceIcon(LootEntry e) {
        ItemStack icon;
        List<String> lore = new ArrayList<>();
        if (e.command() != null) {
            icon = new ItemStack(Material.COMMAND_BLOCK);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.displayName(comp("&eКомандная награда").decoration(TextDecoration.ITALIC, false));
                String command = e.command();
                lore.add("&7" + (command.length() > 42 ? command.substring(0, 42) + "…" : command));
            }
            icon.setItemMeta(meta);
        } else {
            icon = buildItem(e, 1);
        }
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<Component> lines = new ArrayList<>();
            if (meta.hasLore()) {
                lines.addAll(meta.lore());
            }
            for (String line : lore) {
                lines.add(comp(line));
            }
            if (e.sig().startsWith("vanilla:")) {
                lines.add(comp("&7Если выпало по ванильной таблице:"));
                lines.add(comp("&7шанс оставить: &f" + e.chance() + "%"));
                lines.add(comp("&8Это не общий шанс в сундуке"));
            } else {
                lines.add(comp("&7Шанс на одну попытку: &f" + e.chance() + "%"));
            }
            if (e.command() == null) {
                lines.add(comp(e.min() == e.max()
                        ? "&7Количество: &f" + e.min()
                        : "&7Количество: &f" + e.min() + "–" + e.max()));
            }
            lines.add(comp("&eНажми, чтобы изменить шанс"));
            meta.lore(lines);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private ItemStack customChanceIcon() {
        ItemStack icon = new ItemStack(Material.CHEST);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(comp("&#FFD93DДоп. лут из /wcity loot")
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    comp("&7Шанс каждого предмета: &f" + effectiveCustomChance() + "%"),
                    comp("&eНажми, чтобы изменить шанс")));
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private ItemStack pane(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(comp(name).decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack button(Material material, String name) {
        return pane(material, name);
    }

    private ItemStack editorIcon(String sig, boolean custom, int chance) {
        if (custom) {
            ItemStack icon = customChanceIcon();
            return icon;
        }
        for (ChanceEntry entry : chanceEntries()) {
            if (entry.entry().sig().equals(sig)) return chanceEntryIcon(entry);
        }
        ItemStack icon = new ItemStack(Material.PAPER);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(comp("&fШанс: &e" + chance + "%").decoration(TextDecoration.ITALIC, false));
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private void openChanceEditor(Player player, String sig, int page, boolean custom) {
        if (!chanceDataReady || !customLootReady) { send(player, "&cДанные лута недоступны."); return; }
        int chance = custom ? effectiveCustomChance() : currentEntryChance(sig);
        ChanceEditHolder holder = new ChanceEditHolder(sig, page, custom);
        Inventory gui = Bukkit.createInventory(holder, 27, comp(msg("chance-edit-title")));
        holder.inventory = gui;
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < gui.getSize(); slot++) {
            gui.setItem(slot, filler);
        }
        gui.setItem(13, editorIcon(sig, custom, chance));
        gui.setItem(10, pane(Material.RED_STAINED_GLASS_PANE, "&c−10%"));
        gui.setItem(11, pane(Material.RED_STAINED_GLASS_PANE, "&c−5%"));
        gui.setItem(12, pane(Material.RED_STAINED_GLASS_PANE, "&c−1%"));
        gui.setItem(14, pane(Material.GREEN_STAINED_GLASS_PANE, "&a+1%"));
        gui.setItem(15, pane(Material.GREEN_STAINED_GLASS_PANE, "&a+5%"));
        gui.setItem(16, pane(Material.GREEN_STAINED_GLASS_PANE, "&a+10%"));
        gui.setItem(22, button(Material.ARROW, "&e← К списку наград"));
        player.openInventory(gui);
    }

    private int currentEntryChance(String sig) {
        for (ChanceEntry entry : chanceEntries()) {
            if (entry.entry().sig().equals(sig)) return entry.entry().chance();
        }
        return 0;
    }

    private boolean setChance(String sig, boolean custom, int chance) {
        int value = clampChance(chance);
        Integer previous = custom ? customChanceOverride : chanceOverrides.get(sig);
        if (custom) customChanceOverride = value;
        else chanceOverrides.put(sig, value);
        if (saveChanceOverrides()) return true;
        if (custom) customChanceOverride = previous;
        else if (previous == null) chanceOverrides.remove(sig);
        else chanceOverrides.put(sig, previous);
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onChancesClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getInventory().getHolder() instanceof ChancesGuiHolder holder) {
            event.setCancelled(true);
            if (!player.hasPermission("voidwardencity.admin")) return;
            if (event.getClickedInventory() != event.getInventory()) {
                return;
            }
            int slot = event.getSlot();
            player.playSound(player.getLocation(), "minecraft:ui.button.click", 0.6f, 1.0f);
            if (slot == 45 && holder.page > 0) {
                openChancesGui(player, holder.page - 1);
            } else if (slot == 46) {
                openChanceEditor(player, null, holder.page, true);
            } else if (slot == 53) {
                openChancesGui(player, holder.page + 1);
            } else if (slot < 45) {
                List<ChanceEntry> entries = chanceEntries();
                int index = holder.page * 45 + slot;
                if (index >= 0 && index < entries.size()) {
                    openChanceEditor(player, entries.get(index).entry().sig(), holder.page, false);
                }
            }
            return;
        }
        if (event.getInventory().getHolder() instanceof ChanceEditHolder holder) {
            event.setCancelled(true);
            if (!player.hasPermission("voidwardencity.admin")) return;
            if (event.getClickedInventory() != event.getInventory()) {
                return;
            }
            int slot = event.getSlot();
            player.playSound(player.getLocation(), "minecraft:ui.button.click", 0.6f, 1.0f);
            if (slot == 22) {
                openChancesGui(player, holder.page);
                return;
            }
            int delta = switch (slot) {
                case 10 -> -10;
                case 11 -> -5;
                case 12 -> -1;
                case 14 -> 1;
                case 15 -> 5;
                case 16 -> 10;
                default -> 0;
            };
            if (delta != 0) {
                int current = holder.custom
                        ? effectiveCustomChance() : currentEntryChance(holder.sig);
                int value = clampChance(current + delta);
                boolean saved = setChance(holder.sig, holder.custom, value);
                openChanceEditor(player, holder.sig, holder.page, holder.custom);
                if (saved) player.sendActionBar(comp(msg("chance-set").replace("{chance}", String.valueOf(value))));
                else send(player, "&cШанс не сохранён. Проверь файл loot-chances.yml и права на запись.");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onChancesDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof ChancesGuiHolder || holder instanceof ChanceEditHolder) event.setCancelled(true);
    }

    // ------------------------------------------------------------- вардены

    private void spawnWardens() {
        if (!getConfig().getBoolean("warden.spawn-enabled", true)) {
            return;
        }
        Location center = cityCenter();
        if (center == null || center.getWorld() == null) {
            return;
        }
        World world = center.getWorld();
        int max = Math.max(0, getConfig().getInt("warden.max-in-city", 2));
        long alive = world.getEntitiesByClass(Warden.class).stream()
                .filter(w -> inCity(w.getLocation())).count();
        if (alive >= max) {
            return;
        }
        int chance = getConfig().getInt("warden.spawn-chance-percent", 35);
        for (Player player : world.getPlayers()) {
            if (alive >= max) {
                break;
            }
            if (!inCity(player.getLocation())
                    || player.getGameMode() == org.bukkit.GameMode.CREATIVE
                    || player.getGameMode() == org.bukkit.GameMode.SPECTATOR
                    || random.nextInt(100) >= chance) {
                continue;
            }
            Location spawn = findSpawnNear(player.getLocation());
            if (spawn != null) {
                world.spawnEntity(spawn, EntityType.WARDEN);
                alive++;
                send(player, msg("warden-near"));
            }
        }
    }

    private Location findSpawnNear(Location around) {
        double minD = getConfig().getDouble("warden.min-distance", 12);
        double maxD = Math.max(minD + 1, getConfig().getDouble("warden.max-distance", 24));
        World world = around.getWorld();
        for (int tries = 0; tries < 12; tries++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = minD + random.nextDouble() * (maxD - minD);
            int x = (int) Math.floor(around.getX() + Math.cos(angle) * dist);
            int z = (int) Math.floor(around.getZ() + Math.sin(angle) * dist);
            for (int y = around.getBlockY() + 12; y > around.getBlockY() - 20; y--) {
                Block block = world.getBlockAt(x, y, z);
                if (block.getType().isSolid()
                        && world.getBlockAt(x, y + 1, z).getType() == Material.AIR
                        && world.getBlockAt(x, y + 2, z).getType() == Material.AIR) {
                    Location loc = new Location(world, x + 0.5, y + 1, z + 0.5);
                    if (inCity(loc)) {
                        return loc;
                    }
                    break;
                }
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWardenDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Warden) || !inCity(event.getEntity().getLocation())) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        for (String cmd : getConfig().getStringList("warden.kill-reward-commands")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", killer.getName()));
        }
        send(killer, msg("warden-killed"));
    }

    /** Приветствие при входе на территорию города (один раз, пока не выйдешь). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMoveCity(PlayerMoveEvent event) {
        // Движение с места отменяет прогрев телепорта (поворот головы — нет).
        if (tpWarmups.containsKey(event.getPlayer().getUniqueId()) && event.getTo() != null
                && (event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockY() != event.getTo().getBlockY()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ())) {
            cancelWarmup(event.getPlayer(), true);
        }
        if (!getConfig().getBoolean("announcements.enter-enabled", true)) {
            return;
        }
        if (event.getTo() == null) {
            return;
        }
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()) {
            return;
        }
        Player player = event.getPlayer();
        boolean inside = inCity(event.getTo());
        if (inside && insideCity.add(player.getUniqueId())) {
            send(player, msg("enter-city"));
        } else if (!inside) {
            insideCity.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuitCity(PlayerQuitEvent event) {
        cancelWarmup(event.getPlayer(), false);
        insideCity.remove(event.getPlayer().getUniqueId());
        tpCooldowns.remove(event.getPlayer().getUniqueId());
    }

    /** Урон отменяет прогрев телепорта: в бою сбежать в город нельзя. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageWarmup(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            cancelWarmup(player, true);
        }
    }

    // ------------------------------------------------------------- защита

    /** Missing lock-building means an old config: fail closed and protect the city. */
    private boolean buildingLocked(String legacyToggle) {
        return getConfig().getBoolean("protection.lock-building", true)
                || getConfig().getBoolean("protection." + legacyToggle, true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (buildingLocked("prevent-break")
                && inCity(event.getBlock().getLocation())
                && !event.getPlayer().hasPermission("voidwardencity.admin")) {
            event.setCancelled(true);
            send(event.getPlayer(), msg("protected"));
            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordChestBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.CHEST) return;
        String k = key(event.getBlock().getLocation());
        playerChests.remove(k);
        chestRefills.remove(k);
        removeHologram(k);
        saveChestData();
    }

    /** Backup for normal Bukkit explosions crossing the region boundary. HyperTNT
     * modifies blocks directly, so it must also see the WorldGuard region. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplodeBlocks(EntityExplodeEvent event) {
        protectExplosionBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplodeBlocks(BlockExplodeEvent event) {
        protectExplosionBlocks(event.blockList());
    }

    /**
     * Custom TNT (HyperTNT) removes blocks itself and does not fire block events, so
     * the block-list filter above never sees it. Cancelling the prime event stops
     * the blast before any block is touched. Only blasts that can reach the city's
     * square WorldGuard region (within protection.explosion-margin) are cancelled.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        if (!getConfig().getBoolean("protection.prevent-explosions", true)) {
            return;
        }
        if (nearCity(event.getEntity().getLocation(), getConfig().getDouble("protection.explosion-margin", 16))) {
            event.setCancelled(true);
        }
    }

    /** Distance from the location to the city's square/cuboid bounds, in blocks. */
    private boolean nearCity(Location loc, double margin) {
        Location center = cityCenter();
        if (center == null || center.getWorld() == null || loc.getWorld() == null
                || !center.getWorld().equals(loc.getWorld())) {
            return false;
        }
        double radius = getConfig().getDouble("city.radius", 120);
        double dx = Math.max(0, Math.abs(loc.getX() - center.getX()) - radius);
        double dz = Math.max(0, Math.abs(loc.getZ() - center.getZ()) - radius);
        if (Math.hypot(dx, dz) > margin) {
            return false;
        }
        double height = Math.max(getConfig().getDouble("city.y-below", 45), getConfig().getDouble("city.y-above", 45));
        return Math.abs(loc.getY() - center.getY()) - height <= margin;
    }

    private void protectExplosionBlocks(List<Block> blocks) {
        if (getConfig().getBoolean("protection.prevent-explosions", true)) {
            blocks.removeIf(b -> inCity(b.getLocation()));
        } else if (getConfig().getBoolean("protection.prevent-break", false)) {
            // Backwards compatibility for the previous chest-only protection.
            blocks.removeIf(b -> b.getType() == Material.CHEST && inCity(b.getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (buildingLocked("prevent-place")
                && inCity(event.getBlock().getLocation())
                && !event.getPlayer().hasPermission("voidwardencity.admin")) {
            event.setCancelled(true);
            send(event.getPlayer(), msg("protected"));
            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordPlayerChest(BlockPlaceEvent event) {
        if (!(event.getBlockPlaced().getState() instanceof Chest chest)) return;
        // A chest item carrying copied block-entity data must not copy an event marker.
        if (chest.getPersistentDataContainer().has(chestMarker)) {
            chest.getPersistentDataContainer().remove(chestMarker);
            chest.setLootTable(null);
            chest.update(true, false);
        }
        if (!inCity(chest.getLocation())) return;
        String k = key(chest.getLocation());
        playerChests.add(k);
        chestRefills.remove(k);
        removeHologram(k);
        saveChestData();
    }

    // ------------------------------------------------------------- команда

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "tp" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "setcenter" -> {
                if (!admin(sender) || !(sender instanceof Player player)) {
                    send(sender, msg("admin-only"));
                    return true;
                }
                Location loc = player.getLocation();
                // Зеркало в config.yml (совместимость) + источник правды points.yml.
                getConfig().set("city.world", loc.getWorld().getName());
                getConfig().set("city.x", loc.getBlockX());
                getConfig().set("city.y", loc.getBlockY());
                getConfig().set("city.z", loc.getBlockZ());
                saveConfig();
                points.set("center.world", loc.getWorld().getName());
                points.set("center.x", (double) loc.getBlockX());
                points.set("center.y", (double) loc.getBlockY());
                points.set("center.z", (double) loc.getBlockZ());
                if (!savePoints() || !verifyPoint("center", loc.getWorld().getName(),
                        loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
                    send(sender, msgOr("point-save-error",
                            "&cОШИБКА: точка НЕ сохранилась на диск! Проверь папку плагина (points.yml) и свободное место."));
                    return true;
                }
                send(sender, msg("center-set").replace("{x}", String.valueOf(loc.getBlockX()))
                        .replace("{y}", String.valueOf(loc.getBlockY()))
                        .replace("{z}", String.valueOf(loc.getBlockZ())));
                if (!syncWorldGuardRegion()) send(sender, "&c" + regionStatus);
                return true;
            }
            case "setspawn" -> {
                if (!admin(sender) || !(sender instanceof Player player)) {
                    send(sender, msg("admin-only"));
                    return true;
                }
                Location loc = player.getLocation();
                double sx = round(loc.getX());
                double sy = round(loc.getY());
                double sz = round(loc.getZ());
                double syaw = round(loc.getYaw());
                double spitch = round(loc.getPitch());
                // Зеркало в config.yml (совместимость) + источник правды points.yml.
                getConfig().set("teleport.world", loc.getWorld().getName());
                getConfig().set("teleport.x", sx);
                getConfig().set("teleport.y", sy);
                getConfig().set("teleport.z", sz);
                getConfig().set("teleport.yaw", syaw);
                getConfig().set("teleport.pitch", spitch);
                saveConfig();
                points.set("spawn.world", loc.getWorld().getName());
                points.set("spawn.x", sx);
                points.set("spawn.y", sy);
                points.set("spawn.z", sz);
                points.set("spawn.yaw", syaw);
                points.set("spawn.pitch", spitch);
                if (!savePoints() || !verifyPoint("spawn", loc.getWorld().getName(), sx, sy, sz)) {
                    send(sender, msgOr("point-save-error",
                            "&cОШИБКА: точка НЕ сохранилась на диск! Проверь папку плагина (points.yml) и свободное место."));
                    return true;
                }
                send(sender, msg("spawn-set")
                        .replace("{x}", String.valueOf(loc.getBlockX()))
                        .replace("{y}", String.valueOf(loc.getBlockY()))
                        .replace("{z}", String.valueOf(loc.getBlockZ())));
                return true;
            }
            case "chest" -> {
                if (!admin(sender) || !(sender instanceof Player player)) {
                    send(sender, msg("admin-only"));
                    return true;
                }
                if (!inCity(player.getLocation())) {
                    send(sender, msg("chest-city-only"));
                    return true;
                }
                Block target = player.getLocation().getBlock();
                if (!target.getType().isAir()
                        && target.getType() != Material.WATER
                        && target.getType() != Material.LAVA) {
                    send(sender, msg("chest-no-place"));
                    return true;
                }
                target.setType(Material.CHEST);
                if (target.getBlockData() instanceof org.bukkit.block.data.Directional dir) {
                    dir.setFacing(player.getFacing().getOppositeFace());
                    target.setBlockData(dir);
                }
                if (!(target.getState() instanceof Chest chest) || !markChest(chest, CITY_TABLE)) {
                    target.setType(Material.AIR);
                    send(sender, "&cРядом другой сундук: сундук ивента должен быть одиночным.");
                    return true;
                }
                Chest marked = (Chest) target.getState();
                if (refill(marked.getBlockInventory(), null, marked)) {
                    chestRefills.put(key(target.getLocation()), System.currentTimeMillis());
                    saveChestData();
                    ensureHologram(target.getLocation());
                }
                send(sender, msg("chest-placed"));
                return true;
            }
            case "chestadd" -> {
                if (!admin(sender) || !(sender instanceof Player player)) {
                    send(sender, msg("admin-only"));
                    return true;
                }
                Block target = player.getTargetBlockExact(6);
                if (target == null || !(target.getState() instanceof Chest chest)
                        || !inCity(target.getLocation()) || !singleChest(chest)) {
                    send(sender, "&cПосмотри на одиночный сундук внутри города с расстояния не более 6 блоков.");
                    return true;
                }
                if (!markChest(chest, CITY_TABLE)) {
                    send(sender, "&cНе удалось зарегистрировать сундук.");
                    return true;
                }
                // Do not clear this chest during registration: next refill respects existing timer.
                chestRefills.putIfAbsent(key(target.getLocation()), System.currentTimeMillis());
                saveChestData();
                ensureHologram(target.getLocation());
                send(sender, "&aСундук зарегистрирован для ивента; его содержимое не изменено.");
                return true;
            }
            case "loot" -> {
                if (!admin(sender) || !(sender instanceof Player player)) {
                    send(sender, msg("admin-only"));
                    return true;
                }
                openLootGui(player);
                return true;
            }
            case "chances" -> {
                if (!admin(sender) || !(sender instanceof Player player)) {
                    send(sender, msg("admin-only"));
                    return true;
                }
                openChancesGui(player, 0);
                return true;
            }
            case "refill" -> {
                if (!admin(sender)) {
                    send(sender, msg("admin-only"));
                    return true;
                }
                int count = scanChests(true);
                send(sender, msg("refilled").replace("{count}", String.valueOf(count)));
                return true;
            }
            case "reload" -> {
                if (!admin(sender)) {
                    send(sender, msg("admin-only"));
                    return true;
                }
                reloadConfig();
                loadPoints();
                loadChanceOverrides();
                send(sender, msg("reloaded"));
                if (!syncWorldGuardRegion()) send(sender, "&c" + regionStatus);
                return true;
            }
            case "region" -> {
                if (!admin(sender)) {
                    send(sender, msg("admin-only"));
                    return true;
                }
                send(sender, "&e" + regionStatus);
                return true;
            }
            case "info" -> {
                Location center = cityCenter();
                if (center == null) {
                    send(sender, msg("not-set"));
                    return true;
                }
                long wardens = center.getWorld() == null ? 0 : center.getWorld()
                        .getEntitiesByClass(Warden.class).stream().filter(w -> inCity(w.getLocation())).count();
                send(sender, msg("info")
                        .replace("{x}", String.valueOf(center.getBlockX()))
                        .replace("{y}", String.valueOf(center.getBlockY()))
                        .replace("{z}", String.valueOf(center.getBlockZ()))
                        .replace("{radius}", String.valueOf(getConfig().getInt("city.radius", 120)))
                        .replace("{chests}", String.valueOf(chestRefills.size()))
                        .replace("{wardens}", String.valueOf(wardens))
                        .replace("{minutes}", String.valueOf(currentRefillMinutes()))
                        .replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size())));
                return true;
            }
            default -> {
                if (!(sender instanceof Player player)) {
                    send(sender, msg("players-only"));
                    return true;
                }
                teleport(player);
                return true;
            }
        }
    }

    private void teleport(Player player) {
        if (!getConfig().getBoolean("teleport.enabled", true)) {
            send(player, msg("tp-disabled"));
            return;
        }
        // Точка появления из points.yml; если её мир недоступен — центр города.
        Location target = savedSpawn();
        if (target == null) {
            target = savedCenter();
        }
        if (target == null) {
            send(player, msg("not-set"));
            return;
        }
        boolean bypass = player.hasPermission("voidwardencity.bypass");
        long now = System.currentTimeMillis();
        if (!bypass) {
            long until = tpCooldowns.getOrDefault(player.getUniqueId(), 0L);
            if (until > now) {
                send(player, msg("tp-cooldown").replace("{seconds}",
                        String.valueOf((until - now) / 1000 + 1)));
                return;
            }
        }
        int cost = getConfig().getInt("teleport.cost-points", 0);
        // Прогрев: телепорт не сразу, а через teleport.warmup-seconds.
        // Осколки и кулдаун применяются в момент САМОГО телепорта (doTeleportNow):
        // если прогрев отменили движением/уроном, игрок ничего не теряет.
        long warmupSeconds = bypass ? 0L : Math.max(0L, getConfig().getLong("teleport.warmup-seconds", 3L));
        if (warmupSeconds > 0) {
            if (tpWarmups.containsKey(player.getUniqueId())) {
                send(player, msg("tp-warmup-already"));
                return;
            }
            Location finalTarget = target.clone();
            org.bukkit.scheduler.BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
                tpWarmups.remove(player.getUniqueId());
                if (player.isOnline()) {
                    doTeleportNow(player, finalTarget, bypass, cost);
                }
            }, warmupSeconds * 20L);
            tpWarmups.put(player.getUniqueId(), task);
            send(player, msg("tp-warmup").replace("{seconds}", String.valueOf(warmupSeconds)));
            return;
        }
        doTeleportNow(player, target, bypass, cost);
    }

    /** Сам телепорт: списание осколков, кулдаун, звук, сообщение. */
    private void doTeleportNow(Player player, Location target, boolean bypass, int cost) {
        long now = System.currentTimeMillis();
        if (!bypass && cost > 0) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "points take " + player.getName() + " " + cost);
        }
        player.teleport(target);
        tpCooldowns.put(player.getUniqueId(),
                now + Math.max(0L, getConfig().getLong("teleport.cooldown-seconds", 300L)) * 1000L);
        String sound = getConfig().getString("teleport.sound", "minecraft:entity.enderman.teleport");
        if (sound != null && !sound.isBlank()) {
            player.playSound(target, sound, 0.7f, 1.0f);
        }
        send(player, msg(cost > 0 && !bypass ? "teleported-paid" : "teleported")
                .replace("{cost}", String.valueOf(cost)));
    }

    /** Отменить идущий прогрев телепорта (движение/урон/выход). */
    private void cancelWarmup(Player player, boolean notify) {
        org.bukkit.scheduler.BukkitTask task = tpWarmups.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            if (notify) {
                send(player, msg("tp-warmup-cancelled"));
            }
        }
    }

    private boolean admin(CommandSender sender) {
        return sender.hasPermission("voidwardencity.admin");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> base = admin(sender)
                    ? List.of("tp", "setcenter", "setspawn", "chest", "chestadd", "loot", "chances", "refill", "info", "region", "reload")
                    : List.of("tp", "info");
            List<String> out = new ArrayList<>();
            for (String b : base) {
                if (b.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(b);
                }
            }
            return out;
        }
        return List.of();
    }

    // ------------------------------------------------------------- тексты

    private String msg(String key) {
        return getConfig().getString("messages." + key, "");
    }

    /** Сообщение из конфига, а если ключа нет (старый конфиг) — запасной текст. */
    private String msgOr(String key, String fallback) {
        String text = msg(key);
        return text == null || text.isEmpty() ? fallback : text;
    }

    private void send(CommandSender sender, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String prefix = getConfig().getString("messages.prefix", "");
        sender.sendMessage(comp((prefix == null ? "" : prefix) + text));
    }

    private Component comp(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(TextUtil.color(legacy))
                .decoration(TextDecoration.ITALIC, false);
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
