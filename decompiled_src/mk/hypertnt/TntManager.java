package mk.hypertnt;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import mk.hypertnt.HyperTNTPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public final class TntManager {
   private final HyperTNTPlugin plugin;
   private final Map types = new LinkedHashMap();
   private final Set recipes = new HashSet();
   private final NamespacedKey itemKey;
   private final NamespacedKey entityKey;

   public TntManager(HyperTNTPlugin plugin) {
      this.plugin = plugin;
      this.itemKey = new NamespacedKey(plugin, "tnt_type");
      this.entityKey = new NamespacedKey(plugin, "tnt_type_entity");
   }

   public void loadTypes() {
      this.unregisterRecipes();
      this.types.clear();
      File directory = new File(this.plugin.getDataFolder(), "tnts");
      Iterator var2 = this.plugin.getConfig().getStringList("tnts.sources").iterator();

      while(true) {
         while(var2.hasNext()) {
            String source = (String)var2.next();
            File file = new File(directory, source);
            if(!file.exists()) {
               this.plugin.getLogger().warning(source);
            } else {
               YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
               Iterator var6 = yaml.getKeys(false).iterator();

               while(var6.hasNext()) {
                  String id = (String)var6.next();
                  ConfigurationSection section = yaml.getConfigurationSection(id);
                  if(section != null && section.getBoolean("enabled", true)) {
                     String normalized = id.toLowerCase(Locale.ROOT);
                     this.types.put(normalized, new TntManager.TntType(normalized, section));
                  }
               }
            }
         }

         this.registerRecipes();
         this.plugin.getLogger().info(this.types.size());
         return;
      }
   }

   public boolean hasType(String id) {
      return this.types.containsKey(id.toLowerCase(Locale.ROOT));
   }

   public TntManager.TntType getType(String id) {
      return id == null?null:(TntManager.TntType)this.types.get(id.toLowerCase(Locale.ROOT));
   }

   public List getTypeIds() {
      return new ArrayList(this.types.keySet());
   }

   public NamespacedKey getEntityKey() {
      return this.entityKey;
   }

   public String getEntityType(Entity entity) {
      return (String)entity.getPersistentDataContainer().get(this.entityKey, PersistentDataType.STRING);
   }

   public ItemStack createItem(String id, int amount) {
      TntManager.TntType type = this.getType(id);
      if(type == null) {
         return null;
      } else {
         ConfigurationSection item = type.config.getConfigurationSection("item");
         Material material = Material.matchMaterial(item != null?item.getString("material", "TNT"):"TNT");
         if(material == null) {
            material = Material.TNT;
         }

         ItemStack stack = new ItemStack(material, Math.max(1, amount));
         ItemMeta meta = stack.getItemMeta();
         if(meta == null) {
            return stack;
         } else {
            meta.setDisplayName(this.color(item != null?item.getString("name", id):id));
            if(item != null) {
               ArrayList lore = new ArrayList();
               Iterator model = item.getStringList("lore").iterator();

               while(model.hasNext()) {
                  String line = (String)model.next();
                  lore.add(this.color(line));
               }

               meta.setLore(lore);
               int model1 = item.getInt("custom_model_data", 0);
               if(model1 > 0) {
                  meta.setCustomModelData(Integer.valueOf(model1));
               }

               if(item.getBoolean("enchanted", false)) {
                  meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                  meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ENCHANTS});
               }
            }

            meta.getPersistentDataContainer().set(this.itemKey, PersistentDataType.STRING, id);
            stack.setItemMeta(meta);
            return stack;
         }
      }
   }

   public String getItemType(ItemStack item) {
      return item != null && item.hasItemMeta()?(String)item.getItemMeta().getPersistentDataContainer().get(this.itemKey, PersistentDataType.STRING):null;
   }

   private void registerRecipes() {
      Iterator var1 = this.types.values().iterator();

      while(true) {
         TntManager.TntType type;
         NamespacedKey key;
         ShapedRecipe recipe;
         ConfigurationSection ingredients;
         do {
            ConfigurationSection craft;
            List shape;
            do {
               do {
                  do {
                     if(!var1.hasNext()) {
                        return;
                     }

                     type = (TntManager.TntType)var1.next();
                     craft = type.config.getConfigurationSection("craft");
                  } while(craft == null);
               } while(!craft.getBoolean("enabled", false));

               shape = craft.getStringList("shape");
            } while(shape.size() != 3);

            ItemStack result = this.createItem(type.id, 1);
            key = new NamespacedKey(this.plugin, type.id);
            recipe = new ShapedRecipe(key, result);
            recipe.shape(new String[]{(String)shape.get(0), (String)shape.get(1), (String)shape.get(2)});
            ingredients = craft.getConfigurationSection("ingredients");
         } while(ingredients == null);

         boolean valid = true;
         Iterator exception = ingredients.getKeys(false).iterator();

         while(exception.hasNext()) {
            String symbol = (String)exception.next();
            if(symbol.length() != 1) {
               valid = false;
               break;
            }

            Material material = Material.matchMaterial(ingredients.getString(symbol, ""));
            if(material == null) {
               valid = false;
               break;
            }

            recipe.setIngredient(symbol.charAt(0), material);
         }

         if(valid) {
            try {
               Bukkit.addRecipe(recipe);
               this.recipes.add(key);
            } catch (Exception var13) {
               this.plugin.getLogger().warning(type.id);
            }
         }
      }
   }

   private void unregisterRecipes() {
      Iterator var1 = this.recipes.iterator();

      while(var1.hasNext()) {
         NamespacedKey key = (NamespacedKey)var1.next();
         Bukkit.removeRecipe(key);
      }

      this.recipes.clear();
   }

   public void shutdown() {
      this.unregisterRecipes();
      this.types.clear();
   }

   private String color(String value) {
      return ChatColor.translateAlternateColorCodes('&', value == null?"":value);
   }

   public static final class TntType {
      public final String id;
      public final ConfigurationSection config;

      public TntType(String id, ConfigurationSection config) {
         this.id = id;
         this.config = config;
      }

      public double radius() {
         return this.config.getDouble("explosion.radius", 6.0D);
      }

      public double fuseSeconds() {
         return this.config.getDouble("ignite.fuse-seconds", 4.0D);
      }

      public boolean throwEnabled() {
         return this.config.getBoolean("ignite.throw.enabled", false);
      }

      public boolean throwAutoIgnite() {
         return this.config.getBoolean("ignite.throw.auto-ignite", false);
      }

      public boolean autoIgnite() {
         return this.config.getBoolean("ignite.auto-ignite-on-place", false);
      }

      public double dropChance(Material material) {
         String path = material.name();
         return this.config.contains(path)?this.config.getDouble(path):this.config.getDouble("explosion.blocks.default.drop-chance", 1.0D);
      }

      public String blockMode(Material material) {
         String path = material.name();
         return this.config.getString(path, this.config.getString("explosion.blocks.default.mode", "BREAK")).toUpperCase(Locale.ROOT);
      }

      public int cooldown() {
         Iterator var1 = this.config.getStringList("conditions").iterator();

         while(true) {
            String[] parts;
            do {
               do {
                  if(!var1.hasNext()) {
                     return 0;
                  }

                  String condition = (String)var1.next();
                  parts = condition.trim().split("\\s+");
               } while(parts.length != 2);
            } while(!parts[0].equalsIgnoreCase("cooldown"));

            try {
               return Math.max(0, Integer.parseInt(parts[1]));
            } catch (NumberFormatException var5) {
               ;
            }
         }
      }
   }
}
