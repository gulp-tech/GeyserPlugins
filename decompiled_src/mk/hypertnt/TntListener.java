package mk.hypertnt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import mk.hypertnt.HyperTNTPlugin;
import mk.hypertnt.TntManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

public final class TntListener implements Listener {
   private final HyperTNTPlugin plugin;
   private final Map placed = new HashMap();
   private final Map holograms = new HashMap();
   private final Map tasks = new HashMap();
   private final Map cooldowns = new HashMap();

   public TntListener(HyperTNTPlugin plugin) {
      this.plugin = plugin;
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onPlace(BlockPlaceEvent event) {
      ItemStack item = event.getItemInHand();
      String id = this.plugin.getTntManager().getItemType(item);
      if(id != null) {
         TntManager.TntType type = this.plugin.getTntManager().getType(id);
         if(type != null) {
            Location location = event.getBlockPlaced().getLocation();
            this.placed.put(location, id);
            if(type.autoIgnite()) {
               Bukkit.getScheduler();
               HyperTNTPlugin var10001 = this.plugin;
               location.runTask(type, event);
            }

         }
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onBreak(BlockBreakEvent event) {
      Location location = event.getBlock().getLocation();
      String id = (String)this.placed.remove(location);
      if(id != null) {
         TntManager.TntType type = this.plugin.getTntManager().getType(id);
         if(type != null) {
            event.setDropItems(false);
            event.getBlock().getWorld().dropItemNaturally(location, this.plugin.getTntManager().createItem(id, 1));
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onInteract(PlayerInteractEvent event) {
      if(event.getHand() == EquipmentSlot.HAND) {
         Player player = event.getPlayer();
         ItemStack item = event.getItem();
         String id = this.plugin.getTntManager().getItemType(item);
         if(id != null) {
            TntManager.TntType block = this.plugin.getTntManager().getType(id);
            if(block != null && block.throwEnabled() && event.getAction().isRightClick()) {
               event.setCancelled(true);
               if(!this.checkCooldown(player, block)) {
                  return;
               }

               if(player.getGameMode() != GameMode.CREATIVE) {
                  item.setAmount(item.getAmount() - 1);
               }

               Location typeId1 = player.getEyeLocation();
               Vector type1 = typeId1.getDirection();
               double velocityXZ = block.config.getDouble("ignite.throw.velocity-xz", 0.9D);
               double velocityY = block.config.getDouble("ignite.throw.velocity-y", 0.2D);
               Vector velocity = new Vector(type1.getX() * velocityXZ, type1.getY() * velocityY, type1.getZ() * velocityXZ);
               TNTPrimed primed = this.spawnTnt(typeId1.add(type1.multiply(0.6D)), block);
               primed.setVelocity(velocity);
               this.startVisuals(primed, block);
               return;
            }
         }

         if(event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Block block1 = event.getClickedBlock();
            if(block1 != null && block1.getType() == Material.TNT) {
               if(item != null) {
                  if(item.getType() == Material.FLINT_AND_STEEL || item.getType() == Material.FIRE_CHARGE) {
                     String typeId = (String)this.placed.get(block1.getLocation());
                     if(typeId != null) {
                        TntManager.TntType type = this.plugin.getTntManager().getType(typeId);
                        if(type != null) {
                           event.setCancelled(true);
                           if(this.checkCooldown(player, type)) {
                              this.prime(block1.getLocation(), type, player);
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onRedstone(BlockRedstoneEvent event) {
      if(event.getNewCurrent() > 0) {
         Block block = event.getBlock();
         if(block.getType() == Material.TNT) {
            String id = (String)this.placed.get(block.getLocation());
            if(id != null) {
               TntManager.TntType type = this.plugin.getTntManager().getType(id);
               if(type != null) {
                  if(!type.config.getBoolean("ignite.allow.redstone", true)) {
                     event.setNewCurrent(0);
                  } else {
                     this.prime(block.getLocation(), type, (Player)null);
                     event.setNewCurrent(0);
                  }
               }
            }
         }
      }
   }

   private boolean checkCooldown(Player player, TntManager.TntType type) {
      int seconds = type.cooldown();
      if(seconds <= 0) {
         return true;
      } else {
         String.valueOf(player.getUniqueId());
         String key = type.id;
         long now = System.currentTimeMillis();
         long expires = ((Long)this.cooldowns.getOrDefault(key, Long.valueOf(0L))).longValue();
         if(expires > now) {
            Object remaining = (expires - now + 999L) / 1000L;
            String.valueOf(ChatColor.RED).sendMessage((String)remaining);
            return false;
         } else {
            this.cooldowns.put(key, Long.valueOf(now + (long)seconds * 1000L));
            return true;
         }
      }
   }

   private void prime(Location location, TntManager.TntType type, Player player) {
      if(location.getBlock().getType() == Material.TNT) {
         location.getBlock().setType(Material.AIR, false);
         this.placed.remove(location);
         TNTPrimed primed = this.spawnTnt(location.clone().add(0.5D, 0.0D, 0.5D), type);
         this.startVisuals(primed, type);
      }
   }

   private TNTPrimed spawnTnt(Location location, TntManager.TntType type) {
      TNTPrimed primed = (TNTPrimed)location.getWorld().spawn(location, TNTPrimed.class);
      primed.setFuseTicks(Math.max(1, (int)Math.round(type.fuseSeconds() * 20.0D)));
      primed.getPersistentDataContainer().set(this.plugin.getTntManager().getEntityKey(), PersistentDataType.STRING, type.id);
      return primed;
   }

   @EventHandler(
      priority = EventPriority.HIGHEST,
      ignoreCancelled = true
   )
   public void onExplosionPrime(ExplosionPrimeEvent event) {
      Entity entity = event.getEntity();
      if(entity instanceof TNTPrimed) {
         TNTPrimed primed = (TNTPrimed)entity;
         String id = this.plugin.getTntManager().getEntityType(primed);
         if(id != null) {
            TntManager.TntType type = this.plugin.getTntManager().getType(id);
            if(type != null) {
               event.setCancelled(true);
               this.removeVisuals(primed.getUniqueId());
               this.plugin.getExplosionService().explode(primed.getLocation(), type, primed);
               primed.remove();
            }
         }
      }
   }

   private void startVisuals(TNTPrimed primed, TntManager.TntType type) {
      if(type.config.getBoolean("visual.hologram.enabled", false)) {
         List lines = type.config.getStringList("visual.hologram.lines");
         ArrayList stands = new ArrayList();
         Object height = type.config.getDouble("visual.hologram.height", 2.0D);
         Object spacing = type.config.getDouble("visual.hologram.line-spacing", 0.25D);

         for(int task = 0; task < lines.size(); ++task) {
            Location location = primed.getLocation().clone().add(0.0D, height + (double)task * spacing, 0.0D);
            ArmorStand stand = (ArmorStand)location.getWorld().spawn(location, ArmorStand.class);
            stand.setInvisible(true);
            stand.setGravity(false);
            stand.setMarker(true);
            stand.setSmall(true);
            stand.setInvulnerable(true);
            stand.setCustomNameVisible(true);
            stands.add(stand);
         }

         this.holograms.put(primed.getUniqueId(), stands);
         Bukkit.getScheduler();
         HyperTNTPlugin var10001 = this.plugin;
         BukkitTask var12 = lines.runTaskTimer((Plugin)height, (Runnable)spacing, 0L, (long)Math.max(1, type.config.getInt("visual.hologram.update-period-ticks", 4)));
         this.tasks.put(primed.getUniqueId(), var12);
      }
   }

   private void removeVisuals(UUID id) {
      BukkitTask task = (BukkitTask)this.tasks.remove(id);
      if(task != null) {
         task.cancel();
      }

      List stands = (List)this.holograms.remove(id);
      if(stands != null) {
         Iterator var4 = stands.iterator();

         while(var4.hasNext()) {
            ArmorStand stand = (ArmorStand)var4.next();
            if(!stand.isDead()) {
               stand.remove();
            }
         }
      }

   }

   // $FF: synthetic method
   private void lambda$startVisuals$1(TNTPrimed primed, List stands, List lines, double height, double spacing) {
      if(primed.isDead()) {
         this.removeVisuals(primed.getUniqueId());
      } else {
         double seconds = (double)primed.getFuseTicks() / 20.0D;

         for(int i = 0; i < stands.size(); ++i) {
            ArmorStand stand = (ArmorStand)stands.get(i);
            if(!stand.isDead()) {
               String text = ((String)lines.get(i)).replace("{time}", String.format(Locale.US, "%.1f", new Object[]{Double.valueOf(seconds)}));
               stand.setCustomName(ChatColor.translateAlternateColorCodes('&', text));
               stand.teleport(primed.getLocation().clone().add(0.0D, height + (double)i * spacing, 0.0D));
            }
         }

      }
   }

   // $FF: synthetic method
   private void lambda$onPlace$0(Location location, TntManager.TntType type, BlockPlaceEvent event) {
      if(location.getBlock().getType() == Material.TNT) {
         this.prime(location, type, event.getPlayer());
      }

   }
}
