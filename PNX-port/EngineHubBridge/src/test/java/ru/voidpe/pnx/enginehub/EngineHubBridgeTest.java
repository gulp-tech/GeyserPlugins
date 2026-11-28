package ru.voidpe.pnx.enginehub;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.SimpleFlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.index.HashMapIndex;
import com.sk89q.worldguard.protection.managers.storage.MemoryRegionDatabase;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import org.powernukkitx.level.Position;
import org.powernukkitx.math.Vector3;
import java.util.List;
import java.util.Map;

public final class EngineHubBridgeTest {
    public static void main(String[] args) {
        check(PnxBlockCoordinates.toWorldEdit(new Vector3(-0.1, -64.0, 2.99)).equals(BlockVector3.at(-1, -64, 2)), "negative floor");
        check(PnxBlockCoordinates.toWorldEdit(new Vector3(0, 1, -1)).equals(BlockVector3.at(0, 1, -1)), "integer coordinates");
        expect(IllegalArgumentException.class, () -> PnxBlockCoordinates.toWorldEdit(new Vector3(Double.NaN, 0, 0)));
        expect(IllegalArgumentException.class, () -> PnxBlockCoordinates.toWorldEdit(new Vector3(Double.POSITIVE_INFINITY, 0, 0)));
        expect(IllegalArgumentException.class, () -> PnxBlockCoordinates.toWorldEdit(new Vector3(2147483648.0, 0, 0)));
        expect(IllegalArgumentException.class, () -> new PnxWorldKey(" ", 0));

        PnxWorldKey overworld = new PnxWorldKey("world", 0);
        PnxWorldKey nether = new PnxWorldKey("world", 1);
        RegionManager overworldManager = new RegionManager(new MemoryRegionDatabase(), new HashMapIndex.Factory(), new SimpleFlagRegistry());
        RegionManager netherManager = new RegionManager(new MemoryRegionDatabase(), new HashMapIndex.Factory(), new SimpleFlagRegistry());
        StateFlag flag = new StateFlag("test-pvp", false);
        ProtectedCuboidRegion area = new ProtectedCuboidRegion("spawn", BlockVector3.at(-10, -64, -10), BlockVector3.at(10, 80, 10));
        area.setFlag(flag, StateFlag.State.DENY);
        ProtectedCuboidRegion sub = new ProtectedCuboidRegion("arena", BlockVector3.at(0, 0, 0), BlockVector3.at(4, 70, 4));
        sub.setPriority(10);
        sub.setFlag(flag, StateFlag.State.ALLOW);
        overworldManager.addRegion(area);
        overworldManager.addRegion(sub);
        PnxRegionLookup adapter = new PnxRegionLookup(Map.of(overworld, overworldManager, nether, netherManager)::get);
        check(adapter.containsNamedRegion(overworld, new Vector3(-0.01, -64, 0), List.of("SPAWN")), "named region & negative boundary");
        check(!adapter.containsNamedRegion(overworld, new Vector3(-10.01, 0, 0), List.of("spawn")), "outside region");
        check(!adapter.containsNamedRegion(nether, new Vector3(0, 5, 0), List.of("spawn")), "dimension isolation");
        check(adapter.applicable(overworld, new Vector3(1, 1, 1)).size() == 2, "overlap");
        check(adapter.applicable(overworld, new Vector3(1, 1, 1)).queryState(null, flag) == StateFlag.State.ALLOW, "higher priority");
        check(adapter.applicable(overworld, new Vector3(-1, 1, -1)).queryState(null, flag) == StateFlag.State.DENY, "default area");
        expect(PnxRegionLookup.RegionUnavailableException.class, () -> adapter.applicable(new PnxWorldKey("missing", 0), new Vector3()));
        expect(PnxRegionLookup.RegionUnavailableException.class, () -> adapter.containsNamedRegion(new PnxWorldKey("missing", 0), new Vector3(), List.of()));
        expect(IllegalArgumentException.class, () -> adapter.applicable(new Position(0, 0, 0)));
        System.out.println("EngineHubBridgeTest passed");
    }
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
    private static void expect(Class<? extends Throwable> type, Runnable task) {
        try { task.run(); } catch (Throwable e) { if (type.isInstance(e)) return; throw new AssertionError(e); }
        throw new AssertionError("Expected " + type.getName());
    }
}
