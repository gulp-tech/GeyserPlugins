package mk.hypertnt;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Locale;
import mk.hypertnt.HyperTNTPlugin;
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
      if(center != null && target != null && center.getWorld() != null && target.getWorld() != null) {
         if(!center.getWorld().equals(target.getWorld())) {
            return false;
         } else {
            try {
               RegionContainer exception = WorldGuard.getInstance().getPlatform().getRegionContainer();
               RegionQuery query = exception.createQuery();
               ApplicableRegionSet regions = query.getApplicableRegions(BukkitAdapter.adapt(target));
               Iterator var6 = regions.iterator();

               String regionId;
               do {
                  if(!var6.hasNext()) {
                     return true;
                  }

                  ProtectedRegion region = (ProtectedRegion)var6.next();
                  regionId = region.getId().toLowerCase(Locale.ROOT);
               } while(regionId.equals("__global__") || this.isShieldyRegion(target, regionId));

               return false;
            } catch (Exception var9) {
               this.plugin.getLogger();
               var9.getClass().getSimpleName().warning(var9.getMessage());
               return false;
            }
         }
      } else {
         return false;
      }
   }

   private boolean isShieldyRegion(Location location, String regionId) {
      Plugin shieldy = this.getShieldyPlugin();
      if(shieldy == null) {
         return false;
      } else {
         try {
            Object exception = shieldy.getClass().getMethod("currentRegionRepository", new Class[0]).invoke(shieldy, new Object[0]);
            Method findAll = exception.getClass().getMethod("findAll", new Class[0]);
            Object result = findAll.invoke(exception, new Object[0]);
            if(result instanceof Iterable) {
               Iterable records = (Iterable)result;
               Iterator var8 = records.iterator();

               while(var8.hasNext()) {
                  Object record = var8.next();
                  String actualId = String.valueOf(record.getClass().getMethod("getRegionName", new Class[0]).invoke(record, new Object[0]));
                  if(actualId.equalsIgnoreCase(regionId)) {
                     String worldName = String.valueOf(record.getClass().getMethod("getWorldName", new Class[0]).invoke(record, new Object[0]));
                     if(worldName.equals(location.getWorld().getName())) {
                        Object manager = shieldy.getClass().getMethod("currentRegionManager", new Class[0]).invoke(shieldy, new Object[0]);
                        Object foundRecord = manager.getClass().getMethod("findRegionAt", new Class[]{Location.class}).invoke(manager, new Object[]{location});
                        if(foundRecord == null) {
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
         } catch (Exception var15) {
            this.plugin.getLogger();
            var15.getClass().getSimpleName().warning(var15.getMessage());
            return false;
         }
      }
   }

   private Plugin getShieldyPlugin() {
      Plugin shieldy = Bukkit.getPluginManager().getPlugin("ShieldyBlocks");
      return shieldy != null && shieldy.isEnabled()?shieldy:null;
   }

   public boolean isShieldCore(Location location) {
      return this.findShieldCore(location) != null;
   }

   public Object findShieldCore(Location location) {
      Plugin shieldy = this.getShieldyPlugin();
      if(shieldy != null && location != null && location.getWorld() != null) {
         try {
            Object exception = shieldy.getClass().getMethod("currentRegionRepository", new Class[0]).invoke(shieldy, new Object[0]);
            Method findMethod = exception.getClass().getMethod("findByLocation", new Class[]{String.class, Integer.TYPE, Integer.TYPE, Integer.TYPE});
            return findMethod.invoke(exception, new Object[]{location.getWorld().getName(), Integer.valueOf(location.getBlockX()), Integer.valueOf(location.getBlockY()), Integer.valueOf(location.getBlockZ())});
         } catch (Exception var5) {
            this.plugin.getLogger();
            var5.getClass().getSimpleName().warning(var5.getMessage());
            return null;
         }
      } else {
         return null;
      }
   }

   public boolean damageShieldCore(Location location, String tntId) {
      Object record = this.findShieldCore(location);
      if(record == null) {
         return false;
      } else {
         Plugin shieldy = this.getShieldyPlugin();
         if(shieldy == null) {
            return false;
         } else {
            String resolvedTntId = tntId != null && !tntId.isBlank()?tntId:"vanilla";

            try {
               Object exception = shieldy.getClass().getMethod("currentRegionManager", new Class[0]).invoke(shieldy, new Object[0]);
               Method twoParamMethod = null;
               Method oneParamMethod = null;
               Method[] result = exception.getClass().getMethods();
               int resultName = result.length;

               Method dropMethod;
               for(int definition = 0; definition < resultName; ++definition) {
                  dropMethod = result[definition];
                  if(dropMethod.getName().equals("applyTntImpact")) {
                     Class[] paramTypes = dropMethod.getParameterTypes();
                     if(paramTypes.length == 2 && paramTypes[1] == String.class) {
                        twoParamMethod = dropMethod;
                     } else if(paramTypes.length == 1) {
                        oneParamMethod = dropMethod;
                     }
                  }
               }

               Object var18;
               if(twoParamMethod != null) {
                  var18 = twoParamMethod.invoke(exception, new Object[]{record, resolvedTntId});
               } else {
                  if(oneParamMethod == null) {
                     this.plugin.getLogger().warning("ShieldyBlocks applyTntImpact method not found.");
                     return false;
                  }

                  this.plugin.getLogger().warning("ShieldyBlocks does not expose applyTntImpact(record, tntId); falling back to legacy method, allowed-tnts will not work correctly.");
                  var18 = oneParamMethod.invoke(exception, new Object[]{record});
               }

               if(var18 == null) {
                  return false;
               } else {
                  String var19 = var18.getClass().getSimpleName();
                  if(var19.equals("Destroyed")) {
                     Object var20 = var18.getClass().getMethod("getDefinition", new Class[0]).invoke(var18, new Object[0]);
                     location.getBlock().setType(Material.AIR, false);
                     dropMethod = null;
                     Method[] var21 = exception.getClass().getMethods();
                     int var14 = var21.length;

                     for(int var15 = 0; var15 < var14; ++var15) {
                        Method method = var21[var15];
                        if(method.getName().equals("dropShieldBlockItem") && method.getParameterCount() == 2) {
                           dropMethod = method;
                           break;
                        }
                     }

                     if(dropMethod != null) {
                        dropMethod.invoke(exception, new Object[]{location, var20});
                     }
                  }

                  return true;
               }
            } catch (Exception var17) {
               this.plugin.getLogger();
               var17.getClass().getSimpleName().warning(var17.getMessage());
               return false;
            }
         }
      }
   }
}
