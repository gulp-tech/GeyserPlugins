// Decomcompiled with Vineflower (JS/WASM port @run-slicer/vf) from HyperTNT.jar
// Восстановлено из байткода: без комментариев, имена локальных переменных условны.
package mk.hypertnt;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import java.lang.reflect.Method;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;

public final class WorldGuardBridge {
   private final HyperTNTPlugin plugin;

   public WorldGuardBridge(HyperTNTPlugin plugin) {
      this.plugin = plugin;
   }

   public boolean canExplode(Location center, Location target) {
      if (center != null && target != null && center.getWorld() != null && target.getWorld() != null) {
         if (!center.getWorld().equals(target.getWorld())) {
            return false;
         }

         try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionQuery query = container.createQuery();

            for (ProtectedRegion region : query.getApplicableRegions(BukkitAdapter.adapt(target))) {
               String regionId = region.getId().toLowerCase(Locale.ROOT);
               if (!regionId.equals("__global__") && !this.isShieldyRegion(target, regionId)) {
                  return false;
               }
            }

            return true;
         } catch (Exception exception) {
            this.plugin.getLogger().warning("WorldGuard explosion check failed (" + exception.getClass().getSimpleName() + "): " + exception.getMessage());
            return false;
         }
      } else {
         return false;
      }
   }

   private boolean isShieldyRegion(Location location, String regionId) {
      Plugin shieldy = this.getShieldyPlugin();
      if (shieldy == null) {
         return false;
      }

      try {
         Object repository = shieldy.getClass().getMethod("currentRegionRepository", new Class[0]).invoke(shieldy, new Object[0]);
         Method findAll = repository.getClass().getMethod("findAll", new Class[0]);
         Object result = findAll.invoke(repository, new Object[0]);
         if (result instanceof Iterable) {
            for (Object record : (Iterable)result) {
               String actualId = String.valueOf(record.getClass().getMethod("getRegionName", new Class[0]).invoke(record, new Object[0]));
               if (actualId.equalsIgnoreCase(regionId)) {
                  String worldName = String.valueOf(record.getClass().getMethod("getWorldName", new Class[0]).invoke(record, new Object[0]));
                  if (worldName.equals(location.getWorld().getName())) {
                     Object manager = shieldy.getClass().getMethod("currentRegionManager", new Class[0]).invoke(shieldy, new Object[0]);
                     Object foundRecord = manager.getClass().getMethod("findRegionAt", new Class[]{Location.class}).invoke(manager, new Object[]{location});
                     if (foundRecord == null) {
                        return false;
                     }

                     String foundId = String.valueOf(foundRecord.getClass().getMethod("getRegionName", new Class[0]).invoke(foundRecord, new Object[0]));
                     return foundId.equalsIgnoreCase(regionId);
                  }
               }
            }

            return false;
         } else {
            return false;
         }
      } catch (Exception exception) {
         this.plugin.getLogger().warning("ShieldyBlocks region check failed (" + exception.getClass().getSimpleName() + "): " + exception.getMessage());
         return false;
      }
   }

   private Plugin getShieldyPlugin() {
      Plugin shieldy = Bukkit.getPluginManager().getPlugin("ShieldyBlocks");
      return shieldy != null && shieldy.isEnabled() ? shieldy : null;
   }

   public boolean isShieldCore(Location location) {
      return this.findShieldCore(location) != null;
   }

   public Object findShieldCore(Location location) {
      Plugin shieldy = this.getShieldyPlugin();
      if (shieldy != null && location != null && location.getWorld() != null) {
         try {
            Object repository = shieldy.getClass().getMethod("currentRegionRepository", new Class[0]).invoke(shieldy, new Object[0]);
            Method findMethod = repository.getClass().getMethod("findByLocation", new Class[]{String.class, int.class, int.class, int.class});
            return findMethod.invoke(repository, new Object[]{location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ()});
         } catch (Exception exception) {
            this.plugin.getLogger().warning("Shield core lookup failed (" + exception.getClass().getSimpleName() + "): " + exception.getMessage());
            return null;
         }
      } else {
         return null;
      }
   }

   public boolean damageShieldCore(Location location, String tntId) {
      Object record = this.findShieldCore(location);
      if (record == null) {
         return false;
      }

      Plugin shieldy = this.getShieldyPlugin();
      if (shieldy == null) {
         return false;
      }

      String resolvedTntId = tntId != null && !tntId.isBlank() ? tntId : "vanilla";

      try {
         Object manager = shieldy.getClass().getMethod("currentRegionManager", new Class[0]).invoke(shieldy, new Object[0]);
         Method twoParamMethod = null;
         Method oneParamMethod = null;

         for (Method method : manager.getClass().getMethods()) {
            if (method.getName().equals("applyTntImpact")) {
               Class<?>[] paramTypes = method.getParameterTypes();
               if (paramTypes.length == 2 && paramTypes[1] == String.class) {
                  twoParamMethod = method;
               } else if (paramTypes.length == 1) {
                  oneParamMethod = method;
               }
            }
         }

         Object result;
         if (twoParamMethod != null) {
            result = twoParamMethod.invoke(manager, new Object[]{record, resolvedTntId});
         } else {
            if (oneParamMethod == null) {
               this.plugin.getLogger().warning("ShieldyBlocks applyTntImpact method not found.");
               return false;
            }

            this.plugin
               .getLogger()
               .warning("ShieldyBlocks does not expose applyTntImpact(record, tntId); falling back to legacy method, allowed-tnts will not work correctly.");
            result = oneParamMethod.invoke(manager, new Object[]{record});
         }

         if (result == null) {
            return false;
         }

         String resultName = result.getClass().getSimpleName();
         if (resultName.equals("Destroyed")) {
            Object definition = result.getClass().getMethod("getDefinition", new Class[0]).invoke(result, new Object[0]);
            location.getBlock().setType(Material.AIR, false);
            Method dropMethod = null;

            for (Method method : manager.getClass().getMethods()) {
               if (method.getName().equals("dropShieldBlockItem") && method.getParameterCount() == 2) {
                  dropMethod = method;
                  break;
               }
            }

            if (dropMethod != null) {
               dropMethod.invoke(manager, new Object[]{location, definition});
            }
         }

         return true;
      } catch (Exception exception) {
         this.plugin.getLogger().warning("ShieldyBlocks TNT integration failed (" + exception.getClass().getSimpleName() + "): " + exception.getMessage());
         return false;
      }
   }
}
