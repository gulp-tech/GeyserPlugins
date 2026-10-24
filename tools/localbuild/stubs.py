#!/usr/bin/env python3
"""Генератор заглушек Bukkit/Paper для локальной сборки без Maven.

В песочнице нет Maven и paper-api, поэтому плагины собираются ECJ против
этих заглушек. Главное правило: **вид типа должен совпадать с настоящим
API** (class / interface / enum), иначе jar скомпилируется, а сервер упадёт
с IncompatibleClassChangeError (так было с org.bukkit.Registry, который в
Paper интерфейс, и с PotionEffect, который класс).

После каждой сборки всё равно запускайте tools/paper_api_audit.py — заглушка
не заменяет сверку с реальными исходниками.

Использование:
    python3 tools/localbuild/stubs.py [каталог]     # по умолчанию /tmp/acstub
Затем:
    find <каталог> -name '*.java' > /tmp/stubsrc.txt
    java -jar ecj.jar -source 21 -target 21 -nowarn -proc:none \
         -d OUT -cp OUT @/tmp/stubsrc.txt @<список исходников плагина>
"""

import os
import sys

FILES = {}


def add(path, body):
    FILES[path] = body.strip() + "\n"


# ------------------------------------------------------------------ базовые типы

add("org/bukkit/NamespacedKey", """
package org.bukkit;

public final class NamespacedKey {
    private final String key;
    public NamespacedKey(String key) { this.key = key; }
    public static NamespacedKey minecraft(String key) { return new NamespacedKey(key); }
    public String getKey() { return key; }
    public String getNamespace() { return "minecraft"; }
    @Override public String toString() { return "minecraft:" + key; }
    @Override public boolean equals(Object other) { return other instanceof NamespacedKey k && k.key.equals(key); }
    @Override public int hashCode() { return key.hashCode(); }
}
""")

# ВАЖНО: в Paper это ИНТЕРФЕЙС с полями-константами.
add("org/bukkit/Registry", """
package org.bukkit;

import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.potion.PotionEffectType;

/** В настоящем Paper — interface (статический доступ к полям разрешён). */
public interface Registry<T> {
    Registry<Attribute> ATTRIBUTE = RegistrySupport.of("attribute");
    Registry<PotionEffectType> EFFECT = RegistrySupport.of("effect");
    Registry<PotionEffectType> MOB_EFFECT = EFFECT;
    Registry<Enchantment> ENCHANTMENT = RegistrySupport.of("enchantment");

    T get(NamespacedKey key);
}
""")

add("org/bukkit/RegistrySupport", """
package org.bukkit;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeStub;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentStub;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeStub;

/** Только для заглушек: даёт рабочие объекты вместо null. */
public final class RegistrySupport {
    private RegistrySupport() { }

    static <T> Registry<T> of(String kind) {
        return key -> {
            String name = key == null ? "" : key.getKey();
            Object value = switch (kind) {
                case "attribute" -> new AttributeStub(name);
                case "effect" -> new PotionEffectTypeStub(name);
                default -> new EnchantmentStub(name);
            };
            @SuppressWarnings("unchecked") T typed = (T) value;
            return typed;
        };
    }

    public static PotionEffectType effect(String name) { return new PotionEffectTypeStub(name); }
}
""")

add("org/bukkit/attribute/Attribute", """
package org.bukkit.attribute;

public interface Attribute {
    Attribute ATTACK_SPEED = new AttributeStub("attack_speed");
    Attribute MOVEMENT_SPEED = new AttributeStub("movement_speed");
    Attribute ENTITY_INTERACTION_RANGE = new AttributeStub("entity_interaction_range");

    String key();
}
""")

add("org/bukkit/attribute/AttributeStub", """
package org.bukkit.attribute;

public final class AttributeStub implements Attribute {
    private final String key;
    public AttributeStub(String key) { this.key = key; }
    @Override public String key() { return key; }
    @Override public String toString() { return "Attribute(" + key + ")"; }
    @Override public boolean equals(Object other) { return other instanceof AttributeStub a && a.key.equals(key); }
    @Override public int hashCode() { return key.hashCode(); }
}
""")

add("org/bukkit/attribute/AttributeInstance", """
package org.bukkit.attribute;

public interface AttributeInstance {
    double getValue();
    double getBaseValue();
    void setBaseValue(double value);
}
""")

add("org/bukkit/potion/PotionEffectType", """
package org.bukkit.potion;

/** В Paper — abstract class. */
public abstract class PotionEffectType {
    public abstract String key();
}
""")

add("org/bukkit/potion/PotionEffectTypeStub", """
package org.bukkit.potion;

public final class PotionEffectTypeStub extends PotionEffectType {
    private final String key;
    public PotionEffectTypeStub(String key) { this.key = key; }
    @Override public String key() { return key; }
    @Override public String toString() { return "PotionEffectType(" + key + ")"; }
    @Override public boolean equals(Object other) { return other instanceof PotionEffectTypeStub t && t.key.equals(key); }
    @Override public int hashCode() { return key.hashCode(); }
}
""")

# ВАЖНО: в Paper это КЛАСС (не интерфейс) — иначе invokeinterface вместо invokevirtual.
add("org/bukkit/potion/PotionEffect", """
package org.bukkit.potion;

public class PotionEffect {
    private final PotionEffectType type;
    private final int duration;
    private final int amplifier;

    public PotionEffect(PotionEffectType type, int duration, int amplifier) {
        this.type = type;
        this.duration = duration;
        this.amplifier = amplifier;
    }

    public PotionEffectType getType() { return type; }
    public int getDuration() { return duration; }
    public int getAmplifier() { return amplifier; }
}
""")

add("org/bukkit/enchantments/Enchantment", """
package org.bukkit.enchantments;

/** В Paper — abstract class. */
public abstract class Enchantment {
    public abstract String key();
}
""")

add("org/bukkit/enchantments/EnchantmentStub", """
package org.bukkit.enchantments;

public final class EnchantmentStub extends Enchantment {
    private final String key;
    public EnchantmentStub(String key) { this.key = key; }
    @Override public String key() { return key; }
    @Override public String toString() { return "Enchantment(" + key + ")"; }
    @Override public boolean equals(Object other) { return other instanceof EnchantmentStub e && e.key.equals(key); }
    @Override public int hashCode() { return key.hashCode(); }
}
""")

add("org/bukkit/GameMode", """
package org.bukkit;

public enum GameMode { SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR }
""")

add("org/bukkit/Material", """
package org.bukkit;

public enum Material {
    AIR, WATER, LAVA, COBWEB, POWDER_SNOW, BUBBLE_COLUMN, SCAFFOLDING, SLIME_BLOCK, HONEY_BLOCK,
    HAY_BLOCK, ICE, PACKED_ICE, BLUE_ICE, FROSTED_ICE, SOUL_SAND, SOUL_SOIL, LADDER, VINE,
    TWISTING_VINES, WEEPING_VINES, TWISTING_VINES_PLANT, WEEPING_VINES_PLANT, CAVE_VINES,
    CAVE_VINES_PLANT, STONE, DIRT, GRASS_BLOCK, CHEST;

    public boolean isSolid() {
        return switch (this) {
            case AIR, WATER, LAVA, BUBBLE_COLUMN, POWDER_SNOW -> false;
            default -> true;
        };
    }

    public boolean isOccluding() {
        return switch (this) {
            case AIR, WATER, LAVA, BUBBLE_COLUMN, POWDER_SNOW, COBWEB, SCAFFOLDING, LADDER, VINE,
                 TWISTING_VINES, WEEPING_VINES, TWISTING_VINES_PLANT, WEEPING_VINES_PLANT, CAVE_VINES,
                 CAVE_VINES_PLANT -> false;
            default -> true;
        };
    }

    public boolean isLiquid() { return this == WATER || this == LAVA; }
}
""")

add("org/bukkit/util/Vector", """
package org.bukkit.util;

public class Vector {
    private double x;
    private double y;
    private double z;

    public Vector() { this(0, 0, 0); }

    public Vector(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public Vector setX(double value) { x = value; return this; }
    public Vector setY(double value) { y = value; return this; }
    public Vector setZ(double value) { z = value; return this; }
    public double lengthSquared() { return x * x + y * y + z * z; }
    public double length() { return Math.sqrt(lengthSquared()); }
}
""")

add("org/bukkit/util/BoundingBox", """
package org.bukkit.util;

public class BoundingBox {
    private final double minX;
    private final double minY;
    private final double minZ;
    private final double maxX;
    private final double maxY;
    private final double maxZ;

    public BoundingBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public double getMinX() { return minX; }
    public double getMinY() { return minY; }
    public double getMinZ() { return minZ; }
    public double getMaxX() { return maxX; }
    public double getMaxY() { return maxY; }
    public double getMaxZ() { return maxZ; }
}
""")

add("org/bukkit/Location", """
package org.bukkit;

import org.bukkit.util.Vector;

public class Location implements Cloneable {
    private final World world;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;

    public Location(World world, double x, double y, double z) { this(world, x, y, z, 0f, 0f); }

    public Location(World world, double x, double y, double z, float yaw, float pitch) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public World getWorld() { return world; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
    public void setYaw(float value) { yaw = value; }
    public void setPitch(float value) { pitch = value; }
    public int getBlockX() { return (int) Math.floor(x); }
    public int getBlockY() { return (int) Math.floor(y); }
    public int getBlockZ() { return (int) Math.floor(z); }
    public Location add(double dx, double dy, double dz) { x += dx; y += dy; z += dz; return this; }
    public Location add(Vector vector) { return add(vector.getX(), vector.getY(), vector.getZ()); }
    public Vector toVector() { return new Vector(x, y, z); }
    public double distance(Location other) { return Math.sqrt(distanceSquared(other)); }

    public double distanceSquared(Location other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override public Location clone() { return new Location(world, x, y, z, yaw, pitch); }
}
""")

add("org/bukkit/World", """
package org.bukkit;

import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.Collection;
import java.util.List;

public interface World {
    String getName();
    Block getBlockAt(int x, int y, int z);
    Collection<Entity> getNearbyEntities(BoundingBox box);
    List<Player> getPlayers();
    int getMaxHeight();
    void spawnParticle(Particle particle, Location location, int count,
                       double offsetX, double offsetY, double offsetZ, double extra);
    void playSound(Location location, Sound sound, float volume, float pitch);
}
""")

add("org/bukkit/block/Block", """
package org.bukkit.block;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

public interface Block {
    Material getType();
    World getWorld();
    Location getLocation();
    boolean isLiquid();
    int getX();
    int getY();
    int getZ();
}
""")

add("org/bukkit/entity/Entity", """
package org.bukkit.entity;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.UUID;

public interface Entity {
    UUID getUniqueId();
    Location getLocation();
    World getWorld();
    Vector getVelocity();
    BoundingBox getBoundingBox();
    boolean isInWater();
    boolean isDead();
    boolean equals(Object other);
    String getName();
    EntityType getType();
    double getHeight();
    double getWidth();
    boolean isValid();
    void remove();
    void setCustomName(String name);
    String getCustomName();
    void setCustomNameVisible(boolean visible);
    boolean teleport(Location location);
}
""")

add("org/bukkit/entity/HumanEntity", """
package org.bukkit.entity;

import org.bukkit.GameMode;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;

public interface HumanEntity extends LivingEntity {
    String getName();
    Inventory getInventory$placeholder();
    PlayerInventory getInventory();
    Inventory getEnderChest();
    GameMode getGameMode();
    boolean hasPermission(String permission);
}
""")

add("org/bukkit/entity/LivingEntity", """
package org.bukkit.entity;

import org.bukkit.Location;

public interface LivingEntity extends Entity {
    Location getEyeLocation();
    float getFallDistance();
    int getNoDamageTicks();
    void setNoDamageTicks(int ticks);
}
""")

add("org/bukkit/entity/Boat", """
package org.bukkit.entity;

public interface Boat extends Entity {
}
""")

add("org/bukkit/entity/Player", """
package org.bukkit.entity;

import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.Sound;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public interface Player extends LivingEntity {
    String getName();
    boolean isOnline();
    boolean isDead();
    GameMode getGameMode();
    boolean hasPermission(String permission);
    void sendMessage(Component component);
    int getPing();
    boolean isOnGround();
    boolean isFlying();
    boolean isGliding();
    boolean isSwimming();
    boolean isSneaking();
    boolean isSprinting();
    boolean isSleeping();
    Entity getVehicle();
    boolean leaveVehicle();
    boolean teleport(Location location);
    AttributeInstance getAttribute(Attribute attribute);
    PlayerInventory getInventory();
    PotionEffect getPotionEffect(PotionEffectType type);
    boolean hasLineOfSight(Entity entity);
    void playSound(Location location, Sound sound, float volume, float pitch);
    World getWorld();
    Location getLocation();
    Location getEyeLocation();
}
""")

add("org/bukkit/inventory/EquipmentSlot", """
package org.bukkit.inventory;

public enum EquipmentSlot { HAND, OFF_HAND, HEAD, CHEST, LEGS, FEET }
""")

add("org/bukkit/inventory/meta/ItemMeta", """
package org.bukkit.inventory.meta;

public interface ItemMeta {
    boolean hasDisplayName();
}
""")

add("org/bukkit/inventory/ItemStack", """
package org.bukkit.inventory;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.meta.ItemMeta;

public class ItemStack {
    private final org.bukkit.Material type;
    private int amount;

    public ItemStack(org.bukkit.Material type) { this(type, 1); }

    public ItemStack(org.bukkit.Material type, int amount) {
        this.type = type;
        this.amount = amount;
    }

    public org.bukkit.Material getType() { return type; }
    public int getAmount() { return amount; }
    public void setAmount(int amount) { this.amount = amount; }
    public ItemMeta getItemMeta() { return null; }
    public void setItemMeta(ItemMeta meta) { }
    public int getEnchantmentLevel(Enchantment enchantment) { return 0; }
    public boolean isAir() { return type == null || type == org.bukkit.Material.AIR; }
}
""")

add("org/bukkit/inventory/PlayerInventory", """
package org.bukkit.inventory;

public interface PlayerInventory {
    ItemStack getBoots();
    ItemStack getItemInMainHand();
}
""")

# ------------------------------------------------------------------ команды, плагины

add("org/bukkit/command/CommandSender", """
package org.bukkit.command;

import net.kyori.adventure.text.Component;

public interface CommandSender {
    boolean hasPermission(String permission);
    void sendMessage(Component component);
    void sendMessage(String message);
    String getName();
}
""")

add("org/bukkit/command/ConsoleCommandSender", """
package org.bukkit.command;

public interface ConsoleCommandSender extends CommandSender {
}
""")

add("org/bukkit/command/Command", """
package org.bukkit.command;

import java.util.List;

public abstract class Command {
    public abstract boolean execute(CommandSender sender, String commandLabel, String[] args);

    public abstract List<String> tabComplete(CommandSender sender, String alias, String[] args);
}
""")

add("org/bukkit/command/CommandExecutor", """
package org.bukkit.command;

public interface CommandExecutor {
    boolean onCommand(CommandSender sender, Command command, String label, String[] args);
}
""")

add("org/bukkit/command/TabCompleter", """
package org.bukkit.command;

import java.util.List;

public interface TabCompleter {
    List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args);
}
""")

add("org/bukkit/command/PluginCommand", """
package org.bukkit.command;

public final class PluginCommand extends Command {
    private CommandExecutor executor;
    private TabCompleter completer;

    public void setExecutor(CommandExecutor executor) { this.executor = executor; }
    public void setTabCompleter(TabCompleter completer) { this.completer = completer; }
    public CommandExecutor getExecutor() { return executor; }
    public TabCompleter getTabCompleter() { return completer; }

    @Override public boolean execute(CommandSender sender, String label, String[] args) {
        return executor != null && executor.onCommand(sender, this, label, args);
    }

    @Override public java.util.List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return completer == null ? java.util.List.of() : completer.onTabComplete(sender, this, alias, args);
    }
}
""")

add("org/bukkit/plugin/PluginDescriptionFile", """
package org.bukkit.plugin;

public class PluginDescriptionFile {
    public String getVersion() { return "0.0.0"; }
    public String getName() { return "Stub"; }
}
""")

add("org/bukkit/plugin/Plugin", """
package org.bukkit.plugin;

public interface Plugin {
    String getName();
}
""")

add("org/bukkit/plugin/PluginManager", """
package org.bukkit.plugin;

import org.bukkit.event.Listener;

public interface PluginManager {
    void registerEvents(Listener listener, Plugin plugin);
    Plugin getPlugin(String name);
}
""")

add("org/bukkit/plugin/java/JavaPlugin", """
package org.bukkit.plugin.java;

import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import java.io.File;
import java.util.logging.Logger;

public abstract class JavaPlugin implements Plugin {
    public void onEnable() { }
    public void onDisable() { }
    public void saveDefaultConfig() { }
    public void reloadConfig() { }
    public FileConfiguration getConfig() { return null; }
    public Logger getLogger() { return Logger.getLogger(getClass().getName()); }
    public File getDataFolder() { return new File("."); }
    public Server getServer() { return org.bukkit.Bukkit.getServer(); }
    public PluginCommand getCommand(String name) { return null; }
    public String getName() { return getClass().getSimpleName(); }
    public PluginDescriptionFile getDescription() { return new PluginDescriptionFile(); }
}
""")

add("org/bukkit/scheduler/BukkitTask", """
package org.bukkit.scheduler;

public interface BukkitTask {
    void cancel();
    int getTaskId();
}
""")

add("org/bukkit/scheduler/BukkitScheduler", """
package org.bukkit.scheduler;

import org.bukkit.plugin.Plugin;

public interface BukkitScheduler {
    BukkitTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period);
    BukkitTask runTask(Plugin plugin, Runnable task);
}
""")

# ------------------------------------------------------------------ конфиг

add("org/bukkit/configuration/file/FileConfiguration", """
package org.bukkit.configuration.file;

import java.util.List;

public abstract class FileConfiguration {
    public abstract boolean getBoolean(String path, boolean def);
    public abstract int getInt(String path, int def);
    public abstract long getLong(String path, long def);
    public abstract double getDouble(String path, double def);
    public abstract String getString(String path, String def);
    public abstract String getString(String path);
    public abstract List<String> getStringList(String path);
}
""")

# ------------------------------------------------------------------ события

add("org/bukkit/event/Event", """
package org.bukkit.event;

public abstract class Event {
}
""")

add("org/bukkit/event/Cancellable", """
package org.bukkit.event;

public interface Cancellable {
    boolean isCancelled();
    void setCancelled(boolean cancel);
}
""")

add("org/bukkit/event/Listener", """
package org.bukkit.event;

public interface Listener {
}
""")

add("org/bukkit/event/EventPriority", """
package org.bukkit.event;

public enum EventPriority { LOWEST, LOW, NORMAL, HIGH, HIGHEST, MONITOR }
""")

add("org/bukkit/event/EventHandler", """
package org.bukkit.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventHandler {
    EventPriority priority() default EventPriority.NORMAL;
    boolean ignoreCancelled() default false;
}
""")

add("org/bukkit/event/player/PlayerEvent", """
package org.bukkit.event.player;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;

public abstract class PlayerEvent extends Event {
    private final Player player;

    protected PlayerEvent(Player player) { this.player = player; }
    public Player getPlayer() { return player; }
}
""")

add("org/bukkit/event/player/PlayerMoveEvent", """
package org.bukkit.event.player;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;

public class PlayerMoveEvent extends PlayerEvent implements Cancellable {
    private final Location from;
    private Location to;
    private boolean cancelled;

    public PlayerMoveEvent(Player player, Location from, Location to) {
        super(player);
        this.from = from;
        this.to = to;
    }

    public Location getFrom() { return from; }
    public Location getTo() { return to; }
    public void setTo(Location to) { this.to = to; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
}
""")

add("org/bukkit/event/player/PlayerJoinEvent", """
package org.bukkit.event.player;

import org.bukkit.entity.Player;

public class PlayerJoinEvent extends PlayerEvent {
    public PlayerJoinEvent(Player player) { super(player); }
}
""")

add("org/bukkit/event/player/PlayerQuitEvent", """
package org.bukkit.event.player;

import org.bukkit.entity.Player;

public class PlayerQuitEvent extends PlayerEvent {
    public PlayerQuitEvent(Player player) { super(player); }
}
""")

add("org/bukkit/event/player/PlayerTeleportEvent", """
package org.bukkit.event.player;

import org.bukkit.Location;
import org.bukkit.entity.Player;

public class PlayerTeleportEvent extends PlayerEvent {
    private final Location to;

    public PlayerTeleportEvent(Player player, Location from, Location to) {
        super(player);
        this.to = to;
    }

    public Location getTo() { return to; }
}
""")

add("org/bukkit/event/player/PlayerRespawnEvent", """
package org.bukkit.event.player;

import org.bukkit.Location;
import org.bukkit.entity.Player;

public class PlayerRespawnEvent extends PlayerEvent {
    private final Location respawnLocation;

    public PlayerRespawnEvent(Player player, Location respawnLocation) {
        super(player);
        this.respawnLocation = respawnLocation;
    }

    public Location getRespawnLocation() { return respawnLocation; }
}
""")

add("org/bukkit/event/player/PlayerChangedWorldEvent", """
package org.bukkit.event.player;

import org.bukkit.entity.Player;

public class PlayerChangedWorldEvent extends PlayerEvent {
    public PlayerChangedWorldEvent(Player player) { super(player); }
}
""")

add("org/bukkit/event/player/PlayerGameModeChangeEvent", """
package org.bukkit.event.player;

import org.bukkit.entity.Player;

public class PlayerGameModeChangeEvent extends PlayerEvent {
    public PlayerGameModeChangeEvent(Player player) { super(player); }
}
""")

add("org/bukkit/event/player/PlayerVelocityEvent", """
package org.bukkit.event.player;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public class PlayerVelocityEvent extends PlayerEvent {
    private final Vector velocity;

    public PlayerVelocityEvent(Player player, Vector velocity) {
        super(player);
        this.velocity = velocity;
    }

    public Vector getVelocity() { return velocity; }
}
""")

add("org/bukkit/event/player/PlayerRiptideEvent", """
package org.bukkit.event.player;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class PlayerRiptideEvent extends PlayerEvent {
    public PlayerRiptideEvent(Player player) { super(player); }
    public ItemStack getItem() { return null; }
}
""")

add("org/bukkit/event/entity/EntityEvent", """
package org.bukkit.event.entity;

import org.bukkit.entity.Entity;
import org.bukkit.event.Event;

public abstract class EntityEvent extends Event {
    private final Entity entity;

    protected EntityEvent(Entity entity) { this.entity = entity; }
    public Entity getEntity() { return entity; }
}
""")

add("org/bukkit/event/entity/EntityDamageEvent", """
package org.bukkit.event.entity;

import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;

public class EntityDamageEvent extends EntityEvent implements Cancellable {
    public enum DamageCause { ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE, MAGIC, FIRE, VOID, CUSTOM }

    private boolean cancelled;
    private double damage;
    private final DamageCause cause;

    public EntityDamageEvent(Entity entity) { this(entity, DamageCause.CUSTOM, 0.0); }

    public EntityDamageEvent(Entity entity, DamageCause cause, double damage) {
        super(entity);
        this.cause = cause;
        this.damage = damage;
    }

    public DamageCause getCause() { return cause; }
    public double getDamage() { return damage; }
    public void setDamage(double damage) { this.damage = damage; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
}
""")

add("org/bukkit/event/entity/EntityDamageByEntityEvent", """
package org.bukkit.event.entity;

import org.bukkit.entity.Entity;

public class EntityDamageByEntityEvent extends EntityDamageEvent {
    private final Entity damager;
    private final double damage;

    public EntityDamageByEntityEvent(Entity damager, Entity victim, double damage) {
        super(victim, DamageCause.ENTITY_ATTACK, damage);
        this.damager = damager;
        this.damage = damage;
    }

    public Entity getDamager() { return damager; }
}
""")

add("org/bukkit/event/entity/PlayerDeathEvent", """
package org.bukkit.event.entity;

import org.bukkit.entity.Player;

public class PlayerDeathEvent extends EntityEvent {
    public PlayerDeathEvent(Player player) { super(player); }
    @Override public Player getEntity() { return (Player) super.getEntity(); }
}
""")

add("org/bukkit/event/entity/EntityExplodeEvent", """
package org.bukkit.event.entity;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

public class EntityExplodeEvent extends EntityEvent {
    private final Location location;

    public EntityExplodeEvent(Entity entity, Location location) {
        super(entity);
        this.location = location;
    }

    public Location getLocation() { return location; }
}
""")

add("org/bukkit/event/block/BlockEvent", """
package org.bukkit.event.block;

import org.bukkit.block.Block;
import org.bukkit.event.Event;

public abstract class BlockEvent extends Event {
    private final Block block;

    protected BlockEvent(Block block) { this.block = block; }
    public Block getBlock() { return block; }
}
""")

add("org/bukkit/event/block/BlockBreakEvent", """
package org.bukkit.event.block;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;

public class BlockBreakEvent extends BlockEvent implements Cancellable {
    private final Player player;
    private boolean cancelled;

    public BlockBreakEvent(Block block, Player player) {
        super(block);
        this.player = player;
    }

    public Player getPlayer() { return player; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
}
""")

add("org/bukkit/event/block/BlockPlaceEvent", """
package org.bukkit.event.block;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;

public class BlockPlaceEvent extends BlockEvent implements Cancellable {
    private final Block blockPlaced;
    private final Player player;
    private boolean cancelled;

    public BlockPlaceEvent(Block blockPlaced, Block blockAgainst, Player player) {
        super(blockPlaced);
        this.blockPlaced = blockPlaced;
        this.player = player;
    }

    public Block getBlockPlaced() { return blockPlaced; }
    public Player getPlayer() { return player; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
}
""")

add("org/bukkit/event/block/BlockPistonExtendEvent", """
package org.bukkit.event.block;

import org.bukkit.block.Block;

import java.util.List;

public class BlockPistonExtendEvent extends BlockEvent implements org.bukkit.event.Cancellable {
    private boolean cancelled;

    public BlockPistonExtendEvent(Block block) { super(block); }
    public List<Block> getBlocks() { return List.of(); }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
}
""")

add("org/bukkit/event/block/BlockPistonRetractEvent", """
package org.bukkit.event.block;

import org.bukkit.block.Block;

import java.util.List;

public class BlockPistonRetractEvent extends BlockEvent implements org.bukkit.event.Cancellable {
    private boolean cancelled;

    public BlockPistonRetractEvent(Block block) { super(block); }
    public List<Block> getBlocks() { return List.of(); }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
}
""")

# Paper-события: в реальном API это классы
add("io/papermc/paper/event/player/PlayerArmSwingEvent", """
package io.papermc.paper.event.player;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.EquipmentSlot;

public class PlayerArmSwingEvent extends Event {
    private final Player player;
    private final EquipmentSlot hand;

    public PlayerArmSwingEvent(Player player, EquipmentSlot hand) {
        this.player = player;
        this.hand = hand;
    }

    public Player getPlayer() { return player; }
    public EquipmentSlot getHand() { return hand; }
}
""")

add("com/destroystokyo/paper/event/player/PlayerElytraBoostEvent", """
package com.destroystokyo.paper.event.player;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;

public class PlayerElytraBoostEvent extends Event {
    private final Player player;

    public PlayerElytraBoostEvent(Player player) { this.player = player; }
    public Player getPlayer() { return player; }
}
""")


# ------------------------------------------------------------------ частицы/звук/типы (VoidPvP)

add("org/bukkit/Particle", """
package org.bukkit;

public enum Particle { CRIT, ENCHANTED_HIT, SWEEP_ATTACK, DAMAGE_INDICATOR }
""")

add("org/bukkit/Sound", """
package org.bukkit;

public enum Sound { ENTITY_PLAYER_ATTACK_CRIT, ENTITY_PLAYER_ATTACK_SWEEP, ENTITY_PLAYER_HURT }
""")

add("org/bukkit/entity/EntityType", """
package org.bukkit.entity;

public enum EntityType { PLAYER, ZOMBIE, SKELETON, CREEPER, ENDERMAN }
""")


add("org/bukkit/event/block/BlockExplodeEvent", """
package org.bukkit.event.block;

import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;

public class BlockExplodeEvent extends BlockEvent implements org.bukkit.event.Cancellable {
    private final List<Block> blocks = new ArrayList<>();
    private boolean cancelled;

    public BlockExplodeEvent(Block block) { super(block); }
    public List<Block> blockList() { return blocks; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
}
""")

add("org/bukkit/event/block/BlockBurnEvent", """
package org.bukkit.event.block;

import org.bukkit.block.Block;

public class BlockBurnEvent extends BlockEvent implements org.bukkit.event.Cancellable {
    private boolean cancelled;

    public BlockBurnEvent(Block block) { super(block); }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
}
""")

add("org/bukkit/event/block/BlockFromToEvent", """
package org.bukkit.event.block;

import org.bukkit.block.Block;

public class BlockFromToEvent extends BlockEvent implements org.bukkit.event.Cancellable {
    private final Block to;
    private boolean cancelled;

    public BlockFromToEvent(Block from, Block to) {
        super(from);
        this.to = to;
    }

    public Block getToBlock() { return to; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
}
""")

add("org/bukkit/event/block/BlockIgniteEvent", """
package org.bukkit.event.block;

import org.bukkit.block.Block;

public class BlockIgniteEvent extends BlockEvent implements org.bukkit.event.Cancellable {
    public enum IgniteCause { LAVA, FLINT_AND_STEEL, SPREAD, LIGHTNING, FIREBALL, EXPLOSION }

    private final IgniteCause cause;
    private boolean cancelled;

    public BlockIgniteEvent(Block block, IgniteCause cause) {
        super(block);
        this.cause = cause;
    }

    public IgniteCause getCause() { return cause; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
}
""")

add("org/bukkit/event/entity/EntityChangeBlockEvent", """
package org.bukkit.event.entity;

import org.bukkit.block.Block;
import org.bukkit.entity.Entity;

public class EntityChangeBlockEvent extends EntityEvent implements org.bukkit.event.Cancellable {
    private final Block block;
    private boolean cancelled;

    public EntityChangeBlockEvent(Entity entity, Block block) {
        super(entity);
        this.block = block;
    }

    public Block getBlock() { return block; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
}
""")

add("org/bukkit/event/entity/EntityDeathEvent", """
package org.bukkit.event.entity;

import org.bukkit.entity.LivingEntity;

public class EntityDeathEvent extends EntityEvent {
    public EntityDeathEvent(LivingEntity entity) { super(entity); }
    public LivingEntity getEntity() { return (LivingEntity) super.getEntity(); }
}
""")

add("org/bukkit/block/Chest", """
package org.bukkit.block;

import org.bukkit.inventory.Inventory;

public interface Chest extends Block {
    Inventory getInventory();
    Inventory getBlockInventory();
}
""")

add("org/bukkit/entity/ArmorStand", """
package org.bukkit.entity;

public interface ArmorStand extends LivingEntity {
    void setVisible(boolean visible);
    void setMarker(boolean marker);
    void setCustomNameVisible(boolean visible);
    boolean isMarker();
    void setGravity(boolean gravity);
}
""")

add("org/bukkit/entity/Warden", """
package org.bukkit.entity;

public interface Warden extends LivingEntity {
}
""")

add("org/bukkit/inventory/Inventory", """
package org.bukkit.inventory;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;

public interface Inventory {
    ItemStack[] getContents();
    void setContents(ItemStack[] items);
    int getSize();
    ItemStack getItem(int slot);
    void setItem(int slot, ItemStack item);
    void addItem(ItemStack... items);
    java.util.HashMap<Integer, ItemStack> addItem(ItemStack item);
    ItemStack[] getStorageContents();
    void setStorageContents(ItemStack[] items);
    InventoryHolder getHolder();
    Location getLocation();
    boolean isEmpty();
    java.util.List<HumanEntity> getViewers();
    void clear();
}
""")

add("org/bukkit/inventory/InventoryHolder", """
package org.bukkit.inventory;

public interface InventoryHolder {
    Inventory getInventory();
}
""")

add("org/bukkit/event/inventory/InventoryEvent", """
package org.bukkit.event.inventory;

import org.bukkit.event.Event;
import org.bukkit.inventory.Inventory;

public abstract class InventoryEvent extends Event {
    private final Inventory inventory;

    protected InventoryEvent(Inventory inventory) { this.inventory = inventory; }
    public Inventory getInventory() { return inventory; }
}
""")

add("org/bukkit/event/inventory/InventoryOpenEvent", """
package org.bukkit.event.inventory;

import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Inventory;

public class InventoryOpenEvent extends InventoryEvent {
    private final HumanEntity player;

    public InventoryOpenEvent(Inventory inventory, HumanEntity player) {
        super(inventory);
        this.player = player;
    }

    public HumanEntity getPlayer() { return player; }
}
""")

add("org/bukkit/event/inventory/InventoryCloseEvent", """
package org.bukkit.event.inventory;

import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Inventory;

public class InventoryCloseEvent extends InventoryEvent {
    private final HumanEntity player;

    public InventoryCloseEvent(Inventory inventory, HumanEntity player) {
        super(inventory);
        this.player = player;
    }

    public HumanEntity getPlayer() { return player; }
}
""")

add("org/bukkit/event/inventory/InventoryClickEvent", """
package org.bukkit.event.inventory;

import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class InventoryClickEvent extends InventoryEvent implements org.bukkit.event.Cancellable {
    private final HumanEntity whoClicked;
    private final ItemStack currentItem;
    private final int slot;
    private boolean cancelled;

    public InventoryClickEvent(Inventory inventory, HumanEntity whoClicked, ItemStack currentItem, int slot) {
        super(inventory);
        this.whoClicked = whoClicked;
        this.currentItem = currentItem;
        this.slot = slot;
    }

    public HumanEntity getWhoClicked() { return whoClicked; }
    public ItemStack getCurrentItem() { return currentItem; }
    public int getSlot() { return slot; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
}
""")

add("org/bukkit/potion/PotionType", """
package org.bukkit.potion;

public enum PotionType { WATER, MUNDANE, THICK, AWKWARD, SWIFTNESS, SLOWNESS, STRENGTH, HEALING, HARMING, POISON, REGENERATION }
""")

add("org/bukkit/inventory/meta/EnchantmentStorageMeta", """
package org.bukkit.inventory.meta;

import org.bukkit.enchantments.Enchantment;

public interface EnchantmentStorageMeta extends ItemMeta {
    boolean addStoredEnchant(Enchantment enchantment, int level, boolean ignoreLevelRestriction);
    int getStoredEnchantLevel(Enchantment enchantment);
}
""")

add("org/bukkit/inventory/meta/PotionMeta", """
package org.bukkit.inventory.meta;

import org.bukkit.potion.PotionType;

public interface PotionMeta extends ItemMeta {
    void setBasePotionType(PotionType type);
    void setBasePotionData(PotionType type);
}
""")

add("org/bukkit/configuration/file/YamlConfiguration", """
package org.bukkit.configuration.file;

public class YamlConfiguration extends FileConfiguration {
    private final java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();

    public static YamlConfiguration loadConfiguration(java.io.File file) throws java.io.IOException {
        return new YamlConfiguration();
    }

    public void loadFromString(String contents) { }
    public String saveToString() { return ""; }
    public void save(java.io.File file) { }
    public java.util.Set<String> getKeys(boolean deep) { return values.keySet(); }
    public boolean contains(String path) { return values.containsKey(path); }
    public boolean isConfigurationSection(String path) { return false; }
    public java.util.List<java.util.Map<?, ?>> getMapList(String path) { return java.util.List.of(); }
    public Object get(String path) { return values.get(path); }
    public void set(String path, Object value) { values.put(path, value); }

    @Override public boolean getBoolean(String path, boolean def) {
        Object value = values.get(path);
        return value instanceof Boolean b ? b : def;
    }
    @Override public int getInt(String path, int def) {
        Object value = values.get(path);
        return value instanceof Number n ? n.intValue() : def;
    }
    @Override public long getLong(String path, long def) {
        Object value = values.get(path);
        return value instanceof Number n ? n.longValue() : def;
    }
    @Override public double getDouble(String path, double def) {
        Object value = values.get(path);
        return value instanceof Number n ? n.doubleValue() : def;
    }
    @Override public String getString(String path, String def) {
        Object value = values.get(path);
        return value instanceof String s ? s : def;
    }
    @Override public String getString(String path) { return getString(path, null); }
    @Override @SuppressWarnings("unchecked")
    public java.util.List<String> getStringList(String path) {
        Object value = values.get(path);
        return value instanceof java.util.List<?> list ? (java.util.List<String>) list : java.util.List.of();
    }
}
""")

# ------------------------------------------------------------------ сервер

add("org/bukkit/Server", """
package org.bukkit;

import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;

import java.util.Collection;

public interface Server {
    PluginManager getPluginManager();
    BukkitScheduler getScheduler();
    ConsoleCommandSender getConsoleSender();
    Collection<? extends Player> getOnlinePlayers();
}
""")

add("org/bukkit/Bukkit", """
package org.bukkit;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class Bukkit {
    public static Server server;
    public static int tick;
    public static double[] tps = {20.0, 20.0, 20.0};
    public static final List<Player> online = new ArrayList<>();

    public static Server getServer() { return server; }
    public static PluginManager getPluginManager() { return server == null ? null : server.getPluginManager(); }
    public static BukkitScheduler getScheduler() { return server == null ? null : server.getScheduler(); }
    public static ConsoleCommandSender getConsoleSender() { return server == null ? null : server.getConsoleSender(); }
    public static Collection<? extends Player> getOnlinePlayers() { return online; }

    public static Player getPlayerExact(String name) {
        for (Player player : online) {
            if (player.getName().equals(name)) {
                return player;
            }
        }
        return null;
    }

    public static Player getPlayer(UUID id) {
        for (Player player : online) {
            if (player.getUniqueId().equals(id)) {
                return player;
            }
        }
        return null;
    }

    public static org.bukkit.World getWorld(String name) { return worlds.get(name); }
    public static org.bukkit.World getWorld(java.util.UUID id) { return worlds.values().stream().findFirst().orElse(null); }
    public static final java.util.Map<String, org.bukkit.World> worlds = new java.util.HashMap<>();

    public static org.bukkit.entity.Entity getEntity(java.util.UUID id) { return null; }

    public static org.bukkit.inventory.Inventory createInventory(
            org.bukkit.inventory.InventoryHolder holder, int size, String title) {
        return null;
    }

    public static org.bukkit.inventory.Inventory createInventory(
            org.bukkit.inventory.InventoryHolder holder, int size, net.kyori.adventure.text.Component title) {
        return null;
    }

    public static int getCurrentTick() { return tick; }
    public static double[] getTPS() { return tps; }
    public static boolean dispatchCommand(CommandSender sender, String commandLine) { return true; }
}
""")

# ------------------------------------------------------------------ Adventure (упрощённо)

add("net/kyori/adventure/text/Component", """
package net.kyori.adventure.text;

public interface Component {
    Component decoration(net.kyori.adventure.text.format.TextDecoration decoration, boolean value);
    Component clickEvent(net.kyori.adventure.text.event.ClickEvent event);
    Component hoverEvent(net.kyori.adventure.text.event.HoverEvent event);
}
""")

add("net/kyori/adventure/text/event/ClickEvent", """
package net.kyori.adventure.text.event;

public final class ClickEvent {
    private ClickEvent() { }
    public static ClickEvent runCommand(String command) { return new ClickEvent(); }
}
""")

add("net/kyori/adventure/text/event/HoverEvent", """
package net.kyori.adventure.text.event;

public final class HoverEvent {
    private HoverEvent() { }
    public static HoverEvent showText(net.kyori.adventure.text.Component text) { return new HoverEvent(); }
}
""")

add("net/kyori/adventure/text/format/TextDecoration", """
package net.kyori.adventure.text.format;

public enum TextDecoration { ITALIC, BOLD, UNDERLINED }
""")

add("net/kyori/adventure/text/serializer/legacy/LegacyComponentSerializer", """
package net.kyori.adventure.text.serializer.legacy;

import net.kyori.adventure.text.Component;

public final class LegacyComponentSerializer {
    private LegacyComponentSerializer() { }

    public static LegacyComponentSerializer legacySection() { return new LegacyComponentSerializer(); }

    public Component deserialize(String input) {
        return new Component() {
            @Override public Component decoration(net.kyori.adventure.text.format.TextDecoration decoration, boolean value) { return this; }
            @Override public Component clickEvent(net.kyori.adventure.text.event.ClickEvent event) { return this; }
            @Override public Component hoverEvent(net.kyori.adventure.text.event.HoverEvent event) { return this; }
        };
    }
}
""")

add("net/kyori/adventure/text/serializer/plain/PlainTextComponentSerializer", """
package net.kyori.adventure.text.serializer.plain;

import net.kyori.adventure.text.Component;

public final class PlainTextComponentSerializer {
    private PlainTextComponentSerializer() { }

    public static PlainTextComponentSerializer plainText() { return new PlainTextComponentSerializer(); }
    public String serialize(Component component) { return String.valueOf(component); }
}
""")


def main(argv):
    target = argv[1] if len(argv) > 1 else "/tmp/acstub"
    for path, body in FILES.items():
        full = os.path.join(target, path + ".java")
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as fh:
            fh.write(body)
    print("заглушек записано: %d → %s" % (len(FILES), target))


if __name__ == "__main__":
    main(sys.argv)
