package ru.voidpe.anticheat;

import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VoidAntiCheat — Bedrock-only античит для Purpur + Geyser/Floodgate.
 *
 * <p>Дизайн специально не копирует Java-античиты: Grim/Vulcan на Bedrock
 * обычно просто exempt-ят игрока, а Boar/Themis показывают правильный путь —
 * отдельные Bedrock-допуски, буферы вместо мгновенных банов, компенсация
 * лагов и наказания только за устойчивые серии нарушений.</p>
 *
 * <p>Оптимизация: на PlayerMoveEvent только O(1) примитивы и несколько
 * соседних блоков; миры не сканируются, история позиций не хранится,
 * тяжёлая работа (лог-файл/webhook) уходит в один фоновый поток.</p>
 */
public final class VoidAntiCheat extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Map<UUID, PlayerData> players = new HashMap<>();
    private final Map<CheckId, CheckSettings> settings = new EnumMap<>(CheckId.class);
    private final Set<UUID> alertsOff = new HashSet<>();

    private FloodgateHook floodgate;
    private RegionHook regions;
    private BukkitTask secondTask;
    private ExecutorService ioExecutor;
    private HttpClient httpClient;

    private Attribute movementSpeedAttribute;
    private Attribute interactionRangeAttribute;
    private PotionEffectType slowFallingEffect;
    private PotionEffectType jumpBoostEffect;
    private PotionEffectType levitationEffect;
    private Enchantment soulSpeedEnchant;
    private Enchantment depthStriderEnchant;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadCheckSettings();
        resolveRegistryEntries();
        floodgate = new FloodgateHook(getLogger());
        regions = new RegionHook(getLogger());
        ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "VoidAntiCheat-IO");
            thread.setDaemon(true);
            return thread;
        });
        httpClient = HttpClient.newHttpClient();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("vac") != null) {
            getCommand("vac").setExecutor(this);
            getCommand("vac").setTabCompleter(this);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            createData(player, false);
        }
        secondTask = Bukkit.getScheduler().runTaskTimer(this, this::secondTick, 20L, 20L);
        if (!floodgate.available() && getConfig().getBoolean("settings.bedrock-only", true)
                && !getConfig().getBoolean("settings.check-all-if-floodgate-missing", true)) {
            getLogger().warning("Floodgate недоступна, а fallback выключен: проверки Bedrock сейчас не работают");
        }
        getLogger().info("VoidAntiCheat включён: Bedrock-only=" + getConfig().getBoolean("settings.bedrock-only", true)
                + ", Floodgate=" + floodgate.available() + ", проверок=" + settings.size());
    }

    @Override
    public void onDisable() {
        if (secondTask != null) {
            secondTask.cancel();
        }
        if (ioExecutor != null) {
            ioExecutor.shutdownNow();
        }
        players.clear();
        alertsOff.clear();
    }

    private void resolveRegistryEntries() {
        movementSpeedAttribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("movement_speed"));
        if (movementSpeedAttribute == null) {
            movementSpeedAttribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.movement_speed"));
        }
        interactionRangeAttribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("entity_interaction_range"));
        slowFallingEffect = Registry.EFFECT.get(NamespacedKey.minecraft("slow_falling"));
        jumpBoostEffect = Registry.EFFECT.get(NamespacedKey.minecraft("jump_boost"));
        levitationEffect = Registry.EFFECT.get(NamespacedKey.minecraft("levitation"));
        soulSpeedEnchant = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("soul_speed"));
        depthStriderEnchant = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("depth_strider"));
    }

    private void loadCheckSettings() {
        settings.clear();
        for (CheckId check : CheckId.values()) {
            settings.put(check, CheckSettings.load(getConfig(), check));
        }
    }

    private CheckSettings setting(CheckId check) {
        return settings.get(check);
    }

    private Component comp(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(TextUtil.color(legacy))
                .decoration(TextDecoration.ITALIC, false);
    }

    private String plain(String legacy) {
        return PlainTextComponentSerializer.plainText().serialize(comp(legacy));
    }

    // ------------------------------------------------------------ данные игрока

    private PlayerData createData(Player player, boolean joinGrace) {
        PlayerData data = new PlayerData(player.getUniqueId());
        boolean bedrockOnly = getConfig().getBoolean("settings.bedrock-only", true);
        if (!bedrockOnly) {
            data.bedrock = true;
        } else if (floodgate.available()) {
            data.bedrock = floodgate.isBedrock(player.getUniqueId());
        } else {
            data.bedrock = getConfig().getBoolean("settings.check-all-if-floodgate-missing", true);
        }
        if (data.bedrock && floodgate.available()) {
            data.inputMode = floodgate.inputMode(player.getUniqueId());
            data.deviceOs = floodgate.deviceOs(player.getUniqueId());
        }
        data.bypassAll = player.hasPermission("voidanticheat.bypass");
        data.excludedHere = isExcluded(player.getLocation());
        long now = System.currentTimeMillis();
        if (joinGrace) {
            data.graceUntilMillis = now + getConfig().getLong("settings.join-grace-ms", 3500L);
        }
        data.resetMovement(player.getLocation(), Bukkit.getCurrentTick(), System.nanoTime(), player.isOnGround());
        updateSafeLocation(player, player.getLocation(), data);
        players.put(player.getUniqueId(), data);
        return data;
    }

    private PlayerData data(Player player) {
        PlayerData data = players.get(player.getUniqueId());
        return data == null ? createData(player, false) : data;
    }

    private boolean isBedrockOnlyMode() {
        return getConfig().getBoolean("settings.bedrock-only", true);
    }

    private boolean shouldCheck(Player player, PlayerData data) {
        if (!getConfig().getBoolean("settings.enabled", true) || player == null || data == null) {
            return false;
        }
        if (isBedrockOnlyMode() && !data.bedrock) {
            return false;
        }
        if (data.bypassAll || data.excludedHere || !player.isOnline() || player.isDead()) {
            return false;
        }
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }

    private boolean isExcluded(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        String world = location.getWorld().getName().toLowerCase(Locale.ROOT);
        for (String excluded : getConfig().getStringList("settings.excluded-worlds")) {
            if (excluded.equalsIgnoreCase(world)) {
                return true;
            }
        }
        return regions != null && regions.inRegion(location,
                getConfig().getStringList("settings.excluded-worldguard-regions"));
    }

    private boolean inGrace(PlayerData data) {
        return System.currentTimeMillis() < data.graceUntilMillis;
    }

    private void grantGrace(PlayerData data, long millis) {
        data.graceUntilMillis = Math.max(data.graceUntilMillis, System.currentTimeMillis() + Math.max(0L, millis));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        createData(event.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        players.remove(event.getPlayer().getUniqueId());
        alertsOff.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerData data = data(event.getPlayer());
        if (event.getTo() != null) {
            grantGrace(data, getConfig().getLong("settings.teleport-grace-ms", 1750L));
            data.resetMovement(event.getTo(), Bukkit.getCurrentTick(), System.nanoTime(), event.getPlayer().isOnGround());
            updateSafeLocation(event.getPlayer(), event.getTo(), data);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerData data = data(event.getPlayer());
        grantGrace(data, getConfig().getLong("settings.respawn-grace-ms", 3500L));
        data.resetMovement(event.getRespawnLocation(), Bukkit.getCurrentTick(), System.nanoTime(), true);
        data.safeLocation = event.getRespawnLocation().clone();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        PlayerData data = data(event.getPlayer());
        grantGrace(data, getConfig().getLong("settings.world-change-grace-ms", 2500L));
        data.resetMovement(event.getPlayer().getLocation(), Bukkit.getCurrentTick(), System.nanoTime(),
                event.getPlayer().isOnGround());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        PlayerData data = data(event.getPlayer());
        grantGrace(data, 1000L);
        data.resetMovement(event.getPlayer().getLocation(), Bukkit.getCurrentTick(), System.nanoTime(),
                event.getPlayer().isOnGround());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        PlayerData data = players.get(event.getEntity().getUniqueId());
        if (data != null) {
            data.violations.clear();
            grantGrace(data, getConfig().getLong("settings.respawn-grace-ms", 3500L));
        }
    }

    // ------------------------------------------------------------ нарушения

    /**
     * Добавляет VL. Возвращает true, если событие по этой проверке уже надо отменить.
     * Setback для движения не делается прямо здесь: ставится флаг, а обработчик
     * PlayerMoveEvent применит его через event.setTo без лишнего телепорта.
     */
    private boolean flag(Player player, PlayerData data, CheckId check, double points, String details) {
        if (points <= 0 || !shouldCheck(player, data)) {
            return false;
        }
        CheckSettings cfg = setting(check);
        if (cfg == null || !cfg.enabled() || player.hasPermission(check.bypassPermission())) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long lastFlag = data.lastFlagMillis.get(check);
        if (lastFlag != null && now - lastFlag < 120L) {
            return cfg.shouldCancel(data.vl(check));
        }
        data.lastFlagMillis.put(check, now);

        double before = data.vl(check);
        double after = Math.min(cfg.maxVl(), before + points);
        data.setVl(check, after);
        data.lastCheck = check;
        data.lastDetails = details;
        data.lastViolationMillis = now;

        maybeAlert(player, data, check, cfg, after, details, now);

        if (cfg.shouldSetback(after) && isMovementCheck(check)) {
            Long lastSetback = data.lastSetbackMillis.get(check);
            if (lastSetback == null || now - lastSetback >= getConfig().getLong("settings.setback-cooldown-ms", 900L)) {
                data.setbackRequested = true;
                data.lastSetbackMillis.put(check, now);
            }
        }

        if (cfg.shouldPunish(after)) {
            Long lastPunish = data.lastPunishMillis.get(check);
            long cooldown = getConfig().getLong("settings.punish-cooldown-seconds", 60L) * 1000L;
            if (lastPunish == null || now - lastPunish >= cooldown) {
                data.lastPunishMillis.put(check, now);
                runPunishments(player, check, cfg, after, details);
                data.setVl(check, cfg.maxVl() * 0.45);
            }
        }
        return cfg.shouldCancel(after);
    }

    private boolean isMovementCheck(CheckId check) {
        return switch (check) {
            case SPEED, FLY, STEP, JESUS, SPIDER, NOCLIP, TIMER, ELYTRA, VEHICLE -> true;
            default -> false;
        };
    }

    private void maybeAlert(Player player, PlayerData data, CheckId check, CheckSettings cfg,
                            double vl, String details, long now) {
        if (vl < getConfig().getDouble("settings.alert-min-vl", 1.0)) {
            return;
        }
        Long last = data.lastAlertMillis.get(check);
        if (last != null && now - last < getConfig().getLong("settings.alert-cooldown-ms", 2500L)) {
            return;
        }
        data.lastAlertMillis.put(check, now);

        String text = replaceAlert(getConfig().getString("messages.alert", ""), player, check, vl, cfg.maxVl(), details);
        String plainText = plain(getConfig().getString("messages.prefix", "") + text);
        if (getConfig().getBoolean("settings.log-console", true)) {
            getLogger().info(plainText);
        }
        if (getConfig().getBoolean("settings.log-file", true)) {
            writeLogLine(plainText);
        }
        if (getConfig().getBoolean("settings.staff-alerts", true)) {
            String hover = replaceAlert(getConfig().getString("messages.alert-hover", ""), player, check, vl, cfg.maxVl(), details);
            Component component = comp(getConfig().getString("messages.prefix", "") + text)
                    .clickEvent(ClickEvent.runCommand("/vac info " + player.getName()))
                    .hoverEvent(HoverEvent.showText(comp(hover)));
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("voidanticheat.alerts") && !alertsOff.contains(staff.getUniqueId())) {
                    staff.sendMessage(component);
                }
            }
        }
        if (getConfig().getBoolean("webhook.enabled", false)
                && vl >= getConfig().getDouble("webhook.min-vl", 10.0)) {
            sendWebhook(plainText);
        }
    }

    private String replaceAlert(String text, Player player, CheckId check, double vl, double maxVl, String details) {
        if (text == null) {
            return "";
        }
        return text.replace("{player}", player.getName())
                .replace("{check}", check.display())
                .replace("{vl}", formatVl(vl))
                .replace("{max_vl}", formatVl(maxVl))
                .replace("{details}", details == null ? "" : details)
                .replace("{ping}", String.valueOf(player.getPing()))
                .replace("{tps}", formatVl(currentTps()));
    }

    private void runPunishments(Player player, CheckId check, CheckSettings cfg, double vl, String details) {
        for (String raw : cfg.punishCommands()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String command = raw.replace("%player%", player.getName())
                    .replace("%check%", check.display())
                    .replace("%vl%", formatVl(vl))
                    .replace("%max_vl%", formatVl(cfg.maxVl()))
                    .replace("%details%", details == null ? "" : details)
                    .replace("%ping%", String.valueOf(player.getPing()))
                    .replace("%tps%", formatVl(currentTps()))
                    .replace("%world%", player.getWorld().getName());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
        if (getConfig().getBoolean("webhook.enabled", false)) {
            sendWebhook(plain(getConfig().getString("messages.prefix", "")
                    + "&cНаказание: " + player.getName() + " — " + check.display() + " VL " + formatVl(vl)
                    + " (" + details + ")"));
        }
    }

    private void writeLogLine(String plainText) {
        if (ioExecutor == null || ioExecutor.isShutdown()) {
            return;
        }
        String line = "[" + LocalDateTime.now().format(LOG_TIME) + "] " + plainText + System.lineSeparator();
        ioExecutor.execute(() -> {
            try {
                File file = new File(getDataFolder(), "violations.log");
                Files.writeString(file.toPath(), line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
                // Лог не должен ронять античит.
            }
        });
    }

    private void sendWebhook(String plainText) {
        String url = getConfig().getString("webhook.url", "");
        if (url == null || url.isBlank() || ioExecutor == null || ioExecutor.isShutdown()) {
            return;
        }
        String json = "{\"content\":\"" + escapeJson(plainText) + "\"}";
        ioExecutor.execute(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            } catch (Throwable ignored) {
                // Webhook не должен влиять на игровой поток.
            }
        });
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private String formatVl(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.001) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private double currentTps() {
        double[] tps = Bukkit.getTPS();
        return tps.length == 0 ? 20.0 : tps[0];
    }

    // ------------------------------------------------------------ setback и безопасная точка

    private void updateSafeLocation(Player player, Location location, PlayerData data) {
        if (location == null || location.getWorld() == null || player.getVehicle() != null || player.isGliding()) {
            return;
        }
        if (!player.isOnGround() || player.isFlying()) {
            return;
        }
        Block below = blockAt(location, -0.25);
        if (below == null || !below.getType().isSolid() || below.isLiquid()) {
            return;
        }
        if (isLiquid(blockAt(location, 0.05))) {
            return;
        }
        data.safeLocation = location.clone();
        data.safeUpdatedMillis = System.currentTimeMillis();
    }

    private Location safeOr(PlayerData data, Location fallback) {
        if (data.safeLocation != null && data.safeLocation.getWorld() != null) {
            Location safe = data.safeLocation.clone();
            safe.setYaw(fallback.getYaw());
            safe.setPitch(fallback.getPitch());
            return safe;
        }
        return fallback.clone();
    }

    private boolean setbackPlayer(Player player, PlayerData data) {
        if (data.safeLocation == null || data.safeLocation.getWorld() == null) {
            return false;
        }
        Location safe = data.safeLocation.clone();
        safe.setYaw(player.getLocation().getYaw());
        safe.setPitch(player.getLocation().getPitch());
        if (player.getVehicle() != null) {
            player.leaveVehicle();
        }
        boolean ok = player.teleport(safe);
        if (ok) {
            grantGrace(data, 650L);
            data.resetMovement(safe, Bukkit.getCurrentTick(), System.nanoTime(), true);
            data.setbackRequested = false;
        }
        return ok;
    }

    // ------------------------------------------------------------ движение

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerData data = data(player);
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        if (!shouldCheck(player, data)) {
            data.resetMovement(to, Bukkit.getCurrentTick(), System.nanoTime(), player.isOnGround());
            return;
        }

        long nowNanos = System.nanoTime();
        long nowMillis = System.currentTimeMillis();
        int tick = Bukkit.getCurrentTick();

        if (!isFinite(to.getX()) || !isFinite(to.getY()) || !isFinite(to.getZ())
                || !isFinite(to.getYaw()) || !isFinite(to.getPitch())
                || to.getPitch() < -90.5f || to.getPitch() > 90.5f) {
            flag(player, data, CheckId.BADPACKETS, 2.0, "невозможные координаты/углы");
            event.setTo(event.getFrom().clone());
            data.resetMovement(event.getFrom(), tick, nowNanos, player.isOnGround());
            return;
        }

        if (!data.hasLast || !to.getWorld().getName().equals(data.lastWorld)) {
            data.resetMovement(to, tick, nowNanos, player.isOnGround());
            updateSafeLocation(player, to, data);
            return;
        }

        double dx = to.getX() - data.lastX;
        double dy = to.getY() - data.lastY;
        double dz = to.getZ() - data.lastZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        boolean tickChanged = tick != data.lastServerTick;
        int tickDelta = tickChanged ? Math.max(1, tick - data.lastServerTick) : 0;

        float yawDelta = Math.abs(angleDifference(to.getYaw(), data.lastYaw));
        float pitchDelta = Math.abs(to.getPitch() - data.lastPitch);
        if (yawDelta > 125f || pitchDelta > 70f) {
            data.lastBigRotationMillis = nowMillis;
            data.rotationBeforeAttack = yawDelta;
        }

        if (horizontal > 0.0001 || Math.abs(dy) > 0.0001) {
            data.timerPackets++;
        }

        double teleportDelta = getConfig().getDouble("movement.teleport-delta-blocks", 24.0);
        if ((horizontal > teleportDelta || Math.abs(dy) > teleportDelta) && !inGrace(data)) {
            flag(player, data, CheckId.BADPACKETS, 2.0,
                    "скачок позиции " + formatVl(Math.max(horizontal, Math.abs(dy))) + " бл.");
            event.setTo(safeOr(data, event.getFrom()));
            data.resetMovement(event.getTo(), tick, nowNanos, player.isOnGround());
            return;
        }

        // Транспорт проверяется отдельно: физика игрока-пассажира тут не работает.
        if (player.getVehicle() != null) {
            vehicleCheck(player, data, to, nowNanos);
            data.rememberPosition(to, tick, nowNanos, player.isOnGround());
            return;
        }

        MovementContext context = movementContext(player, to);

        // Позиции после телепортов/нокбэков/входа не штрафуем, только перебазируемся.
        if (inGrace(data) || currentTps() < getConfig().getDouble("settings.min-tps-for-movement", 16.0)) {
            data.resetAirState();
            updateSafeLocation(player, to, data);
            data.rememberPosition(to, tick, nowNanos, player.isOnGround());
            return;
        }

        evaluateVelocity(player, data, dx, dz, tick);

        if (player.isGliding()) {
            elytraCheck(player, data, to, dy, horizontal, nowNanos, tickChanged, context);
            data.rememberPosition(to, tick, nowNanos, player.isOnGround());
            return;
        }

        if (!context.exemptSpeed()) {
            speedCheck(player, data, to, horizontal, nowNanos, tickDelta, context);
        }

        if (tickChanged) {
            verticalChecks(player, data, to, dy, horizontal, tickDelta, context, nowMillis);
            noclipCheck(player, data, to, context);
        }

        if (data.setbackRequested) {
            Location safe = safeOr(data, to);
            event.setTo(safe);
            data.resetMovement(safe, tick, nowNanos, true);
            data.setbackRequested = false;
            return;
        }

        updateSafeLocation(player, to, data);
        data.rememberPosition(to, tick, nowNanos, player.isOnGround());
    }

    private record MovementContext(boolean inFluid, boolean climbable, boolean cobweb,
                                   boolean bubbleColumn, boolean levitation, boolean slowFalling) {
        boolean exemptSpeed() {
            return false;
        }

        boolean exemptVertical() {
            return inFluid || climbable || cobweb || bubbleColumn || levitation;
        }
    }

    private MovementContext movementContext(Player player, Location to) {
        Block feet = blockAt(to, 0.05);
        Block below = blockAt(to, -0.25);
        boolean inFluid = player.isInWater() || isLiquid(feet) || isLiquid(below);
        boolean climbable = isClimbable(feet) || isClimbable(blockAt(to, 1.05));
        boolean cobweb = feet != null && (feet.getType() == Material.COBWEB || feet.getType() == Material.POWDER_SNOW);
        boolean bubble = feet != null && feet.getType() == Material.BUBBLE_COLUMN
                || below != null && below.getType() == Material.BUBBLE_COLUMN;
        return new MovementContext(inFluid, climbable, cobweb, bubble,
                effect(player, levitationEffect) != null,
                effect(player, slowFallingEffect) != null);
    }

    private void speedCheck(Player player, PlayerData data, Location to, double horizontal,
                            long nowNanos, int tickDelta, MovementContext context) {
        if (data.speedRefillNanos == 0L) {
            data.speedRefillNanos = nowNanos;
            data.speedTokens = getConfig().getDouble("movement.speed-token-cap", 4.5);
        }
        double elapsed = (nowNanos - data.speedRefillNanos) / 1_000_000_000.0;
        data.speedRefillNanos = nowNanos;
        if (elapsed > 0.60) {
            // Долгий лаг/фриз: не превращаем наверстывание пакетов в Speed.
            data.speedTokens = Math.max(data.speedTokens, 2.0);
            return;
        }
        double allowed = allowedHorizontalBps(player, to, data, context);
        double cap = getConfig().getDouble("movement.speed-token-cap", 4.5);
        // Пакеты после сетевого всплеска могут обрабатываться пачкой в одном тике.
        // Игрок же перемещался по серверным тикам, поэтому время не занижаем.
        double effectiveElapsed = Math.max(elapsed, Math.max(0, tickDelta) / 20.0);
        data.speedTokens = Math.min(cap, data.speedTokens + allowed * Math.max(0.0, effectiveElapsed));
        data.speedTokens -= horizontal;
        if (effectiveElapsed > 0.004) {
            data.lastHorizontalBps = horizontal / effectiveElapsed;
        }
        double burst = getConfig().getDouble("movement.speed-burst-blocks", 1.30);
        if (data.speedTokens < -burst && horizontal > 0.05) {
            double excess = -data.speedTokens - burst;
            flag(player, data, CheckId.SPEED, Math.min(3.0, 0.8 + excess * 0.45),
                    "горизонтальная скорость " + formatVl(data.lastHorizontalBps) + " бл/с, лимит ~"
                            + formatVl(allowed) + " бл/с");
            data.speedTokens = -burst * 0.45;
        }
    }

    private double allowedHorizontalBps(Player player, Location to, PlayerData data, MovementContext context) {
        if (player.isFlying() || player.getVehicle() != null) {
            return 999.0;
        }
        if (context.inFluid()) {
            double base = player.isSwimming() ? 5.9 : 4.7;
            int depthStrider = enchantLevel(player.getInventory().getBoots(), depthStriderEnchant);
            base += Math.min(3, depthStrider) * 0.65;
            return applyLagTolerance(base);
        }
        double attribute = attributeValue(player, movementSpeedAttribute, 0.1);
        double bps = attribute * 43.17;
        if (player.isSneaking()) {
            bps = Math.min(bps, 1.75);
        } else if (player.isSprinting()) {
            bps *= 1.30;
        }
        if (!player.isOnGround()) {
            // Bedrock спринт-прыжки и сохранение инерции шумнее Java модели.
            bps *= 1.30;
        }
        Block below = blockAt(to, -0.35);
        Material belowType = below == null ? Material.AIR : below.getType();
        if (isIce(belowType)) {
            double multiplier = belowType == Material.BLUE_ICE ? 3.0
                    : belowType == Material.PACKED_ICE ? 2.55 : 2.15;
            bps *= player.isOnGround() ? multiplier * 0.82 : multiplier;
        } else if (belowType == Material.SOUL_SAND || belowType == Material.SOUL_SOIL) {
            int soulSpeed = enchantLevel(player.getInventory().getBoots(), soulSpeedEnchant());
            if (soulSpeed > 0) {
                bps *= 1.0 + soulSpeed * 0.34;
            }
        }
        if (System.currentTimeMillis() < data.velocityGraceUntilMillis) {
            Vector velocity = player.getVelocity();
            double velocityBps = Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ()) * 20.0;
            bps = Math.max(bps, velocityBps * 1.45);
        }
        return Math.max(3.4, Math.min(42.0, applyLagTolerance(bps)));
    }

    private Enchantment soulSpeedEnchant() {
        return soulSpeedEnchant;
    }

    private double applyLagTolerance(double base) {
        double tps = currentTps();
        double tpsFactor = 1.0 + Math.max(0.0, 19.0 - tps) * 0.035;
        // У Geyser ping часто 0/локальный, поэтому это только мягкая добавка.
        double pingFactor = 1.0;
        return base * tpsFactor * pingFactor;
    }

    private void verticalChecks(Player player, PlayerData data, Location to, double rawDy,
                                double horizontal, int tickDelta, MovementContext context, long nowMillis) {
        boolean onGround = player.isOnGround();
        double dy = rawDy / Math.max(1, tickDelta);

        if (player.isFlying() || context.exemptVertical()) {
            if (context.inFluid()) {
                jesusCheck(player, data, to, horizontal);
            }
            data.resetAirState();
            data.lastDeltaY = dy;
            return;
        }

        if (onGround) {
            if (!data.lastOnGround) {
                landingCheck(player, data, to);
            } else {
                // Step виден именно как «земля → земля» с подъёмом выше ванильного за тик.
                stepCheck(player, data, dy, tickDelta, nowMillis);
            }
            data.resetAirState();
            data.peakY = to.getY();
            data.lastDeltaY = dy;
            return;
        }

        if (data.lastOnGround) {
            data.airTicks = 0;
            data.peakY = Math.max(data.lastY, to.getY());
        }
        data.airTicks += Math.max(1, tickDelta);
        data.peakY = Math.max(data.peakY, to.getY());

        if (tickDelta > 3) {
            data.resetAirState();
            data.lastDeltaY = dy;
            return;
        }

        boolean afterVelocityGrace = nowMillis >= data.velocityGraceUntilMillis;

        // Hover: в воздухе почти нет вертикального движения слишком долго.
        double hoverMaxDy = getConfig().getDouble("movement.hover-max-dy", 0.040);
        if (data.airTicks >= 8 && Math.abs(dy) <= hoverMaxDy && afterVelocityGrace && !context.slowFalling()) {
            data.hoverStreak++;
            if (data.hoverStreak >= getConfig().getInt("movement.hover-ticks", 5)) {
                flag(player, data, CheckId.FLY, 1.0,
                        "зависание в воздухе " + data.hoverStreak + " тиков, dy=" + formatVl(dy));
                data.hoverStreak = 0;
            }
        } else {
            data.hoverStreak = 0;
        }

        // Подъём: после первых тиков прыжка dy должен быстро падать.
        int jumpAmplifier = effectAmplifier(player, jumpBoostEffect);
        double allowedUp = data.airTicks <= 2 ? 0.50 + jumpAmplifier * 0.115 : 0.145;
        if (dy > allowedUp + 0.075 && afterVelocityGrace && !context.slowFalling()) {
            data.ascentStreak++;
            if (data.ascentStreak >= getConfig().getInt("movement.ascent-ticks", 3)) {
                flag(player, data, CheckId.FLY, 1.0,
                        "подъём без прыжка/нокбэка, dy=" + formatVl(dy) + " бл/тик");
                data.ascentStreak = 0;
            }
        } else {
            data.ascentStreak = 0;
        }

        // Слишком медленное падение после разгона вниз.
        if (data.airTicks >= 14 && dy > -0.22 && data.lastDeltaY < 0.02 && afterVelocityGrace
                && !context.slowFalling()) {
            data.slowFallStreak++;
            if (data.slowFallStreak >= getConfig().getInt("movement.slow-fall-ticks", 6)) {
                flag(player, data, CheckId.FLY, 1.0,
                        "слишком медленное падение, dy=" + formatVl(dy) + " бл/тик");
                data.slowFallStreak = 0;
            }
        } else {
            data.slowFallStreak = 0;
        }

        // Spider/Climb: подъём у стены без лестницы/лианы/воды.
        if (dy > 0.075 && dy < 0.68 && afterVelocityGrace && isNearWall(to)
                && !context.climbable() && !context.inFluid()) {
            data.climbStreak++;
            if (data.climbStreak >= getConfig().getInt("movement.climb-ticks", 8)) {
                flag(player, data, CheckId.SPIDER, 1.0,
                        "подъём по стене без лестницы, dy=" + formatVl(dy));
                data.climbStreak = 0;
            }
        } else {
            data.climbStreak = 0;
        }

        data.lastDeltaY = dy;
    }

    private void landingCheck(Player player, PlayerData data, Location to) {
        double drop = data.peakY - to.getY();
        if (drop < getConfig().getDouble("movement.nofall-min-drop", 5.0)) {
            return;
        }
        Block below = blockAt(to, -0.25);
        Material belowType = below == null ? Material.AIR : below.getType();
        if (belowType == Material.SLIME_BLOCK || belowType == Material.HONEY_BLOCK
                || belowType == Material.POWDER_SNOW || isLiquid(below)) {
            return;
        }
        double serverFall = player.getFallDistance();
        double ratio = getConfig().getDouble("movement.nofall-server-fall-ratio", 0.30);
        if (serverFall < drop * ratio) {
            flag(player, data, CheckId.NOFALL, 1.2,
                    "падение " + formatVl(drop) + " бл., серверная дистанция " + formatVl(serverFall));
        }
    }

    private void stepCheck(Player player, PlayerData data, double dy, int tickDelta, long nowMillis) {
        if (tickDelta != 1 || nowMillis < data.velocityGraceUntilMillis) {
            return;
        }
        if (dy >= getConfig().getDouble("movement.step-min-dy", 0.96)
                && effect(player, jumpBoostEffect) == null) {
            flag(player, data, CheckId.STEP, 1.2,
                    "мгновенный подъём на " + formatVl(dy) + " бл. за тик");
        }
    }

    private void jesusCheck(Player player, PlayerData data, Location to, double horizontal) {
        Block below = blockAt(to, -0.25);
        Block feet = blockAt(to, 0.05);
        boolean standingOnLiquid = isLiquid(below) && !isLiquid(feet)
                && player.isOnGround() && !player.isSwimming();
        // Frost Walker превращает воду под ногами в FROSTED_ICE — это уже не liquid.
        if (standingOnLiquid && horizontal > 0.015) {
            data.jesusTicks++;
            if (data.jesusTicks >= getConfig().getInt("movement.jesus-ticks", 7)) {
                flag(player, data, CheckId.JESUS, 1.0, "ходьба по поверхности жидкости");
                data.jesusTicks = 0;
            }
        } else {
            data.jesusTicks = 0;
        }
    }

    private void noclipCheck(Player player, PlayerData data, Location to, MovementContext context) {
        if (context.inFluid() || context.climbable()) {
            data.noclipStreak = 0;
            return;
        }
        Block feet = blockAt(to, 0.10);
        Block head = blockAt(to, 1.55);
        boolean inside = isHardSolid(feet) && isHardSolid(head);
        if (inside) {
            data.noclipStreak++;
            if (data.noclipStreak >= getConfig().getInt("movement.noclip-ticks", 3)) {
                flag(player, data, CheckId.NOCLIP, 1.2,
                        "позиция внутри твёрдых блоков " + name(feet) + "/" + name(head));
                data.noclipStreak = 0;
            }
        } else {
            data.noclipStreak = 0;
        }
    }

    private void elytraCheck(Player player, PlayerData data, Location to, double rawDy,
                             double horizontal, long nowNanos, boolean tickChanged,
                             MovementContext context) {
        long nowMillis = System.currentTimeMillis();
        if (tickChanged && !context.inFluid() && nowMillis >= data.elytraGraceUntilMillis
                && nowMillis >= data.velocityGraceUntilMillis) {
            double dy = rawDy;
            if (dy > getConfig().getDouble("elytra.climb-dy-per-tick", 0.55)) {
                data.ascentStreak++;
                if (data.ascentStreak >= getConfig().getInt("elytra.climb-ticks", 4)) {
                    flag(player, data, CheckId.ELYTRA, 1.2,
                            "набор высоты на элитре без фейерверка, dy=" + formatVl(dy));
                    data.ascentStreak = 0;
                }
            } else {
                data.ascentStreak = 0;
            }
        }
        if (data.speedRefillNanos > 0L) {
            double elapsed = (nowNanos - data.speedRefillNanos) / 1_000_000_000.0;
            if (elapsed > 0.01 && horizontal / elapsed > getConfig().getDouble("elytra.max-horizontal-bps", 118.0)) {
                flag(player, data, CheckId.ELYTRA, 1.0,
                        "скорость элитры " + formatVl(horizontal / elapsed) + " бл/с");
            }
        }
    }

    private void vehicleCheck(Player player, PlayerData data, Location to, long nowNanos) {
        Entity vehicle = player.getVehicle();
        if (!(vehicle instanceof Boat)) {
            data.vehicleLastNanos = 0L;
            return;
        }
        if (data.vehicleLastNanos == 0L || !to.getWorld().getName().equals(data.vehicleWorld)) {
            data.vehicleWorld = to.getWorld().getName();
            data.vehicleX = to.getX();
            data.vehicleZ = to.getZ();
            data.vehicleLastNanos = nowNanos;
            data.vehicleTokens = getConfig().getDouble("vehicle.burst-blocks", 3.0) + 2.0;
            return;
        }
        double elapsed = (nowNanos - data.vehicleLastNanos) / 1_000_000_000.0;
        data.vehicleLastNanos = nowNanos;
        double dx = to.getX() - data.vehicleX;
        double dz = to.getZ() - data.vehicleZ;
        data.vehicleX = to.getX();
        data.vehicleZ = to.getZ();
        if (elapsed <= 0.001 || elapsed > 0.60) {
            return;
        }
        Block below = blockAt(vehicle.getLocation(), -0.35);
        Material belowType = below == null ? Material.AIR : below.getType();
        double max;
        if (belowType == Material.BLUE_ICE) {
            max = getConfig().getDouble("vehicle.blue-ice-max-bps", 48.0);
        } else if (belowType == Material.PACKED_ICE) {
            max = getConfig().getDouble("vehicle.packed-ice-max-bps", 40.0);
        } else if (isIce(belowType)) {
            max = getConfig().getDouble("vehicle.ice-max-bps", 34.0);
        } else if (isLiquid(below) || vehicle.isInWater()) {
            max = getConfig().getDouble("vehicle.water-max-bps", 16.0);
        } else {
            max = getConfig().getDouble("vehicle.ground-max-bps", 14.0);
        }
        double burst = getConfig().getDouble("vehicle.burst-blocks", 3.0);
        data.vehicleTokens = Math.min(burst + 2.0, data.vehicleTokens + max * elapsed);
        data.vehicleTokens -= Math.sqrt(dx * dx + dz * dz);
        if (data.vehicleTokens < -burst) {
            flag(player, data, CheckId.VEHICLE, 1.2,
                    "скорость лодки " + formatVl(Math.sqrt(dx * dx + dz * dz) / elapsed)
                            + " бл/с, лимит ~" + formatVl(max));
            data.vehicleTokens = -burst * 0.45;
        }
    }

    // ------------------------------------------------------------ velocity

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        PlayerData data = data(player);
        if (!shouldCheck(player, data)) {
            return;
        }
        grantGrace(data, getConfig().getLong("settings.velocity-grace-ms", 900L));
        data.velocityGraceUntilMillis = System.currentTimeMillis()
                + getConfig().getLong("settings.velocity-grace-ms", 900L);
        Vector velocity = event.getVelocity();
        double horizontal = Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ());
        int ticks = Math.max(1, getConfig().getInt("combat.velocity-check-ticks", 5));
        if (horizontal > 0.16) {
            double expected = 0.0;
            double current = horizontal;
            for (int i = 0; i < ticks; i++) {
                expected += current;
                current *= 0.91;
            }
            data.velocityPending = true;
            data.velocityStartMillis = System.currentTimeMillis();
            data.velocityStartTick = Bukkit.getCurrentTick();
            data.velocityUnitX = velocity.getX() / horizontal;
            data.velocityUnitZ = velocity.getZ() / horizontal;
            data.velocityExpectedBlocks = expected;
            data.velocityTravelBlocks = 0.0;
        }
    }

    private void evaluateVelocity(Player player, PlayerData data, double dx, double dz, int tick) {
        if (!data.velocityPending) {
            return;
        }
        data.velocityTravelBlocks += dx * data.velocityUnitX + dz * data.velocityUnitZ;
        int ticks = Math.max(1, getConfig().getInt("combat.velocity-check-ticks", 5));
        if (tick < data.velocityStartTick + ticks) {
            return;
        }
        data.velocityPending = false;
        double expected = data.velocityExpectedBlocks;
        if (expected < getConfig().getDouble("combat.velocity-min-expected-blocks", 1.10)) {
            return;
        }
        if (isWallInDirection(player, data.velocityUnitX, data.velocityUnitZ)) {
            return;
        }
        double ratio = data.velocityTravelBlocks / expected;
        if (ratio < getConfig().getDouble("combat.velocity-min-travel-ratio", 0.30)) {
            flag(player, data, CheckId.VELOCITY, 1.2,
                    "нокбэк пройден " + formatVl(Math.max(0.0, ratio * 100.0)) + "% от ожидаемого");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onElytraBoost(PlayerElytraBoostEvent event) {
        PlayerData data = data(event.getPlayer());
        data.elytraGraceUntilMillis = System.currentTimeMillis()
                + getConfig().getLong("elytra.boost-grace-ms", 4000L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRiptide(PlayerRiptideEvent event) {
        PlayerData data = data(event.getPlayer());
        long now = System.currentTimeMillis();
        data.elytraGraceUntilMillis = now + getConfig().getLong("elytra.boost-grace-ms", 4000L);
        data.velocityGraceUntilMillis = now + 2500L;
        grantGrace(data, 900L);
    }

    // ------------------------------------------------------------ бой

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAttackHardReach(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        PlayerData data = data(attacker);
        if (!shouldCheck(attacker, data) || inGrace(data)) {
            return;
        }
        double distance = eyeToBoxDistance(attacker, target);
        double hard = getConfig().getDouble("combat.reach-hard-cancel-blocks", 4.60);
        if (distance > hard) {
            flag(attacker, data, CheckId.REACH, 2.0,
                    "удар на " + formatVl(distance) + " бл. (жёсткий предел " + formatVl(hard) + ")");
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        PlayerData data = data(attacker);
        if (!shouldCheck(attacker, data)) {
            return;
        }
        long now = System.currentTimeMillis();
        recordAttack(data, target, now);
        if (inGrace(data)) {
            return;
        }

        double distance = eyeToBoxDistance(attacker, target);
        double max = "TOUCH".equalsIgnoreCase(data.inputMode)
                ? getConfig().getDouble("combat.reach-touch-max-blocks", 3.65)
                : getConfig().getDouble("combat.reach-max-blocks", 3.45);
        double interaction = attributeValue(attacker, interactionRangeAttribute, 3.0);
        if (interaction > 3.0) {
            max = Math.max(max, interaction + 0.42);
        }
        Vector targetVelocity = target.getVelocity();
        double targetSpeed = Math.sqrt(targetVelocity.getX() * targetVelocity.getX()
                + targetVelocity.getZ() * targetVelocity.getZ()) * 20.0;
        if (targetSpeed > 0.5) {
            double comp = Math.min(getConfig().getDouble("combat.reach-ping-comp-max-blocks", 0.40),
                    attacker.getPing() / 100.0 * getConfig().getDouble("combat.reach-ping-comp-per-100ms", 0.045));
            max += comp;
        }
        if (distance > max) {
            double points = distance > max + 0.75 ? 2.0 : 1.0;
            if (flag(attacker, data, CheckId.REACH, points,
                    "удар на " + formatVl(distance) + " бл., лимит ~" + formatVl(max))) {
                // MONITOR уже не может отменить событие; отмена делается hard-проверкой выше.
            }
        }

        // KillAura: несколько разных целей за короткое окно.
        int distinct = distinctTargets(data, now, getConfig().getLong("combat.killaura-window-ms", 1000L));
        if (distinct >= getConfig().getInt("combat.killaura-multi-targets", 4)) {
            flag(attacker, data, CheckId.KILLAURA, 1.0,
                    distinct + " разных целей за 1 сек.");
            data.attacks.clear();
        }

        // Удары сквозь стену: только серией и не на короткой дистанции.
        if (distance > getConfig().getDouble("combat.killaura-wall-min-distance", 1.60)
                && !attacker.hasLineOfSight(target)) {
            flag(attacker, data, CheckId.KILLAURA, 0.7,
                    "удар без прямой видимости на " + formatVl(distance) + " бл.");
        }
    }

    private void recordAttack(PlayerData data, LivingEntity target, long now) {
        while (!data.attacks.isEmpty() && now - data.attacks.peekFirst().timeMillis() > 2500L) {
            data.attacks.removeFirst();
        }
        data.attacks.addLast(new PlayerData.TimedTarget(now, target.getUniqueId()));
        while (data.attacks.size() > 24) {
            data.attacks.removeFirst();
        }
    }

    private int distinctTargets(PlayerData data, long now, long windowMillis) {
        Set<UUID> targets = new HashSet<>();
        for (PlayerData.TimedTarget attack : data.attacks) {
            if (now - attack.timeMillis() <= windowMillis) {
                targets.add(attack.target());
            }
        }
        return targets.size();
    }

    private double eyeToBoxDistance(Player attacker, LivingEntity target) {
        Vector eye = attacker.getEyeLocation().toVector();
        BoundingBox box = target.getBoundingBox();
        double nearestX = Math.max(box.getMinX(), Math.min(eye.getX(), box.getMaxX()));
        double nearestY = Math.max(box.getMinY(), Math.min(eye.getY(), box.getMaxY()));
        double nearestZ = Math.max(box.getMinZ(), Math.min(eye.getZ(), box.getMaxZ()));
        double dx = eye.getX() - nearestX;
        double dy = eye.getY() - nearestY;
        double dz = eye.getZ() - nearestZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmSwing(PlayerArmSwingEvent event) {
        Player player = event.getPlayer();
        PlayerData data = data(player);
        if (!shouldCheck(player, data)) {
            return;
        }
        long now = System.currentTimeMillis();
        data.swings.addLast(now);
        while (!data.swings.isEmpty() && now - data.swings.peekFirst() > 1500L) {
            data.swings.removeFirst();
        }
        while (data.swings.size() > 80) {
            data.swings.removeFirst();
        }
        int lastSecond = 0;
        for (Long time : data.swings) {
            if (now - time <= 1000L) {
                lastSecond++;
            }
        }
        int maxCps = "TOUCH".equalsIgnoreCase(data.inputMode)
                ? getConfig().getInt("combat.autoclicker-touch-cps", 27)
                : getConfig().getInt("combat.autoclicker-cps", 24);
        Long lastFlag = data.lastFlagMillis.get(CheckId.AUTOCLICKER);
        if (lastSecond > maxCps && (lastFlag == null || now - lastFlag > 1000L)) {
            flag(player, data, CheckId.AUTOCLICKER, 1.0, "CPS " + lastSecond + " (лимит " + maxCps + ")");
            return;
        }
        int sample = getConfig().getInt("combat.autoclicker-sample-clicks", 28);
        // На тач-устройствах удержание даёт почти идеальный тиковый ритм — это не
        // доказательство автокликера, поэтому ровность проверяем только мышь/геймпад.
        if (!"TOUCH".equalsIgnoreCase(data.inputMode)
                && data.swings.size() >= sample && (lastFlag == null || now - lastFlag > 1500L)) {
            List<Long> times = new ArrayList<>(data.swings);
            int from = times.size() - sample;
            double total = 0.0;
            List<Double> intervals = new ArrayList<>();
            for (int i = from + 1; i < times.size(); i++) {
                double interval = times.get(i) - times.get(i - 1);
                intervals.add(interval);
                total += interval;
            }
            if (!intervals.isEmpty()) {
                double avg = total / intervals.size();
                if (avg > 0) {
                    double variance = 0.0;
                    for (double interval : intervals) {
                        variance += (interval - avg) * (interval - avg);
                    }
                    double deviation = Math.sqrt(variance / intervals.size());
                    double cps = 1000.0 / avg;
                    if (cps >= getConfig().getDouble("combat.autoclicker-consistent-min-cps", 20.0)
                            && deviation <= getConfig().getDouble("combat.autoclicker-consistent-max-deviation-ms", 3.5)) {
                        flag(player, data, CheckId.AUTOCLICKER, 1.0,
                                "слишком ровные клики " + formatVl(cps) + " CPS, отклонение "
                                        + formatVl(deviation) + " мс");
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ блоки

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        PlayerData data = data(player);
        if (!shouldCheck(player, data)) {
            return;
        }
        if (blockReachViolation(player, data, event.getBlock())) {
            event.setCancelled(true);
            return;
        }
        long now = System.currentTimeMillis();
        int tick = Bukkit.getCurrentTick();
        if (data.lastBreakTick == tick) {
            data.breaksThisTick++;
        } else {
            data.lastBreakTick = tick;
            data.breaksThisTick = 1;
        }
        data.breaks.addLast(now);
        pruneWindow(data.breaks, now, 1000L, 80);
        int sameTickLimit = getConfig().getInt("world.fastbreak-same-tick", 4);
        int perSecondLimit = getConfig().getInt("world.fastbreak-per-second", 28);
        boolean lowTps = currentTps() < getConfig().getDouble("settings.min-tps-for-movement", 16.0);
        if (!lowTps && (data.breaksThisTick >= sameTickLimit || data.breaks.size() > perSecondLimit)) {
            boolean cancel = flag(player, data, CheckId.FASTBREAK,
                    data.breaksThisTick >= sameTickLimit ? 2.0 : 1.0,
                    data.breaksThisTick >= sameTickLimit
                            ? data.breaksThisTick + " блоков за тик"
                            : data.breaks.size() + " блоков/сек");
            if (cancel || data.breaksThisTick > sameTickLimit + 2) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        PlayerData data = data(player);
        if (!shouldCheck(player, data)) {
            return;
        }
        if (blockReachViolation(player, data, event.getBlockPlaced())) {
            event.setCancelled(true);
            return;
        }
        long now = System.currentTimeMillis();
        int tick = Bukkit.getCurrentTick();
        if (data.lastPlaceTick == tick) {
            data.placesThisTick++;
        } else {
            data.lastPlaceTick = tick;
            data.placesThisTick = 1;
        }
        data.places.addLast(now);
        pruneWindow(data.places, now, 1000L, 80);
        int sameTickLimit = getConfig().getInt("world.fastplace-same-tick", 4);
        int perSecondLimit = getConfig().getInt("world.fastplace-per-second", 28);
        boolean lowTps = currentTps() < getConfig().getDouble("settings.min-tps-for-movement", 16.0);
        if (!lowTps && (data.placesThisTick >= sameTickLimit || data.places.size() > perSecondLimit)) {
            boolean cancel = flag(player, data, CheckId.FASTPLACE,
                    data.placesThisTick >= sameTickLimit ? 2.0 : 1.0,
                    data.placesThisTick >= sameTickLimit
                            ? data.placesThisTick + " блоков за тик"
                            : data.places.size() + " блоков/сек");
            if (cancel || data.placesThisTick > sameTickLimit + 2) {
                event.setCancelled(true);
                return;
            }
        }
        scaffoldCheck(player, data, event, now);
    }

    private boolean blockReachViolation(Player player, PlayerData data, Block block) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        double distance = player.getEyeLocation().distance(center);
        double hard = getConfig().getDouble("world.blockreach-hard-cancel-blocks", 7.0);
        double max = getConfig().getDouble("world.blockreach-max-blocks", 6.20);
        if (distance > hard) {
            flag(player, data, CheckId.BLOCKREACH, 2.0,
                    "блок на " + formatVl(distance) + " бл. (жёсткий предел " + formatVl(hard) + ")");
            return true;
        }
        if (distance > max) {
            return flag(player, data, CheckId.BLOCKREACH, 1.0,
                    "блок на " + formatVl(distance) + " бл., лимит ~" + formatVl(max));
        }
        return false;
    }

    private void scaffoldCheck(Player player, PlayerData data, BlockPlaceEvent event, long now) {
        Block placed = event.getBlockPlaced();
        Location loc = player.getLocation();
        double centerX = placed.getX() + 0.5;
        double centerZ = placed.getZ() + 0.5;
        double horizontalDistance = Math.sqrt(Math.pow(loc.getX() - centerX, 2) + Math.pow(loc.getZ() - centerZ, 2));
        boolean underFeet = placed.getY() < Math.floor(loc.getY()) && horizontalDistance <= 0.85;
        if (!underFeet || player.isSneaking() || player.getVehicle() != null) {
            return;
        }
        // У Bedrock есть легальное быстрое строительство вперёд, поэтому смотрим
        // только строгую серию блоков строго под ногами на скорости и без взгляда вниз.
        if (data.lastHorizontalBps < getConfig().getDouble("world.scaffold-min-speed-bps", 3.0)
                || loc.getPitch() > getConfig().getDouble("world.scaffold-max-pitch", 55.0)) {
            return;
        }
        long window = getConfig().getLong("world.scaffold-window-ms", 5000L);
        if (now - data.scaffoldWindowStartMillis > window) {
            data.scaffoldWindowStartMillis = now;
            data.scaffoldPlaces = 0;
        }
        data.scaffoldPlaces++;
        if (data.scaffoldPlaces >= getConfig().getInt("world.scaffold-places-in-window", 3)) {
            boolean cancel = flag(player, data, CheckId.SCAFFOLD, 1.0,
                    "серия блоков строго под ногами на скорости " + formatVl(data.lastHorizontalBps) + " бл/с");
            data.scaffoldPlaces = 0;
            if (cancel) {
                event.setCancelled(true);
            }
        }
    }

    private void pruneWindow(ArrayDeque<Long> deque, long now, long windowMillis, int maxSize) {
        while (!deque.isEmpty() && now - deque.peekFirst() > windowMillis) {
            deque.removeFirst();
        }
        while (deque.size() > maxSize) {
            deque.removeFirst();
        }
    }

    // ------------------------------------------------------------ секундный тик

    private void secondTick() {
        long now = System.currentTimeMillis();
        int tick = Bukkit.getCurrentTick();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = players.get(player.getUniqueId());
            if (data == null) {
                continue;
            }
            data.bypassAll = player.hasPermission("voidanticheat.bypass");
            data.excludedHere = isExcluded(player.getLocation());

            // Затухание VL.
            for (CheckId check : CheckId.values()) {
                double vl = data.vl(check);
                if (vl > 0) {
                    data.setVl(check, vl - setting(check).decayPerSecond());
                }
            }

            timerCheck(player, data, tick, now);
        }
        // Удаляем данные ушедших, даже если QuitEvent где-то потерялся.
        players.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    private void timerCheck(Player player, PlayerData data, int tick, long nowMillis) {
        if (!shouldCheck(player, data) || inGrace(data) || player.getVehicle() != null) {
            data.timerWindowStartNanos = System.nanoTime();
            data.timerWindowStartTick = tick;
            data.timerPackets = 0;
            return;
        }
        int windowTicks = Math.max(40, getConfig().getInt("movement.timer-window-ticks", 100));
        int elapsedTicks = tick - data.timerWindowStartTick;
        if (elapsedTicks < windowTicks) {
            return;
        }
        double ratio = data.timerPackets / (double) Math.max(1, elapsedTicks);
        double allowedRatio = getConfig().getDouble("movement.timer-ratio", 1.24);
        if (player.getPing() > 250) {
            allowedRatio += 0.08;
        }
        int extra = getConfig().getInt("movement.timer-extra-packets", 24);
        if (ratio > allowedRatio && data.timerPackets > elapsedTicks + extra) {
            flag(player, data, CheckId.TIMER, ratio > 1.5 ? 2.0 : 1.0,
                    "движений " + data.timerPackets + " за " + elapsedTicks + " тиков (x" + formatVl(ratio) + ")");
        }
        data.timerWindowStartNanos = System.nanoTime();
        data.timerWindowStartTick = tick;
        data.timerPackets = 0;
    }

    // ------------------------------------------------------------ помощники мира/физики

    private boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private float angleDifference(float a, float b) {
        float diff = (a - b) % 360f;
        if (diff > 180f) {
            diff -= 360f;
        } else if (diff < -180f) {
            diff += 360f;
        }
        return diff;
    }

    private Block blockAt(Location location, double dy) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        return world.getBlockAt((int) Math.floor(location.getX()),
                (int) Math.floor(location.getY() + dy), (int) Math.floor(location.getZ()));
    }

    private boolean isLiquid(Block block) {
        return block != null && block.isLiquid();
    }

    private boolean isHardSolid(Block block) {
        return block != null && block.getType().isOccluding();
    }

    private String name(Block block) {
        return block == null ? "AIR" : block.getType().name();
    }

    private boolean isIce(Material type) {
        return type == Material.ICE || type == Material.FROSTED_ICE
                || type == Material.PACKED_ICE || type == Material.BLUE_ICE;
    }

    private boolean isClimbable(Block block) {
        if (block == null) {
            return false;
        }
        Material type = block.getType();
        return type == Material.LADDER || type == Material.VINE || type == Material.SCAFFOLDING
                || type == Material.TWISTING_VINES || type == Material.WEEPING_VINES
                || type == Material.TWISTING_VINES_PLANT || type == Material.WEEPING_VINES_PLANT
                || type == Material.CAVE_VINES || type == Material.CAVE_VINES_PLANT;
    }

    private boolean isNearWall(Location to) {
        int x = (int) Math.floor(to.getX());
        int y = (int) Math.floor(to.getY() + 0.25);
        int z = (int) Math.floor(to.getZ());
        World world = to.getWorld();
        if (world == null) {
            return false;
        }
        return world.getBlockAt(x + 1, y, z).getType().isSolid()
                || world.getBlockAt(x - 1, y, z).getType().isSolid()
                || world.getBlockAt(x, y, z + 1).getType().isSolid()
                || world.getBlockAt(x, y, z - 1).getType().isSolid();
    }

    private boolean isWallInDirection(Player player, double unitX, double unitZ) {
        Location loc = player.getLocation();
        int x = (int) Math.floor(loc.getX() + unitX * 0.85);
        int z = (int) Math.floor(loc.getZ() + unitZ * 0.85);
        World world = loc.getWorld();
        if (world == null) {
            return false;
        }
        int y = (int) Math.floor(loc.getY());
        return world.getBlockAt(x, y, z).getType().isSolid()
                || world.getBlockAt(x, y + 1, z).getType().isSolid();
    }

    private PotionEffect effect(Player player, PotionEffectType type) {
        return type == null ? null : player.getPotionEffect(type);
    }

    private int effectAmplifier(Player player, PotionEffectType type) {
        PotionEffect effect = effect(player, type);
        return effect == null ? -1 : effect.getAmplifier();
    }

    private double attributeValue(Player player, Attribute attribute, double fallback) {
        if (attribute == null) {
            return fallback;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }

    private int enchantLevel(ItemStack item, Enchantment enchantment) {
        if (item == null || enchantment == null || item.getItemMeta() == null) {
            return 0;
        }
        return item.getEnchantmentLevel(enchantment);
    }

    // ------------------------------------------------------------ команды

    private void tell(CommandSender sender, String key, String... pairs) {
        String text = getConfig().getString("messages." + key, "");
        if (text == null || text.isEmpty()) {
            return;
        }
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace(pairs[i], pairs[i + 1]);
        }
        String prefix = getConfig().getString("messages.prefix", "");
        sender.sendMessage(comp((prefix == null ? "" : prefix) + text));
    }

    private boolean admin(CommandSender sender) {
        return sender.hasPermission("voidanticheat.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!admin(sender)) {
            tell(sender, "no-permission");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                loadCheckSettings();
                tell(sender, "reloaded");
                return true;
            }
            case "alerts" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(comp("&7Команда alerts доступна только игроку."));
                    return true;
                }
                if (alertsOff.remove(player.getUniqueId())) {
                    tell(sender, "alerts-on");
                } else {
                    alertsOff.add(player.getUniqueId());
                    tell(sender, "alerts-off");
                }
                return true;
            }
            case "info" -> {
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) {
                    tell(sender, "player-not-found");
                    return true;
                }
                PlayerData data = data(target);
                tell(sender, "info-header",
                        "{player}", target.getName(),
                        "{bedrock}", String.valueOf(data.bedrock),
                        "{input}", data.inputMode,
                        "{device}", data.deviceOs,
                        "{ping}", String.valueOf(target.getPing()));
                List<CheckId> active = new ArrayList<>();
                for (CheckId check : CheckId.values()) {
                    if (data.vl(check) > 0) {
                        active.add(check);
                    }
                }
                if (active.isEmpty()) {
                    tell(sender, "info-none");
                } else {
                    active.sort(Comparator.comparingDouble((CheckId c) -> data.vl(c)).reversed());
                    for (CheckId check : active) {
                        tell(sender, "info-line",
                                "{check}", check.display(),
                                "{vl}", formatVl(data.vl(check)),
                                "{max_vl}", formatVl(setting(check).maxVl()));
                    }
                }
                if (data.lastCheck != null) {
                    sender.sendMessage(comp("&7Последнее: &f" + data.lastCheck.display()
                            + " &8• &7" + data.lastDetails));
                }
                return true;
            }
            case "reset" -> {
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) {
                    tell(sender, "player-not-found");
                    return true;
                }
                PlayerData data = data(target);
                String scope = "";
                if (args.length >= 3) {
                    CheckId check = CheckId.byKey(args[2]);
                    if (check == null) {
                        tell(sender, "check-not-found");
                        return true;
                    }
                    data.setVl(check, 0.0);
                    scope = " (" + check.display() + ")";
                } else {
                    data.violations.clear();
                }
                tell(sender, "reset-done", "{player}", target.getName(), "{scope}", scope);
                return true;
            }
            case "setback" -> {
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) {
                    tell(sender, "player-not-found");
                    return true;
                }
                PlayerData data = data(target);
                if (setbackPlayer(target, data)) {
                    tell(sender, "setback-done", "{player}", target.getName());
                } else {
                    tell(sender, "no-safe-location");
                }
                return true;
            }
            case "help" -> {
                sendHelp(sender);
                return true;
            }
            default -> {
                if (!sub.isEmpty()) {
                    sendHelp(sender);
                    return true;
                }
                tell(sender, "status",
                        "{state}", getConfig().getString(getConfig().getBoolean("settings.enabled", true)
                                ? "messages.state-on" : "messages.state-off", ""),
                        "{floodgate}", floodgate.available() ? "подключён" : "fallback",
                        "{tracked}", String.valueOf(players.size()),
                        "{tps}", formatVl(currentTps()));
                return true;
            }
        }
    }

    private void sendHelp(CommandSender sender) {
        String prefix = getConfig().getString("messages.prefix", "");
        for (String line : getConfig().getStringList("messages.help")) {
            sender.sendMessage(comp((prefix == null ? "" : prefix) + line));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!admin(sender)) {
            return out;
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = List.of();
        if (args.length == 1) {
            options = List.of("info", "alerts", "reset", "setback", "reload", "help");
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("info")
                || args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("setback"))) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            options = names;
        } else if (args.length == 3 && args[0].equalsIgnoreCase("reset")) {
            List<String> checks = new ArrayList<>();
            for (CheckId check : CheckId.values()) {
                checks.add(check.key());
            }
            options = checks;
        }
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(option);
            }
        }
        return out;
    }
}
