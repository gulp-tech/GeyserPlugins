package ru.voidpe.smoke;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CI-only: runs on a throwaway local Purpur world, never included in a release.
 * Phase 1 builds state and stops the server; phase 2 is a second JVM on the same world
 * directory and checks what survived the restart.
 */
public final class SecuritySmoke extends JavaPlugin implements Listener {
    /** Restart probe: one custom TNT block placed in phase 1, plus a second one placed after restart. */
    private static final int PROBE_X = 470, PROBE_Z = 470;
    private final List<ItemStack> spawned = new ArrayList<>();

    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        boolean restart = "2".equals(System.getProperty("voidpe.smoke.phase", "1"));
        Runnable scenario;
        if (restart) scenario = this::runRestartChecks;
        else scenario = this::runTests;
        Bukkit.getScheduler().runTaskLater(this, scenario, 80L);
    }

    @EventHandler public void onSpawn(ItemSpawnEvent event) { spawned.add(event.getEntity().getItemStack().clone()); }

    private static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }

    private static Object call(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        return target.getClass().getMethod(method, types).invoke(target, args);
    }

    /** Same as {@link #call} but also reaches non-public methods; used only for HyperTNT internals. */
    private static Object reflect(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static Object findListener(Plugin plugin, String simpleName) {
        for (RegisteredListener registered : HandlerList.getRegisteredListeners(plugin)) {
            Object listener = registered.getListener();
            if (listener.getClass().getName().equals("mk.hypertnt." + simpleName)) return listener;
        }
        throw new AssertionError("HyperTNT listener is not registered: " + simpleName);
    }

    @SuppressWarnings("unchecked")
    private static Map<Location, String> placedMap(Object listener) throws Exception {
        Field field = listener.getClass().getDeclaredField("placed");
        field.setAccessible(true);
        return (Map<Location, String>) field.get(listener);
    }

    /** Places a custom TNT through HyperTNT's own onPlace handler, the same path a player's placement takes. */
    private static void placeCustomTnt(Plugin tnt, Object tntManager, Block block) throws Exception {
        BlockState replaced = block.getState();
        ItemStack item = (ItemStack) reflect(tntManager, "createItem", new Class<?>[]{String.class, int.class}, "tnt", 1);
        block.setType(Material.TNT, false);
        BlockPlaceEvent place = new BlockPlaceEvent(block, replaced, block.getRelative(BlockFace.DOWN), item, null, true, EquipmentSlot.HAND);
        reflect(findListener(tnt, "TntListener"), "onPlace", new Class<?>[]{BlockPlaceEvent.class}, place);
    }

    private void runTests() {
        try {
            World world = Bukkit.getWorld("world");
            Plugin city = Bukkit.getPluginManager().getPlugin("VoidWardenCity");
            Plugin tnt = Bukkit.getPluginManager().getPlugin("HyperTNT");
            check(world != null && city != null && city.isEnabled() && tnt != null && tnt.isEnabled(), "plugins and world loaded");
            RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
            check(manager != null, "WorldGuard region manager");
            File stateFile = new File(city.getDataFolder(), "worldguard-region.yml");
            String id = YamlConfiguration.loadConfiguration(stateFile).getString("region-id");
            check(id != null && id.startsWith("voidwardencity_"), "managed region ownership file");
            ProtectedRegion managed = manager.getRegion(id);
            check(managed != null, "managed region created");
            check(managed.contains(BlockVector3.at(0, 80, 0)), "city center covered");
            check(managed.contains(BlockVector3.at(8, 85, 8)), "square and upper bound covered");
            check(!managed.contains(BlockVector3.at(9, 80, 0)), "outside bounds");
            check(managed.getFlag(Flags.TNT) == State.DENY
                    && managed.getFlag(Flags.CREEPER_EXPLOSION) == State.DENY
                    && managed.getFlag(Flags.OTHER_EXPLOSION) == State.DENY
                    && managed.getFlag(Flags.PASSTHROUGH) == State.ALLOW
                    && managed.getFlag(Flags.EXP_DROPS) == State.ALLOW, "region flags");
            check(managed.getFlag(Flags.PVP) == null && managed.getFlag(Flags.CHEST_ACCESS) == null,
                    "do not force PvP or chest access");

            ProtectedCuboidRegion other = new ProtectedCuboidRegion("smoke_existing", BlockVector3.at(250, 60, 250), BlockVector3.at(260, 90, 260));
            other.setPriority(6);
            other.setFlag(Flags.BLOCK_BREAK, State.DENY);
            manager.addRegion(other);
            manager.saveChanges();
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "wcity reload");
            check(manager.getRegion("smoke_existing") == other && other.getPriority() == 6
                    && other.getFlag(Flags.BLOCK_BREAK) == State.DENY, "existing region unchanged on reload");
            check(manager.hasRegion(id), "managed region persists on reload");

            Object bridge = call(tnt, "getWorldGuardBridge", new Class<?>[0]);
            Location inside = new Location(world, 0, 80, 0);
            Location outside = new Location(world, 400, 80, 400);
            Method canExplode = bridge.getClass().getMethod("canExplode", Location.class, Location.class);
            check(Boolean.FALSE.equals(canExplode.invoke(bridge, outside, inside)), "HyperTNT denies protected destination across border");
            check(Boolean.TRUE.equals(canExplode.invoke(bridge, outside, outside)), "HyperTNT still works outside regions");

            Block insideBlock = inside.getBlock();
            Block outsideBlock = outside.getBlock();
            insideBlock.setType(Material.STONE);
            outsideBlock.setType(Material.STONE);
            Entity entity = world.spawn(outside.clone().add(.5, 0, .5), TNTPrimed.class);
            List<Block> entityBlocks = new ArrayList<>(List.of(insideBlock, outsideBlock));
            EntityExplodeEvent entityEvent = new EntityExplodeEvent(entity, outside, entityBlocks, 1f, ExplosionResult.DESTROY);
            city.getClass().getMethod("onExplodeBlocks", EntityExplodeEvent.class).invoke(city, entityEvent);
            check(!entityBlocks.contains(insideBlock) && entityBlocks.contains(outsideBlock), "entity explosion block filtering");
            List<Block> blockBlocks = new ArrayList<>(List.of(insideBlock, outsideBlock));
            BlockExplodeEvent blockEvent = new BlockExplodeEvent(outsideBlock, outsideBlock.getState(), blockBlocks, 1f, ExplosionResult.DESTROY);
            city.getClass().getMethod("onExplodeBlocks", BlockExplodeEvent.class).invoke(city, blockEvent);
            check(!blockBlocks.contains(insideBlock) && blockBlocks.contains(outsideBlock), "block explosion block filtering");
            entity.remove();

            Object managerTnt = call(tnt, "getTntManager", new Class<?>[0]);
            List<Material> shulkers = java.util.Arrays.stream(Material.values())
                    .filter(m -> m.name().equals("SHULKER_BOX") || m.name().endsWith("_SHULKER_BOX"))
                    .filter(m -> !m.isLegacy()).filter(Material::isBlock).toList();
            check(shulkers.size() == 17, "17 shulker variants");
            for (String typeName : List.of("tnt", "tnt_fire", "tnt_obs", "tnt_heavy", "tnt_explosive", "tnt_poseidon")) {
                Object type = call(managerTnt, "getType", new Class<?>[]{String.class}, typeName);
                check(type != null, "HyperTNT type loaded: " + typeName);
                Method mode = type.getClass().getMethod("blockMode", Material.class);
                for (Material box : shulkers) check("IGNORE".equals(mode.invoke(type, box)), typeName + " " + box + " ignore");
            }

            // Invoke the same listener path as an actual primed HyperTNT entity.
            NamespacedKey entityKey = (NamespacedKey) call(managerTnt, "getEntityKey", new Class<?>[0]);
            Block blastBox = world.getBlockAt(440, 80, 440);
            blastBox.setType(Material.PURPLE_SHULKER_BOX);
            ((ShulkerBox) blastBox.getState()).getInventory().setItem(0, new ItemStack(Material.DIAMOND, 7));
            Block nearbyStone = world.getBlockAt(441, 80, 440);
            nearbyStone.setType(Material.STONE);
            TNTPrimed primed = world.spawn(new Location(world, 440.5, 80.5, 440.5), TNTPrimed.class);
            primed.getPersistentDataContainer().set(entityKey, PersistentDataType.STRING, "tnt");
            ExplosionPrimeEvent prime = new ExplosionPrimeEvent(primed, 6, false);
            Bukkit.getPluginManager().callEvent(prime);
            check(prime.isCancelled() && !primed.isValid(), "custom TNT listener ran");
            check(blastBox.getType() == Material.PURPLE_SHULKER_BOX
                    && ((ShulkerBox) blastBox.getState()).getInventory().getItem(0).getAmount() == 7,
                    "safe config preserves filled shulker in actual explosion");
            check(nearbyStone.getType().isAir(), "ordinary block destroyed outside region");

            Block protectedEdge = world.getBlockAt(8, 80, 0);
            Block unprotectedOrigin = world.getBlockAt(10, 80, 0);
            protectedEdge.setType(Material.STONE);
            unprotectedOrigin.setType(Material.STONE);
            TNTPrimed borderTnt = world.spawn(new Location(world, 10.5, 80.5, 0.5), TNTPrimed.class);
            borderTnt.getPersistentDataContainer().set(entityKey, PersistentDataType.STRING, "tnt");
            ExplosionPrimeEvent borderPrime = new ExplosionPrimeEvent(borderTnt, 6, false);
            Bukkit.getPluginManager().callEvent(borderPrime);
            check(borderPrime.isCancelled(), "TNT within margin of city is cancelled");
            check(unprotectedOrigin.getType() == Material.STONE, "TNT near city does not blast outside stone");
            check(protectedEdge.getType() == Material.STONE, "TNT near city does not blast protected edge");

            // Double chest straddling the region boundary: a blast from outside must not empty it.
            Block insideHalf = world.getBlockAt(8, 80, 3);
            Block outsideHalf = world.getBlockAt(9, 80, 3);
            insideHalf.setType(Material.CHEST, false);
            outsideHalf.setType(Material.CHEST, false);
            boolean joined = false;
            for (boolean reverse : new boolean[] {false, true}) {
                org.bukkit.block.data.type.Chest a = (org.bukkit.block.data.type.Chest) insideHalf.getBlockData();
                org.bukkit.block.data.type.Chest b = (org.bukkit.block.data.type.Chest) outsideHalf.getBlockData();
                a.setFacing(BlockFace.NORTH); b.setFacing(BlockFace.NORTH);
                a.setType(reverse ? org.bukkit.block.data.type.Chest.Type.RIGHT : org.bukkit.block.data.type.Chest.Type.LEFT);
                b.setType(reverse ? org.bukkit.block.data.type.Chest.Type.LEFT : org.bukkit.block.data.type.Chest.Type.RIGHT);
                insideHalf.setBlockData(a, false); outsideHalf.setBlockData(b, false);
                if (((Chest) outsideHalf.getState()).getInventory().getSize() == 54) { joined = true; break; }
            }
            check(joined, "test double chest joins across region boundary");
            ((Chest) insideHalf.getState()).getBlockInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
            spawned.clear();
            TNTPrimed chestTnt = world.spawn(new Location(world, 10.5, 80.5, 3.5), TNTPrimed.class);
            chestTnt.getPersistentDataContainer().set(entityKey, PersistentDataType.STRING, "tnt");
            Bukkit.getPluginManager().callEvent(new ExplosionPrimeEvent(chestTnt, 6, false));
            int leaked = spawned.stream().filter(i -> i.getType() == Material.DIAMOND).mapToInt(ItemStack::getAmount).sum();
            check(leaked == 0 && ((Chest) insideHalf.getState()).getBlockInventory().contains(Material.DIAMOND, 5),
                    "double chest across boundary keeps its protected contents");

            // Reproduce the original JAR's breakBlock bug on a throwaway box.
            Block boxBlock = world.getBlockAt(420, 80, 420);
            boxBlock.setType(Material.PURPLE_SHULKER_BOX);
            ShulkerBox box = (ShulkerBox) boxBlock.getState();
            box.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 7));
            check(box.getInventory().getItem(0) != null, "test box filled");
            Object service = call(tnt, "getExplosionService", new Class<?>[0]);
            Method breakBlock = service.getClass().getDeclaredMethod("breakBlock", Block.class, double.class);
            breakBlock.setAccessible(true);
            spawned.clear();
            breakBlock.invoke(service, boxBlock, 1.0d);
            int loose = 0, insideDrop = 0;
            for (ItemStack item : spawned) {
                if (item.getType() == Material.DIAMOND) loose += item.getAmount();
                if (item.getType() == Material.PURPLE_SHULKER_BOX && item.getItemMeta() instanceof BlockStateMeta meta
                        && meta.getBlockState() instanceof ShulkerBox dropped) {
                    for (ItemStack stored : dropped.getSnapshotInventory().getContents())
                        if (stored != null && stored.getType() == Material.DIAMOND) insideDrop += stored.getAmount();
                }
            }
            getLogger().info("FINDING shulker-breakBlock: loose diamonds=" + loose + ", inside box diamonds=" + insideDrop
                    + (loose == 7 && insideDrop == 7 ? " -> DUPLICATION" : " -> no duplication"));

            // Restart probe: phase 2 checks that this block is still in the world and whether HyperTNT still knows its type.
            Block probe = world.getBlockAt(PROBE_X, 80, PROBE_Z);
            probe.setType(Material.AIR, false);
            placeCustomTnt(tnt, managerTnt, probe);
            check("tnt".equals(placedMap(findListener(tnt, "TntListener")).get(probe.getLocation())),
                    "HyperTNT records the placed type in memory before restart");
            // Entities still in the world would otherwise be saved with the chunk and re-tick after the restart.
            for (TNTPrimed leftover : world.getEntitiesByClass(TNTPrimed.class)) leftover.remove();

            getLogger().info("SECURITY_SMOKE_PASS (phase 1): WG region, boundary TNT, double chest, 17 shulker-colour configs; restart probe written");
            Files.writeString(Path.of(System.getProperty("voidpe.smoke.marker")), "passed\n");
        } catch (Throwable e) {
            getLogger().severe("SECURITY_SMOKE_FAILED: " + e);
            e.printStackTrace();
        } finally {
            Bukkit.shutdown();
        }
    }

    /** Phase 2: a fresh JVM on the same world directory, after the phase-1 server stopped. */
    private void runRestartChecks() {
        try {
            World world = Bukkit.getWorld("world");
            Plugin city = Bukkit.getPluginManager().getPlugin("VoidWardenCity");
            Plugin tnt = Bukkit.getPluginManager().getPlugin("HyperTNT");
            check(world != null && city != null && city.isEnabled() && tnt != null && tnt.isEnabled(), "plugins and world loaded after restart");
            RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
            check(manager != null, "WorldGuard region manager after restart");

            File stateFile = new File(city.getDataFolder(), "worldguard-region.yml");
            String id = YamlConfiguration.loadConfiguration(stateFile).getString("region-id");
            check(id != null && id.startsWith("voidwardencity_"), "region ownership survives restart");
            ProtectedRegion managed = manager.getRegion(id);
            check(managed != null, "managed region still exists after restart");
            long managedCount = manager.getRegions().keySet().stream().filter(k -> k.startsWith("voidwardencity_")).count();
            check(managedCount == 1, "no duplicate managed city region after restart (found " + managedCount + ")");
            check(managed.contains(BlockVector3.at(0, 80, 0)) && !managed.contains(BlockVector3.at(9, 80, 0)),
                    "managed region bounds after restart");
            check(managed.getFlag(Flags.TNT) == State.DENY
                    && managed.getFlag(Flags.CREEPER_EXPLOSION) == State.DENY
                    && managed.getFlag(Flags.OTHER_EXPLOSION) == State.DENY
                    && managed.getFlag(Flags.PASSTHROUGH) == State.ALLOW
                    && managed.getFlag(Flags.EXP_DROPS) == State.ALLOW, "region flags after restart");

            ProtectedRegion other = manager.getRegion("smoke_existing");
            check(other != null && other.getPriority() == 6 && other.getFlag(Flags.BLOCK_BREAK) == State.DENY,
                    "pre-existing region untouched across restart");

            NamespacedKey entityKey = (NamespacedKey) call(call(tnt, "getTntManager", new Class<?>[0]), "getEntityKey", new Class<?>[0]);
            Block borderEdge = world.getBlockAt(8, 80, 0);
            Block borderOrigin = world.getBlockAt(10, 80, 0);
            borderEdge.setType(Material.STONE);
            borderOrigin.setType(Material.STONE);
            TNTPrimed borderTnt = world.spawn(new Location(world, 10.5, 80.5, 0.5), TNTPrimed.class);
            borderTnt.getPersistentDataContainer().set(entityKey, PersistentDataType.STRING, "tnt");
            ExplosionPrimeEvent borderPrime = new ExplosionPrimeEvent(borderTnt, 6, false);
            Bukkit.getPluginManager().callEvent(borderPrime);
            check(borderPrime.isCancelled(), "TNT near city is still cancelled after restart");
            check(borderOrigin.getType() == Material.STONE && borderEdge.getType() == Material.STONE,
                    "no blast damage to city edge after restart");

            // Restart probe. The custom TNT block must still be in the world; HyperTNT's type map is memory only.
            Block probe = world.getBlockAt(PROBE_X, 80, PROBE_Z);
            check(probe.getType() == Material.TNT, "placed custom TNT block survives the restart in the world");
            Object listener = findListener(tnt, "TntListener");
            Map<Location, String> placed = placedMap(listener);
            boolean typeKept = placed.containsKey(probe.getLocation());
            getLogger().info("FINDING restart-placed-hypertnt: world block=TNT, in-memory placed map has type=" + typeKept
                    + (typeKept ? " -> type kept" : " -> type lost; break/redstone/flint paths ignore this block and treat it as vanilla TNT"));

            // A placement after restart must be recorded again, so HyperTNT works for new blocks.
            Block fresh = world.getBlockAt(PROBE_X + 1, 80, PROBE_Z);
            fresh.setType(Material.AIR, false);
            placeCustomTnt(tnt, call(tnt, "getTntManager", new Class<?>[0]), fresh);
            check("tnt".equals(placedMap(listener).get(fresh.getLocation())), "new custom TNT placement recorded after restart");

            getLogger().info("SECURITY_SMOKE_PASS (phase 2): region, flags, boundary TNT and world blocks survived the restart; see FINDING lines");
            Files.writeString(Path.of(System.getProperty("voidpe.smoke.marker")), "passed\n");
        } catch (Throwable e) {
            getLogger().severe("SECURITY_SMOKE_FAILED: " + e);
            e.printStackTrace();
        } finally {
            Bukkit.shutdown();
        }
    }
}
