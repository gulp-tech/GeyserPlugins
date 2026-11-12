package mk.hypertnt;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import mk.hypertnt.HyperTNTPlugin;
import mk.hypertnt.TntManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

public final class ExplosionService {
   private final HyperTNTPlugin plugin;
   private final Random random = new Random();

   public ExplosionService(HyperTNTPlugin plugin) {
      this.plugin = plugin;
   }

   public void explode(Location center, TntManager.TntType type, Entity source) {
      World world = center.getWorld();
      if(world != null && type != null) {
         ConfigurationSection config = type.config;
         double radius = Math.max(0.1D, Math.min(64.0D, type.radius()));
         int maxBlocks = config.getInt("explosion.blocks.max-blocks", 0);
         boolean inLiquid = config.getBoolean("explosion.blocks.in-liquid", false);
         Set affected = this.collectBlocks(center, type, radius);
         HashSet processedCores = new HashSet();
         int changed = 0;
         Iterator var13 = affected.iterator();

         while(var13.hasNext()) {
            Block block = (Block)var13.next();
            if(maxBlocks > 0 && changed >= maxBlocks) {
               break;
            }

            Location location = block.getLocation();
            if(this.plugin.getWorldGuardBridge().canExplode(center, location)) {
               Material material = block.getType();
               if(!material.isAir() && (inLiquid || !this.isLiquid(material))) {
                  if(this.plugin.getWorldGuardBridge().isShieldCore(location)) {
                     if(processedCores.add(location)) {
                        this.plugin.getWorldGuardBridge().damageShieldCore(location, type.id);
                        ++changed;
                     }
                  } else {
                     String mode = type.blockMode(material);
                     if(!mode.equalsIgnoreCase("IGNORE")) {
                        if(mode.equalsIgnoreCase("PLACE")) {
                           Material place = Material.matchMaterial(config.getString("explosion.blocks.place.material", "SAND"));
                           if(place != null && !place.isAir()) {
                              block.setType(place, false);
                              ++changed;
                           }
                        } else if(!this.isUnbreakable(material)) {
                           this.breakBlock(block, type.dropChance(material));
                           ++changed;
                        }
                     }
                  }
               }
            }
         }

         this.damageEntities(center, type, source);
         this.applyFire(center, type, affected);
         this.applyCommands(center, type);
         this.playEffects(center, type);
      }
   }

   private Set collectBlocks(Location center, TntManager.TntType type, double radius) {
      LinkedHashSet blocks = new LinkedHashSet();
      World world = center.getWorld();
      if(world == null) {
         return blocks;
      } else {
         int rays = type.config.getInt("explosion.raycast.rays", 16);
         rays = Math.max(2, Math.min(64, rays));
         double strengthMultiplier = type.config.getDouble("explosion.raycast.strength-multiplier", 1.0D);
         double resistanceMultiplier = type.config.getDouble("explosion.raycast.resistance-multiplier", 1.0D);

         for(int x = 0; x < rays; ++x) {
            for(int y = 0; y < rays; ++y) {
               for(int z = 0; z < rays; ++z) {
                  if(x == 0 || x == rays - 1 || y == 0 || y == rays - 1 || z == 0 || z == rays - 1) {
                     Vector direction = new Vector((double)x / (double)(rays - 1) * 2.0D - 1.0D, (double)y / (double)(rays - 1) * 2.0D - 1.0D, (double)z / (double)(rays - 1) * 2.0D - 1.0D);
                     if(direction.lengthSquared() != 0.0D) {
                        direction.normalize().multiply(0.3D);
                        Location position = center.clone();
                        double strength = radius * (0.7D + this.random.nextDouble() * 0.6D) * strengthMultiplier;

                        for(double distance = 0.0D; strength > 0.0D && distance <= radius; distance += 0.3D) {
                           Block block = position.getBlock();
                           Material material = block.getType();
                           if(!material.isAir()) {
                              blocks.add(block);
                              double resistance = (double)material.getBlastResistance();
                              strength -= (resistance * resistanceMultiplier + 0.3D) * 0.3D;
                           }

                           position.add(direction);
                           strength -= 0.225D;
                        }
                     }
                  }
               }
            }
         }

         return blocks;
      }
   }

   private void breakBlock(Block block, double dropChance) {
      BlockState state = block.getState();
      Collection drops = block.getDrops();
      if(state instanceof InventoryHolder) {
         InventoryHolder holder = (InventoryHolder)state;
         ItemStack[] drop = holder.getInventory().getContents();
         int var8 = drop.length;

         for(int var9 = 0; var9 < var8; ++var9) {
            ItemStack item = drop[var9];
            if(item != null && !item.getType().isAir()) {
               block.getWorld().dropItemNaturally(block.getLocation().add(0.5D, 0.5D, 0.5D), item.clone());
            }
         }

         holder.getInventory().clear();
      }

      Iterator var11 = drops.iterator();

      while(true) {
         ItemStack var12;
         do {
            if(!var11.hasNext()) {
               block.setType(Material.AIR, false);
               return;
            }

            var12 = (ItemStack)var11.next();
         } while(dropChance < 1.0D && this.random.nextDouble() > dropChance);

         block.getWorld().dropItemNaturally(block.getLocation().add(0.5D, 0.5D, 0.5D), var12);
      }
   }

   private void damageEntities(Location center, TntManager.TntType type, Entity source) {
      ConfigurationSection config = type.config;
      if(config.getBoolean("explosion.damage.enabled", true)) {
         double radius = type.radius();
         double maximum = config.getDouble("explosion.damage.max-damage", 20.0D);
         double minimum = config.getDouble("explosion.damage.min-damage", 0.0D);
         double knockback = config.getDouble("explosion.knockback.living-multiplier", 1.0D);
         Iterator var13 = center.getWorld().getNearbyEntities(center, radius, radius, radius).iterator();

         while(var13.hasNext()) {
            Entity entity = (Entity)var13.next();
            if(entity != source && entity instanceof LivingEntity) {
               LivingEntity living = (LivingEntity)entity;
               double distance = entity.getLocation().distance(center);
               if(distance <= radius) {
                  double factor = 1.0D - distance / radius;
                  double damage = minimum + (maximum - minimum) * factor;
                  if(damage > 0.0D) {
                     living.damage(damage, source);
                  }

                  if(config.getBoolean("explosion.knockback.enabled", true)) {
                     Vector direction = entity.getLocation().toVector().subtract(center.toVector());
                     if(direction.lengthSquared() > 0.0D) {
                        direction.normalize().multiply(factor * knockback);
                        entity.setVelocity(entity.getVelocity().add(direction));
                     }
                  }
               }
            }
         }

      }
   }

   private void applyFire(Location center, TntManager.TntType type, Set affected) {
      if(type.config.getBoolean("explosion.post.set-fire", false)) {
         double chance = type.config.getDouble("explosion.post.fire-chance", 0.0D);
         Iterator var6 = affected.iterator();

         while(var6.hasNext()) {
            Block block = (Block)var6.next();
            if(block.getType().isAir() && this.random.nextDouble() <= chance && this.plugin.getWorldGuardBridge().canExplode(center, block.getLocation())) {
               Block below = block.getRelative(0, -1, 0);
               if(below.getType().isSolid()) {
                  block.setType(Material.FIRE, false);
               }
            }
         }

      }
   }

   private void applyCommands(Location center, TntManager.TntType type) {
      Iterator var3 = type.config.getStringList("explosion.post.commands").iterator();

      while(var3.hasNext()) {
         String command = (String)var3.next();
         String formatted = command.replace("{world}", center.getWorld().getName()).replace("{x}", String.valueOf(center.getBlockX())).replace("{y}", String.valueOf(center.getBlockY())).replace("{z}", String.valueOf(center.getBlockZ()));
         Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formatted);
      }

   }

   private void playEffects(Location center, TntManager.TntType type) {
      World world = center.getWorld();
      if(world != null) {
         String soundName = type.config.getString("visual.sounds.explosion.sound", "ENTITY_GENERIC_EXPLODE");

         try {
            Sound particleName = Sound.valueOf(soundName.toUpperCase(Locale.ROOT));
            world.playSound(center, particleName, (float)type.config.getDouble("visual.sounds.explosion.volume", 4.0D), (float)type.config.getDouble("visual.sounds.explosion.pitch", 1.0D));
         } catch (Exception var8) {
            world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 4.0F, 1.0F);
         }

         world.spawnParticle(Particle.EXPLOSION, center, 1);
         if(type.config.getBoolean("visual.particles.enabled", false)) {
            String particleName1 = type.config.getString("visual.particles.type", "FLAME");
            if(particleName1.equalsIgnoreCase("EXPLOSION_NORMAL")) {
               particleName1 = "EXPLOSION";
            }

            if(particleName1.equalsIgnoreCase("WATER_SPLASH")) {
               particleName1 = "SPLASH";
            }

            try {
               Particle particle = Particle.valueOf(particleName1.toUpperCase(Locale.ROOT));
               world.spawnParticle(particle, center, type.config.getInt("visual.particles.count", 20), type.config.getDouble("visual.particles.offset.x", 0.3D), type.config.getDouble("visual.particles.offset.y", 0.3D), type.config.getDouble("visual.particles.offset.z", 0.3D), type.config.getDouble("visual.particles.extra", 0.0D));
            } catch (Exception var7) {
               ;
            }
         }

      }
   }

   private boolean isLiquid(Material material) {
      return material == Material.WATER || material == Material.LAVA;
   }

   private boolean isUnbreakable(Material material) {
      boolean var10000;
      switch(null.$SwitchMap$org$bukkit$Material[material.ordinal()]) {
      case 1:
      case 2:
      case 3:
      case 4:
      case 5:
      case 6:
      case 7:
      case 8:
      case 9:
      case 10:
         var10000 = true;
         break;
      default:
         var10000 = false;
      }

      return var10000;
   }
}
