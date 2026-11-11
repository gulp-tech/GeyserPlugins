// Decomcompiled with Vineflower (JS/WASM port @run-slicer/vf) from HyperTNT.jar
// Восстановлено из байткода: без комментариев, имена локальных переменных условны.
package mk.hypertnt;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
   private final Map<String, TntManager.TntType> types = new LinkedHashMap();
   private final Set<NamespacedKey> recipes = new HashSet();
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

      for (String source : this.plugin.getConfig().getStringList("tnts.sources")) {
         File file = new File(directory, source);
         if (!file.exists()) {
            this.plugin.getLogger().warning("TNT file not found: " + source);
         } else {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

            for (String id : yaml.getKeys(false)) {
               ConfigurationSection section = yaml.getConfigurationSection(id);
               if (section != null && section.getBoolean("enabled", true)) {
                  String normalized = id.toLowerCase(Locale.ROOT);
                  this.types.put(normalized, new TntManager.TntType(normalized, section));
               }
            }
         }
      }

      this.registerRecipes();
      this.plugin.getLogger().info("Loaded " + this.types.size() + " TNT types.");
   }

   public boolean hasType(String id) {
      return this.types.containsKey(id.toLowerCase(Locale.ROOT));
   }

   public TntManager.TntType getType(String id) {
      return id == null ? null : (TntManager.TntType)this.types.get(id.toLowerCase(Locale.ROOT));
   }

   public List<String> getTypeIds() {
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
      if (type == null) {
         return null;
      }

      ConfigurationSection item = type.config.getConfigurationSection("item");
      Material material = Material.matchMaterial(item != null ? item.getString("material", "TNT") : "TNT");
      if (material == null) {
         material = Material.TNT;
      }

      ItemStack stack = new ItemStack(material, Math.max(1, amount));
      ItemMeta meta = stack.getItemMeta();
      if (meta == null) {
         return stack;
      }

      meta.setDisplayName(this.color(item != null ? item.getString("name", id) : id));
      if (item != null) {
         List<String> lore = new ArrayList();

         for (String line : item.getStringList("lore")) {
            lore.add(this.color(line));
         }

         meta.setLore(lore);
         int model = item.getInt("custom_model_data", 0);
         if (model > 0) {
            meta.setCustomModelData(model);
         }

         if (item.getBoolean("enchanted", false)) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ENCHANTS});
         }
      }

      meta.getPersistentDataContainer().set(this.itemKey, PersistentDataType.STRING, id);
      stack.setItemMeta(meta);
      return stack;
   }

   public String getItemType(ItemStack item) {
      return item != null && item.hasItemMeta() ? (String)item.getItemMeta().getPersistentDataContainer().get(this.itemKey, PersistentDataType.STRING) : null;
   }

   private void registerRecipes() {
      for (TntManager.TntType type : this.types.values()) {
         ConfigurationSection craft = type.config.getConfigurationSection("craft");
         if (craft != null && craft.getBoolean("enabled", false)) {
            List<String> shape = craft.getStringList("shape");
            if (shape.size() == 3) {
               ItemStack result = this.createItem(type.id, 1);
               NamespacedKey key = new NamespacedKey(this.plugin, "recipe_" + type.id);
               ShapedRecipe recipe = new ShapedRecipe(key, result);
               recipe.shape(new String[]{(String)shape.get(0), (String)shape.get(1), (String)shape.get(2)});
               ConfigurationSection ingredients = craft.getConfigurationSection("ingredients");
               if (ingredients != null) {
                  boolean valid = true;

                  for (String symbol : ingredients.getKeys(false)) {
                     if (symbol.length() != 1) {
                        valid = false;
                        break;
                     }

                     Material material = Material.matchMaterial(ingredients.getString(symbol, ""));
                     if (material == null) {
                        valid = false;
                        break;
                     }

                     recipe.setIngredient(symbol.charAt(0), material);
                  }

                  if (valid) {
                     try {
                        Bukkit.addRecipe(recipe);
                        this.recipes.add(key);
                     } catch (Exception exception) {
                        this.plugin.getLogger().warning("Failed to register recipe: " + type.id);
                     }
                  }
               }
            }
         }
      }
   }

   private void unregisterRecipes() {
      for (NamespacedKey key : this.recipes) {
         Bukkit.removeRecipe(key);
      }

      this.recipes.clear();
   }

   public void shutdown() {
      this.unregisterRecipes();
      this.types.clear();
   }

   private String color(String value) {
      return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
   }

   public static final class TntType {
      public final String id;
      public final ConfigurationSection config;

      public TntType(String id, ConfigurationSection config) {
         this.id = id;
         this.config = config;
      }

      public double radius() {
         return this.config.getDouble("explosion.radius", 6.0);
      }

      public double fuseSeconds() {
         return this.config.getDouble("ignite.fuse-seconds", 4.0);
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
         String path = "explosion.blocks.per-material." + material.name();
         return this.config.contains(path + ".drop-chance")
            ? this.config.getDouble(path + ".drop-chance")
            : this.config.getDouble("explosion.blocks.default.drop-chance", 1.0);
      }

      public String blockMode(Material material) {
         String path = "explosion.blocks.per-material." + material.name() + ".mode";
         return this.config.getString(path, this.config.getString("explosion.blocks.default.mode", "BREAK")).toUpperCase(Locale.ROOT);
      }

      public int cooldown() {
         for (String condition : this.config.getStringList("conditions")) {
            String[] parts = condition.trim().split("\\s+");
            if (parts.length == 2 && parts[0].equalsIgnoreCase("cooldown")) {
               try {
                  return Math.max(0, Integer.parseInt(parts[1]));
               } catch (NumberFormatException var5) {
               }
            }
         }

         return 0;
      }
   }
}
