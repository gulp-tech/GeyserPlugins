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
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** CI-only: runs on a throwaway local Purpur world, never included in a release. */
public final class SecuritySmoke extends JavaPlugin implements Listener {
    private final List<ItemStack> spawned = new ArrayList<>();
    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskLater(this, this::runTests, 80L);
    }
    @EventHandler public void onSpawn(ItemSpawnEvent event) { spawned.add(event.getEntity().getItemStack().clone()); }
    private static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }
    private static Object call(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        return target.getClass().getMethod(method, types).invoke(target, args);
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
                    .filter(Material::isBlock).toList();
            check(shulkers.size() == 17, "17 shulker variants");
            for (String typeName : List.of("tnt", "tnt_fire", "tnt_obs", "tnt_heavy", "tnt_explosive", "tnt_poseidon")) {
                Object type = call(managerTnt, "getType", new Class<?>[]{String.class}, typeName);
                check(type != null, "HyperTNT type loaded: " + typeName);
                Method mode = type.getClass().getMethod("blockMode", Material.class);
                for (Material box : shulkers) check("IGNORE".equals(mode.invoke(type, box)), typeName + " " + box + " ignore");
            }

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
            check(loose == 7 && insideDrop == 7, "reproduce original HyperTNT shulker duplication");
            getLogger().info("SECURITY_SMOKE_PASS: WG region, explosions, all TNT configs, and original TNT dupe reproduced");
            Files.writeString(Path.of(System.getProperty("voidpe.smoke.marker")), "passed\n");
        } catch (Throwable e) {
            getLogger().severe("SECURITY_SMOKE_FAILED: " + e);
            e.printStackTrace();
        } finally {
            Bukkit.shutdown();
        }
    }
}
