package ru.FaralMC.talismans;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Загрузка талисманов из конфига, создание предметов, эффекты, рецепты. */
public final class TalismanService {

    private final FaralTalismans plugin;
    private final NamespacedKey idKey;
    private final Map<String, Talisman> talismans = new LinkedHashMap<>();

    public TalismanService(FaralTalismans plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "talisman_id");
        load();
        registerRecipes();
    }

    // ------------------------------------------------------------------ загрузка
    public void load() {
        talismans.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("talismans");
        if (section == null) {
            plugin.getLogger().warning("В config.yml нет секции talismans!");
            return;
        }
        for (String id : section.getKeys(false)) {
            String path = "talismans." + id;
            Material material = Material.matchMaterial(plugin.getConfig().getString(path + ".material", "AMETHYST_SHARD"));
            if (material == null) {
                plugin.getLogger().warning("Талисман " + id + ": неизвестный материал, пропускаю");
                continue;
            }
            List<Talisman.EffectDef> effects = new ArrayList<>();
            for (Map<?, ?> raw : plugin.getConfig().getMapList(path + ".effects")) {
                String typeName = String.valueOf(raw.get("type"));
                PotionEffectType type = potionType(typeName);
                if (type == null) {
                    plugin.getLogger().warning("Талисман " + id + ": неизвестный эффект " + typeName);
                    continue;
                }
                Object ampRaw = raw.get("amplifier");
                int amp = ampRaw instanceof Number ? ((Number) ampRaw).intValue() : 0;
                effects.add(new Talisman.EffectDef(type, amp));
            }
            List<Talisman.AttributeDef> attributes = new ArrayList<>();
            for (Map<?, ?> raw : plugin.getConfig().getMapList(path + ".attributes")) {
                String key = String.valueOf(raw.get("type")).toLowerCase(Locale.ROOT).replace("generic_", "");
                Object amountRaw = raw.get("amount");
                double amount = amountRaw instanceof Number ? ((Number) amountRaw).doubleValue() : 0;
                AttributeModifier.Operation op = AttributeModifier.Operation.ADD_NUMBER;
                Object opRaw = raw.get("operation");
                try {
                    op = AttributeModifier.Operation.valueOf(
                            String.valueOf(opRaw == null ? "ADD_NUMBER" : opRaw).toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                }
                attributes.add(new Talisman.AttributeDef(key, amount, op));
            }
            Talisman.Where where = Talisman.Where.INVENTORY;
            try {
                where = Talisman.Where.valueOf(plugin.getConfig().getString(path + ".where", "INVENTORY").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
            Map<Character, Material> ingredients = new HashMap<>();
            ConfigurationSection ing = plugin.getConfig().getConfigurationSection(path + ".recipe.ingredients");
            if (ing != null) {
                for (String k : ing.getKeys(false)) {
                    Material m = Material.matchMaterial(ing.getString(k, ""));
                    if (m != null && !k.isEmpty()) {
                        ingredients.put(k.charAt(0), m);
                    }
                }
            }
            Talisman.RecipeDef recipe = new Talisman.RecipeDef(
                    plugin.getConfig().getBoolean(path + ".recipe.enabled", false),
                    plugin.getConfig().getStringList(path + ".recipe.shape"),
                    ingredients);
            talismans.put(id, new Talisman(
                    id,
                    plugin.getConfig().getString(path + ".display-name", id),
                    material,
                    plugin.getConfig().getStringList(path + ".lore"),
                    plugin.getConfig().getBoolean(path + ".glow", true),
                    where,
                    plugin.getConfig().getString(path + ".permission", ""),
                    effects, attributes, recipe));
        }
        plugin.getLogger().info("Загружено талисманов: " + talismans.size());
    }

    private PotionEffectType potionType(String name) {
        PotionEffectType type = PotionEffectType.getByName(name.toUpperCase(Locale.ROOT));
        if (type == null) {
            type = Registry.EFFECT.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
        }
        return type;
    }

    // ------------------------------------------------------------------ рецепты
    public void registerRecipes() {
        for (Talisman t : talismans.values()) {
            NamespacedKey key = new NamespacedKey(plugin, "talisman_" + t.id());
            Bukkit.removeRecipe(key);
            if (!t.recipe().enabled() || t.recipe().shape().isEmpty()) {
                continue;
            }
            ShapedRecipe recipe = new ShapedRecipe(key, createItem(t, 1));
            recipe.shape(t.recipe().shape().toArray(new String[0]));
            t.recipe().ingredients().forEach(recipe::setIngredient);
            Bukkit.addRecipe(recipe);
            plugin.getLogger().info("Рецепт талисмана: " + t.id());
        }
    }

    // ------------------------------------------------------------------ предметы
    public ItemStack createItem(Talisman talisman, int amount) {
        ItemStack item = new ItemStack(talisman.material(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(component(TextUtil.color(talisman.displayName())));
        List<Component> lore = new ArrayList<>();
        for (String line : talisman.lore()) {
            lore.add(component(TextUtil.color(line)));
        }
        meta.lore(lore);
        if (talisman.glow()) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, talisman.id());
        item.setItemMeta(meta);
        return item;
    }

    public String identify(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    public Talisman byId(String id) {
        return talismans.get(id);
    }

    public Collection<Talisman> all() {
        return talismans.values();
    }

    // ------------------------------------------------------------------ активность
    /** Талисманы, которые прямо сейчас работают у игрока (с учётом слота и прав). */
    public List<Talisman> activeTalismans(Player player) {
        Map<String, Talisman> active = new LinkedHashMap<>();
        List<ItemStack> toScan = new ArrayList<>();
        for (Talisman t : talismans.values()) {
            if (t.where() == Talisman.Where.OFFHAND) {
                String id = identify(player.getInventory().getItemInOffHand());
                if (t.id().equals(id) && t.hasPermission(player)) {
                    active.put(t.id(), t);
                }
            } else if (t.where() == Talisman.Where.HOTBAR) {
                for (int i = 0; i < 9; i++) {
                    toScan.add(player.getInventory().getItem(i));
                }
            } else {
                for (ItemStack item : player.getInventory().getContents()) {
                    toScan.add(item);
                }
            }
        }
        // HOTBAR/INVENTORY талисманы ищем по отсканированным предметам (без дублей по слотам)
        java.util.Set<String> seenSlots = new java.util.HashSet<>();
        for (ItemStack item : toScan) {
            String id = identify(item);
            if (id == null || !seenSlots.add(id)) {
                continue;
            }
            Talisman t = talismans.get(id);
            if (t != null && t.where() != Talisman.Where.OFFHAND && t.hasPermission(player)) {
                active.putIfAbsent(t.id(), t);
            }
        }
        int max = plugin.getConfig().getInt("settings.max-active-talismans", 0);
        List<Talisman> result = new ArrayList<>(active.values());
        if (max > 0 && result.size() > max) {
            result = result.subList(0, max);
        }
        return result;
    }

    /** Применить эффекты и атрибуты активных талисманов. Вызывается по таймеру. */
    public void refresh(Player player) {
        List<Talisman> active = activeTalismans(player);
        int duration = plugin.getConfig().getInt("settings.refresh-interval-ticks", 40) + 100;
        boolean particles = plugin.getConfig().getBoolean("settings.effect-particles", false);
        for (Talisman t : active) {
            for (Talisman.EffectDef e : t.effects()) {
                PotionEffect current = player.getPotionEffect(e.type());
                if (current != null && current.getAmplifier() > e.amplifier()) {
                    continue; // не перебиваем более сильный эффект из другого источника
                }
                player.addPotionEffect(new PotionEffect(e.type(), duration, e.amplifier(), true, particles, true), false);
            }
        }
        // атрибуты: модификатор должен висеть ровно пока талисман активен
        for (Talisman t : talismans.values()) {
            boolean isActive = active.contains(t);
            for (Talisman.AttributeDef a : t.attributes()) {
                Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(a.attributeKey()));
                if (attribute == null) {
                    continue;
                }
                AttributeInstance instance = player.getAttribute(attribute);
                if (instance == null) {
                    continue;
                }
                NamespacedKey key = new NamespacedKey(plugin, "talisman_" + t.id() + "_" + a.attributeKey());
                AttributeModifier existing = null;
                for (AttributeModifier m : instance.getModifiers()) {
                    if (m.getKey().equals(key)) {
                        existing = m;
                        break;
                    }
                }
                if (isActive && existing == null) {
                    instance.addModifier(new AttributeModifier(key, a.amount(), a.operation()));
                } else if (!isActive && existing != null) {
                    instance.removeModifier(existing);
                }
            }
        }
        if (plugin.getConfig().getBoolean("settings.show-actionbar", false) && !active.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Talisman t : active) {
                if (sb.length() > 0) {
                    sb.append(" &8| ");
                }
                sb.append(t.displayName());
            }
            player.sendActionBar(component(TextUtil.color(sb.toString())));
        }
    }

    /** Игрок вышел: снять все атрибут-модификаторы талисманов. */
    public void clearAttributes(Player player) {
        for (Talisman t : talismans.values()) {
            for (Talisman.AttributeDef a : t.attributes()) {
                Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(a.attributeKey()));
                if (attribute == null) {
                    continue;
                }
                AttributeInstance instance = player.getAttribute(attribute);
                if (instance == null) {
                    continue;
                }
                NamespacedKey key = new NamespacedKey(plugin, "talisman_" + t.id() + "_" + a.attributeKey());
                for (AttributeModifier m : new ArrayList<>(instance.getModifiers())) {
                    if (m.getKey().equals(key)) {
                        instance.removeModifier(m);
                    }
                }
            }
        }
    }

    static Component component(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy == null ? "" : legacy);
    }
}
