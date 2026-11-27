package ru.voidpe.gifts;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Main-thread-only storage, separate from gifts.yml and data.yml. */
final class GiftLootStore {
    private final VoidGifts plugin;
    private final File file;
    private final Map<String, List<Reward>> byType = new LinkedHashMap<>();
    private boolean ready;

    record Reward(String id, ItemStack storedItem, double chance) {
        Reward {
            storedItem = storedItem.clone();
            chance = LootChance.clamp(chance);
        }
        ItemStack item() { return storedItem.clone(); }
    }

    GiftLootStore(VoidGifts plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "loot.yml");
    }

    boolean isReady() { return ready; }

    void load() {
        ready = false;
        Map<String, List<Reward>> loaded = new LinkedHashMap<>();
        if (file.exists()) {
            YamlConfiguration config = new YamlConfiguration();
            try {
                config.load(file);
                ConfigurationSection groups = config.getConfigurationSection("gifts");
                if (config.contains("gifts") && groups == null) throw new InvalidConfigurationException("gifts должен быть разделом");
                if (groups != null) {
                    for (String key : groups.getKeys(false)) {
                        ConfigurationSection group = groups.getConfigurationSection(key);
                        if (group == null) throw new InvalidConfigurationException("Неверный раздел подарка: " + key);
                        String type = group.getString("type");
                        if (type == null || type.isBlank() || loaded.containsKey(type)) {
                            throw new InvalidConfigurationException("Неверный или повторяющийся тип: " + key);
                        }
                        ConfigurationSection list = group.getConfigurationSection("rewards");
                        if (group.contains("rewards") && list == null) throw new InvalidConfigurationException("Неверный список наград: " + type);
                        List<Reward> rewards = new ArrayList<>();
                        if (list != null) {
                            for (String id : list.getKeys(false)) {
                                UUID.fromString(id);
                                ConfigurationSection entry = list.getConfigurationSection(id);
                                if (entry == null) throw new InvalidConfigurationException("Неверная награда: " + id);
                                ItemStack item = entry.getItemStack("item");
                                Object chanceValue = entry.get("chance");
                                if (item == null || item.getType() == Material.AIR || item.getAmount() < 1
                                        || !(chanceValue instanceof Number n) || !Double.isFinite(n.doubleValue())
                                        || n.doubleValue() < 0 || n.doubleValue() > 100) {
                                    throw new InvalidConfigurationException("Неверный предмет или шанс: " + id);
                                }
                                rewards.add(new Reward(id, item, ((Number) chanceValue).doubleValue()));
                            }
                        }
                        loaded.put(type, rewards);
                    }
                }
            } catch (IOException | InvalidConfigurationException | RuntimeException e) {
                plugin.getLogger().severe("Не удалось загрузить " + file + ": " + e.getMessage()
                        + ". Файл не перезаписан; открытие подарков и меню временно отключены.");
                return;
            }
        }
        byType.clear();
        byType.putAll(loaded);
        ready = true;
    }

    List<Reward> rewards(String type) { return List.copyOf(byType.getOrDefault(type, List.of())); }

    Reward reward(String type, String id) {
        for (Reward reward : rewards(type)) if (reward.id().equals(id)) return reward;
        return null;
    }

    boolean add(String type, ItemStack item) {
        if (!ready || item == null || item.getType().isAir() || item.getAmount() < 1) return false;
        List<Reward> next = new ArrayList<>(rewards(type));
        next.add(new Reward(UUID.randomUUID().toString(), item, 100.0));
        return replace(type, next);
    }

    boolean setChance(String type, String id, double chance) {
        if (!ready || !Double.isFinite(chance)) return false;
        List<Reward> next = new ArrayList<>(rewards(type));
        for (int i = 0; i < next.size(); i++) {
            Reward old = next.get(i);
            if (old.id().equals(id)) {
                next.set(i, new Reward(id, old.item(), chance));
                return replace(type, next);
            }
        }
        return false;
    }

    boolean remove(String type, String id) {
        if (!ready) return false;
        List<Reward> next = new ArrayList<>(rewards(type));
        if (!next.removeIf(reward -> reward.id().equals(id))) return false;
        return replace(type, next);
    }

    private boolean replace(String type, List<Reward> next) {
        boolean existed = byType.containsKey(type);
        List<Reward> before = byType.get(type);
        byType.put(type, List.copyOf(next));
        if (save()) return true;
        if (existed) byType.put(type, before); else byType.remove(type);
        return false;
    }

    private boolean save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, List<Reward>> group : byType.entrySet()) {
            String key = Base64.getUrlEncoder().withoutPadding().encodeToString(group.getKey().getBytes(StandardCharsets.UTF_8));
            String path = "gifts." + key;
            yaml.set(path + ".type", group.getKey());
            for (Reward reward : group.getValue()) {
                String entry = path + ".rewards." + reward.id();
                yaml.set(entry + ".item", reward.item());
                yaml.set(entry + ".chance", reward.chance());
            }
        }
        Path temporary = null;
        try {
            Files.createDirectories(file.toPath().getParent());
            temporary = Files.createTempFile(file.toPath().getParent(), "loot-", ".tmp");
            yaml.save(temporary.toFile());
            try {
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().severe("Не удалось сохранить loot.yml: " + e.getMessage());
            return false;
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
}
