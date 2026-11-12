package mk.hypertnt;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import mk.hypertnt.ExplosionService;
import mk.hypertnt.TntListener;
import mk.hypertnt.TntManager;
import mk.hypertnt.WorldGuardBridge;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class HyperTNTPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
   private TntManager tntManager;
   private ExplosionService explosionService;
   private WorldGuardBridge worldGuardBridge;
   private File messagesFile;
   private FileConfiguration messages;

   public void onEnable() {
      this.saveDefaultConfig();
      this.loadMessages();
      this.saveTntResources();
      this.worldGuardBridge = new WorldGuardBridge(this);
      this.explosionService = new ExplosionService(this);
      this.tntManager = new TntManager(this);
      this.tntManager.loadTypes();
      Bukkit.getPluginManager().registerEvents(new TntListener(this), this);
      PluginCommand command = this.getCommand("hypertnt");
      if(command == null) {
         this.getLogger().severe("Command \'hypertnt\' is missing in plugin.yml!");
         Bukkit.getPluginManager().disablePlugin(this);
      } else {
         command.setExecutor(this);
         command.setTabCompleter(this);
         this.getLogger().info("==================================");
         this.getLogger().info("HyperTNT 2.0.0 enabled.");
         this.getLogger().info(this.tntManager.getTypeIds().size());
         if(Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            this.getLogger().info("WorldGuard detected.");
         }

         if(Bukkit.getPluginManager().isPluginEnabled("ShieldyBlocks")) {
            this.getLogger().info("ShieldyBlocks detected. Integration enabled.");
         }

         this.getLogger().info("==================================");
      }
   }

   public void onDisable() {
      if(this.tntManager != null) {
         this.tntManager.shutdown();
      }

      this.getLogger().info("HyperTNT disabled.");
   }

   private void loadMessages() {
      this.messagesFile = new File(this.getDataFolder(), "messages.yml");
      if(!this.messagesFile.exists()) {
         try {
            this.saveResource("messages.yml", false);
         } catch (IllegalArgumentException var2) {
            this.getLogger().warning("messages.yml was not found in JAR.");
         }
      }

      this.messages = YamlConfiguration.loadConfiguration(this.messagesFile);
   }

   private void saveTntResources() {
      File directory = new File(this.getDataFolder(), "tnts");
      if(!directory.exists() && !directory.mkdirs()) {
         this.getLogger().warning("Could not create TNT directory.");
      }

      Iterator var2 = this.getConfig().getStringList("tnts.sources").iterator();

      while(var2.hasNext()) {
         String source = (String)var2.next();
         File file = new File(directory, source);
         if(!file.exists()) {
            try {
               this.saveResource(source, false);
               this.getLogger().info(source);
            } catch (IllegalArgumentException var6) {
               this.getLogger().warning(source);
            }
         }
      }

   }

   public void reloadPlugin() {
      this.reloadConfig();
      this.loadMessages();
      this.saveTntResources();
      if(this.tntManager != null) {
         this.tntManager.loadTypes();
      }

      this.getLogger().info("HyperTNT configuration reloaded.");
   }

   public String message(String path) {
      String text = this.messages.getString(path, path);
      String prefix = this.messages.getString("prefix", "&8[&cHyperTNT&8] &r");
      return this.color(text.replace("{prefix}", prefix));
   }

   public List messageList(String path) {
      ArrayList result = new ArrayList();
      String prefix = this.messages.getString("prefix", "&8[&cHyperTNT&8] &r");
      Iterator var4 = this.messages.getStringList(path).iterator();

      while(var4.hasNext()) {
         String line = (String)var4.next();
         result.add(this.color(line.replace("{prefix}", prefix)));
      }

      return result;
   }

   public String color(String text) {
      return text == null?"":ChatColor.translateAlternateColorCodes('&', text);
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if(!sender.hasPermission("hypertnt.admin")) {
         sender.sendMessage(this.message("errors.no-permission"));
         return true;
      } else if(args.length == 0) {
         this.sendLines(sender, "commands.help");
         return true;
      } else {
         String var5 = args[0].toLowerCase(Locale.ROOT);
         byte var6 = -1;
         switch(var5.hashCode()) {
         case -934641255:
            if(var5.equals("reload")) {
               var6 = 0;
            }
            break;
         case 3173137:
            if(var5.equals("give")) {
               var6 = 2;
            }
            break;
         case 110844025:
            if(var5.equals("types")) {
               var6 = 1;
            }
         }

         switch(var6) {
         case 0:
            this.reloadPlugin();
            sender.sendMessage(this.message("commands.reloaded"));
            return true;
         case 1:
            this.sendLines(sender, "commands.types");
            return true;
         case 2:
            if(args.length < 3) {
               this.sendLines(sender, "commands.help");
               return true;
            } else {
               Player target = Bukkit.getPlayerExact(args[1]);
               if(target == null) {
                  sender.sendMessage(this.message("errors.player-not-found").replace("{player}", args[1]));
                  return true;
               } else {
                  String typeId = args[2].toLowerCase(Locale.ROOT);
                  if(!this.tntManager.hasType(typeId)) {
                     sender.sendMessage(this.message("errors.type-not-found").replace("{type}", typeId));
                     return true;
                  } else {
                     int amount = 1;
                     if(args.length >= 4) {
                        try {
                           amount = Integer.parseInt(args[3]);
                        } catch (NumberFormatException var16) {
                           sender.sendMessage(this.message("errors.invalid-number"));
                           return true;
                        }
                     }

                     if(amount >= 1 && amount <= 2304) {
                        int stackSize;
                        for(int remaining = amount; remaining > 0; remaining -= stackSize) {
                           stackSize = Math.min(64, remaining);
                           ItemStack item = this.tntManager.createItem(typeId, stackSize);
                           if(item == null) {
                              sender.sendMessage(this.message("errors.type-not-found").replace("{type}", typeId));
                              return true;
                           }

                           HashMap leftovers = target.getInventory().addItem(new ItemStack[]{item});
                           Iterator var14 = leftovers.values().iterator();

                           while(var14.hasNext()) {
                              ItemStack leftover = (ItemStack)var14.next();
                              target.getWorld().dropItemNaturally(target.getLocation(), leftover);
                           }
                        }

                        sender.sendMessage(this.message("commands.given").replace("{amount}", String.valueOf(amount)).replace("{type}", typeId).replace("{player}", target.getName()));
                        return true;
                     } else {
                        sender.sendMessage(this.message("errors.invalid-number"));
                        return true;
                     }
                  }
               }
            }
         default:
            this.sendLines(sender, "commands.help");
            return true;
         }
      }
   }

   private void sendLines(CommandSender sender, String path) {
      Iterator var3 = this.messageList(path).iterator();

      while(var3.hasNext()) {
         String line = (String)var3.next();
         sender.sendMessage(line);
      }

   }

   public List onTabComplete(CommandSender param1, Command param2, String param3, String[] param4) {
      // $FF: Couldn't be decompiled
   }

   private List filter(List values, String input) {
      String prefix = input.toLowerCase(Locale.ROOT);
      return values.stream().filter(prefix).toList();
   }

   public TntManager getTntManager() {
      return this.tntManager;
   }

   public ExplosionService getExplosionService() {
      return this.explosionService;
   }

   public WorldGuardBridge getWorldGuardBridge() {
      return this.worldGuardBridge;
   }

   // $FF: synthetic method
   private static boolean lambda$filter$0(String prefix, String value) {
      return value.toLowerCase(Locale.ROOT).startsWith(prefix);
   }
}
