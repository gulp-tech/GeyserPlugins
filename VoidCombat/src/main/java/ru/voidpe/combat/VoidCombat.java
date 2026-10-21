package ru.voidpe.combat;

import io.papermc.paper.event.player.PlayerElytraBoostEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * VoidCombat — режим боя для Bedrock-сервера VoidPE.
 *
 * <p>Игрок получает метку боя, когда наносит или получает PvP-урон. Пока
 * метка активна, плагин ограничивает предметы, которыми обычно спасаются
 * в бою: золотые яблоки, зелья исцеления и кастомные предметы VoidItems.
 * Фейерверки в бою запрещены всеми способами: рукой, элитрами и арбалетом.</p>
 *
 * <p>Кастомные предметы узнаются по скрытой PDC-метке VoidItems
 * ({@code voiditems:item_id}), поэтому переименованием их не подделать,
 * а жёсткой зависимости от VoidItems у плагина нет.</p>
 */
public final class VoidCombat extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final NamespacedKey VOID_ITEMS_ID = NamespacedKey.fromString("voiditems:item_id");

    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldownUntil = new HashMap<>();
    private final Map<UUID, Map<String, Long>> lastBlockMessage = new HashMap<>();
    private BukkitTask timerTask;

    private record CooldownItem(String key, String label, int seconds) {
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("combat") != null) {
            getCommand("combat").setExecutor(this);
            getCommand("combat").setTabCompleter(this);
        }
        timerTask = Bukkit.getScheduler().runTaskTimer(this, this::tickCombat, 20L, 20L);
        getLogger().info("VoidCombat включён: режим боя, кулдауны предметов и запрет фейерверков в бою.");
    }

    @Override
    public void onDisable() {
        if (timerTask != null) {
            timerTask.cancel();
        }
        combatUntil.clear();
        cooldownUntil.clear();
        lastBlockMessage.clear();
    }

    private Component comp(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(TextUtil.color(legacy))
                .decoration(TextDecoration.ITALIC, false);
    }

    private void msg(Player player, String key, String... pairs) {
        String text = getConfig().getString("messages." + key, "");
        if (text == null || text.isEmpty()) {
            return;
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace(pairs[i], pairs[i + 1]);
        }
        String prefix = getConfig().getString("messages.prefix", "");
        player.sendMessage(comp((prefix == null ? "" : prefix) + text));
    }

    private void msg(CommandSender sender, String key, String... pairs) {
        if (sender instanceof Player) {
            msg((Player) sender, key, pairs);
            return;
        }
        String text = getConfig().getString("messages." + key, "");
        if (text == null) {
            return;
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace(pairs[i], pairs[i + 1]);
        }
        sender.sendMessage(comp(text));
    }

    // ------------------------------------------------------------ режим боя

    public boolean isInCombat(Player player) {
        return player != null && combatLeftMillis(player.getUniqueId()) > 0;
    }

    private long combatLeftMillis(UUID uuid) {
        Long until = combatUntil.get(uuid);
        if (until == null) {
            return 0;
        }
        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            combatUntil.remove(uuid);
            return 0;
        }
        return left;
    }

    private int combatLeftSeconds(UUID uuid) {
        return (int) Math.ceil(combatLeftMillis(uuid) / 1000.0);
    }

    private void tagCombat(Player player, Player opponent) {
        if (!getConfig().getBoolean("combat.enabled", true)) {
            return;
        }
        if (getConfig().getBoolean("combat.ignore-creative-and-spectator", true)
                && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                || opponent.getGameMode() == GameMode.CREATIVE || opponent.getGameMode() == GameMode.SPECTATOR)) {
            return;
        }
        boolean wasInCombat = isInCombat(player);
        int duration = Math.max(1, getConfig().getInt("combat.duration-seconds", 15));
        combatUntil.put(player.getUniqueId(), System.currentTimeMillis() + duration * 1000L);
        if (!wasInCombat) {
            msg(player, player.equals(opponent) ? "combat-started" : "combat-started",
                    "{player}", opponent.getName());
        }
    }

    private void tickCombat() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = combatUntil.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                it.remove();
                continue;
            }
            long leftMillis = entry.getValue() - now;
            if (leftMillis <= 0) {
                it.remove();
                msg(player, "combat-ended");
                continue;
            }
            if (getConfig().getBoolean("combat.display.actionbar", true)) {
                String format = getConfig().getString("combat.display.actionbar-format",
                        "&#FF5555Режим боя: &f{seconds} сек.");
                int seconds = (int) Math.ceil(leftMillis / 1000.0);
                player.sendActionBar(comp(format.replace("{seconds}", String.valueOf(seconds))));
            }
        }
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player) {
            return (Player) damager;
        }
        if (damager instanceof Projectile) {
            ProjectileSource shooter = ((Projectile) damager).getShooter();
            if (shooter instanceof Player) {
                return (Player) shooter;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPvpDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player) || event.getFinalDamage() <= 0) {
            return;
        }
        Player victim = (Player) event.getEntity();
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        // Метка ставится после всех защит: если WorldGuard или другой плагин
        // отменил урон, событие сюда не попадёт (ignoreCancelled).
        tagCombat(victim, attacker);
        tagCombat(attacker, victim);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        combatUntil.remove(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        combatUntil.remove(uuid);
        cooldownUntil.remove(uuid);
        lastBlockMessage.remove(uuid);
    }

    // ------------------------------------------------------------ кулдауны

    private boolean cooldownsApply(Player player) {
        if (!getConfig().getBoolean("cooldowns.enabled", true)) {
            return false;
        }
        return !getConfig().getBoolean("cooldowns.only-in-combat", true) || isInCombat(player);
    }

    private String customItemId(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || VOID_ITEMS_ID == null || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        String id = meta.getPersistentDataContainer().get(VOID_ITEMS_ID, PersistentDataType.STRING);
        return id == null || id.isBlank() ? null : id.toLowerCase(Locale.ROOT);
    }

    private String itemLabel(ItemStack stack, String fallback) {
        if (stack != null && stack.hasItemMeta()) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null && meta.displayName() != null) {
                String plain = PlainTextComponentSerializer.plainText().serialize(meta.displayName()).trim();
                if (!plain.isEmpty()) {
                    return plain;
                }
            }
        }
        return fallback;
    }

    private int customCooldownSeconds(String id) {
        int seconds = getConfig().getInt("cooldowns.custom-items.items." + id,
                getConfig().getInt("cooldowns.custom-items.default-seconds", 60));
        if (seconds <= 0) {
            return 0;
        }
        int minimum = Math.max(0, getConfig().getInt("cooldowns.custom-items.minimum-seconds", 60));
        return Math.max(minimum, seconds);
    }

    private boolean isHealingPotion(ItemStack stack) {
        if (stack == null || !(stack.getItemMeta() instanceof PotionMeta)) {
            return false;
        }
        PotionMeta meta = (PotionMeta) stack.getItemMeta();
        PotionType base = meta.getBasePotionType();
        if (base == PotionType.HEALING || base == PotionType.STRONG_HEALING) {
            return true;
        }
        return PotionEffectType.INSTANT_HEALTH != null && meta.hasCustomEffect(PotionEffectType.INSTANT_HEALTH);
    }

    private CooldownItem resolveCooldownItem(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return null;
        }
        String customId = customItemId(stack);
        if (customId != null && getConfig().getBoolean("cooldowns.custom-items.enabled", true)) {
            // Кастомное зелье VoidItems может не быть в tracked-ids: при питье
            // оно всё равно получает кастомный кулдаун, а не зелья исцеления.
            boolean tracked = getConfig().getStringList("cooldowns.custom-items.tracked-ids")
                    .stream().anyMatch(id -> id.equalsIgnoreCase(customId));
            if (tracked || stack.getType() == Material.POTION) {
                int seconds = customCooldownSeconds(customId);
                if (seconds > 0) {
                    return new CooldownItem("custom:" + customId, itemLabel(stack, customId), seconds);
                }
            }
        }
        Material type = stack.getType();
        if (type == Material.ENCHANTED_GOLDEN_APPLE) {
            int seconds = getConfig().getInt("cooldowns.vanilla.enchanted-golden-apple", 180);
            return seconds > 0 ? new CooldownItem("enchanted-golden-apple",
                    getConfig().getString("item-names.enchanted-golden-apple", "Зачарованное золотое яблоко"), seconds) : null;
        }
        if (type == Material.GOLDEN_APPLE) {
            int seconds = getConfig().getInt("cooldowns.vanilla.golden-apple", 30);
            return seconds > 0 ? new CooldownItem("golden-apple",
                    getConfig().getString("item-names.golden-apple", "Золотое яблоко"), seconds) : null;
        }
        if ((type == Material.POTION || type == Material.SPLASH_POTION || type == Material.LINGERING_POTION)
                && isHealingPotion(stack)) {
            int seconds = getConfig().getInt("cooldowns.vanilla.healing-potion", 15);
            return seconds > 0 ? new CooldownItem("healing-potion",
                    getConfig().getString("item-names.healing-potion", "Зелье исцеления"), seconds) : null;
        }
        return null;
    }

    private long cooldownLeftMillis(UUID uuid, String key) {
        Map<String, Long> byItem = cooldownUntil.get(uuid);
        if (byItem == null) {
            return 0;
        }
        Long until = byItem.get(key);
        if (until == null) {
            return 0;
        }
        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            byItem.remove(key);
            if (byItem.isEmpty()) {
                cooldownUntil.remove(uuid);
            }
            return 0;
        }
        return left;
    }

    private boolean shouldSendBlockMessage(Player player, String key) {
        long now = System.currentTimeMillis();
        Map<String, Long> byKey = lastBlockMessage.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        Long last = byKey.get(key);
        if (last != null && now - last < 1000L) {
            return false;
        }
        byKey.put(key, now);
        return true;
    }

    /** true — предмет на кулдауне, использование надо отменить. */
    private boolean checkCooldown(Player player, CooldownItem item) {
        if (item == null || item.seconds() <= 0 || !cooldownsApply(player)) {
            return false;
        }
        long left = cooldownLeftMillis(player.getUniqueId(), item.key());
        if (left <= 0) {
            return false;
        }
        if (shouldSendBlockMessage(player, "cooldown:" + item.key())) {
            msg(player, "cooldown", "{item}", item.label(),
                    "{seconds}", String.valueOf((int) Math.ceil(left / 1000.0)));
        }
        return true;
    }

    private void startCooldown(Player player, CooldownItem item) {
        if (item == null || item.seconds() <= 0 || !cooldownsApply(player)) {
            return;
        }
        cooldownUntil.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .put(item.key(), System.currentTimeMillis() + item.seconds() * 1000L);
    }

    // ------------------------------------------------------------ использование предметов

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsumeCheck(PlayerItemConsumeEvent event) {
        CooldownItem item = resolveCooldownItem(event.getItem());
        if (item != null && checkCooldown(event.getPlayer(), item)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsumeDone(PlayerItemConsumeEvent event) {
        startCooldown(event.getPlayer(), resolveCooldownItem(event.getItem()));
    }

    /** Взрывные и оседающие зелья исцеления не пьют, а бросают. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionThrowCheck(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof ThrownPotion)) {
            return;
        }
        ProjectileSource shooter = event.getEntity().getShooter();
        if (!(shooter instanceof Player)) {
            return;
        }
        ItemStack stack = ((ThrownPotion) event.getEntity()).getItem();
        CooldownItem item = resolveCooldownItem(stack);
        if (item != null && item.key().equals("healing-potion")
                && checkCooldown((Player) shooter, item)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionThrowDone(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof ThrownPotion)) {
            return;
        }
        ProjectileSource shooter = event.getEntity().getShooter();
        if (!(shooter instanceof Player)) {
            return;
        }
        CooldownItem item = resolveCooldownItem(((ThrownPotion) event.getEntity()).getItem());
        if (item != null && item.key().equals("healing-potion")) {
            startCooldown((Player) shooter, item);
        }
    }

    /**
     * Активные кастомные предметы (трапка, пыль, дезориентация): если предмет
     * на боевом кулдауне — клик отменяется до VoidItems (он уважает отмену).
     * Если кулдауна нет — после клика проверяем, правда ли предмет потратился:
     * кулдаун включается только за успешное использование.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCustomInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        String customId = customItemId(hand);
        if (customId == null) {
            return;
        }
        CooldownItem item = resolveCooldownItem(hand);
        if (item == null || !item.key().startsWith("custom:")) {
            return;
        }
        if (checkCooldown(player, item)) {
            event.setCancelled(true);
            return;
        }
        if (!cooldownsApply(player)) {
            return;
        }
        int beforeAmount = hand.getAmount();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            ItemStack now = player.getInventory().getItemInMainHand();
            String nowId = customItemId(now);
            if (nowId == null || !nowId.equals(customId) || now.getAmount() < beforeAmount) {
                startCooldown(player, item);
            }
        });
    }

    /** Лом спавнеров срабатывает не кликом, а ломанием спавнера. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCustomLomCheck(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.SPAWNER) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!"spawner_lom".equals(customItemId(hand))) {
            return;
        }
        CooldownItem item = resolveCooldownItem(hand);
        if (item == null) {
            return;
        }
        if (checkCooldown(player, item)) {
            event.setCancelled(true);
            return;
        }
        if (!cooldownsApply(player)) {
            return;
        }
        int beforeAmount = hand.getAmount();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            ItemStack now = player.getInventory().getItemInMainHand();
            if (!"spawner_lom".equals(customItemId(now)) || now.getAmount() < beforeAmount) {
                startCooldown(player, item);
            }
        });
    }

    // ------------------------------------------------------------ фейерверки в бою

    private boolean blockFirework(Player player) {
        if (!getConfig().getBoolean("fireworks.block-in-combat", true) || !isInCombat(player)) {
            return false;
        }
        if (shouldSendBlockMessage(player, "firework")) {
            msg(player, "firework-blocked");
        }
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFireworkInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.FIREWORK_ROCKET) {
            return;
        }
        if (blockFirework(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onElytraBoost(PlayerElytraBoostEvent event) {
        if (blockFirework(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrossbowFirework(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        ItemStack consumable = event.getConsumable();
        if (consumable == null || consumable.getType() != Material.FIREWORK_ROCKET) {
            return;
        }
        if (blockFirework((Player) event.getEntity())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------ команда

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("voidcombat.admin")) {
                msg(sender, "no-permission");
                return true;
            }
            reloadConfig();
            msg(sender, "reloaded");
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(comp("&fИспользование: &b/combat reload &7— из консоли доступна только перезагрузка."));
            return true;
        }
        Player player = (Player) sender;
        if (isInCombat(player)) {
            msg(player, "combat-status-active", "{seconds}", String.valueOf(combatLeftSeconds(player.getUniqueId())));
        } else {
            msg(player, "combat-status-none");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission("voidcombat.admin")
                && "reload".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            result.add("reload");
        }
        return result;
    }
}
