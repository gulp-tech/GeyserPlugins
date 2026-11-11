// Основан на декомпилированном TntListener 2.0.0 (Vineflower) и доработан:
// карта placed в памяти заменена на TntRegistry с сохранением на диск,
// добавлены обработчики взрывов, поджога, поршней, лавы и валидации чанков.
package mk.hypertnt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

public final class TntListener implements Listener {
   private final HyperTNTPlugin plugin;
   private final Map<UUID, List<ArmorStand>> holograms = new HashMap();
   private final Map<UUID, BukkitTask> tasks = new HashMap();
   private final Map<String, Long> cooldowns = new HashMap();

   public TntListener(HyperTNTPlugin plugin) {
      this.plugin = plugin;
   }

   private TntRegistry registry() {
      return this.plugin.getRegistry();
   }

   private static TntRegistry.Pos pos(Block block) {
      return new TntRegistry.Pos(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
   }

   private static TntRegistry.Pos pos(Location location) {
      return new TntRegistry.Pos(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onPlace(BlockPlaceEvent event) {
      ItemStack item = event.getItemInHand();
      String id = this.plugin.getTntManager().getItemType(item);
      if (id != null) {
         TntManager.TntType type = this.plugin.getTntManager().getType(id);
         if (type != null) {
            Location location = event.getBlockPlaced().getLocation();
            this.registry().put(pos(location), id);
            if (type.autoIgnite()) {
               Bukkit.getScheduler().runTask(this.plugin, () -> {
                  if (location.getBlock().getType() == Material.TNT) {
                     this.prime(location, type, event.getPlayer());
                  }
               });
            }
            return;
         }
      }

      // Любой иной блок на этих координатах делает запись устаревшей:
      // обычный TNT, поставленный на старые координаты, не должен стать кастомным.
      this.registry().remove(pos(event.getBlockPlaced()));
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onBreak(BlockBreakEvent event) {
      Location location = event.getBlock().getLocation();
      String id = this.registry().remove(pos(location));
      if (id != null) {
         TntManager.TntType type = this.plugin.getTntManager().getType(id);
         if (type != null) {
            event.setDropItems(false);
            event.getBlock().getWorld().dropItemNaturally(location, this.plugin.getTntManager().createItem(id, 1));
         }
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onInteract(PlayerInteractEvent event) {
      if (event.getHand() == EquipmentSlot.HAND) {
         Player player = event.getPlayer();
         ItemStack item = event.getItem();
         String id = this.plugin.getTntManager().getItemType(item);
         if (id != null) {
            TntManager.TntType type = this.plugin.getTntManager().getType(id);
            if (type != null && type.throwEnabled() && event.getAction().isRightClick()) {
               event.setCancelled(true);
               if (!this.checkCooldown(player, type)) {
                  return;
               }

               if (player.getGameMode() != GameMode.CREATIVE) {
                  item.setAmount(item.getAmount() - 1);
               }

               Location location = player.getEyeLocation();
               Vector direction = location.getDirection();
               double velocityXZ = type.config.getDouble("ignite.throw.velocity-xz", 0.9);
               double velocityY = type.config.getDouble("ignite.throw.velocity-y", 0.2);
               Vector velocity = new Vector(direction.getX() * velocityXZ, direction.getY() * velocityY, direction.getZ() * velocityXZ);
               TNTPrimed primed = this.spawnTnt(location.add(direction.multiply(0.6)), type);
               primed.setVelocity(velocity);
               this.startVisuals(primed, type);
               return;
            }
         }

         if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Block block = event.getClickedBlock();
            if (block != null && block.getType() == Material.TNT) {
               if (item != null) {
                  if (item.getType() == Material.FLINT_AND_STEEL || item.getType() == Material.FIRE_CHARGE) {
                     String typeId = this.registry().get(pos(block));
                     if (typeId != null) {
                        TntManager.TntType type = this.plugin.getTntManager().getType(typeId);
                        if (type != null) {
                           event.setCancelled(true);
                           if (this.checkCooldown(player, type)) {
                              this.prime(block.getLocation(), type, player);
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onRedstone(BlockRedstoneEvent event) {
      if (event.getNewCurrent() > 0) {
         Block block = event.getBlock();
         if (block.getType() == Material.TNT) {
            String id = this.registry().get(pos(block));
            if (id != null) {
               TntManager.TntType type = this.plugin.getTntManager().getType(id);
               if (type != null) {
                  if (!type.config.getBoolean("ignite.allow.redstone", true)) {
                     event.setNewCurrent(0);
                  } else {
                     this.prime(block.getLocation(), type, null);
                     event.setNewCurrent(0);
                  }
               }
            }
         }
      }
   }

   /**
    * Поджог огнём/диспенсером: подменяем ванильное воспламенение своим,
    * чтобы тип из реестра не потерялся.
    */
   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onIgnite(BlockIgniteEvent event) {
      Block block = event.getBlock();
      if (block.getType() == Material.TNT) {
         String id = this.registry().get(pos(block));
         if (id != null) {
            TntManager.TntType type = this.plugin.getTntManager().getType(id);
            if (type != null) {
               event.setCancelled(true);
               this.prime(block.getLocation(), type, null);
            }
         }
      }
   }

   /**
    * Лава касается TNT — воспламеняем своим типом (иначе сгорит ванильно).
    */
   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onLiquidFlow(BlockFromToEvent event) {
      if (event.getBlock().getType() == Material.LAVA) {
         Block target = event.getToBlock();
         if (target.getType() == Material.TNT) {
            String id = this.registry().get(pos(target));
            if (id != null) {
               TntManager.TntType type = this.plugin.getTntManager().getType(id);
               if (type != null) {
                  event.setCancelled(true);
                  this.prime(target.getLocation(), type, null);
               }
            }
         }
      }
   }

   /** Прямое уничтожение огнём — запись устаревает. */
   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onBurn(BlockBurnEvent event) {
      this.registry().remove(pos(event.getBlock()));
   }

   /**
    * Взрыв (сущность или блок): записанный TNT, попавший в список разрушаемых,
    * подрывается своим типом (цепные взрывы не теряют тип).
    */
   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onExplode(EntityExplodeEvent event) {
      this.primeFromBlast(event.blockList());
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onBlockExplode(BlockExplodeEvent event) {
      this.primeFromBlast(event.blockList());
   }

   private void primeFromBlast(List<Block> blockList) {
      Iterator<Block> iterator = blockList.iterator();
      while (iterator.hasNext()) {
         Block block = iterator.next();
         String id = this.registry().get(pos(block));
         if (id != null && block.getType() == Material.TNT) {
            TntManager.TntType type = this.plugin.getTntManager().getType(id);
            if (type != null) {
               iterator.remove();
               this.prime(block.getLocation(), type, null);
            }
         }
      }
   }

   /**
    * Поршни двигают блоки — записи едут вместе с ними.
    * Для выдвижения и втягивания блоки перемещаются вдоль getDirection().
    */
   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onPistonExtend(BlockPistonExtendEvent event) {
      this.moveWithPiston(event.getBlocks(), event.getDirection());
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onPistonRetract(BlockPistonRetractEvent event) {
      this.moveWithPiston(event.getBlocks(), event.getDirection());
   }

   private void moveWithPiston(List<Block> moved, org.bukkit.block.BlockFace direction) {
      for (Block block : moved) {
         TntRegistry.Pos from = pos(block);
         TntRegistry.Pos to = pos(block.getRelative(direction));
         String type = this.registry().get(from);
         if (type != null) {
            this.registry().remove(from);
            this.registry().put(to, type);
         } else {
            // На месте прибытия блок заменяется — старая запись там устарела.
            this.registry().remove(to);
         }
      }
   }

   /**
    * Загрузка чанка: устаревшие записи (блок больше не TNT) удаляются.
    * Потерянные типы не угадываются.
    */
   @EventHandler(priority = EventPriority.MONITOR)
   public void onChunkLoad(ChunkLoadEvent event) {
      Chunk chunk = event.getChunk();
      UUID world = chunk.getWorld().getUID();
      int dropped = this.registry().validateIn(world, chunk.getX(), chunk.getZ(), (tntPos) -> {
         World target = Bukkit.getWorld(tntPos.world());
         if (target == null || tntPos.y() < -2048 || tntPos.y() > 2048) {
            return false;
         }
         return target.getBlockAt(tntPos.x(), tntPos.y(), tntPos.z()).getType() == Material.TNT;
      });
      if (dropped > 0) {
         this.plugin.getLogger().info("Удалено устаревших записей TNT в чанке " + chunk.getX() + "," + chunk.getZ() + ": " + dropped);
      }
   }

   private boolean checkCooldown(Player player, TntManager.TntType type) {
      int seconds = type.cooldown();
      if (seconds <= 0) {
         return true;
      } else {
         String key = player.getUniqueId() + ":" + type.id;
         long now = System.currentTimeMillis();
         long expires = (Long)this.cooldowns.getOrDefault(key, 0L);
         if (expires > now) {
            long remaining = (expires - now + 999L) / 1000L;
            player.sendMessage(ChatColor.RED + "Подождите " + remaining + " сек. перед использованием.");
            return false;
         } else {
            this.cooldowns.put(key, now + seconds * 1000L);
            return true;
         }
      }
   }

   private void prime(Location location, TntManager.TntType type, Player player) {
      if (location.getBlock().getType() == Material.TNT) {
         location.getBlock().setType(Material.AIR, false);
         this.registry().remove(pos(location));
         TNTPrimed primed = this.spawnTnt(location.clone().add(0.5, 0.0, 0.5), type);
         this.startVisuals(primed, type);
      }
   }

   private TNTPrimed spawnTnt(Location location, TntManager.TntType type) {
      TNTPrimed primed = (TNTPrimed)location.getWorld().spawn(location, TNTPrimed.class);
      primed.setFuseTicks(Math.max(1, (int)Math.round(type.fuseSeconds() * 20.0)));
      primed.getPersistentDataContainer().set(this.plugin.getTntManager().getEntityKey(), PersistentDataType.STRING, type.id);
      return primed;
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onExplosionPrime(ExplosionPrimeEvent event) {
      if (event.getEntity() instanceof TNTPrimed primed) {
         String id = this.plugin.getTntManager().getEntityType(primed);
         if (id != null) {
            TntManager.TntType type = this.plugin.getTntManager().getType(id);
            if (type != null) {
               event.setCancelled(true);
               this.removeVisuals(primed.getUniqueId());
               this.plugin.getExplosionService().explode(primed.getLocation(), type, primed);
               primed.remove();
            }
         }
      }
   }

   private void startVisuals(TNTPrimed primed, TntManager.TntType type) {
      if (type.config.getBoolean("visual.hologram.enabled", false)) {
         List<String> lines = type.config.getStringList("visual.hologram.lines");
         List<ArmorStand> stands = new ArrayList();
         double height = type.config.getDouble("visual.hologram.height", 2.0);
         double spacing = type.config.getDouble("visual.hologram.line-spacing", 0.25);

         for (int i = 0; i < lines.size(); i++) {
            Location location = primed.getLocation().clone().add(0.0, height + i * spacing, 0.0);
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
         BukkitTask task = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            if (primed.isDead()) {
               this.removeVisuals(primed.getUniqueId());
            } else {
               double seconds = primed.getFuseTicks() / 20.0;

               for (int i = 0; i < stands.size(); i++) {
                  ArmorStand standx = (ArmorStand)stands.get(i);
                  if (!standx.isDead()) {
                     String text = ((String)lines.get(i)).replace("{time}", String.format(Locale.US, "%.1f", new Object[]{seconds}));
                     standx.setCustomName(ChatColor.translateAlternateColorCodes('&', text));
                     standx.teleport(primed.getLocation().clone().add(0.0, height + i * spacing, 0.0));
                  }
               }
            }
         }, 0L, Math.max(1, type.config.getInt("visual.hologram.update-period-ticks", 4)));
         this.tasks.put(primed.getUniqueId(), task);
      }
   }

   private void removeVisuals(UUID id) {
      BukkitTask task = (BukkitTask)this.tasks.remove(id);
      if (task != null) {
         task.cancel();
      }

      List<ArmorStand> stands = (List<ArmorStand>)this.holograms.remove(id);
      if (stands != null) {
         for (ArmorStand stand : stands) {
            if (!stand.isDead()) {
               stand.remove();
            }
         }
      }
   }
}
