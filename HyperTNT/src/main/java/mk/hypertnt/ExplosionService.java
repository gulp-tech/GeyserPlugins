// Decomcompiled with Vineflower (JS/WASM port @run-slicer/vf) from HyperTNT.jar
// Восстановлено из байткода: без комментариев, имена локальных переменных условны.
package mk.hypertnt;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
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
      if (world != null && type != null) {
         ConfigurationSection config = type.config;
         double radius = Math.max(0.1, Math.min(64.0, type.radius()));
         int maxBlocks = config.getInt("explosion.blocks.max-blocks", 0);
         boolean inLiquid = config.getBoolean("explosion.blocks.in-liquid", false);
         Set<Block> affected = this.collectBlocks(center, type, radius);
         Set<Location> processedCores = new HashSet();
         int changed = 0;

         for (Block block : affected) {
            if (maxBlocks > 0 && changed >= maxBlocks) {
               break;
            }

            Location location = block.getLocation();
            if (this.plugin.getWorldGuardBridge().canExplode(center, location)) {
               Material material = block.getType();
               if (!material.isAir() && (inLiquid || !this.isLiquid(material))) {
                  if (this.plugin.getWorldGuardBridge().isShieldCore(location)) {
                     if (processedCores.add(location)) {
                        this.plugin.getWorldGuardBridge().damageShieldCore(location, type.id);
                        changed++;
                     }
                  } else {
                     String mode = type.blockMode(material);
                     if (!mode.equalsIgnoreCase("IGNORE")) {
                        if (mode.equalsIgnoreCase("PLACE")) {
                           Material place = Material.matchMaterial(config.getString("explosion.blocks.place.material", "SAND"));
                           if (place != null && !place.isAir()) {
                              this.plugin.getRegistry().remove(pos(block));
                              block.setType(place, false);
                              changed++;
                           }
                        } else if (!this.isUnbreakable(material)) {
                           this.breakBlock(block, type.dropChance(material));
                           changed++;
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

   private Set<Block> collectBlocks(Location center, TntManager.TntType type, double radius) {
      Set<Block> blocks = new LinkedHashSet();
      World world = center.getWorld();
      if (world == null) {
         return blocks;
      }

      int rays = type.config.getInt("explosion.raycast.rays", 16);
      rays = Math.max(2, Math.min(64, rays));
      double strengthMultiplier = type.config.getDouble("explosion.raycast.strength-multiplier", 1.0);
      double resistanceMultiplier = type.config.getDouble("explosion.raycast.resistance-multiplier", 1.0);

      for (int x = 0; x < rays; x++) {
         for (int y = 0; y < rays; y++) {
            for (int z = 0; z < rays; z++) {
               if (x == 0 || x == rays - 1 || y == 0 || y == rays - 1 || z == 0 || z == rays - 1) {
                  Vector direction = new Vector((double)x / (rays - 1) * 2.0 - 1.0, (double)y / (rays - 1) * 2.0 - 1.0, (double)z / (rays - 1) * 2.0 - 1.0);
                  if (direction.lengthSquared() != 0.0) {
                     direction.normalize().multiply(0.3);
                     Location position = center.clone();
                     double strength = radius * (0.7 + this.random.nextDouble() * 0.6) * strengthMultiplier;

                     for (double distance = 0.0; strength > 0.0 && distance <= radius; distance += 0.3) {
                        Block block = position.getBlock();
                        Material material = block.getType();
                        if (!material.isAir()) {
                           blocks.add(block);
                           double resistance = material.getBlastResistance();
                           strength -= (resistance * resistanceMultiplier + 0.3) * 0.3;
                        }

                        position.add(direction);
                        strength -= 0.225;
                     }
                  }
               }
            }
         }
      }

      return blocks;
   }

   private static TntRegistry.Pos pos(Block block) {
      return new TntRegistry.Pos(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
   }

   private void breakBlock(Block block, double dropChance) {
      BlockState state = block.getState();
      Collection<ItemStack> drops = block.getDrops();
      String customId = this.plugin.getRegistry().remove(pos(block));
      if (customId != null) {
         ItemStack custom = this.plugin.getTntManager().createItem(customId, 1);
         if (custom != null) {
            drops = java.util.Collections.singletonList(custom);
         }
      }
      // Шалкер-бокс: getDrops() уже отдаёт предмет с содержимым внутри (BlockStateMeta),
      // поэтому россыпь содержимого отдельно удваивала бы предметы. Россыпаем только
      // у контейнеров, чей дроп-предмет не несёт содержимое (сундуки, воронки и т.п.).
      if (state instanceof InventoryHolder holder && !(state instanceof org.bukkit.block.ShulkerBox)) {
         for (ItemStack item : holder.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
               block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), item.clone());
            }
         }

         holder.getInventory().clear();
      }

      for (ItemStack drop : drops) {
         if (!(dropChance < 1.0) || !(this.random.nextDouble() > dropChance)) {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);
         }
      }

      block.setType(Material.AIR, false);
   }

   private void damageEntities(Location center, TntManager.TntType type, Entity source) {
      ConfigurationSection config = type.config;
      if (config.getBoolean("explosion.damage.enabled", true)) {
         double radius = type.radius();
         double maximum = config.getDouble("explosion.damage.max-damage", 20.0);
         double minimum = config.getDouble("explosion.damage.min-damage", 0.0);
         double knockback = config.getDouble("explosion.knockback.living-multiplier", 1.0);

         for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity != source && entity instanceof LivingEntity living) {
               double distance = entity.getLocation().distance(center);
               if (!(distance > radius)) {
                  double factor = 1.0 - distance / radius;
                  double damage = minimum + (maximum - minimum) * factor;
                  if (damage > 0.0) {
                     living.damage(damage, source);
                  }

                  if (config.getBoolean("explosion.knockback.enabled", true)) {
                     Vector direction = entity.getLocation().toVector().subtract(center.toVector());
                     if (direction.lengthSquared() > 0.0) {
                        direction.normalize().multiply(factor * knockback);
                        entity.setVelocity(entity.getVelocity().add(direction));
                     }
                  }
               }
            }
         }
      }
   }

   private void applyFire(Location center, TntManager.TntType type, Set<Block> affected) {
      if (type.config.getBoolean("explosion.post.set-fire", false)) {
         double chance = type.config.getDouble("explosion.post.fire-chance", 0.0);

         for (Block block : affected) {
            if (block.getType().isAir() && !(this.random.nextDouble() > chance) && this.plugin.getWorldGuardBridge().canExplode(center, block.getLocation())) {
               Block below = block.getRelative(0, -1, 0);
               if (below.getType().isSolid()) {
                  block.setType(Material.FIRE, false);
               }
            }
         }
      }
   }

   private void applyCommands(Location center, TntManager.TntType type) {
      for (String command : type.config.getStringList("explosion.post.commands")) {
         String formatted = command.replace("{world}", center.getWorld().getName())
            .replace("{x}", String.valueOf(center.getBlockX()))
            .replace("{y}", String.valueOf(center.getBlockY()))
            .replace("{z}", String.valueOf(center.getBlockZ()));
         Bukkit.dispatchCommand(Bukkit.getConsoleSender(), formatted);
      }
   }

   private void playEffects(Location center, TntManager.TntType type) {
      World world = center.getWorld();
      if (world != null) {
         String soundName = type.config.getString("visual.sounds.explosion.sound", "ENTITY_GENERIC_EXPLODE");

         try {
            Sound sound = Sound.valueOf(soundName.toUpperCase(Locale.ROOT));
            world.playSound(
               center,
               sound,
               (float)type.config.getDouble("visual.sounds.explosion.volume", 4.0),
               (float)type.config.getDouble("visual.sounds.explosion.pitch", 1.0)
            );
         } catch (Exception ignored) {
            world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 4.0F, 1.0F);
         }

         world.spawnParticle(Particle.EXPLOSION, center, 1);
         if (type.config.getBoolean("visual.particles.enabled", false)) {
            String particleName = type.config.getString("visual.particles.type", "FLAME");
            if (particleName.equalsIgnoreCase("EXPLOSION_NORMAL")) {
               particleName = "EXPLOSION";
            }

            if (particleName.equalsIgnoreCase("WATER_SPLASH")) {
               particleName = "SPLASH";
            }

            try {
               Particle particle = Particle.valueOf(particleName.toUpperCase(Locale.ROOT));
               world.spawnParticle(
                  particle,
                  center,
                  type.config.getInt("visual.particles.count", 20),
                  type.config.getDouble("visual.particles.offset.x", 0.3),
                  type.config.getDouble("visual.particles.offset.y", 0.3),
                  type.config.getDouble("visual.particles.offset.z", 0.3),
                  type.config.getDouble("visual.particles.extra", 0.0)
               );
            } catch (Exception var7) {
            }
         }
      }
   }

   private boolean isLiquid(Material material) {
      return material == Material.WATER || material == Material.LAVA;
   }

   private boolean isUnbreakable(Material material) {
      return switch (material) {
         case BEDROCK, BARRIER, COMMAND_BLOCK, CHAIN_COMMAND_BLOCK, REPEATING_COMMAND_BLOCK, STRUCTURE_BLOCK, STRUCTURE_VOID, END_PORTAL, END_PORTAL_FRAME, NETHER_PORTAL -> true;
         default -> false;
      };
   }
}
