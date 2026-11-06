package ru.voidpe.wardencity;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** Only loaded after checking that WorldGuard is enabled. */
final class WorldGuardCityRegion {
    private final VoidWardenCity plugin;
    private String status = "регион ещё не создан";

    WorldGuardCityRegion(VoidWardenCity plugin) { this.plugin = plugin; }
    String status() { return status; }

    boolean sync(Location center) {
        if (center == null || center.getWorld() == null) return fail("мир центра не загружен");
        World world = center.getWorld();
        CityBounds.Cuboid b;
        try {
            b = CityBounds.covering(center.getX(), center.getY(), center.getZ(),
                    plugin.getConfig().getDouble("city.radius", 120),
                    plugin.getConfig().getDouble("city.y-below", 45),
                    plugin.getConfig().getDouble("city.y-above", 45),
                    world.getMinHeight(), world.getMaxHeight());
        } catch (IllegalArgumentException e) {
            return fail(e.getMessage());
        }
        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        if (manager == null) return fail("WorldGuard не загрузил регионы мира " + world.getName());
        File file = new File(plugin.getDataFolder(), "worldguard-region.yml");
        YamlConfiguration state = YamlConfiguration.loadConfiguration(file);
        String id = state.getString("region-id", "");
        String oldWorld = state.getString("world", "");
        if (id.isBlank() || !oldWorld.equals(world.getName())) {
            String previous = id;
            do {
                // Never take over another existing named region.
                id = "voidwardencity_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            } while (manager.hasRegion(id));
            state.set("region-id", id);
            state.set("world", world.getName());
            try {
                // Persist ownership before creating/updating the WG region.
                state.save(file);
            } catch (IOException e) {
                return fail("нельзя записать worldguard-region.yml: " + e.getMessage());
            }
            if (!previous.isBlank()) {
                plugin.getLogger().warning("Центр перенесён в другой мир. Старый управляемый регион " + previous
                        + " в мире " + oldWorld + " оставлен без изменений; удалите его вручную, если он больше не нужен.");
            }
        }
        ProtectedRegion old = manager.getRegion(id);
        ProtectedCuboidRegion region = new ProtectedCuboidRegion(id,
                BlockVector3.at(b.minX(), b.minY(), b.minZ()), BlockVector3.at(b.maxX(), b.maxY(), b.maxZ()));
        if (old != null) region.copyFrom(old); // retain other flags, membership, priority and parent
        else region.setPriority(-100); // existing normal-priority regions take precedence
        // Passthrough is designed to add flags without overriding existing build regions.
        region.setFlag(Flags.PASSTHROUGH, State.ALLOW);
        region.setFlag(Flags.TNT, State.DENY);
        region.setFlag(Flags.CREEPER_EXPLOSION, State.DENY);
        region.setFlag(Flags.OTHER_EXPLOSION, State.DENY);
        region.setFlag(Flags.GHAST_FIREBALL, State.DENY);
        region.setFlag(Flags.WITHER_DAMAGE, State.DENY);
        region.setFlag(Flags.FIRE_SPREAD, State.DENY);
        region.setFlag(Flags.LAVA_FIRE, State.DENY);
        region.setFlag(Flags.EXP_DROPS, State.ALLOW);
        // Do not set PvP, mob spawning, chest access or build flags: preserve gameplay/other regions.
        manager.addRegion(region);
        try {
            manager.saveChanges();
        } catch (Exception e) {
            return fail("регион " + id + " активен в памяти, но не сохранён WorldGuard: " + e.getMessage());
        }
        status = "WorldGuard: " + id + ", мир " + world.getName() + ", X=" + b.minX() + ".." + b.maxX()
                + ", Y=" + b.minY() + ".." + b.maxY() + ", Z=" + b.minZ() + ".." + b.maxZ();
        plugin.getLogger().info(status + ". Взрывы запрещены, доступ к сундукам/PvP не менялся.");
        return true;
    }

    private boolean fail(String reason) {
        status = "ОШИБКА региона: " + reason;
        plugin.getLogger().severe(status + ". Проверьте защиту и не запускайте HyperTNT до исправления.");
        return false;
    }
}
