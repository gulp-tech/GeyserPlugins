package ru.voidpe.enchants;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VoidEnchants — кастомные зачарования VoidPE (Purpur 26.2 + Geyser/Floodgate):
 * Автоплавка (переплавляет дроп), Магнит (дроп сразу в инвентарь, на топоре — и с мобов),
 * Бур I (3x3) / Бур II (3x3 в 2 слоя). Чары хранятся в PDC предмета и пишутся в лор.
 * Работают вместе: бур ломает область — автоплавка плавит каждый блок, магнит собирает всё.
 * Книги с чарами: выдача /vench give, стол зачарований (только 30 уровень), наковальня
 * (объединение одинаковых чар с повышением выше ванильных капов — см. конфиг).
 */
public final class VoidEnchants extends JavaPlugin implements Listener, TabCompleter {

    private NamespacedKey keyAutosmelt;
    private NamespacedKey keyMagnet;
    private NamespacedKey keyDrill;

    /** Игроки, у которых прямо сейчас обрабатываются дополнительные блоки бура (анти-рекурсия). */
    private final Set<UUID> drilling = new HashSet<>();

    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V"};

    @Override
    public void onEnable() {
        saveDefaultConfig();
        keyAutosmelt = new NamespacedKey(this, "autosmelt");
        keyMagnet = new NamespacedKey(this, "magnet");
        keyDrill = new NamespacedKey(this, "drill");
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("voidenchants") != null) {
            getCommand("voidenchants").setExecutor(this);
            getCommand("voidenchants").setTabCompleter(this);
        }
        getLogger().info("VoidEnchants включён: Автоплавка, Магнит, Бур.");
    }

    // ------------------------------------------------------------ цвета

    public static String color(String text) {
        if (text == null) {
            return "";
        }
        Matcher m = HEX.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String hex = m.group(1);
            StringBuilder rep = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                rep.append('§').append(c);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        return sb.toString().replace('&', '§');
    }

    private Component comp(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(color(text));
    }

    private void msg(CommandSender sender, String path, String... replaces) {
        String text = getConfig().getString("messages." + path, path);
        for (int i = 0; i + 1 < replaces.length; i += 2) {
            text = text.replace("{" + replaces[i] + "}", replaces[i + 1]);
        }
        sender.sendMessage(comp(getConfig().getString("messages.prefix", "") + text));
    }

    // ------------------------------------------------------------ данные чар

    private int getCustom(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) {
            return 0;
        }
        Integer v = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    private int autosmelt(ItemStack item) {
        return getCustom(item, keyAutosmelt);
    }

    private int magnet(ItemStack item) {
        return getCustom(item, keyMagnet);
    }

    private int drill(ItemStack item) {
        return getCustom(item, keyDrill);
    }

    private boolean isBook(ItemStack item) {
        return item != null && (item.getType() == Material.ENCHANTED_BOOK || item.getType() == Material.BOOK);
    }

    private boolean isPickaxe(ItemStack item) {
        return item != null && item.getType().name().endsWith("_PICKAXE");
    }

    private boolean isShovel(ItemStack item) {
        return item != null && item.getType().name().endsWith("_SHOVEL");
    }

    private boolean isAxe(ItemStack item) {
        return item != null && item.getType().name().endsWith("_AXE") && !isPickaxe(item);
    }

    private boolean isHoe(ItemStack item) {
        return item != null && item.getType().name().endsWith("_HOE");
    }

    /** Куда можно наложить чар: drill/autosmelt — кирка и лопата; magnet — кирка, топор, лопата, мотыга; книги — на всё. */
    private boolean applicable(String enchant, ItemStack item) {
        if (item == null) {
            return false;
        }
        if (isBook(item)) {
            return true;
        }
        if (enchant.equals("drill") || enchant.equals("autosmelt")) {
            return isPickaxe(item) || isShovel(item);
        }
        if (enchant.equals("magnet")) {
            return isPickaxe(item) || isAxe(item) || isShovel(item) || isHoe(item);
        }
        return false;
    }

    /** Применить/обновить кастомные чары на предмете и перестроить их строки в лоре. */
    private void setCustom(ItemStack item, int autosmelt, int magnet, int drill) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (autosmelt > 0) pdc.set(keyAutosmelt, PersistentDataType.INTEGER, autosmelt); else pdc.remove(keyAutosmelt);
        if (magnet > 0) pdc.set(keyMagnet, PersistentDataType.INTEGER, magnet); else pdc.remove(keyMagnet);
        if (drill > 0) pdc.set(keyDrill, PersistentDataType.INTEGER, drill); else pdc.remove(keyDrill);

        // лор: убрать старые строки наших чар и добавить актуальные
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        String aName = plainName("autosmelt");
        String mName = plainName("magnet");
        String dName = plainName("drill");
        lore.removeIf(c -> {
            String plain = LegacyComponentSerializer.legacySection().serialize(c).replaceAll("§.", "");
            return plain.startsWith(aName) || plain.startsWith(mName) || plain.startsWith(dName);
        });
        List<Component> custom = new ArrayList<>();
        if (autosmelt > 0) custom.add(comp(getConfig().getString("enchant-names.autosmelt", "&6Автоплавка")));
        if (magnet > 0) custom.add(comp(getConfig().getString("enchant-names.magnet", "&bМагнит")));
        if (drill > 0) custom.add(comp(getConfig().getString("enchant-names.drill", "&eБур") + " &e" + ROMAN[Math.min(drill, 5)]));
        lore.addAll(0, custom);
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    private String plainName(String enchant) {
        return color(getConfig().getString("enchant-names." + enchant, enchant)).replaceAll("§.", "");
    }

    // ------------------------------------------------------------ добыча: автоплавка + магнит + бур

    // ВАЖНО: приоритет MONITOR + ignoreCancelled. Защита региона может отменить событие
    // на HIGHEST/MONITOR ПОСЛЕ обычного HIGH — если выдать дроп раньше (как было), блок
    // остаётся на месте, а дроп уже у игрока = бесконечный дюп буром в чужих регионах.
    // На MONITOR событие уже финальное: отменено — нас вообще не вызовут.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        boolean extra = drilling.contains(player.getUniqueId());
        if (extra) {
            // Дополнительный блок бура (синтетическое событие из expandDrill): дроп и
            // разрушение делает сам expandDrill ПОСЛЕ проверки, что событие не отменила
            // защита. Здесь ничего не выдаём — иначе дюп в защищённых регионах.
            return;
        }
        boolean hasCustom = autosmelt(tool) > 0 || magnet(tool) > 0 || drill(tool) > 0;
        if (!hasCustom) {
            return;
        }
        boolean creative = player.getGameMode() == GameMode.CREATIVE;

        // Дроп уже забрал другой плагин (кастомный дроп шахты LMAutoMine: керамика -> жемчуг, пурпур -> хорус).
        // Блок не трогаем, но магнит подберёт выброшенное, а бур всё равно сработает.
        if (!event.isDropItems()) {
            if (magnet(tool) > 0) {
                magnetSweep(player, event.getBlock().getLocation());
            }
            if (!extra && drill(tool) > 0 && (!creative || getConfig().getBoolean("drill.in-creative", true))) {
                expandDrill(player, event.getBlock(), tool, drill(tool));
            }
            return;
        }
        if (creative && !getConfig().getBoolean("drill.in-creative", true)) {
            return;
        }

        boolean silk = hasEnchant(tool, "silk_touch");
        if (!creative) {
            Collection<ItemStack> drops = event.getBlock().getDrops(tool, player);
            event.setDropItems(false);
            deliver(player, event.getBlock().getLocation(), tool, drops, silk);
        }
        if (!extra && drill(tool) > 0) {
            expandDrill(player, event.getBlock(), tool, drill(tool));
        }
    }

    /** Расширение бура: каждый блок — через настоящее событие ломания (WorldGuard и шахта могут отменить/забрать дроп). */
    private void expandDrill(Player player, Block center, ItemStack tool, int drillLevel) {
        List<Block> targets = drillTargets(player, center, drillLevel);
        if (targets.isEmpty()) {
            return;
        }
        drilling.add(player.getUniqueId());
        try {
            for (Block target : targets) {
                if (target.getType().isAir() || target.getType() == Material.BEDROCK) {
                    continue;
                }
                BlockBreakEvent synthetic = new BlockBreakEvent(target, player);
                Bukkit.getPluginManager().callEvent(synthetic);
                // callEvent синхронный: к этому моменту отработали ВСЕ плагины всех
                // приоритетов (включая защиту региона на HIGHEST/MONITOR). Отменено —
                // блок не трогаем и НИЧЕГО не выдаём (раньше дроп выдавался раньше отмены).
                if (synthetic.isCancelled()) {
                    continue; // защита региона/другого плагина — блок не трогаем
                }
                boolean creative = player.getGameMode() == GameMode.CREATIVE;
                if (!creative) {
                    if (synthetic.isDropItems()) {
                        boolean silk = hasEnchant(tool, "silk_touch");
                        Collection<ItemStack> drops = target.getDrops(tool, player);
                        target.setType(Material.AIR, false);
                        deliver(player, target.getLocation(), tool, drops, silk);
                        if (synthetic.getExpToDrop() > 0) {
                            player.giveExp(synthetic.getExpToDrop());
                        }
                    } else {
                        // дроп забрал другой плагин (кастомный дроп шахты) — блок ломаем,
                        // дроп не дублируем, магнит подберёт выброшенное рядом.
                        target.setType(Material.AIR, false);
                        if (magnet(tool) > 0) {
                            magnetSweep(player, target.getLocation());
                        }
                    }
                } else {
                    target.setType(Material.AIR, false);
                }
                if (getConfig().getBoolean("drill.damage-per-block", true)) {
                    damageTool(player, tool, getConfig().getDouble("drill.durability-multiplier", 1.3));
                    if (tool.getAmount() == 0) {
                        break; // инструмент сломался
                    }
                }
            }
        } finally {
            drilling.remove(player.getUniqueId());
        }
    }

    /** Магнит для дропа, который выбросил другой плагин предметом на землю: подобрать свежевыпавшее рядом. */
    private void magnetSweep(Player player, Location at) {
        Location center = at.clone().add(0.5, 0.5, 0.5);
        Bukkit.getScheduler().runTask(this, () -> {
            for (org.bukkit.entity.Entity entity : center.getWorld().getNearbyEntities(center, 1.6, 1.6, 1.6)) {
                if (entity instanceof org.bukkit.entity.Item itemEntity && itemEntity.getTicksLived() < 40) {
                    ItemStack stack = itemEntity.getItemStack().clone();
                    itemEntity.remove();
                    if (tryAutoBuy(player, stack)) {
                        continue; // автоскупщик продал предмет - в инвентарь НЕ кладём (иначе дюп: деньги + предмет)
                    }
                    Map<Integer, ItemStack> left = player.getInventory().addItem(stack);
                    for (ItemStack rest : left.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), rest);
                    }
                }
            }
        });
    }

    /**
     * Поддержка авто-скупщика (LMbuyer): если плагин стоит, магнитный предмет сначала
     * проходит через настоящее событие подбора — автоскупщик успевает его продать.
     * @return true, если предмет забрал скупщик и в инвентарь его класть не надо
     */
    private boolean tryAutoBuy(Player player, ItemStack stack) {
        if (!getConfig().getBoolean("magnet.autobuy-support", true)) {
            return false;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("LMbuyer")) {
            return false;
        }
        org.bukkit.entity.Item entity = player.getWorld().dropItem(player.getLocation(), stack.clone());
        entity.setPickupDelay(32767);
        EntityPickupItemEvent pickup = new EntityPickupItemEvent(player, entity, 0);
        Bukkit.getPluginManager().callEvent(pickup);
        boolean consumed = pickup.isCancelled() || !entity.isValid();
        entity.remove();
        return consumed;
    }

    /** Обработать дроп одного блока: автоплавка -> магнит в инвентарь / на землю. */
    private void deliver(Player player, Location at, ItemStack tool, Collection<ItemStack> drops, boolean silk) {
        boolean smelt = autosmelt(tool) > 0 && !silk;
        boolean toInventory = magnet(tool) > 0 && getConfig().getBoolean("magnet.direct-to-inventory", true);
        for (ItemStack drop : drops) {
            if (drop == null || drop.getType().isAir()) {
                continue;
            }
            ItemStack out = drop.clone();
            if (smelt) {
                String mapped = getConfig().getString("smelt-map." + out.getType().name());
                if (mapped != null) {
                    Material result = Material.matchMaterial(mapped);
                    if (result != null) {
                        out.setType(result);
                    }
                }
            }
            if (toInventory && tryAutoBuy(player, out)) {
                continue; // автоскупщик LMbuyer забрал предмет через событие подбора
            }
            if (toInventory) {
                Map<Integer, ItemStack> left = player.getInventory().addItem(out);
                for (ItemStack rest : left.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
            } else if (magnet(tool) > 0) {
                player.getWorld().dropItemNaturally(player.getLocation(), out);
            } else {
                at.getWorld().dropItemNaturally(at.clone().add(0.5, 0.3, 0.5), out);
            }
        }
    }

    /** Блоки бура: 3x3 в плоскости взгляда; уровень II — второй слой вглубь. */
    private List<Block> drillTargets(Player player, Block center, int level) {
        List<Block> result = new ArrayList<>();
        Vector dir = player.getLocation().getDirection();
        int ax; // 0=X,1=Y,2=Z — ось нормали (вглубь)
        if (Math.abs(dir.getY()) > 0.7) {
            ax = 1;
        } else if (Math.abs(dir.getX()) > Math.abs(dir.getZ())) {
            ax = 0;
        } else {
            ax = 2;
        }
        int depth = level >= 2 ? 2 : 1;
        int step = 0;
        if (ax == 1) step = dir.getY() > 0 ? 1 : -1;
        if (ax == 0) step = dir.getX() > 0 ? 1 : -1;
        if (ax == 2) step = dir.getZ() > 0 ? 1 : -1;

        for (int layer = 0; layer < depth; layer++) {
            for (int a = -1; a <= 1; a++) {
                for (int b = -1; b <= 1; b++) {
                    int x = center.getX(), y = center.getY(), z = center.getZ();
                    if (ax == 0) { x += layer * step; y += a; z += b; }
                    if (ax == 1) { y += layer * step; x += a; z += b; }
                    if (ax == 2) { z += layer * step; x += a; y += b; }
                    if (layer == 0 && a == 0 && b == 0) {
                        continue; // сам центральный блок уже сломан
                    }
                    Block block = center.getWorld().getBlockAt(x, y, z);
                    if (block.getType().isAir() || block.getType() == Material.BEDROCK
                            || block.getType().getHardness() < 0) {
                        continue;
                    }
                    // сундуки, спавнеры, таблички и прочие хранилища бур не трогает
                    if (block.getState() instanceof org.bukkit.block.TileState) {
                        continue;
                    }
                    // только природные блоки из конфига (поставленные игроками постройки бур не сносит)
                    List<String> allowed = getConfig().getStringList("drill.allowed-blocks");
                    if (!allowed.isEmpty() && !allowed.contains(block.getType().name())) {
                        continue;
                    }
                    result.add(block);
                }
            }
        }
        return result;
    }

    /** Урон инструменту за блок бура: множитель износа (1.3 = на 30% быстрее обычной скорости). */
    private void damageTool(Player player, ItemStack tool, double multiplier) {
        int times = (int) multiplier;
        if (ThreadLocalRandom.current().nextDouble() < multiplier - times) {
            times++;
        }
        for (int i = 0; i < times && tool.getAmount() > 0; i++) {
            damageTool(player, tool);
        }
    }

    /** Урон инструменту за блок бура с шансом от Прочности. */
    private void damageTool(Player player, ItemStack tool) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        ItemMeta meta = tool.getItemMeta();
        if (!(meta instanceof Damageable) || tool.getType().getMaxDurability() <= 0) {
            return;
        }
        Damageable damageable = (Damageable) meta;
        int unbreaking = meta.getEnchantLevel(ench("unbreaking"));
        if (unbreaking > 0 && ThreadLocalRandom.current().nextInt(unbreaking + 1) != 0) {
            return;
        }
        int damage = damageable.getDamage() + 1;
        if (damage > tool.getType().getMaxDurability()) {
            tool.setAmount(0);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
            return;
        }
        damageable.setDamage(damage);
        tool.setItemMeta(meta);
    }

    // ------------------------------------------------------------ магнит на мобах (топор)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!getConfig().getBoolean("magnet.mob-drops", true)) {
            return;
        }
        if (event.getEntity() instanceof Player) {
            return; // на убийствах игроков магнит не работает
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        ItemStack tool = killer.getInventory().getItemInMainHand();
        if (magnet(tool) <= 0) {
            return;
        }
        List<ItemStack> drops = new ArrayList<>(event.getDrops());
        event.getDrops().clear();
        for (ItemStack drop : drops) {
            Map<Integer, ItemStack> left = killer.getInventory().addItem(drop);
            for (ItemStack rest : left.values()) {
                killer.getWorld().dropItemNaturally(killer.getLocation(), rest);
            }
        }
    }

    // ------------------------------------------------------------ стол зачарований (только 30 уровень)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (!getConfig().getBoolean("enchanting-table.enabled", true)) {
            return;
        }
        if (event.getExpLevelCost() < getConfig().getInt("enchanting-table.min-level-cost", 30)) {
            return;
        }
        ItemStack item = event.getItem();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int a = 0, m = 0, d = 0;
        if (applicable("autosmelt", item) && rnd.nextDouble() < getConfig().getDouble("enchanting-table.chances.autosmelt", 0.15)) a = 1;
        if (applicable("magnet", item) && rnd.nextDouble() < getConfig().getDouble("enchanting-table.chances.magnet", 0.15)) m = 1;
        if (applicable("drill", item) && rnd.nextDouble() < getConfig().getDouble("enchanting-table.chances.drill", 0.08)) d = 1;
        if (a == 0 && m == 0 && d == 0) {
            return;
        }
        final int fa = Math.max(a, autosmelt(item)), fm = Math.max(m, magnet(item)), fd = Math.max(d, drill(item));
        // применяем на следующем тике — когда ванильные чары уже легли на предмет
        Bukkit.getScheduler().runTask(this, () -> {
            if (item.getAmount() > 0) {
                setCustom(item, fa, fm, fd);
            }
        });
    }

    // ------------------------------------------------------------ наковальня: объединение выше ванильных капов

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnvil(PrepareAnvilEvent event) {
        AnvilInventory inventory = event.getInventory();
        ItemStack first = inventory.getItem(0);
        ItemStack second = inventory.getItem(1);
        if (first == null || second == null) {
            return;
        }
        boolean secondIsBook = second.getType() == Material.ENCHANTED_BOOK;
        if (!secondIsBook && first.getType() != second.getType() && !isBook(first)) {
            return; // объединяем только: инструмент+книга, инструмент+такой же инструмент, книга+книга
        }

        Map<Enchantment, Integer> enchFirst = enchantsOf(first);
        Map<Enchantment, Integer> enchSecond = enchantsOf(second);
        int aFirst = autosmelt(first), aSecond = autosmelt(second);
        int mFirst = magnet(first), mSecond = magnet(second);
        int dFirst = drill(first), dSecond = drill(second);
        boolean anyCustom = aSecond > 0 || mSecond > 0 || dSecond > 0;
        boolean raisesVanilla = false;
        for (Map.Entry<Enchantment, Integer> e : enchSecond.entrySet()) {
            Integer lvlFirst = enchFirst.get(e.getKey());
            if (lvlFirst != null && lvlFirst.equals(e.getValue()) && e.getValue() < capOf(e.getKey())) {
                raisesVanilla = true;
            }
        }
        if (!anyCustom && !raisesVanilla && event.getResult() != null) {
            return; // ванильная наковальня и так справляется
        }

        // Базовый предмет: книга + инструмент = ИНСТРУМЕНТ (раньше получалась книга и инструмент пропадал).
        ItemStack result;
        if (isBook(first) && !isBook(second)) {
            result = second.clone();
        } else {
            result = event.getResult() != null ? event.getResult().clone() : first.clone();
        }

        // ванильные чары: равный уровень + равный = +1 (до капа из конфига)
        Set<Enchantment> all = new HashSet<>();
        all.addAll(enchFirst.keySet());
        all.addAll(enchSecond.keySet());
        boolean bookResult = isBook(result);
        ItemMeta resultMeta = result.getItemMeta();
        if (resultMeta == null) {
            return;
        }
        // сброс чар результата перед пересборкой
        if (resultMeta instanceof EnchantmentStorageMeta) {
            EnchantmentStorageMeta storage = (EnchantmentStorageMeta) resultMeta;
            for (Enchantment ench : new HashMap<>(storage.getStoredEnchants()).keySet()) {
                storage.removeStoredEnchant(ench);
            }
        } else {
            for (Enchantment ench : new HashMap<>(resultMeta.getEnchants()).keySet()) {
                resultMeta.removeEnchant(ench);
            }
        }
        result.setItemMeta(resultMeta);
        ItemMeta meta2 = result.getItemMeta();
        for (Enchantment ench : all) {
            int lvlFirst = enchFirst.getOrDefault(ench, 0);
            int lvlSecond = enchSecond.getOrDefault(ench, 0);
            int lvl = Math.max(lvlFirst, lvlSecond);
            if (lvlFirst > 0 && lvlFirst == lvlSecond && lvl < capOf(ench)) {
                lvl++;
            }
            if (lvl <= 0) {
                continue;
            }
            if (meta2 instanceof EnchantmentStorageMeta) {
                ((EnchantmentStorageMeta) meta2).addStoredEnchant(ench, lvl, true);
            } else {
                meta2.addEnchant(ench, lvl, true);
            }
        }
        result.setItemMeta(meta2);

        // кастомные чары: одинаковый уровень объединяется в +1 (бур — до II), применимость проверяем
        int a = combine(aFirst, aSecond, 1);
        int m = combine(mFirst, mSecond, 1);
        int d = combine(dFirst, dSecond, 2);
        if (!applicable("autosmelt", result)) a = Math.max(aFirst, 0);
        if (!applicable("magnet", result)) m = Math.max(mFirst, 0);
        if (!applicable("drill", result)) d = Math.max(dFirst, 0);
        if (isBook(result)) {
            a = combine(aFirst, aSecond, 1);
            m = combine(mFirst, mSecond, 1);
            d = combine(dFirst, dSecond, 2);
        }
        setCustom(result, a, m, d);

        event.setResult(result);
        inventory.setMaximumRepairCost(999);
        inventory.setRepairCost(Math.max(1, getConfig().getInt("anvil.xp-cost", 8)));
    }

    private int combine(int first, int second, int cap) {
        if (first > 0 && first == second && first < cap) {
            return first + 1;
        }
        return Math.max(first, second);
    }

    private int capOf(Enchantment enchantment) {
        String key = enchantment.getKey().getKey();
        if (getConfig().isSet("anvil.caps." + key)) {
            return getConfig().getInt("anvil.caps." + key);
        }
        return enchantment.getMaxLevel();
    }

    private Map<Enchantment, Integer> enchantsOf(ItemStack item) {
        Map<Enchantment, Integer> result = new HashMap<>();
        if (item == null || !item.hasItemMeta()) {
            return result;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta) {
            result.putAll(((EnchantmentStorageMeta) meta).getStoredEnchants());
        } else if (meta != null) {
            result.putAll(meta.getEnchants());
        }
        return result;
    }

    private boolean hasEnchant(ItemStack item, String key) {
        return enchantsOf(item).containsKey(ench(key));
    }

    private Enchantment ench(String key) {
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
    }

    // ------------------------------------------------------------ готовые предметы

    private ItemStack buildPick(String id) {
        String path = "picks." + id;
        Material material = Material.matchMaterial(getConfig().getString(path + ".material", "NETHERITE_PICKAXE"));
        ItemStack item = new ItemStack(material == null ? Material.NETHERITE_PICKAXE : material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(comp(getConfig().getString(path + ".name", id)));
            meta.addEnchant(ench("efficiency"), getConfig().getInt(path + ".efficiency", 5), true);
            meta.addEnchant(ench("fortune"), getConfig().getInt(path + ".fortune", 3), true);
            meta.addEnchant(ench("unbreaking"), getConfig().getInt(path + ".unbreaking", 3), true);
            if (getConfig().getBoolean(path + ".mending", true)) {
                meta.addEnchant(ench("mending"), 1, true);
            }
            item.setItemMeta(meta);
        }
        setCustom(item,
                getConfig().getBoolean(path + ".autosmelt", true) ? 1 : 0,
                getConfig().getBoolean(path + ".magnet", true) ? 1 : 0,
                getConfig().getInt(path + ".drill", 0));
        return item;
    }

    private ItemStack buildBook(String enchant) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        int level = enchant.equals("drill") ? 1 : 1;
        setCustom(book,
                enchant.equals("autosmelt") ? level : 0,
                enchant.equals("magnet") ? level : 0,
                enchant.equals("drill") ? level : 0);
        return book;
    }

    // ------------------------------------------------------------ команды

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("voidenchants.admin")) {
            msg(sender, "no-permission");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            msg(sender, "reloaded");
            return true;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("give")) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                msg(sender, "player-not-found");
                return true;
            }
            String what = args[2].toLowerCase(Locale.ROOT);
            ItemStack item;
            String display;
            if (what.equals("miner1") || what.equals("miner2") || what.equals("miner3")) {
                item = buildPick(what);
                display = getConfig().getString("picks." + what + ".name", what);
            } else if (what.equals("book_autosmelt")) {
                item = buildBook("autosmelt");
                display = plainName("autosmelt") + " (книга)";
            } else if (what.equals("book_magnet")) {
                item = buildBook("magnet");
                display = plainName("magnet") + " (книга)";
            } else if (what.equals("book_drill")) {
                item = buildBook("drill");
                display = plainName("drill") + " I (книга)";
            } else {
                msg(sender, "usage");
                return true;
            }
            int amount = 1;
            if (args.length >= 4) {
                try {
                    amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
                } catch (NumberFormatException ignored) {
                }
            }
            for (int i = 0; i < amount; i++) {
                target.getInventory().addItem(item.clone());
            }
            msg(sender, "given", "player", target.getName(), "item", color(display));
            msg(target, "received", "item", color(display));
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("enchant")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Только для игроков.");
                return true;
            }
            Player player = (Player) sender;
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                msg(player, "no-item");
                return true;
            }
            String enchant = args[1].toLowerCase(Locale.ROOT);
            int level = 1;
            if (args.length >= 3) {
                try {
                    level = Math.max(1, Integer.parseInt(args[2]));
                } catch (NumberFormatException ignored) {
                }
            }
            if (!applicable(enchant, hand)) {
                msg(player, "not-applicable");
                return true;
            }
            int cap = enchant.equals("drill") ? 2 : 1;
            setCustom(hand,
                    enchant.equals("autosmelt") ? Math.min(level, cap) : autosmelt(hand),
                    enchant.equals("magnet") ? Math.min(level, cap) : magnet(hand),
                    enchant.equals("drill") ? Math.min(level, cap) : drill(hand));
            msg(player, "enchanted", "enchant", plainName(enchant));
            return true;
        }
        sendHelp(sender);
        return true;
    }

    private void sendHelp(CommandSender sender) {
        for (String line : getConfig().getStringList("messages.help")) {
            sender.sendMessage(comp(getConfig().getString("messages.prefix", "") + line));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (!sender.hasPermission("voidenchants.admin")) {
            return result;
        }
        if (args.length == 1) {
            for (String sub : new String[]{"give", "enchant", "reload", "help"}) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) result.add(sub);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) result.add(online.getName());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (String what : new String[]{"miner1", "miner2", "miner3", "book_autosmelt", "book_magnet", "book_drill"}) {
                if (what.startsWith(args[2].toLowerCase(Locale.ROOT))) result.add(what);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("enchant")) {
            for (String ench : new String[]{"autosmelt", "magnet", "drill"}) {
                if (ench.startsWith(args[1].toLowerCase(Locale.ROOT))) result.add(ench);
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("enchant")) {
            result.add("1");
            if (args[1].equalsIgnoreCase("drill")) result.add("2");
        }
        return result;
    }
}
