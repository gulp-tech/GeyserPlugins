package ru.FaralMC.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Загрузка предметов из конфига, выдача, рецепты, вся механика (трапка, пыль, дезориентация, лом). */
public final class ItemsService implements Listener {

    private final FaralItems plugin;
    private final WGHook worldGuard;
    private final NamespacedKey idKey;
    private final Map<String, CustomItem> items = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final List<Trap> traps = new ArrayList<>();
    private final Map<String, Trap> trapBlocks = new HashMap<>(); // "world:x,y,z" -> трапка
    private final Map<UUID, Long> revealed = new HashMap<>(); // подсвеченные явной пылью: uuid -> конец (мс)
    private final Map<UUID, org.bukkit.entity.ArmorStand> markers = new HashMap<>(); // белая метка над подсвеченным

    private static final class Trap {
        UUID owner;
        UUID target;
        final Map<Location, BlockData> changed = new LinkedHashMap<>();
        int minX, maxX, minY, maxY, minZ, maxZ;
        String world;
        BukkitTask task;
    }

    public ItemsService(FaralItems plugin) {
        this.plugin = plugin;
        this.worldGuard = new WGHook(plugin.getLogger());
        this.idKey = new NamespacedKey(plugin, "item_id");
        load();
        registerRecipes();
        // пока пыль действует: повторно выпитый инвиз не прячет, метка над головой следует за игроком
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            revealed.entrySet().removeIf(e -> e.getValue() <= now);
            for (UUID uuid : new ArrayList<>(markers.keySet())) {
                if (!revealed.containsKey(uuid)) {
                    removeMarker(uuid);
                }
            }
            for (UUID uuid : revealed.keySet()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline() || p.isDead()) {
                    removeMarker(uuid);
                    continue;
                }
                p.removePotionEffect(PotionEffectType.INVISIBILITY);
                org.bukkit.entity.ArmorStand marker = markers.get(uuid);
                if (marker != null && marker.isValid()) {
                    marker.teleport(p.getLocation().add(0, 2.45, 0));
                }
            }
        }, 4L, 4L);
    }

    // ------------------------------------------------------------------ загрузка
    public void load() {
        items.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("items");
        if (section == null) {
            plugin.getLogger().warning("В config.yml нет секции items!");
            return;
        }
        for (String id : section.getKeys(false)) {
            String path = "items." + id;
            ConfigurationSection itemSection = section.getConfigurationSection(id);
            CustomItem.Kind kind;
            try {
                kind = CustomItem.Kind.valueOf(plugin.getConfig().getString(path + ".kind", "DUST").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Предмет " + id + ": неизвестный kind, пропускаю");
                continue;
            }
            Material material = Material.matchMaterial(plugin.getConfig().getString(path + ".material", "SUGAR"));
            if (material == null) {
                plugin.getLogger().warning("Предмет " + id + ": неизвестный материал, пропускаю");
                continue;
            }
            Map<Character, Material> ingredients = new HashMap<>();
            ConfigurationSection ing = plugin.getConfig().getConfigurationSection(path + ".recipe.ingredients");
            if (ing != null) {
                for (String k : ing.getKeys(false)) {
                    Material m = Material.matchMaterial(ing.getString(k, ""));
                    if (m != null && !k.isEmpty()) {
                        ingredients.put(k.charAt(0), m);
                    }
                }
            }
            CustomItem.RecipeDef recipe = new CustomItem.RecipeDef(
                    plugin.getConfig().getBoolean(path + ".recipe.enabled", false),
                    plugin.getConfig().getStringList(path + ".recipe.shape"),
                    ingredients);
            items.put(id, new CustomItem(
                    id, kind,
                    plugin.getConfig().getBoolean(path + ".enabled", true),
                    plugin.getConfig().getString(path + ".display-name", id),
                    material,
                    plugin.getConfig().getStringList(path + ".lore"),
                    plugin.getConfig().getBoolean(path + ".glow", true),
                    recipe, itemSection));
        }
        plugin.getLogger().info("Загружено кастомных предметов: " + items.size());
    }

    public void registerRecipes() {
        for (CustomItem item : items.values()) {
            NamespacedKey key = new NamespacedKey(plugin, "item_" + item.id());
            Bukkit.removeRecipe(key);
            if (!item.enabled() || !item.recipe().enabled() || item.recipe().shape().isEmpty()) {
                continue;
            }
            ShapedRecipe recipe = new ShapedRecipe(key, createItem(item, 1));
            recipe.shape(item.recipe().shape().toArray(new String[0]));
            item.recipe().ingredients().forEach(recipe::setIngredient);
            Bukkit.addRecipe(recipe);
            plugin.getLogger().info("Рецепт предмета: " + item.id());
        }
    }

    public static PotionEffectType potionType(String name) {
        PotionEffectType type = PotionEffectType.getByName(name.toUpperCase(Locale.ROOT));
        if (type == null) {
            type = Registry.EFFECT.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
        }
        return type;
    }

    // ------------------------------------------------------------------ предметы
    public ItemStack createItem(CustomItem item, int amount) {
        ItemStack stack = new ItemStack(item.material(), Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(component(TextUtil.color(item.displayName())));
        List<Component> lore = new ArrayList<>();
        for (String line : item.lore()) {
            lore.add(component(TextUtil.color(line)));
        }
        meta.lore(lore);
        if (item.glow()) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        if (item.kind() == CustomItem.Kind.POTION && meta instanceof PotionMeta) {
            PotionMeta potion = (PotionMeta) meta;
            for (CustomItem.EffectDef e : item.effects("effects")) {
                potion.addCustomEffect(new PotionEffect(e.type(), e.durationSeconds() * 20, e.amplifier()), true);
            }
            String colorHex = item.str("color", "#FF5555").replace("#", "");
            try {
                potion.setColor(org.bukkit.Color.fromRGB(Integer.parseInt(colorHex, 16)));
            } catch (NumberFormatException ignored) {
            }
        }
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, item.id());
        stack.setItemMeta(meta);
        return stack;
    }

    public String identify(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    public CustomItem byId(String id) {
        CustomItem item = items.get(id);
        return item != null && item.enabled() ? item : null;
    }

    public Collection<CustomItem> all() {
        List<CustomItem> result = new ArrayList<>();
        for (CustomItem item : items.values()) {
            if (item.enabled()) {
                result.add(item);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ запретные зоны
    /** В этой точке кастомные предметы запрещены (мир целиком или регион WorldGuard). Зелья — исключение, их тут не проверяют. */
    public boolean isBanned(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        if (plugin.getConfig().getStringList("banned.worlds").contains(loc.getWorld().getName())) {
            return true;
        }
        return worldGuard.inBannedRegion(loc, plugin.getConfig().getStringList("banned.worldguard-regions"));
    }

    // ------------------------------------------------------------------ кулдауны
    private long cooldownLeft(UUID uuid, CustomItem item) {
        Map<String, Long> byItem = cooldowns.get(uuid);
        if (byItem == null) {
            return 0;
        }
        Long until = byItem.get(item.id());
        if (until == null) {
            return 0;
        }
        long left = (until - System.currentTimeMillis()) / 1000L;
        return Math.max(0, left);
    }

    private void startCooldown(UUID uuid, CustomItem item) {
        long seconds = (long) item.num("cooldown-seconds", 30);
        if (seconds <= 0) {
            return;
        }
        cooldowns.computeIfAbsent(uuid, k -> new HashMap<>())
                .put(item.id(), System.currentTimeMillis() + seconds * 1000L);
    }

    private void consumeMainHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        hand.setAmount(hand.getAmount() - 1);
    }

    private void msg(Player player, String key, CustomItem item, String... pairs) {
        String text = plugin.getConfig().getString("messages." + key, "");
        if (text == null || text.isEmpty()) {
            return;
        }
        if (item != null) {
            text = text.replace("{item}", item.displayName());
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace(pairs[i], pairs[i + 1]);
        }
        String prefix = TextUtil.color(plugin.getConfig().getString("messages.prefix", "&#C86BFFFaralMC &8» &f"));
        player.sendMessage(component(prefix + TextUtil.color(text)));
    }

    // ------------------------------------------------------------------ использование (ПКМ)
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
        CustomItem item = byId(id);
        if (item == null || item.kind() == CustomItem.Kind.POTION || item.kind() == CustomItem.Kind.LOM) {
            return; // зелья пьются как обычно, лом работает при ломании блока
        }
        event.setCancelled(true);
        if (isBanned(player.getLocation())) {
            msg(player, "banned", item);
            return;
        }
        long left = cooldownLeft(player.getUniqueId(), item);
        if (left > 0) {
            msg(player, "cooldown", item, "{seconds}", String.valueOf(left));
            return;
        }
        boolean used = false;
        if (item.kind() == CustomItem.Kind.TRAP) {
            used = useTrap(player, item);
        } else if (item.kind() == CustomItem.Kind.DUST) {
            used = useDust(player, item);
        } else if (item.kind() == CustomItem.Kind.DISORIENT) {
            used = useDisorient(player, item);
        }
        if (used) {
            consumeMainHand(player);
            startCooldown(player.getUniqueId(), item);
        }
    }

    // ------------------------------------------------------------------ трапка
    private boolean useTrap(Player user, CustomItem item) {
        // Трапка появляется там, где стоит игрок (как на FunTime): клетка 3x3 вокруг него.
        buildTrap(user, item);
        msg(user, "trap-used", item);
        playSound(user, item.str("sound", "block.crying_obsidian.place"));
        return true;
    }

    private boolean trappedPlayer(UUID uuid) {
        for (Trap trap : traps) {
            if (trap.target.equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    private void buildTrap(Player owner, CustomItem item) {
        Location base = owner.getLocation();
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();
        Material floor = mat(item.str("blocks.floor", "POLISHED_BLACKSTONE"), Material.POLISHED_BLACKSTONE);
        Material walls = mat(item.str("blocks.walls", "CRYING_OBSIDIAN"), Material.CRYING_OBSIDIAN);
        Material corners = mat(item.str("blocks.corners", "GILDED_BLACKSTONE"), Material.GILDED_BLACKSTONE);
        Material ceiling = mat(item.str("blocks.ceiling", "POLISHED_BLACKSTONE"), Material.POLISHED_BLACKSTONE);
        Material light = mat(item.str("blocks.light", "SEA_LANTERN"), Material.SEA_LANTERN);

        Trap trap = new Trap();
        trap.owner = owner.getUniqueId();
        trap.target = owner.getUniqueId();
        trap.world = base.getWorld().getName();
        trap.minX = bx - 1; trap.maxX = bx + 1;
        trap.minZ = bz - 1; trap.maxZ = bz + 1;
        trap.minY = by - 1; trap.maxY = by + 3;

        for (int x = bx - 1; x <= bx + 1; x++) {
            for (int z = bz - 1; z <= bz + 1; z++) {
                boolean corner = Math.abs(x - bx) == 1 && Math.abs(z - bz) == 1;
                setTrapBlock(trap, base.getWorld().getBlockAt(x, by - 1, z), corner ? corners : floor);
                setTrapBlock(trap, base.getWorld().getBlockAt(x, by + 3, z), corner ? corners : ceiling);
                for (int y = by; y <= by + 2; y++) {
                    if (Math.abs(x - bx) == 1 || Math.abs(z - bz) == 1) {
                        setTrapBlock(trap, base.getWorld().getBlockAt(x, y, z), corner ? corners : walls);
                    }
                }
            }
        }
        // внутри пусто: центральный столб очищаем (блоки вернутся, когда трапка рассыплется)
        for (int y = by; y <= by + 2; y++) {
            setTrapBlock(trap, base.getWorld().getBlockAt(bx, y, bz), Material.AIR);
        }
        setTrapBlock(trap, base.getWorld().getBlockAt(bx, by + 3, bz), light);
        traps.add(trap);

        // кто оказался внутри вместе с хозяином — тот тоже в трапке
        for (Player other : base.getWorld().getPlayers()) {
            if (other.equals(owner) || other.isDead()) {
                continue;
            }
            Location loc = other.getLocation();
            if (loc.getBlockX() >= trap.minX && loc.getBlockX() <= trap.maxX
                    && loc.getBlockY() >= trap.minY && loc.getBlockY() <= trap.maxY
                    && loc.getBlockZ() >= trap.minZ && loc.getBlockZ() <= trap.maxZ) {
                msg(other, "trapped-you", item, "{player}", owner.getName());
            }
        }

        long durationTicks = (long) item.num("duration-seconds", 20) * 20L;
        trap.task = Bukkit.getScheduler().runTaskLater(plugin, () -> endTrap(trap, true), durationTicks);
    }

    private void setTrapBlock(Trap trap, Block block, Material material) {
        if (block.getType() == Material.BEDROCK) {
            return; // бедрок не трогаем
        }
        if (trapBlocks.containsKey(key(block.getLocation()))) {
            return; // блок другой трапки
        }
        // Запоминаем ВСЁ, что меняем (включая очистку внутренности), —
        // когда трапка рассыплется, территория восстановится блок в блок.
        trap.changed.put(block.getLocation(), block.getBlockData().clone());
        trapBlocks.put(key(block.getLocation()), trap);
        block.setType(material, false);
    }

    private void endTrap(Trap trap, boolean restore) {
        traps.remove(trap);
        if (trap.task != null) {
            trap.task.cancel();
        }
        for (Map.Entry<Location, BlockData> e : trap.changed.entrySet()) {
            trapBlocks.remove(key(e.getKey()));
            if (restore) {
                e.getKey().getBlock().setBlockData(e.getValue(), false);
            }
        }
        trap.changed.clear();
        Player target = Bukkit.getPlayer(trap.target);
        if (target != null) {
            msg(target, "trap-ended", null);
        }
    }

    /** Вызывается при выключении плагина: вернуть все блоки трапок. */
    public void shutdown() {
        for (Trap trap : new ArrayList<>(traps)) {
            endTrap(trap, true);
        }
        for (UUID uuid : new ArrayList<>(markers.keySet())) {
            removeMarker(uuid);
        }
    }

    private static String key(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private Material mat(String name, Material def) {
        Material m = Material.matchMaterial(name);
        return m == null ? def : m;
    }

    // трапку нельзя сломать и внутри неё нельзя ставить блоки
    @EventHandler
    public void onBreakTrap(BlockBreakEvent event) {
        if (trapBlocks.containsKey(key(event.getBlock().getLocation()))) {
            event.setCancelled(true);
            msg(event.getPlayer(), "trap-unbreakable", null);
        }
    }

    @EventHandler
    public void onPlaceInsideTrap(BlockPlaceEvent event) {
        Location loc = event.getBlockPlaced().getLocation();
        for (Trap trap : traps) {
            if (trap.world.equals(loc.getWorld().getName())
                    && loc.getBlockX() >= trap.minX && loc.getBlockX() <= trap.maxX
                    && loc.getBlockY() >= trap.minY && loc.getBlockY() <= trap.maxY
                    && loc.getBlockZ() >= trap.minZ && loc.getBlockZ() <= trap.maxZ) {
                event.setCancelled(true);
                msg(event.getPlayer(), "trap-no-place", null);
                return;
            }
        }
    }

    @EventHandler
    public void onExplodeBlocks(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> trapBlocks.containsKey(key(b.getLocation())));
    }

    @EventHandler
    public void onExplodeBlocks(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> trapBlocks.containsKey(key(b.getLocation())));
    }

    // ------------------------------------------------------------------ явная пыль
    private boolean useDust(Player user, CustomItem item) {
        double radius = item.num("radius", 15);
        int glowingSeconds = (int) item.num("glowing-seconds", 10);
        int found = 0;
        for (Player other : user.getWorld().getPlayers()) {
            if (other.equals(user) || other.isDead() || isBanned(other.getLocation())) {
                continue;
            }
            if (other.getLocation().distanceSquared(user.getLocation()) > radius * radius) {
                continue;
            }
            other.removePotionEffect(PotionEffectType.INVISIBILITY);
            other.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glowingSeconds * 20, 0, true, false, true));
            revealed.put(other.getUniqueId(), System.currentTimeMillis() + glowingSeconds * 1000L);
            spawnMarker(other, item);
            msg(other, "revealed-you", item);
            found++;
        }
        playSound(user, item.str("sound", "entity.firework_rocket.launch"));
        msg(user, "dust-used", item);
        return true;
    }

    /** Белая метка над подсвеченным игроком: в Bedrock неймтеги видны сквозь блоки — игрока видно через стены. */
    private void spawnMarker(Player player, CustomItem item) {
        removeMarker(player.getUniqueId());
        org.bukkit.entity.ArmorStand marker = (org.bukkit.entity.ArmorStand) player.getWorld()
                .spawnEntity(player.getLocation().add(0, 2.45, 0), org.bukkit.entity.EntityType.ARMOR_STAND);
        marker.setVisible(false);
        marker.setMarker(true);
        marker.setGravity(false);
        marker.setInvulnerable(true);
        marker.setPersistent(false);
        marker.customName(component(TextUtil.color(item.str("marker-text", "&f&l▼"))));
        marker.setCustomNameVisible(true);
        markers.put(player.getUniqueId(), marker);
    }

    private void removeMarker(UUID uuid) {
        org.bukkit.entity.ArmorStand marker = markers.remove(uuid);
        if (marker != null) {
            marker.remove();
        }
    }

    // ------------------------------------------------------------------ дезориентация
    private boolean useDisorient(Player user, CustomItem item) {
        double radius = item.num("radius", 9);
        List<CustomItem.EffectDef> effects = item.effects("effects");
        int hit = 0;
        for (Player other : user.getWorld().getPlayers()) {
            if (other.equals(user) || other.isDead() || isBanned(other.getLocation())) {
                continue;
            }
            if (other.getLocation().distanceSquared(user.getLocation()) > radius * radius) {
                continue;
            }
            for (CustomItem.EffectDef e : effects) {
                other.addPotionEffect(new PotionEffect(e.type(), e.durationSeconds() * 20, e.amplifier(), true, false, true));
            }
            if (item.num("rotate-view", 1) > 0) {
                Location loc = other.getLocation();
                float turn = 120f + ThreadLocalRandom.current().nextFloat() * 120f;
                if (ThreadLocalRandom.current().nextBoolean()) {
                    turn = -turn;
                }
                loc.setYaw(loc.getYaw() + turn);
                loc.setPitch(Math.max(-60f, Math.min(60f, loc.getPitch() + (ThreadLocalRandom.current().nextFloat() * 50f - 25f))));
                other.teleport(loc);
            }
            hit++;
        }
        if (hit == 0) {
            msg(user, "no-target", item);
            return false;
        }
        playSound(user, item.str("sound", "entity.wither.ambient"));
        msg(user, "disorient-used", item);
        return true;
    }

    // ------------------------------------------------------------------ лом спавнеров
    @EventHandler
    public void onBreakSpawner(BlockBreakEvent event) {
        Block block = event.getBlock();
        // блоки трапок не ломаются вообще (отдельный обработчик выше), тут — спавнеры
        if (block.getType() != Material.SPAWNER) {
            return;
        }
        Player player = event.getPlayer();
        String id = identify(player.getInventory().getItemInMainHand());
        CustomItem item = id == null ? null : byId(id);
        if (item == null || item.kind() != CustomItem.Kind.LOM) {
            return;
        }
        if (isBanned(block.getLocation())) {
            msg(player, "banned", item);
            event.setCancelled(true); // в запретной зоне лом не работает и спавнер не теряется
            return;
        }
        // Моб сохраняется: в выпавшем спавнере будет тот же моб.
        org.bukkit.entity.EntityType mob = null;
        if (block.getState() instanceof CreatureSpawner) {
            mob = ((CreatureSpawner) block.getState()).getSpawnedType();
        }
        event.setDropItems(false);
        event.setExpToDrop(0);
        ItemStack spawnerItem = new ItemStack(Material.SPAWNER);
        ItemMeta spawnerMeta = spawnerItem.getItemMeta();
        String mobName = mob == null ? "" : mob.name();
        spawnerMeta.displayName(component(TextUtil.color(item.str("spawner-item-name", "&#FFB340Спавнер")
                .replace("{mob}", mobName))));
        if (spawnerMeta instanceof BlockStateMeta && mob != null) {
            BlockStateMeta bsm = (BlockStateMeta) spawnerMeta;
            if (bsm.getBlockState() instanceof CreatureSpawner) {
                ((CreatureSpawner) bsm.getBlockState()).setSpawnedType(mob);
                bsm.setBlockState(bsm.getBlockState());
            }
        }
        spawnerItem.setItemMeta(spawnerMeta);
        consumeMainHand(player); // лом одноразовый
        Bukkit.getScheduler().runTask(plugin, () -> {
            block.setType(Material.AIR, false);
            java.util.Map<Integer, ItemStack> rest = player.getInventory().addItem(spawnerItem);
            for (ItemStack drop : rest.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        });
        msg(player, "lom-used", item, "{mob}", mobName);
        event.setCancelled(true);
    }

    private void playSound(Player player, String sound) {
        if (sound == null || sound.isEmpty()) {
            return;
        }
        try {
            player.playSound(player.getLocation(), sound.toLowerCase(Locale.ROOT), 0.8f, 1.0f);
        } catch (Throwable ignored) {
        }
    }

    static Component component(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy == null ? "" : legacy);
    }
}
