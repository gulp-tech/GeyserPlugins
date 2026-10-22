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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
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
public final class VoidWardenCity extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Random random = new Random();
    /** Время последнего наполнения сундука: "world;x;y;z" -> millis. */
    private final Map<String, Long> chestRefills = new HashMap<>();
    /** Кулдауны телепорта игроков. */
    private final Map<UUID, Long> tpCooldowns = new HashMap<>();
    /** Голограммы над сундуками: ключ сундука -> UUID стенда. */
    private final Map<String, UUID> holograms = new HashMap<>();

    private record LootEntry(Material material, String command, int chance, int min, int max,
                             String name, int customModelData, String itemModel,
                             Map<String, Integer> enchantments, String potion) {
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadChestData();
        loadCustomLoot();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("wcity") != null) {
            getCommand("wcity").setExecutor(this);
            getCommand("wcity").setTabCompleter(this);
        }
        long chestPeriod = Math.max(20L, getConfig().getLong("chests.scan-seconds", 60L) * 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::scanChests, chestPeriod, chestPeriod);
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

    private Location cityCenter() {
        World world = Bukkit.getWorld(getConfig().getString("city.world", "world"));
        if (world == null) {
            return null;
        }
        return new Location(world,
                getConfig().getDouble("city.x", 0),
                getConfig().getDouble("city.y", -51),
                getConfig().getDouble("city.z", 0));
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChestOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Location loc = holderLocation(event.getInventory());
        if (loc == null || !inCity(loc)) {
            return;
        }
        String key = key(loc);
        long now = System.currentTimeMillis();
        long cooldown = currentRefillMillis();
        Long last = chestRefills.get(key);
        if (last == null || now - last >= cooldown) {
            refill(event.getInventory(), player);
            chestRefills.put(key, now);
            saveChestData();
            ensureHologram(loc);
        }
    }

    private Location holderLocation(Inventory inv) {
        Object holder = inv.getHolder();
        if (holder instanceof Chest chest) {
            return chest.getLocation();
        }
        if (holder instanceof org.bukkit.block.DoubleChest dc) {
            return dc.getLocation();
        }
        return null;
    }

    /** Периодически находит сундуки в загруженных чанках города и обновляет протухший лут. */
    private void scanChests() {
        scanChests(false);
    }

    /** @return сколько сундуков наполнено. force — обновить все, игнорируя кулдаун. */
    private int scanChests(boolean force) {
        Location center = cityCenter();
        if (center == null || center.getWorld() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long cooldown = currentRefillMillis();
        double radius = getConfig().getDouble("city.radius", 120);
        boolean changed = false;
        int refilled = 0;
        for (org.bukkit.Chunk chunk : center.getWorld().getLoadedChunks()) {
            double cx = chunk.getX() * 16 + 8;
            double cz = chunk.getZ() * 16 + 8;
            if (Math.hypot(cx - center.getX(), cz - center.getZ()) > radius + 24) {
                continue;
            }
            for (org.bukkit.block.BlockState state : chunk.getTileEntities()) {
                if (!(state instanceof Chest chest) || !inCity(chest.getLocation())) {
                    continue;
                }
                String key = key(chest.getLocation());
                Long last = chestRefills.get(key);
                if (force || last == null || now - last >= cooldown) {
                    refill(chest.getBlockInventory(), null);
                    chestRefills.put(key, now);
                    changed = true;
                    refilled++;
                }
                ensureHologram(chest.getLocation());
            }
        }
        if (changed) {
            saveChestData();
        }
        return refilled;
    }

    private void refill(Inventory inv, Player opener) {
        inv.clear();
        List<LootEntry> loot = parseLoot();
        if (loot.isEmpty()) {
            return;
        }
        int rollsMin = Math.max(0, getConfig().getInt("chests.rolls-min", 2));
        int rollsMax = Math.max(rollsMin, getConfig().getInt("chests.rolls-max", 4));
        int rolls = rollsMin + (rollsMax > rollsMin ? random.nextInt(rollsMax - rollsMin + 1) : 0);
        List<LootEntry> shuffled = new ArrayList<>(loot);
        java.util.Collections.shuffle(shuffled, random);
        int placed = 0;
        for (LootEntry e : shuffled) {
            if (placed >= rolls) {
                break;
            }
            if (random.nextInt(100) >= e.chance()) {
                continue;
            }
            if (e.command() != null) {
                // Командный лут (сферы, талисманы, осколки) выдаётся тому, кто открыл
                // свежезаполненный сундук. При фоновом обновлении без игрока — пропускаем.
                if (opener != null) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            e.command().replace("%player%", opener.getName()));
                    placed++;
                }
                continue;
            }
            int amount = e.min() + (e.max() > e.min() ? random.nextInt(e.max() - e.min() + 1) : 0);
            if (amount <= 0) {
                continue;
            }
            int slot = freeSlot(inv);
            if (slot >= 0) {
                inv.setItem(slot, buildItem(e, amount));
                placed++;
            }
        }
        // Дополнение к ванильному луту из GUI (/wcity loot): каждый сохранённый
        // предмет с шансом кладётся в сундук в случайном количестве 1..размер стака.
        int customChance = getConfig().getInt("custom-loot.chance-percent", 30);
        for (ItemStack saved : customLoot) {
            if (random.nextInt(100) >= customChance) {
                continue;
            }
            int slot = freeSlot(inv);
            if (slot < 0) {
                break;
            }
            ItemStack clone = saved.clone();
            clone.setAmount(1 + random.nextInt(Math.max(1, saved.getAmount())));
            inv.setItem(slot, clone);
        }
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
                out.add(new LootEntry(null, String.valueOf(raw.get("command")), chance, min, max, name, cmd, model, enchants, potion));
            } else if (raw.get("material") != null) {
                Material mat = Material.matchMaterial(String.valueOf(raw.get("material")));
                if (mat != null) {
                    out.add(new LootEntry(mat, null, chance, min, max, name, cmd, model, enchants, potion));
                }
            }
        }
        return out;
    }

    private String key(Location loc) {
        return loc.getWorld().getName() + ";" + loc.getBlockX() + ";" + loc.getBlockY() + ";" + loc.getBlockZ();
    }

    private File chestFile() {
        return new File(getDataFolder(), "chests.yml");
    }

    private void loadChestData() {
        File file = chestFile();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        if (data.isConfigurationSection("chests")) {
            for (String k : data.getConfigurationSection("chests").getKeys(false)) {
                chestRefills.put(k, data.getLong("chests." + k));
            }
        }
    }

    private void saveChestData() {
        YamlConfiguration data = new YamlConfiguration();
        for (Map.Entry<String, Long> e : chestRefills.entrySet()) {
            data.set("chests." + e.getKey(), e.getValue());
        }
        try {
            data.save(chestFile());
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
        customLoot.clear();
        File file = customLootFile();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
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

    private void saveCustomLoot() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("items", customLoot);
        try {
            data.save(customLootFile());
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить loot-items.yml: " + e.getMessage());
        }
    }

    private void openLootGui(Player player) {
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
        customLoot.clear();
        for (ItemStack item : event.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
                customLoot.add(item.clone());
            }
        }
        saveCustomLoot();
        if (event.getPlayer() instanceof Player player) {
            send(player, msg("gui-saved").replace("{count}", String.valueOf(customLoot.size())));
        }
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
        insideCity.remove(event.getPlayer().getUniqueId());
        tpCooldowns.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------- защита

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (getConfig().getBoolean("protection.prevent-break", false)
                && inCity(event.getBlock().getLocation())
                && !event.getPlayer().hasPermission("voidwardencity.admin")) {
            event.setCancelled(true);
            send(event.getPlayer(), msg("protected"));
        }
    }

    /** При включённой защите взрывы не разносят сундуки города. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplodeBlocks(EntityExplodeEvent event) {
        if (getConfig().getBoolean("protection.prevent-break", false)) {
            event.blockList().removeIf(b -> b.getType() == Material.CHEST && inCity(b.getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplodeBlocks(BlockExplodeEvent event) {
        if (getConfig().getBoolean("protection.prevent-break", false)) {
            event.blockList().removeIf(b -> b.getType() == Material.CHEST && inCity(b.getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (getConfig().getBoolean("protection.prevent-place", false)
                && inCity(event.getBlock().getLocation())
                && !event.getPlayer().hasPermission("voidwardencity.admin")) {
            event.setCancelled(true);
            send(event.getPlayer(), msg("protected"));
        }
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
                getConfig().set("city.world", loc.getWorld().getName());
                getConfig().set("city.x", loc.getBlockX());
                getConfig().set("city.y", loc.getBlockY());
                getConfig().set("city.z", loc.getBlockZ());
                saveConfig();
                send(sender, msg("center-set").replace("{x}", String.valueOf(loc.getBlockX()))
                        .replace("{y}", String.valueOf(loc.getBlockY()))
                        .replace("{z}", String.valueOf(loc.getBlockZ())));
                return true;
            }
            case "setspawn" -> {
                if (!admin(sender) || !(sender instanceof Player player)) {
                    send(sender, msg("admin-only"));
                    return true;
                }
                Location loc = player.getLocation();
                getConfig().set("teleport.world", loc.getWorld().getName());
                getConfig().set("teleport.x", round(loc.getX()));
                getConfig().set("teleport.y", round(loc.getY()));
                getConfig().set("teleport.z", round(loc.getZ()));
                getConfig().set("teleport.yaw", round(loc.getYaw()));
                getConfig().set("teleport.pitch", round(loc.getPitch()));
                saveConfig();
                send(sender, msg("spawn-set"));
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
                if (target.getState() instanceof Chest chest) {
                    refill(chest.getBlockInventory(), null);
                    chestRefills.put(key(target.getLocation()), System.currentTimeMillis());
                    saveChestData();
                    ensureHologram(target.getLocation());
                }
                send(sender, msg("chest-placed"));
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
                send(sender, msg("reloaded"));
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
        World world = Bukkit.getWorld(getConfig().getString("teleport.world",
                getConfig().getString("city.world", "world")));
        if (world == null) {
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
        if (!bypass && cost > 0) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "points take " + player.getName() + " " + cost);
        }
        Location target = new Location(world,
                getConfig().getDouble("teleport.x", getConfig().getDouble("city.x", 0)),
                getConfig().getDouble("teleport.y", getConfig().getDouble("city.y", -51)),
                getConfig().getDouble("teleport.z", getConfig().getDouble("city.z", 0)),
                (float) getConfig().getDouble("teleport.yaw", 0),
                (float) getConfig().getDouble("teleport.pitch", 0));
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

    private boolean admin(CommandSender sender) {
        return sender.hasPermission("voidwardencity.admin");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> base = admin(sender)
                    ? List.of("tp", "setcenter", "setspawn", "chest", "loot", "refill", "info", "reload")
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
