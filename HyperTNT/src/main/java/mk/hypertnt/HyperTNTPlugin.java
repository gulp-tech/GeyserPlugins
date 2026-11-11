// Decomcompiled with Vineflower (JS/WASM port @run-slicer/vf) from HyperTNT.jar
// Восстановлено из байткода: без комментариев, имена локальных переменных условны.
package mk.hypertnt;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
   private TntRegistry registry;
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
      this.registry = new TntRegistry(new File(this.getDataFolder(), "placed-tnts.txt").toPath(), this.getLogger()::warning);
      this.registry.load();
      this.getLogger().info("Placed TNT registry entries: " + this.registry.size());
      Bukkit.getPluginManager().registerEvents(new TntListener(this), this);
      PluginCommand command = this.getCommand("hypertnt");
      if (command == null) {
         this.getLogger().severe("Command 'hypertnt' is missing in plugin.yml!");
         Bukkit.getPluginManager().disablePlugin(this);
      } else {
         command.setExecutor(this);
         command.setTabCompleter(this);
         this.getLogger().info("==================================");
         this.getLogger().info("HyperTNT 2.1.0 enabled.");
         this.getLogger().info("Loaded TNT types: " + this.tntManager.getTypeIds().size());
         if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            this.getLogger().info("WorldGuard detected.");
         }

         if (Bukkit.getPluginManager().isPluginEnabled("ShieldyBlocks")) {
            this.getLogger().info("ShieldyBlocks detected. Integration enabled.");
         }

         this.getLogger().info("==================================");
      }
   }

   public void onDisable() {
      if (this.registry != null) {
         this.registry.save();
      }

      if (this.tntManager != null) {
         this.tntManager.shutdown();
      }

      this.getLogger().info("HyperTNT disabled.");
   }

   public TntRegistry getRegistry() {
      return this.registry;
   }

   private void loadMessages() {
      this.messagesFile = new File(this.getDataFolder(), "messages.yml");
      if (!this.messagesFile.exists()) {
         try {
            this.saveResource("messages.yml", false);
         } catch (IllegalArgumentException exception) {
            this.getLogger().warning("messages.yml was not found in JAR.");
         }
      }

      this.messages = YamlConfiguration.loadConfiguration(this.messagesFile);
   }

   private void saveTntResources() {
      File directory = new File(this.getDataFolder(), "tnts");
      if (!directory.exists() && !directory.mkdirs()) {
         this.getLogger().warning("Could not create TNT directory.");
      }

      for (String source : this.getConfig().getStringList("tnts.sources")) {
         File file = new File(directory, source);
         if (!file.exists()) {
            try {
               this.saveResource("tnts/" + source, false);
               this.getLogger().info("Created TNT configuration: " + source);
            } catch (IllegalArgumentException exception) {
               this.getLogger().warning("TNT resource not found: " + source);
            }
         }
      }
   }

   public void reloadPlugin() {
      this.reloadConfig();
      this.loadMessages();
      this.saveTntResources();
      if (this.tntManager != null) {
         this.tntManager.loadTypes();
      }

      this.getLogger().info("HyperTNT configuration reloaded.");
   }

   public String message(String path) {
      String text = this.messages.getString(path, "&cMessage not found: " + path);
      String prefix = this.messages.getString("prefix", "&8[&cHyperTNT&8] &r");
      return this.color(text.replace("{prefix}", prefix));
   }

   public List<String> messageList(String path) {
      List<String> result = new ArrayList();
      String prefix = this.messages.getString("prefix", "&8[&cHyperTNT&8] &r");

      for (String line : this.messages.getStringList(path)) {
         result.add(this.color(line.replace("{prefix}", prefix)));
      }

      return result;
   }

   public String color(String text) {
      return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!sender.hasPermission("hypertnt.admin")) {
         sender.sendMessage(this.message("errors.no-permission"));
         return true;
      }

      if (args.length == 0) {
         this.sendLines(sender, "commands.help");
         return true;
      }

      switch (args[0].toLowerCase(Locale.ROOT)) {
         case "reload":
            this.reloadPlugin();
            sender.sendMessage(this.message("commands.reloaded"));
            return true;
         case "types":
            this.sendLines(sender, "commands.types");
            return true;
         case "give":
            if (args.length < 3) {
               this.sendLines(sender, "commands.help");
               return true;
            } else {
               Player target = Bukkit.getPlayerExact(args[1]);
               if (target == null) {
                  sender.sendMessage(this.message("errors.player-not-found").replace("{player}", args[1]));
                  return true;
               } else {
                  String typeId = args[2].toLowerCase(Locale.ROOT);
                  if (!this.tntManager.hasType(typeId)) {
                     sender.sendMessage(this.message("errors.type-not-found").replace("{type}", typeId));
                     return true;
                  } else {
                     int amount = 1;
                     if (args.length >= 4) {
                        try {
                           amount = Integer.parseInt(args[3]);
                        } catch (NumberFormatException exception) {
                           sender.sendMessage(this.message("errors.invalid-number"));
                           return true;
                        }
                     }

                     if (amount >= 1 && amount <= 2304) {
                        int remaining = amount;

                        while (remaining > 0) {
                           int stackSize = Math.min(64, remaining);
                           ItemStack item = this.tntManager.createItem(typeId, stackSize);
                           if (item == null) {
                              sender.sendMessage(this.message("errors.type-not-found").replace("{type}", typeId));
                              return true;
                           }

                           Map<Integer, ItemStack> leftovers = target.getInventory().addItem(new ItemStack[]{item});

                           for (ItemStack leftover : leftovers.values()) {
                              target.getWorld().dropItemNaturally(target.getLocation(), leftover);
                           }

                           remaining -= stackSize;
                        }

                        sender.sendMessage(
                           this.message("commands.given")
                              .replace("{amount}", String.valueOf(amount))
                              .replace("{type}", typeId)
                              .replace("{player}", target.getName())
                        );
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

   private void sendLines(CommandSender sender, String path) {
      for (String line : this.messageList(path)) {
         sender.sendMessage(line);
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (!sender.hasPermission("hypertnt.admin")) {
         return Collections.emptyList();
      } else if (args.length == 1) {
         return this.filter(List.of("give", "reload", "types"), args[0]);
      } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
         return this.filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
      } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
         return this.filter(this.tntManager.getTypeIds(), args[2]);
      } else {
         return args.length == 4 && args[0].equalsIgnoreCase("give") ? this.filter(List.of("1", "16", "32", "64"), args[3]) : Collections.emptyList();
      }
   }

   private List<String> filter(List<String> values, String input) {
      String prefix = input.toLowerCase(Locale.ROOT);
      return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
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
}
