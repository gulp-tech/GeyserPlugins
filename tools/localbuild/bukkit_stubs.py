#!/usr/bin/env python3
"""Заглушки Bukkit/Paper для локальной сборки (Maven и paper-api недоступны).

ВАЖНО: вид типа и сигнатуры здесь обязаны совпадать с настоящим API.
Компилятор разницы не видит: если объявить класс там, где в Paper интерфейс,
ECJ сгенерирует invokevirtual вместо invokeinterface и сервер упадёт с
IncompatibleClassChangeError (так падали VoidAntiCheat 2.0.0 и VoidPvP 1.0.0).

Поэтому после каждой локальной сборки обязательна сверка:

    python3 tools/paperapi_audit.py <jar>

Тела методов здесь — заглушки: плагины компилируются, но не запускаются на
этих классах. Значения по умолчанию (0/null) нужны только компилятору.

    python3 tools/localbuild/bukkit_stubs.py /tmp/stubs
"""

import os
import sys

FILES = {}


def add(path, body):
    FILES[path] = body.strip() + "\n"


# ------------------------------------------------------------------ базовые значения

add("org/bukkit/ChatColor", """
package org.bukkit;

public final class ChatColor {
    public static final char COLOR_CHAR = '\u00A7';

    private ChatColor() {
    }

    public static String translateAlternateColorCodes(char altColorChar, String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == altColorChar && "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx".indexOf(chars[i + 1]) > -1) {
                chars[i] = COLOR_CHAR;
                chars[i + 1] = Character.toLowerCase(chars[i + 1]);
            }
        }
        return new String(chars);
    }

    public static String stripColor(String text) {
        return text == null ? null : text.replaceAll("(?i)\u00A7[0-9A-FK-ORX]", "");
    }
}
""")

add("org/bukkit/NamespacedKey", """
package org.bukkit;

public final class NamespacedKey {
    private final String key;

    public NamespacedKey(String key) {
        this.key = key;
    }

    public NamespacedKey(String namespace, String key) {
        this.key = key;
    }

    public NamespacedKey(org.bukkit.plugin.Plugin plugin, String key) {
        this.key = key;
    }

    public static NamespacedKey minecraft(String key) {
        return new NamespacedKey(key);
    }

    public String getKey() {
        return key;
    }

    public String getNamespace() {
        return "minecraft";
    }

    @Override
    public String toString() {
        return "minecraft:" + key;
    }
}
""")

# В Paper это ИНТЕРФЕЙС (статический доступ к полям разрешён).
add("org/bukkit/Registry", """
package org.bukkit;

import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.potion.PotionEffectType;

public interface Registry<T> {
    Registry<Attribute> ATTRIBUTE = StubRegistrySupport.ofAttributes();
    Registry<Enchantment> ENCHANTMENT = StubRegistrySupport.ofEnchantments();
    Registry<PotionEffectType> EFFECT = StubRegistrySupport.ofEffects();
    Registry<PotionEffectType> MOB_EFFECT = Registry.EFFECT;
    Registry<org.bukkit.inventory.meta.trim.TrimMaterial> TRIM_MATERIAL = key -> null;
    Registry<org.bukkit.inventory.meta.trim.TrimPattern> TRIM_PATTERN = key -> null;

    T get(NamespacedKey key);
}
""")

add("org/bukkit/StubRegistrySupport", """
package org.bukkit;

import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.potion.PotionEffectType;

/** Только для заглушек: возвращает пригодные объекты вместо null. */
public final class StubRegistrySupport {
    private StubRegistrySupport() {
    }

    public static Registry<Attribute> ofAttributes() {
        return key -> Attribute.valueOf(key.getKey());
    }

    public static Registry<Enchantment> ofEnchantments() {
        return key -> Enchantment.valueOf(key.getKey());
    }

    public static Registry<PotionEffectType> ofEffects() {
        return key -> PotionEffectType.valueOf(key.getKey());
    }
}
""")

add("org/bukkit/attribute/Attribute", """
package org.bukkit.attribute;

public interface Attribute {
    Attribute ATTACK_SPEED = new StubAttribute("attack_speed");
    Attribute MOVEMENT_SPEED = new StubAttribute("movement_speed");
    Attribute ENTITY_INTERACTION_RANGE = new StubAttribute("entity_interaction_range");

    static Attribute valueOf(String key) {
        return new StubAttribute(key);
    }

    String getKey();
}
""")

add("org/bukkit/attribute/StubAttribute", """
package org.bukkit.attribute;

public final class StubAttribute implements Attribute {
    private final String key;

    public StubAttribute(String key) {
        this.key = key;
    }

    @Override
    public String getKey() {
        return key;
    }

    @Override
    public String toString() {
        return "Attribute(" + key + ")";
    }
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

# В Paper это КЛАСС (в заглушке когда-то был интерфейс — отсюда падение VoidAntiCheat).
add("org/bukkit/potion/PotionEffect", """
package org.bukkit.potion;

public class PotionEffect {
    private final PotionEffectType type;
    private final int duration;
    private final int amplifier;

    public PotionEffect(PotionEffectType type, int duration, int amplifier) {
        this(type, duration, amplifier, true, true, true);
    }

    public PotionEffect(PotionEffectType type, int duration, int amplifier,
                        boolean ambient, boolean particles, boolean icon) {
        this.type = type;
        this.duration = duration;
        this.amplifier = amplifier;
    }

    public PotionEffectType getType() {
        return type;
    }

    public int getDuration() {
        return duration;
    }

    public int getAmplifier() {
        return amplifier;
    }
}
""")

add("org/bukkit/potion/PotionEffectType", """
package org.bukkit.potion;

/** В Paper — абстрактный класс. */
public abstract class PotionEffectType {
    public static final PotionEffectType INVISIBILITY = new StubPotionEffectType("invisibility");
    public static final PotionEffectType GLOWING = new StubPotionEffectType("glowing");
    public static final PotionEffectType BLINDNESS = new StubPotionEffectType("blindness");
    public static final PotionEffectType NAUSEA = new StubPotionEffectType("nausea");
    public static final PotionEffectType SLOWNESS = new StubPotionEffectType("slowness");
    public static final PotionEffectType SPEED = new StubPotionEffectType("speed");
    public static final PotionEffectType STRENGTH = new StubPotionEffectType("strength");
    public static final PotionEffectType JUMP_BOOST = new StubPotionEffectType("jump_boost");
    public static final PotionEffectType RESISTANCE = new StubPotionEffectType("resistance");
    public static final PotionEffectType FIRE_RESISTANCE = new StubPotionEffectType("fire_resistance");

    public static PotionEffectType getByName(String name) {
        return valueOf(name);
    }

    public static PotionEffectType valueOf(String key) {
        return new StubPotionEffectType(key);
    }

    public abstract String getKey();
}
""")

add("org/bukkit/potion/StubPotionEffectType", """
package org.bukkit.potion;

public final class StubPotionEffectType extends PotionEffectType {
    private final String key;

    public StubPotionEffectType(String key) {
        this.key = key;
    }

    @Override
    public String getKey() {
        return key;
    }

    @Override
    public String toString() {
        return "PotionEffectType(" + key + ")";
    }
}
""")

add("org/bukkit/enchantments/Enchantment", """
package org.bukkit.enchantments;

/** В Paper — абстрактный класс. */
public abstract class Enchantment {
    public static final Enchantment UNBREAKING = new StubEnchantment("unbreaking");
    public static final Enchantment PROTECTION = new StubEnchantment("protection");
    public static final Enchantment SHARPNESS = new StubEnchantment("sharpness");
    public static final Enchantment EFFICIENCY = new StubEnchantment("efficiency");

    public static Enchantment getByName(String name) {
        return valueOf(name);
    }

    public static Enchantment valueOf(String key) {
        return new StubEnchantment(key);
    }

    public abstract String getKey();
}
""")

add("org/bukkit/enchantments/StubEnchantment", """
package org.bukkit.enchantments;

public final class StubEnchantment extends Enchantment {
    private final String key;

    public StubEnchantment(String key) {
        this.key = key;
    }

    @Override
    public String getKey() {
        return key;
    }

    @Override
    public String toString() {
        return "Enchantment(" + key + ")";
    }
}
""")

add("org/bukkit/GameMode", """
package org.bukkit;

public enum GameMode {
    SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR
}
""")

add("org/bukkit/Material", """
package org.bukkit;

public enum Material {
    AIR, STONE, DIRT, GRASS_BLOCK, CHEST, HAY_BLOCK, SLIME_BLOCK, HONEY_BLOCK, ICE, PACKED_ICE,
    BLUE_ICE, FROSTED_ICE, LADDER, VINE, SCAFFOLDING, COBWEB, POWDER_SNOW, BUBBLE_COLUMN,
    SOUL_SAND, SOUL_SOIL, WATER, LAVA, BARRIER, OBSIDIAN;

    public boolean isSolid() {
        return this != AIR && this != WATER && this != LAVA && this != BUBBLE_COLUMN && this != POWDER_SNOW;
    }

    public boolean isInteractable() {
        return this == CHEST;
    }

    public boolean isLiquid() {
        return this == WATER || this == LAVA;
    }
}
""")

add("org/bukkit/util/Vector", """
package org.bukkit.util;

public class Vector {
    private double x;
    private double y;
    private double z;

    public Vector() {
        this(0, 0, 0);
    }

    public Vector(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public Vector setX(double value) {
        x = value;
        return this;
    }

    public Vector setY(double value) {
        y = value;
        return this;
    }

    public Vector setZ(double value) {
        z = value;
        return this;
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }
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

    public double getMinX() {
        return minX;
    }

    public double getMinY() {
        return minY;
    }

    public double getMinZ() {
        return minZ;
    }

    public double getMaxX() {
        return maxX;
    }

    public double getMaxY() {
        return maxY;
    }

    public double getMaxZ() {
        return maxZ;
    }
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

    public Location(World world, double x, double y, double z) {
        this(world, x, y, z, 0f, 0f);
    }

    public Location(World world, double x, double y, double z, float yaw, float pitch) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public World getWorld() {
        return world;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public void setYaw(float value) {
        yaw = value;
    }

    public void setPitch(float value) {
        pitch = value;
    }

    public org.bukkit.block.Block getBlock() {
        return world == null ? null : world.getBlockAt(getBlockX(), getBlockY(), getBlockZ());
    }

    public int getBlockX() {
        return (int) Math.floor(x);
    }

    public int getBlockY() {
        return (int) Math.floor(y);
    }

    public int getBlockZ() {
        return (int) Math.floor(z);
    }

    public Location add(double dx, double dy, double dz) {
        x += dx;
        y += dy;
        z += dz;
        return this;
    }

    public Location add(Vector vector) {
        return add(vector.getX(), vector.getY(), vector.getZ());
    }

    public Vector toVector() {
        return new Vector(x, y, z);
    }

    public double distance(Location other) {
        return Math.sqrt(distanceSquared(other));
    }

    public double distanceSquared(Location other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public Location clone() {
        return new Location(world, x, y, z, yaw, pitch);
    }
}
""")

add("org/bukkit/World", """
package org.bukkit;

import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.util.Collection;
import java.util.List;

public interface World {
    String getName();

    Block getBlockAt(int x, int y, int z);

    Collection<Entity> getNearbyEntities(BoundingBox box);

    List<Player> getPlayers();

    org.bukkit.entity.Item dropItemNaturally(Location location, org.bukkit.inventory.ItemStack stack);

    org.bukkit.entity.Item dropItem(Location location, org.bukkit.inventory.ItemStack stack);

    int getMaxHeight();

    File getWorldFolder();
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

    void setType(Material type, boolean applyPhysics);

    void setType(Material type);

    void setBlockData(org.bukkit.block.data.BlockData data, boolean applyPhysics);

    void setBlockData(org.bukkit.block.data.BlockData data);

    org.bukkit.block.data.BlockData getBlockData();

    BlockState getState();
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

    String getName();

    boolean teleport(Location location);

    void setRotation(float yaw, float pitch);

    void remove();

    void setGlowing(boolean glowing);

    boolean isGlowing();
}
""")

add("org/bukkit/entity/LivingEntity", """
package org.bukkit.entity;

import org.bukkit.Location;

public interface LivingEntity extends Entity {
    Location getEyeLocation();

    float getFallDistance();
}
""")

add("org/bukkit/OfflinePlayer", """
package org.bukkit;

import java.util.UUID;

public interface OfflinePlayer {
    String getName();

    UUID getUniqueId();

    boolean isOnline();

    boolean hasPlayedBefore();

    boolean isOp();

    void setOp(boolean value);
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public interface Player extends LivingEntity, org.bukkit.OfflinePlayer {
    GameMode getGameMode();

    boolean hasPermission(String permission);

    void sendMessage(Component component);

    void sendMessage(String message);

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

    AttributeInstance getAttribute(Attribute attribute);

    PlayerInventory getInventory();

    PotionEffect getPotionEffect(PotionEffectType type);

    boolean hasLineOfSight(Entity entity);

    World getWorld();

    Location getLocation();

    Location getEyeLocation();

    String getDisplayName();

    void setDisplayName(String name);

    String getPlayerListName();

    void setPlayerListName(String name);

    void kickPlayer(String message);

    void updateInventory();

    void playSound(Location location, String sound, float volume, float pitch);

    InventoryView openInventory(Inventory inventory);

    void openInventory(InventoryView view);

    boolean addPotionEffect(PotionEffect effect);

    void removePotionEffect(PotionEffectType type);

    boolean hasPotionEffect(PotionEffectType type);
}
""")

add("org/bukkit/inventory/EquipmentSlot", """
package org.bukkit.inventory;

public enum EquipmentSlot {
    HAND, OFF_HAND, HEAD, CHEST, LEGS, FEET
}
""")

add("org/bukkit/inventory/meta/ItemMeta", """
package org.bukkit.inventory.meta;

public interface ItemMeta {
    boolean hasDisplayName();
}
""")

add("org/bukkit/inventory/ItemStack", """
package org.bukkit.inventory;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.meta.ItemMeta;

public class ItemStack {
    private final Material type;
    private int amount;

    public ItemStack(Material type) {
        this(type, 1);
    }

    public ItemStack(Material type, int amount) {
        this.type = type;
        this.amount = amount;
    }

    public Material getType() {
        return type;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public ItemMeta getItemMeta() {
        return null;
    }

    public int getEnchantmentLevel(Enchantment enchantment) {
        return 0;
    }

    public boolean isAir() {
        return type == null || type == Material.AIR;
    }
}
""")

add("org/bukkit/inventory/PlayerInventory", """
package org.bukkit.inventory;

public interface PlayerInventory {
    ItemStack getBoots();

    ItemStack getItemInMainHand();
}
""")

# ------------------------------------------------------------------ команды

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

    public void setExecutor(CommandExecutor executor) {
        this.executor = executor;
    }

    public void setTabCompleter(TabCompleter completer) {
        this.completer = completer;
    }

    public CommandExecutor getExecutor() {
        return executor;
    }

    public TabCompleter getTabCompleter() {
        return completer;
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        return executor != null && executor.onCommand(sender, this, label, args);
    }

    @Override
    public java.util.List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return completer == null ? java.util.List.of() : completer.onTabComplete(sender, this, alias, args);
    }
}
""")

# ------------------------------------------------------------------ плагин и сервер

add("org/bukkit/plugin/Plugin", """
package org.bukkit.plugin;

import java.util.logging.Logger;

public interface Plugin {
    String getName();

    Logger getLogger();
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

add("org/bukkit/plugin/ServicesManager", """
package org.bukkit.plugin;

public interface ServicesManager {
    <T> RegisteredServiceProvider<T> getRegistration(Class<T> service);
}
""")

add("org/bukkit/plugin/PluginDescriptionFile", """
package org.bukkit.plugin;

public class PluginDescriptionFile {
    public String getVersion() {
        return "0.0.0";
    }

    public String getName() {
        return "Stub";
    }
}
""")

add("org/bukkit/plugin/RegisteredServiceProvider", """
package org.bukkit.plugin;

public class RegisteredServiceProvider<T> {
    private final T provider;

    public RegisteredServiceProvider(T provider) {
        this.provider = provider;
    }

    public T getProvider() {
        return provider;
    }
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
    public void onEnable() {
    }

    public void onDisable() {
    }

    public void saveConfig() {
    }

    public void saveDefaultConfig() {
    }

    public void reloadConfig() {
    }

    public FileConfiguration getConfig() {
        return null;
    }

    public Logger getLogger() {
        return Logger.getLogger(getClass().getName());
    }

    public File getDataFolder() {
        return new File(".");
    }

    public Server getServer() {
        return org.bukkit.Bukkit.getServer();
    }

    public PluginCommand getCommand(String name) {
        return null;
    }

    public String getName() {
        return getClass().getSimpleName();
    }

    public PluginDescriptionFile getDescription() {
        return new PluginDescriptionFile();
    }
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

    BukkitTask runTaskLater(Plugin plugin, Runnable task, long delay);
}
""")

# ------------------------------------------------------------------ конфиг

add("org/bukkit/configuration/ConfigurationSection", """
package org.bukkit.configuration;

import java.util.List;
import java.util.Set;

public interface ConfigurationSection {
    boolean getBoolean(String path);

    boolean getBoolean(String path, boolean def);

    int getInt(String path);

    int getInt(String path, int def);

    long getLong(String path);

    long getLong(String path, long def);

    double getDouble(String path);

    double getDouble(String path, double def);

    String getString(String path);

    String getString(String path, String def);

    List<String> getStringList(String path);

    ConfigurationSection getConfigurationSection(String path);

    ConfigurationSection createSection(String path);

    Set<String> getKeys(boolean deep);

    boolean contains(String path);

    default boolean isSet(String path) {
        return contains(path);
    }

    void set(String path, Object value);

    default java.util.List<java.util.Map<?, ?>> getMapList(String path) {
        return new java.util.ArrayList<>();
    }
}
""")

add("org/bukkit/configuration/file/FileConfiguration", """
package org.bukkit.configuration.file;

import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/** В Paper — абстрактный класс, наследник ConfigurationSection. */
public abstract class FileConfiguration implements ConfigurationSection {
    public abstract void save(File file) throws IOException;
}
""")

add("org/bukkit/configuration/file/YamlConfiguration", """
package org.bukkit.configuration.file;

import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class YamlConfiguration extends FileConfiguration {
    private final Map<String, Object> values = new LinkedHashMap<>();

    public static YamlConfiguration loadConfiguration(File file) throws IOException {
        return new YamlConfiguration();
    }

    public String saveToString() {
        return "";
    }

    @Override
    public void save(File file) throws IOException {
    }

    @Override
    public boolean getBoolean(String path) {
        return getBoolean(path, false);
    }

    @Override
    public boolean getBoolean(String path, boolean def) {
        Object value = values.get(path);
        return value instanceof Boolean bool ? bool : def;
    }

    @Override
    public int getInt(String path) {
        return getInt(path, 0);
    }

    @Override
    public int getInt(String path, int def) {
        Object value = values.get(path);
        return value instanceof Number number ? number.intValue() : def;
    }

    @Override
    public long getLong(String path) {
        return getLong(path, 0L);
    }

    @Override
    public long getLong(String path, long def) {
        Object value = values.get(path);
        return value instanceof Number number ? number.longValue() : def;
    }

    @Override
    public double getDouble(String path) {
        return getDouble(path, 0.0);
    }

    @Override
    public double getDouble(String path, double def) {
        Object value = values.get(path);
        return value instanceof Number number ? number.doubleValue() : def;
    }

    @Override
    public String getString(String path) {
        return getString(path, null);
    }

    @Override
    public String getString(String path, String def) {
        Object value = values.get(path);
        return value instanceof String string ? string : def;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> getStringList(String path) {
        Object value = values.get(path);
        return value instanceof List<?> list ? (List<String>) list : new ArrayList<>();
    }

    @Override
    public ConfigurationSection getConfigurationSection(String path) {
        return null;
    }

    @Override
    public ConfigurationSection createSection(String path) {
        return null;
    }

    @Override
    public Set<String> getKeys(boolean deep) {
        return values.keySet();
    }

    @Override
    public boolean contains(String path) {
        return values.containsKey(path);
    }

    @Override
    public void set(String path, Object value) {
        values.put(path, value);
    }
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

public enum EventPriority {
    LOWEST, LOW, NORMAL, HIGH, HIGHEST, MONITOR
}
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

    protected PlayerEvent() {
        this(null);
    }

    protected PlayerEvent(Player player) {
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }
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

    public Location getFrom() {
        return from;
    }

    public Location getTo() {
        return to;
    }

    public void setTo(Location to) {
        this.to = to;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
""")

add("org/bukkit/event/player/PlayerJoinEvent", """
package org.bukkit.event.player;

import org.bukkit.entity.Player;

public class PlayerJoinEvent extends PlayerEvent {
    private String joinMessage;

    public PlayerJoinEvent(Player player, String joinMessage) {
        super(player);
        this.joinMessage = joinMessage;
    }

    public String getJoinMessage() {
        return joinMessage;
    }

    public void setJoinMessage(String joinMessage) {
        this.joinMessage = joinMessage;
    }
}
""")

add("org/bukkit/event/player/PlayerQuitEvent", """
package org.bukkit.event.player;

import org.bukkit.entity.Player;

public class PlayerQuitEvent extends PlayerEvent {
    private String quitMessage;

    public PlayerQuitEvent(Player player, String quitMessage) {
        super(player);
        this.quitMessage = quitMessage;
    }

    public String getQuitMessage() {
        return quitMessage;
    }

    public void setQuitMessage(String quitMessage) {
        this.quitMessage = quitMessage;
    }
}
""")

add("org/bukkit/event/player/AsyncPlayerChatEvent", """
package org.bukkit.event.player;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;

import java.util.HashSet;
import java.util.Set;

public class AsyncPlayerChatEvent extends PlayerEvent implements Cancellable {
    private String message;
    private String format = "<%1$s> %2$s";
    private boolean cancelled;

    public AsyncPlayerChatEvent(Player player, String message, Set<Player> recipients) {
        super(player);
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public Set<Player> getRecipients() {
        return new HashSet<>();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
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

    public Location getTo() {
        return to;
    }
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

    public Location getRespawnLocation() {
        return respawnLocation;
    }
}
""")

add("org/bukkit/event/player/PlayerChangedWorldEvent", """
package org.bukkit.event.player;

import org.bukkit.entity.Player;

public class PlayerChangedWorldEvent extends PlayerEvent {
    public PlayerChangedWorldEvent(Player player) {
        super(player);
    }
}
""")

add("org/bukkit/event/entity/EntityEvent", """
package org.bukkit.event.entity;

import org.bukkit.entity.Entity;
import org.bukkit.event.Event;

public abstract class EntityEvent extends Event {
    private final Entity entity;

    protected EntityEvent() {
        this(null);
    }

    protected EntityEvent(Entity entity) {
        this.entity = entity;
    }

    public Entity getEntity() {
        return entity;
    }
}
""")

add("org/bukkit/event/entity/EntityDamageEvent", """
package org.bukkit.event.entity;

import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;

public class EntityDamageEvent extends EntityEvent implements Cancellable {
    public enum DamageCause {
        ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE, MAGIC, FIRE, VOID, CUSTOM
    }

    private boolean cancelled;
    private double damage;
    private final DamageCause cause;

    public EntityDamageEvent(Entity entity) {
        this(entity, DamageCause.CUSTOM, 0.0);
    }

    public EntityDamageEvent(Entity entity, DamageCause cause, double damage) {
        super(entity);
        this.cause = cause;
        this.damage = damage;
    }

    public DamageCause getCause() {
        return cause;
    }

    public double getDamage() {
        return damage;
    }

    public void setDamage(double damage) {
        this.damage = damage;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
""")

add("org/bukkit/event/entity/EntityDamageByEntityEvent", """
package org.bukkit.event.entity;

import org.bukkit.entity.Entity;

public class EntityDamageByEntityEvent extends EntityDamageEvent {
    private final Entity damager;

    public EntityDamageByEntityEvent(Entity damager, Entity victim, double damage) {
        super(victim, DamageCause.ENTITY_ATTACK, damage);
        this.damager = damager;
    }

    public Entity getDamager() {
        return damager;
    }
}
""")

add("org/bukkit/event/block/BlockEvent", """
package org.bukkit.event.block;

import org.bukkit.block.Block;
import org.bukkit.event.Event;

public abstract class BlockEvent extends Event {
    private final Block block;

    protected BlockEvent() {
        this(null);
    }

    protected BlockEvent(Block block) {
        this.block = block;
    }

    public Block getBlock() {
        return block;
    }
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

    public Player getPlayer() {
        return player;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
""")

add("org/bukkit/event/block/BlockPlaceEvent", """
package org.bukkit.event.block;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;

public class BlockPlaceEvent extends BlockEvent implements Cancellable {
    private final Player player;
    private boolean cancelled;

    public BlockPlaceEvent(Block block, Player player) {
        super(block);
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
""")

# ------------------------------------------------------------------ scoreboard

add("org/bukkit/scoreboard/Score", """
package org.bukkit.scoreboard;

public interface Score {
    int getScore();

    void setScore(int score);

    boolean isScoreSet();
}
""")

add("org/bukkit/scoreboard/Objective", """
package org.bukkit.scoreboard;

public interface Objective {
    String getName();

    Score getScore(String entry);
}
""")

add("org/bukkit/scoreboard/Team", """
package org.bukkit.scoreboard;

import java.util.Set;

public interface Team {
    String getName();

    Set<String> getEntries();

    void addEntry(String entry);

    boolean removeEntry(String entry);
}
""")

add("org/bukkit/scoreboard/Scoreboard", """
package org.bukkit.scoreboard;

import java.util.Set;

public interface Scoreboard {
    Set<Team> getTeams();

    Set<Objective> getObjectives();

    void resetScores(String entry);
}
""")

add("org/bukkit/scoreboard/ScoreboardManager", """
package org.bukkit.scoreboard;

public interface ScoreboardManager {
    Scoreboard getMainScoreboard();

    Scoreboard getNewScoreboard();
}
""")

# ------------------------------------------------------------------ Paper-чат

add("net/kyori/adventure/audience/Audience", """
package net.kyori.adventure.audience;

public interface Audience {
}
""")

add("net/kyori/adventure/text/Component", """
package net.kyori.adventure.text;

import net.kyori.adventure.text.format.TextDecoration;

public interface Component {
    Component append(Component component);

    Component decoration(TextDecoration decoration, boolean value);

    static Component text(String text) {
        return new Component() {
            @Override
            public Component append(Component component) {
                return this;
            }

            @Override
            public Component decoration(TextDecoration decoration, boolean value) {
                return this;
            }

            @Override
            public String toString() {
                return text;
            }
        };
    }

    static Component empty() {
        return Component.text("");
    }
}
""")

add("net/kyori/adventure/text/format/TextDecoration", """
package net.kyori.adventure.text.format;

public enum TextDecoration {
    ITALIC, BOLD, UNDERLINED, STRIKETHROUGH, OBFUSCATED
}
""")

add("net/kyori/adventure/text/serializer/legacy/LegacyComponentSerializer", """
package net.kyori.adventure.text.serializer.legacy;

import net.kyori.adventure.text.Component;

public final class LegacyComponentSerializer {
    private String lastText = "";

    private LegacyComponentSerializer() {
    }

    public static LegacyComponentSerializer legacySection() {
        return new LegacyComponentSerializer();
    }

    public Component deserialize(String text) {
        lastText = text;
        Component base = Component.text(text);
        return new Component() {
            @Override
            public Component append(Component component) {
                return this;
            }

            @Override
            public Component decoration(net.kyori.adventure.text.format.TextDecoration decoration, boolean value) {
                return this;
            }

            @Override
            public String toString() {
                return lastText;
            }
        };
    }

    public String serialize(Component component) {
        return component == null ? "" : component.toString();
    }
}
""")

add("io/papermc/paper/chat/ChatRenderer", """
package io.papermc.paper.chat;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

public interface ChatRenderer {
    Component render(Player source, Component sourceDisplayName, Component message, Audience viewer);
}
""")

add("io/papermc/paper/event/player/AbstractChatEvent", """
package io.papermc.paper.event.player;

import io.papermc.paper.chat.ChatRenderer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;

import java.util.Set;

public abstract class AbstractChatEvent extends Event {
    private final Player player;
    private final Set<Audience> viewers;
    private final Component message;
    private ChatRenderer renderer;

    protected AbstractChatEvent(Player player, Set<Audience> viewers, ChatRenderer renderer, Component message) {
        this.player = player;
        this.viewers = viewers;
        this.renderer = renderer;
        this.message = message;
    }

    public Player getPlayer() {
        return player;
    }

    public Set<Audience> viewers() {
        return viewers;
    }

    public Component message() {
        return message;
    }

    public ChatRenderer renderer() {
        return renderer;
    }

    public void renderer(ChatRenderer renderer) {
        this.renderer = renderer;
    }
}
""")

add("io/papermc/paper/event/player/AsyncChatEvent", """
package io.papermc.paper.event.player;

import io.papermc.paper.chat.ChatRenderer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.Set;

public final class AsyncChatEvent extends AbstractChatEvent {
    public AsyncChatEvent(boolean async, Player player, Set<Audience> viewers, ChatRenderer renderer, Component message) {
        super(player, viewers, renderer, message);
    }
}
""")

# ------------------------------------------------------------------ сервер

add("org/bukkit/Server", """
package org.bukkit;

import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scoreboard.ScoreboardManager;

import java.util.Collection;

public interface Server {
    PluginManager getPluginManager();

    ServicesManager getServicesManager();

    BukkitScheduler getScheduler();

    ScoreboardManager getScoreboardManager();

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
import org.bukkit.plugin.ServicesManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scoreboard.ScoreboardManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Bukkit {
    public static Server server;
    public static final List<Player> online = new ArrayList<>();
    public static final Map<UUID, OfflinePlayer> offline = new HashMap<>();
    public static final List<String> dispatched = new ArrayList<>();
    public static final List<String> broadcasts = new ArrayList<>();
    public static int tick;

    public static Server getServer() {
        return server;
    }

    public static PluginManager getPluginManager() {
        return server == null ? null : server.getPluginManager();
    }

    public static ServicesManager getServicesManager() {
        return server == null ? null : server.getServicesManager();
    }

    public static BukkitScheduler getScheduler() {
        return server == null ? null : server.getScheduler();
    }

    public static ScoreboardManager getScoreboardManager() {
        return server == null ? null : server.getScoreboardManager();
    }

    public static ConsoleCommandSender getConsoleSender() {
        return server == null ? null : server.getConsoleSender();
    }

    public static Collection<? extends Player> getOnlinePlayers() {
        return online;
    }

    public static List<World> getWorlds() {
        return new ArrayList<>();
    }

    public static World getWorld(String name) {
        return null;
    }

    public static Player getPlayer(UUID uuid) {
        for (Player player : online) {
            if (player.getUniqueId().equals(uuid)) {
                return player;
            }
        }
        return null;
    }

    public static Player getPlayerExact(String name) {
        for (Player player : online) {
            if (player.getName().equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }

    public static OfflinePlayer getOfflinePlayer(String name) {
        for (OfflinePlayer player : offline.values()) {
            if (name.equalsIgnoreCase(player.getName())) {
                return player;
            }
        }
        return null;
    }

    public static OfflinePlayer getOfflinePlayer(UUID uuid) {
        return offline.get(uuid);
    }

    public static boolean dispatchCommand(CommandSender sender, String commandLine) {
        dispatched.add(commandLine);
        return true;
    }

    public static int broadcastMessage(String message) {
        broadcasts.add(message);
        return online.size();
    }

    public static int getCurrentTick() {
        return tick;
    }

    public static boolean addRecipe(org.bukkit.inventory.Recipe recipe) {
        return true;
    }

    public static boolean removeRecipe(NamespacedKey key) {
        return true;
    }

    public static org.bukkit.inventory.Inventory createInventory(
            org.bukkit.inventory.InventoryHolder holder, int size, net.kyori.adventure.text.Component title) {
        return null;
    }

    public static org.bukkit.block.data.BlockData createBlockData(String data) {
        return null;
    }
}
""")


# ------------------------------------------------------------------ предметы, блоки, события
# Нужны для VoidItems (кастомные предметы). Виды типов сверены с Paper API:
# Material — enum, ItemMeta/BlockState/PersistentDataContainer — интерфейсы.

add("org/bukkit/Material", """
package org.bukkit;

public enum Material {
    AIR, STONE, DIRT, GRASS_BLOCK, CHEST, SPAWNER, BEDROCK, OBSIDIAN, CRYING_OBSIDIAN,
    POLISHED_BLACKSTONE, GILDED_BLACKSTONE, SEA_LANTERN, GLOWSTONE_DUST, ENDER_EYE,
    ENDER_PEARL, FIRE_CHARGE, SNOWBALL, NETHERITE_SCRAP, GOLDEN_PICKAXE, GOLD_INGOT, STICK,
    POTION, GLASS_BOTTLE, NETHER_WART, BLAZE_POWDER, SUGAR, FERMENTED_SPIDER_EYE, DIAMOND,
    EMERALD, FEATHER, MAGMA_CREAM, GLOW_INK_SAC, DIAMOND_HELMET, DIAMOND_CHESTPLATE,
    DIAMOND_LEGGINGS, DIAMOND_BOOTS, DIAMOND_SWORD, DIAMOND_PICKAXE, DIAMOND_AXE,
    DIAMOND_SHOVEL, DIAMOND_HOE, WATER, LAVA, BARRIER;

    public static Material matchMaterial(String name) {
        return name == null ? null : STONE;
    }

    public boolean isInteractable() {
        return this == CHEST;
    }

    public boolean isAir() {
        return this == AIR;
    }

    public boolean isSolid() {
        return this != AIR && this != WATER && this != LAVA;
    }

    public boolean isLiquid() {
        return this == WATER || this == LAVA;
    }

    public boolean isBlock() {
        return this != POTION;
    }

    public boolean isItem() {
        return true;
    }

    public boolean isEdible() {
        return false;
    }
}
""")

add("org/bukkit/inventory/meta/ItemMeta", """
package org.bukkit.inventory.meta;

import net.kyori.adventure.text.Component;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.persistence.PersistentDataContainer;

import java.util.List;

public interface ItemMeta {
    boolean hasDisplayName();

    Component displayName();

    void displayName(Component name);

    boolean hasLore();

    List<Component> lore();

    void lore(List<Component> lore);

    boolean addEnchant(Enchantment enchantment, int level, boolean ignoreLevelRestriction);

    void addItemFlags(ItemFlag... flags);

    PersistentDataContainer getPersistentDataContainer();
}
""")

add("org/bukkit/inventory/ItemStack", """
package org.bukkit.inventory;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.meta.ItemMeta;

public class ItemStack implements Cloneable {
    private Material type;
    private int amount;
    private ItemMeta meta;

    public ItemStack(Material type) {
        this(type, 1);
    }

    public ItemStack(Material type, int amount) {
        this.type = type;
        this.amount = amount;
    }

    public Material getType() {
        return type;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public ItemMeta getItemMeta() {
        return meta;
    }

    public boolean setItemMeta(ItemMeta meta) {
        this.meta = meta;
        return true;
    }

    public boolean hasItemMeta() {
        return meta != null;
    }

    public int getMaxStackSize() {
        return type == Material.POTION ? 1 : 64;
    }

    public int getEnchantmentLevel(Enchantment enchantment) {
        return 0;
    }

    public boolean isAir() {
        return type == null || type == Material.AIR;
    }

    public boolean isSimilar(ItemStack other) {
        return other != null && other.type == type;
    }

    @Override
    public ItemStack clone() {
        return new ItemStack(type, amount);
    }
}
""")

add("org/bukkit/inventory/PlayerInventory", """
package org.bukkit.inventory;

import java.util.HashMap;
import java.util.Map;

public interface PlayerInventory extends Inventory {
    ItemStack getBoots();

    ItemStack getItemInMainHand();

    void setItemInMainHand(ItemStack item);

    ItemStack getItemInOffHand();

    void setItemInOffHand(ItemStack item);

    HashMap<Integer, ItemStack> addItem(ItemStack... items);
}
""")

add("org/bukkit/inventory/Inventory", """
package org.bukkit.inventory;

public interface Inventory {
    ItemStack getItem(int slot);

    void setItem(int slot, ItemStack item);

    int getSize();

    InventoryHolder getHolder();
}
""")

add("org/bukkit/inventory/InventoryView", """
package org.bukkit.inventory;

public abstract class InventoryView {
    public Inventory getTopInventory() {
        return null;
    }

    public Inventory getBottomInventory() {
        return null;
    }
}
""")

add("org/bukkit/inventory/InventoryHolder", """
package org.bukkit.inventory;

public interface InventoryHolder {
    Inventory getInventory();
}
""")

add("org/bukkit/inventory/Recipe", """
package org.bukkit.inventory;

public interface Recipe {
}
""")

add("org/bukkit/inventory/ShapedRecipe", """
package org.bukkit.inventory;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;

public class ShapedRecipe implements Recipe {
    public ShapedRecipe(NamespacedKey key, ItemStack result) {
    }

    public ShapedRecipe shape(String... shape) {
        return this;
    }

    public ShapedRecipe setIngredient(char key, Material material) {
        return this;
    }
}
""")

add("org/bukkit/inventory/ItemFlag", """
package org.bukkit.inventory;

public enum ItemFlag {
    HIDE_ENCHANTS, HIDE_ATTRIBUTES, HIDE_UNBREAKABLE, HIDE_DESTROYS, HIDE_PLACED_ON, HIDE_DYE
}
""")

add("org/bukkit/inventory/meta/BlockStateMeta", """
package org.bukkit.inventory.meta;

import org.bukkit.block.BlockState;

public interface BlockStateMeta extends ItemMeta {
    BlockState getBlockState();

    void setBlockState(BlockState state);
}
""")

add("org/bukkit/inventory/meta/ArmorMeta", """
package org.bukkit.inventory.meta;

import org.bukkit.inventory.meta.trim.ArmorTrim;

public interface ArmorMeta extends ItemMeta {
    ArmorTrim getTrim();

    void setTrim(ArmorTrim trim);
}
""")

add("org/bukkit/inventory/meta/PotionMeta", """
package org.bukkit.inventory.meta;

import org.bukkit.Color;
import org.bukkit.potion.PotionEffect;

public interface PotionMeta extends ItemMeta {
    boolean addCustomEffect(PotionEffect effect, boolean overwrite);

    void clearCustomEffects();

    void setColor(Color color);

    Color getColor();
}
""")

add("org/bukkit/inventory/meta/trim/ArmorTrim", """
package org.bukkit.inventory.meta.trim;

public class ArmorTrim {
    public ArmorTrim(TrimMaterial material, TrimPattern pattern) {
    }

    public TrimMaterial getMaterial() {
        return null;
    }

    public TrimPattern getPattern() {
        return null;
    }
}
""")

add("org/bukkit/inventory/meta/trim/TrimMaterial", """
package org.bukkit.inventory.meta.trim;

public interface TrimMaterial {
}
""")

add("org/bukkit/inventory/meta/trim/TrimPattern", """
package org.bukkit.inventory.meta.trim;

public interface TrimPattern {
}
""")

add("org/bukkit/Color", """
package org.bukkit;

public final class Color {
    public static Color fromRGB(int red, int green, int blue) {
        return new Color();
    }

    public static Color fromRGB(int rgb) {
        return new Color();
    }
}
""")

add("org/bukkit/persistence/PersistentDataContainer", """
package org.bukkit.persistence;

import org.bukkit.NamespacedKey;

public interface PersistentDataContainer {
    <P, C> C get(NamespacedKey key, PersistentDataType<P, C> type);

    <P, C> void set(NamespacedKey key, PersistentDataType<P, C> type, C value);

    boolean has(NamespacedKey key);

    void remove(NamespacedKey key);
}
""")

add("org/bukkit/persistence/PersistentDataType", """
package org.bukkit.persistence;

public interface PersistentDataType<P, C> {
    PersistentDataType<String, String> STRING = new PersistentDataType<String, String>() {
    };
    PersistentDataType<Integer, Integer> INTEGER = new PersistentDataType<Integer, Integer>() {
    };
    PersistentDataType<Byte, Byte> BYTE = new PersistentDataType<Byte, Byte>() {
    };
}
""")

add("org/bukkit/block/BlockState", """
package org.bukkit.block;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

public interface BlockState {
    Material getType();

    World getWorld();

    Location getLocation();

    boolean update(boolean applyPhysics, boolean force);

    boolean update(boolean applyPhysics);

    boolean update();

    org.bukkit.block.data.BlockData getBlockData();
}
""")

add("org/bukkit/block/CreatureSpawner", """
package org.bukkit.block;

import org.bukkit.entity.EntityType;

public interface CreatureSpawner extends BlockState {
    EntityType getSpawnedType();

    void setSpawnedType(EntityType type);
}
""")

add("org/bukkit/block/data/BlockData", """
package org.bukkit.block.data;

public interface BlockData extends Cloneable {
    BlockData clone();

    String getAsString();

    String getAsString(boolean hideUnspecified);
}
""")

add("org/bukkit/entity/Item", """
package org.bukkit.entity;

public interface Item extends Entity {
    org.bukkit.inventory.ItemStack getItemStack();

    void setItemStack(org.bukkit.inventory.ItemStack stack);
}
""")

add("org/bukkit/entity/EntityType", """
package org.bukkit.entity;

public enum EntityType {
    ARMOR_STAND, ITEM_FRAME, PAINTING, WARDEN, ZOMBIE, SKELETON, SPIDER, CREEPER, BLAZE;
}
""")

add("org/bukkit/event/block/Action", """
package org.bukkit.event.block;

public enum Action {
    LEFT_CLICK_BLOCK, LEFT_CLICK_AIR, RIGHT_CLICK_BLOCK, RIGHT_CLICK_AIR, PHYSICAL
}
""")

add("org/bukkit/event/player/PlayerInteractEvent", """
package org.bukkit.event.player;

import org.bukkit.block.Block;
import org.bukkit.event.Cancellable;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class PlayerInteractEvent extends PlayerEvent implements Cancellable {
    private boolean cancelled;

    public Action getAction() {
        return Action.RIGHT_CLICK_AIR;
    }

    public EquipmentSlot getHand() {
        return EquipmentSlot.HAND;
    }

    public Block getClickedBlock() {
        return null;
    }

    public ItemStack getItem() {
        return null;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
""")

add("org/bukkit/event/player/PlayerItemConsumeEvent", """
package org.bukkit.event.player;

import org.bukkit.event.Cancellable;
import org.bukkit.inventory.ItemStack;

public class PlayerItemConsumeEvent extends PlayerEvent implements Cancellable {
    private boolean cancelled;

    public ItemStack getItem() {
        return null;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
""")

add("org/bukkit/event/block/BlockExplodeEvent", """
package org.bukkit.event.block;

import org.bukkit.block.Block;

import java.util.List;

public class BlockExplodeEvent extends BlockEvent {
    public List<Block> blockList() {
        return java.util.Collections.emptyList();
    }
}
""")

add("org/bukkit/event/entity/EntityExplodeEvent", """
package org.bukkit.event.entity;

import org.bukkit.block.Block;

import java.util.List;

public class EntityExplodeEvent extends EntityEvent {
    public List<Block> blockList() {
        return java.util.Collections.emptyList();
    }
}
""")


# Переопределения: методы, которые нужны VoidItems и есть в Paper.
add("org/bukkit/event/block/BlockPlaceEvent", """
package org.bukkit.event.block;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.inventory.ItemStack;

public class BlockPlaceEvent extends BlockEvent implements Cancellable {
    private final Player player;
    private boolean cancelled;

    public BlockPlaceEvent(Block block, Player player) {
        super(block);
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }

    public Block getBlockPlaced() {
        return getBlock();
    }

    public ItemStack getItemInHand() {
        return null;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
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
    private boolean dropItems = true;
    private int expToDrop;

    public BlockBreakEvent(Block block, Player player) {
        super(block);
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }

    public void setDropItems(boolean drop) {
        this.dropItems = drop;
    }

    public boolean isDropItems() {
        return dropItems;
    }

    public void setExpToDrop(int amount) {
        this.expToDrop = amount;
    }

    public int getExpToDrop() {
        return expToDrop;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
""")

add("org/bukkit/event/inventory/InventoryClickEvent", """
package org.bukkit.event.inventory;

import org.bukkit.entity.HumanEntity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class InventoryClickEvent extends Event implements Cancellable {
    private boolean cancelled;

    public Inventory getInventory() {
        return null;
    }

    public Inventory getClickedInventory() {
        return null;
    }

    public HumanEntity getWhoClicked() {
        return null;
    }

    public ItemStack getCurrentItem() {
        return null;
    }

    public ItemStack getCursor() {
        return null;
    }

    public int getSlot() {
        return 0;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
""")

add("org/bukkit/entity/HumanEntity", """
package org.bukkit.entity;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;

public interface HumanEntity extends LivingEntity {
    String getName();

    PlayerInventory getInventory();

    Inventory getEnderChest();
}
""")


def main(argv):
    target = argv[1] if len(argv) > 1 else "/tmp/stubs"
    for path, body in FILES.items():
        full = os.path.join(target, path + ".java")
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as handle:
            handle.write(body)
    print("заглушек: %d → %s" % (len(FILES), target))


if __name__ == "__main__":
    main(sys.argv)
