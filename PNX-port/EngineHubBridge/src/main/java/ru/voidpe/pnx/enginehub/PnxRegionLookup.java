package ru.voidpe.pnx.enginehub;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.powernukkitx.level.Position;
import org.powernukkitx.math.Vector3;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;

/**
 * Read-only adapter for real WorldGuard region queries.
 * This is not a protection listener, plugin, player adapter, or WorldGuard platform.
 * Callers must treat lookup failure as unavailable, never as permission to bypass protection.
 */
public final class PnxRegionLookup {
    private final Function<PnxWorldKey, RegionManager> managers;

    public PnxRegionLookup(Function<PnxWorldKey, RegionManager> managers) {
        this.managers = Objects.requireNonNull(managers, "managers");
    }

    public ApplicableRegionSet applicable(Position position) {
        Objects.requireNonNull(position, "position");
        if (position.getLevel() == null) throw new IllegalArgumentException("Position has no PNX level");
        return applicable(PnxWorldKey.of(position.getLevel()), position);
    }

    public ApplicableRegionSet applicable(PnxWorldKey world, Vector3 point) {
        BlockVector3 vector = PnxBlockCoordinates.toWorldEdit(point);
        return requireManager(world).getApplicableRegions(vector);
    }

    /** Match configured WorldGuard region IDs like the original VoidItems/VoidCombat hooks. */
    public boolean containsNamedRegion(PnxWorldKey world, Vector3 point, Collection<String> regionNames) {
        Objects.requireNonNull(regionNames, "regionNames");
        BlockVector3 vector = PnxBlockCoordinates.toWorldEdit(point);
        RegionManager manager = requireManager(world);
        for (String name : regionNames) {
            if (name == null || name.isBlank()) continue;
            ProtectedRegion region = manager.getRegion(name);
            if (region != null && region.contains(vector)) return true;
        }
        return false;
    }

    private RegionManager requireManager(PnxWorldKey world) {
        Objects.requireNonNull(world, "world");
        RegionManager manager = managers.apply(world);
        if (manager == null) throw new RegionUnavailableException(world);
        return manager;
    }

    public static final class RegionUnavailableException extends IllegalStateException {
        public RegionUnavailableException(PnxWorldKey world) {
            super("No WorldGuard region manager for " + world);
        }
    }
}
