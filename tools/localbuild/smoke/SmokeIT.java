package ru.voidpe.anticheat;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Быстрый интеграционный стенд: настоящие обработчики плагина на фейковом
 * Bukkit. Проверяет две вещи: честные Bedrock-профили не дают VL, а читы
 * ловятся. Конфиг берётся из src/main/resources/config.yml через
 * сгенерированный ConfigData (см. gen_config_data.py).
 */
public final class SmokeIT {

    private static int passed;
    private static int failed;
    private static TestPlugin plugin;
    private static FakeWorld world;
    private static FakePlayer player;

    public static void main(String[] args) {
        setup();

        honestWalk();
        honestSprint();
        honestSprintJump();
        honestIce();
        honestLagSpike();
        honestTeleport();
        honestServerLaunch();
        honestTouchReach();

        cheatFly();
        cheatHover();
        cheatSpeed();
        cheatReach();
        cheatStep();
        cheatNoFall();
        cheatNoclip();
        cheatAutoclicker();
        offHandIgnored();
        observeMode();

        System.out.println("----");
        System.out.println(passed + " OK, " + failed + " FAIL");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ сценарии

    private static void honestWalk() {
        resetPlayer(0.5, 64, 0.5);
        double x = 0.5;
        boolean flagged = false;
        for (int i = 0; i < 400; i++) {
            x += 0.2;
            move(x, 64, 0.5, true, 0);
            flagged |= totalVl() > 0;
        }
        check("ходьба 400 тиков: ни одного VL", !flagged);
        report("walk");
    }

    private static void honestSprint() {
        resetPlayer(0.5, 64, 0.5);
        player.sprinting = true;
        double x = 0.5;
        boolean flagged = false;
        for (int i = 0; i < 400; i++) {
            x += 0.28;
            move(x, 64, 0.5, true, 0);
            flagged |= totalVl() > 0;
        }
        check("спринт 400 тиков: ни одного VL", !flagged);
        report("sprint");
    }

    private static void honestSprintJump() {
        resetPlayer(0.5, 64, 0.5);
        player.sprinting = true;
        double x = 0.5;
        boolean flagged = false;
        for (int jump = 0; jump < 40; jump++) {
            for (int i = 0; i < 3; i++) {
                x += 0.28;
                move(x, 64, 0.5, true, 0);
            }
            double vy = 0.42;
            double y = 64;
            for (int t = 0; t < 13; t++) {
                y += vy;
                x += 0.30;
                vy = (vy - 0.08) * 0.98;
                move(x, y, 0.5, false, 0);
            }
            player.fallDistance = 0;
            move(x, 64, 0.5, true, 0);
            flagged |= totalVl() > 0;
        }
        check("40 спринт-прыжков: ни одного VL", !flagged);
        report("jump");
    }

    private static void honestIce() {
        resetPlayer(0.5, 64, 0.5);
        world.setRegion(-20, 63, -20, 400, 63, 20, Material.ICE);
        player.sprinting = true;
        double x = 0.5;
        boolean flagged = false;
        for (int i = 0; i < 240; i++) {
            x += 0.45;
            move(x, 64, 0.5, true, 0);
            flagged |= totalVl() > 0;
        }
        world.clearRegions();
        check("лёд: 9 бл/с 240 тиков — ни одного VL", !flagged);
        report("ice");
    }

    private static void honestLagSpike() {
        resetPlayer(0.5, 64, 0.5);
        double x = 0.5;
        for (int i = 0; i < 20; i++) {
            x += 0.2;
            move(x, 64, 0.5, true, 0);
        }
        boolean flagged = false;
        sleep(1300);
        x += 1.2;
        move(x, 64, 0.5, true, 0);
        for (int i = 0; i < 6; i++) {
            x += 0.6;
            move(x, 64, 0.5, true, 0);
            flagged |= totalVl() > 0;
        }
        check("лаг-спайк 1.3 с и догоняющие пакеты: ни одного VL", !flagged);
        report("lag");
    }

    private static void honestTeleport() {
        resetPlayer(0.5, 64, 0.5);
        for (int i = 0; i < 10; i++) {
            move(0.5 + i * 0.2, 64, 0.5, true, 0);
        }
        plugin.peek(player).clearViolations();
        plugin.onTeleport(new PlayerTeleportEvent(player,
                new Location(world, 0.5, 64, 0.5), new Location(world, 500.5, 80, 500.5)));
        player.x = 500.5;
        player.y = 80;
        player.z = 500.5;
        player.teleport(new Location(world, 500.5, 80, 500.5));
        move(500.7, 80, 500.5, true, 0);
        check("телепорт на 700 блоков: ни одного VL", totalVl() == 0);
    }

    private static void honestServerLaunch() {
        resetPlayer(0.5, 64, 0.5);
        player.velocity = new Vector(0.15, 0.90, 0.0);
        player.onGround = false;
        PlayerData data = plugin.peek(player);
        data.graceUntilMillis = 0;
        data.velocityGraceUntilMillis = 0;
        double y = 64;
        double vy = 0.90;
        boolean flagged = false;
        for (int i = 0; i < 40; i++) {
            vy = (vy - 0.08) * 0.98;
            y += vy;
            move(0.5, y, 0.5, false, 0);
            flagged |= totalVl() > 0;
        }
        player.velocity = new Vector(0, 0, 0);
        check("серверный импульс Y=0.9: ни одного VL", !flagged);
        report("launch");
    }

    private static void honestTouchReach() {
        resetPlayer(0.5, 64, 0.5);
        plugin.peek(player).inputMode = "TOUCH";
        FakeTarget target = new FakeTarget(world, 4.15);
        boolean flagged = false;
        for (int i = 0; i < 8; i++) {
            plugin.onAttackReach(new EntityDamageByEntityEvent(player, target, 1.0));
            flagged |= vl(CheckId.REACH) > 0;
        }
        plugin.peek(player).inputMode = "UNKNOWN";
        check("тач-ввод: удар на 3.65 бл. — Reach не сработал", !flagged);
    }

    private static void cheatFly() {
        resetPlayer(0.5, 90, 0.5);
        player.onGround = false;
        double y = 90;
        boolean caught = false;
        for (int i = 0; i < 140; i++) {
            y += 0.42;
            move(0.5, y, 0.5, false, 55);
            caught |= vl(CheckId.FLY) > 0;
        }
        check("Fly пойман, FLY VL=" + round(vl(CheckId.FLY)), caught);
    }

    private static void cheatHover() {
        resetPlayer(0.5, 90, 0.5);
        player.onGround = false;
        boolean caught = false;
        for (int i = 0; i < 140; i++) {
            move(0.5, 90, 0.5, false, 55);
            caught |= vl(CheckId.FLY) > 0;
        }
        check("Hover пойман, FLY VL=" + round(vl(CheckId.FLY)), caught);
    }

    private static void cheatSpeed() {
        resetPlayer(0.5, 64, 0.5);
        double x = 0.5;
        boolean caught = false;
        for (int i = 0; i < 120; i++) {
            x += 0.62;
            move(x, 64, 0.5, true, 55);
            caught |= vl(CheckId.SPEED) > 0;
        }
        check("Speed x2.8 пойман, SPEED VL=" + round(vl(CheckId.SPEED)), caught);
    }

    private static void cheatReach() {
        resetPlayer(0.5, 64, 0.5);
        FakeTarget target = new FakeTarget(world, 4.40);
        boolean caught = false;
        for (int i = 0; i < 6; i++) {
            plugin.onAttackReach(new EntityDamageByEntityEvent(player, target, 1.0));
            sleep(140);
            caught |= vl(CheckId.REACH) > 0;
        }
        check("Reach 4.4 бл. пойман, REACH VL=" + round(vl(CheckId.REACH)), caught);
    }

    private static void cheatStep() {
        resetPlayer(0.5, 64, 0.5);
        boolean caught = false;
        for (int i = 0; i < 8; i++) {
            move(0.5, 65.3, 0.5, true, 130);
            move(0.5, 64, 0.5, true, 130);
            caught |= vl(CheckId.STEP) > 0;
        }
        check("Step 1.3 бл за тик пойман, STEP VL=" + round(vl(CheckId.STEP)), caught);
    }

    private static void cheatNoFall() {
        resetPlayer(0.5, 80, 0.5);
        boolean caught = false;
        for (int fall = 0; fall < 3; fall++) {
            PlayerData data = plugin.peek(player);
            player.x = 0.5;
            player.y = 80;
            player.z = 0.5;
            player.onGround = false;
            player.fallDistance = 0;
            data.graceUntilMillis = 0;
            data.velocityGraceUntilMillis = 0;
            data.prediction.reset();
            data.resetMovement(new Location(world, 0.5, 80, 0.5), Bukkit.tick, System.nanoTime(), false);
            double y = 80;
            for (int i = 0; i < 8; i++) {
                y -= 1.0;
                move(0.5, y, 0.5, false, 0);
            }
            player.fallDistance = 0;
            move(0.5, 64, 0.5, true, 250);
            caught |= vl(CheckId.NOFALL) > 0;
        }
        check("NoFall пойман, NOFALL VL=" + round(vl(CheckId.NOFALL)), caught);
    }

    private static void cheatNoclip() {
        resetPlayer(0.5, 64, 0.5);
        world.setRegion(0, 64, 0, 0, 65, 0, Material.STONE);
        boolean caught = false;
        for (int i = 0; i < 40; i++) {
            move(0.5 + i * 0.001, 64, 0.5, true, 0);
            caught |= vl(CheckId.NOCLIP) > 0;
        }
        world.clearRegions();
        check("NoClip пойман, NOCLIP VL=" + round(vl(CheckId.NOCLIP)), caught);
    }

    private static void cheatAutoclicker() {
        resetPlayer(0.5, 64, 0.5);
        boolean caught = false;
        for (int i = 0; i < 160; i++) {
            plugin.onArmSwing(new io.papermc.paper.event.player.PlayerArmSwingEvent(
                    player, EquipmentSlot.HAND));
            sleep(15);
            caught |= vl(CheckId.AUTOCLICKER) > 0;
        }
        check("AutoClicker 60 CPS пойман, AUTOCLICKER VL=" + round(vl(CheckId.AUTOCLICKER)), caught);
    }

    private static void offHandIgnored() {
        resetPlayer(0.5, 64, 0.5);
        for (int i = 0; i < 60; i++) {
            plugin.onArmSwing(new io.papermc.paper.event.player.PlayerArmSwingEvent(
                    player, EquipmentSlot.OFF_HAND));
        }
        check("взмахи левой рукой не считаются автокликером", vl(CheckId.AUTOCLICKER) == 0);
    }

    private static void observeMode() {
        TestPlugin observe = new TestPlugin(Map.of("settings.enforcement", Boolean.FALSE));
        observe.onEnable();
        FakePlayer watcher = new FakePlayer(world, "Watcher");
        Bukkit.online.add(watcher);
        watcher.y = 90;
        watcher.onGround = false;
        double y = 90;
        Location lastTo = null;
        boolean caught = false;
        for (int i = 0; i < 120; i++) {
            Location from = new Location(world, 0.5, y, 0.5);
            y += 0.42;
            watcher.y = y;
            Location to = new Location(world, 0.5, y, 0.5);
            Bukkit.tick++;
            PlayerMoveEvent event = new PlayerMoveEvent(watcher, from, to);
            observe.onMove(event);
            lastTo = event.getTo();
            PlayerData data = observe.peek(watcher);
            caught |= data != null && data.vl(CheckId.FLY) > 0;
            sleep(55);
        }
        Bukkit.online.remove(watcher);
        check("режим наблюдения: Fly виден, позиция не правится",
                caught && lastTo != null && Math.abs(lastTo.getY() - y) < 1e-9);
    }

    // ------------------------------------------------------------ механика стенда

    private static void setup() {
        world = new FakeWorld();
        Bukkit.server = new FakeServer();
        plugin = new TestPlugin(Map.of());
        plugin.onEnable();
        player = new FakePlayer(world, "Steve");
        Bukkit.online.add(player);
        Bukkit.tick = 100;
        move(0.5, 64.0, 0.5, true, 0);
    }

    private static void resetPlayer(double x, double y, double z) {
        player.x = x;
        player.y = y;
        player.z = z;
        player.onGround = true;
        player.sprinting = false;
        player.flying = false;
        player.gliding = false;
        player.swimming = false;
        player.inWater = false;
        player.vehicle = null;
        player.ping = 0;
        player.effects.clear();
        player.fallDistance = 0;
        player.velocity = new Vector(0, 0, 0);
        Bukkit.tps[0] = 20.0;
        PlayerData data = plugin.peek(player);
        if (data == null) {
            move(x, y, z, true, 0);
            return;
        }
        data.clearViolations();
        data.clearSustainAll();
        data.graceUntilMillis = 0;
        data.speedRefillNanos = 0;
        data.lastBlockChangeMillis = 0;
        data.velocityGraceUntilMillis = 0;
        data.elytraGraceUntilMillis = 0;
        data.riptideUntilMillis = 0;
        data.pistonUntilMillis = 0;
        data.explosionUntilMillis = 0;
        data.lastDamageMillis = 0;
        data.setbackRequested = false;
        data.velocityPending = false;
        data.lastViolationMillis = 0;
        data.env = null;
        data.envTick = Integer.MIN_VALUE;
        data.swings.clear();
        data.breaks.clear();
        data.places.clear();
        data.attacks.clear();
        data.targets.clear();
        data.cpsHighWindows = 0;
        data.airTicks = 0;
        data.climbStreak = 0;
        data.noclipStreak = 0;
        data.slowFallStreak = 0;
        data.prediction.reset();
        data.lag.reset();
        data.speed.reset(4.5);
        data.safeLocation = new Location(world, x, y, z);
        data.resetMovement(new Location(world, x, y, z), Bukkit.tick, System.nanoTime(), true);
    }

    private static void move(double x, double y, double z, boolean onGround, long sleepMs) {
        Location from = new Location(world, player.x, player.y, player.z);
        player.x = x;
        player.y = y;
        player.z = z;
        player.onGround = onGround;
        Bukkit.tick++;
        plugin.onMove(new PlayerMoveEvent(player, from, new Location(world, x, y, z)));
        sleep(sleepMs);
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static double vl(CheckId check) {
        PlayerData data = plugin.peek(player);
        return data == null ? 0 : data.vl(check);
    }

    private static double totalVl() {
        PlayerData data = plugin.peek(player);
        if (data == null) {
            return 0;
        }
        double sum = 0;
        for (CheckId check : CheckId.values()) {
            sum += data.vl(check);
        }
        return sum;
    }

    private static void report(String label) {
        PlayerData data = plugin.peek(player);
        StringBuilder sb = new StringBuilder();
        for (CheckId check : CheckId.values()) {
            if (data.vl(check) > 0) {
                sb.append(' ').append(check.key()).append('=').append(round(data.vl(check)));
            }
        }
        if (sb.length() > 0) {
            System.out.println("       " + label + ":" + sb + " | последнее: "
                    + (data.lastCheck == null ? "-" : data.lastCheck.key() + " " + data.lastDetails));
        }
    }

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[OK]   " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }

    // ------------------------------------------------------------ фейковый мир

    static final class FakeWorld implements World {
        private final List<int[]> regions = new ArrayList<>();
        private final List<Material> materials = new ArrayList<>();
        final List<Entity> nearby = new ArrayList<>();

        void setRegion(int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
            regions.add(new int[]{x1, y1, z1, x2, y2, z2});
            materials.add(material);
        }

        void clearRegions() {
            regions.clear();
            materials.clear();
        }

        @Override public String getName() { return "world"; }

        @Override public Block getBlockAt(int x, int y, int z) {
            for (int i = 0; i < regions.size(); i++) {
                int[] r = regions.get(i);
                if (x >= r[0] && x <= r[3] && y >= r[1] && y <= r[4] && z >= r[2] && z <= r[5]) {
                    return new FakeBlock(this, x, y, z, materials.get(i));
                }
            }
            return new FakeBlock(this, x, y, z, y <= 63 ? Material.STONE : Material.AIR);
        }

        @Override public Collection<Entity> getNearbyEntities(BoundingBox box) {
            return new ArrayList<>(nearby);
        }

        @Override public List<Player> getPlayers() { return List.of(); }
        @Override public int getMaxHeight() { return 320; }

        @Override public void spawnParticle(org.bukkit.Particle particle, Location location, int count,
                                            double offsetX, double offsetY, double offsetZ, double extra) { }

        @Override public void playSound(Location location, org.bukkit.Sound sound, float volume, float pitch) { }
    }

    static final class FakeBlock implements Block {
        private final World world;
        private final int x;
        private final int y;
        private final int z;
        private final Material material;

        FakeBlock(World world, int x, int y, int z, Material material) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.material = material;
        }

        @Override public Material getType() { return material; }
        @Override public World getWorld() { return world; }
        @Override public Location getLocation() { return new Location(world, x, y, z); }
        @Override public boolean isLiquid() { return material.isLiquid(); }
        @Override public int getX() { return x; }
        @Override public int getY() { return y; }
        @Override public int getZ() { return z; }
    }

    static final class FakePlayer implements Player {
        private final UUID uuid = UUID.randomUUID();
        private final String name;
        private final World world;
        public double x;
        public double y;
        public double z;
        public boolean onGround = true;
        public boolean sprinting;
        public boolean flying;
        public boolean gliding;
        public boolean swimming;
        public boolean sneaking;
        public boolean sleeping;
        public boolean inWater;
        public GameMode mode = GameMode.SURVIVAL;
        public int ping;
        public double fallDistance;
        public Vector velocity = new Vector(0, 0, 0);
        public Entity vehicle;
        final Map<PotionEffectType, PotionEffect> effects = new HashMap<>();
        private final Set<String> permissions = new HashSet<>();

        FakePlayer(World world, String name) {
            this.world = world;
            this.name = name;
        }

        @Override public String getName() { return name; }
        @Override public UUID getUniqueId() { return uuid; }
        @Override public Location getLocation() { return new Location(world, x, y, z); }
        @Override public Location getEyeLocation() { return new Location(world, x, y + 1.62, z); }
        @Override public World getWorld() { return world; }
        @Override public boolean isOnline() { return true; }
        @Override public boolean isDead() { return false; }
        @Override public GameMode getGameMode() { return mode; }
        @Override public boolean hasPermission(String permission) { return permissions.contains(permission); }
        @Override public void sendMessage(Component component) { }
        @Override public int getPing() { return ping; }
        @Override public boolean isOnGround() { return onGround; }
        @Override public boolean isFlying() { return flying; }
        @Override public boolean isGliding() { return gliding; }
        @Override public boolean isSwimming() { return swimming; }
        @Override public boolean isSneaking() { return sneaking; }
        @Override public boolean isSprinting() { return sprinting; }
        @Override public boolean isSleeping() { return sleeping; }
        @Override public Entity getVehicle() { return vehicle; }
        @Override public boolean leaveVehicle() { vehicle = null; return true; }
        @Override public boolean teleport(Location location) {
            x = location.getX();
            y = location.getY();
            z = location.getZ();
            return true;
        }
        @Override public AttributeInstance getAttribute(Attribute attribute) {
            return new AttributeInstance() {
                @Override public double getValue() { return 0.1; }
                @Override public double getBaseValue() { return 0.1; }
                @Override public void setBaseValue(double value) { }
            };
        }
        @Override public PlayerInventory getInventory() {
            return new PlayerInventory() {
                @Override public ItemStack getBoots() { return null; }
                @Override public ItemStack getItemInMainHand() { return null; }
            };
        }
        @Override public PotionEffect getPotionEffect(PotionEffectType type) { return effects.get(type); }
        @Override public boolean hasLineOfSight(Entity entity) { return true; }
        @Override public boolean isInWater() { return inWater; }
        @Override public BoundingBox getBoundingBox() {
            return new BoundingBox(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
        }
        @Override public Vector getVelocity() { return velocity; }
        @Override public float getFallDistance() { return (float) fallDistance; }
        @Override public org.bukkit.entity.EntityType getType() { return org.bukkit.entity.EntityType.PLAYER; }
        @Override public double getHeight() { return 1.8; }
        @Override public double getWidth() { return 0.6; }
        @Override public boolean isValid() { return true; }
        @Override public int getNoDamageTicks() { return 0; }
        @Override public void setNoDamageTicks(int ticks) { }
        @Override public void playSound(Location location, org.bukkit.Sound sound, float volume, float pitch) { }
    }

    static final class FakeTarget implements LivingEntity {
        private final World world;
        private final double x;
        private final UUID uuid = UUID.randomUUID();

        FakeTarget(World world, double x) {
            this.world = world;
            this.x = x;
        }

        @Override public UUID getUniqueId() { return uuid; }
        @Override public Location getLocation() { return new Location(world, x, 64, 0.5); }
        @Override public World getWorld() { return world; }
        @Override public Vector getVelocity() { return new Vector(0, 0, 0); }
        @Override public BoundingBox getBoundingBox() {
            return new BoundingBox(x, 64, 0.2, x + 0.6, 65.8, 0.8);
        }
        @Override public boolean isInWater() { return false; }
        @Override public boolean isDead() { return false; }
        @Override public String getName() { return "Target"; }
        @Override public Location getEyeLocation() { return new Location(world, x, 65.5, 0.5); }
        @Override public float getFallDistance() { return 0f; }
        @Override public org.bukkit.entity.EntityType getType() { return org.bukkit.entity.EntityType.PLAYER; }
        @Override public double getHeight() { return 1.8; }
        @Override public double getWidth() { return 0.6; }
        @Override public boolean isValid() { return true; }
        @Override public int getNoDamageTicks() { return 0; }
        @Override public void setNoDamageTicks(int ticks) { }
    }

    static final class FakeBoat implements Boat {
        private final World world;
        private final UUID uuid = UUID.randomUUID();

        FakeBoat(World world) {
            this.world = world;
        }

        @Override public UUID getUniqueId() { return uuid; }
        @Override public Location getLocation() { return new Location(world, 0.5, 64, 0.5); }
        @Override public World getWorld() { return world; }
        @Override public Vector getVelocity() { return new Vector(0, 0, 0); }
        @Override public BoundingBox getBoundingBox() { return new BoundingBox(0, 64, 0, 1, 64.6, 1); }
        @Override public boolean isInWater() { return false; }
        @Override public boolean isDead() { return false; }
        @Override public String getName() { return "Boat"; }
        @Override public org.bukkit.entity.EntityType getType() { return org.bukkit.entity.EntityType.PLAYER; }
        @Override public double getHeight() { return 0.6; }
        @Override public double getWidth() { return 1.0; }
        @Override public boolean isValid() { return true; }
    }

    // ------------------------------------------------------------ фейковый сервер и плагин

    static final class FakeServer implements Server {
        private final PluginManager pluginManager = new PluginManager() {
            @Override public void registerEvents(org.bukkit.event.Listener listener, Plugin plugin) { }
            @Override public Plugin getPlugin(String name) { return null; }
        };
        private final BukkitScheduler scheduler = new BukkitScheduler() {
            @Override public BukkitTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
                return null;
            }
            @Override public BukkitTask runTask(Plugin plugin, Runnable task) { return null; }
        };
        private final ConsoleCommandSender console = new ConsoleCommandSender() {
            @Override public boolean hasPermission(String permission) { return true; }
            @Override public void sendMessage(Component component) { }
            @Override public void sendMessage(String message) { }
            @Override public String getName() { return "CONSOLE"; }
        };

        @Override public PluginManager getPluginManager() { return pluginManager; }
        @Override public BukkitScheduler getScheduler() { return scheduler; }
        @Override public ConsoleCommandSender getConsoleSender() { return console; }
        @Override public Collection<? extends Player> getOnlinePlayers() { return Bukkit.online; }
    }

    static final class MapConfig extends FileConfiguration {
        private final Map<String, Object> overrides;

        MapConfig(Map<String, Object> overrides) {
            this.overrides = overrides;
        }

        private Object value(String path) {
            return overrides.containsKey(path) ? overrides.get(path) : ConfigData.VALUES.get(path);
        }

        @Override public boolean getBoolean(String path, boolean def) {
            Object value = value(path);
            return value instanceof Boolean b ? b : def;
        }
        @Override public int getInt(String path, int def) {
            Object value = value(path);
            return value instanceof Number n ? n.intValue() : def;
        }
        @Override public long getLong(String path, long def) {
            Object value = value(path);
            return value instanceof Number n ? n.longValue() : def;
        }
        @Override public double getDouble(String path, double def) {
            Object value = value(path);
            return value instanceof Number n ? n.doubleValue() : def;
        }
        @Override public String getString(String path, String def) {
            Object value = value(path);
            return value instanceof String s ? s : def;
        }
        @Override public String getString(String path) { return getString(path, null); }
        @Override public List<String> getStringList(String path) {
            Object value = value(path);
            if (value instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object item : list) {
                    out.add(String.valueOf(item));
                }
                return out;
            }
            return List.of();
        }
    }

    static final class TestPlugin extends VoidAntiCheat {
        private final FileConfiguration config;
        private final Logger logger = Logger.getLogger("VoidAntiCheatSmoke");
        private final File folder = new File(System.getProperty("java.io.tmpdir"), "vac-smoke");
        private final Server server = new FakeServer();

        TestPlugin(Map<String, Object> overrides) {
            this.config = new MapConfig(overrides);
        }

        @Override public void saveDefaultConfig() { }
        @Override public void reloadConfig() { }
        @Override public FileConfiguration getConfig() { return config; }
        @Override public Logger getLogger() { return logger; }
        @Override public File getDataFolder() { return folder; }
        @Override public Server getServer() { return server; }
    }
}
