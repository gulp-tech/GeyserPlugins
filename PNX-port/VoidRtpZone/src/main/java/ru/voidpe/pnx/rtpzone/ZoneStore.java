package ru.voidpe.pnx.rtpzone;

import org.powernukkitx.utils.Config;
import org.powernukkitx.utils.ConfigSection;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Read legacy-compatible zones.yml and atomically replace it only after validation. */
public final class ZoneStore {
    private final Path file;
    public ZoneStore(Path file) { this.file = file; }
    public Map<String, Zone> load() throws IOException {
        Map<String, Zone> result = new LinkedHashMap<>();
        if (!Files.exists(file)) return result;
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) throw new IOException("Файл зон недоступен");
        try {
            Config config = new Config(file.toFile(), Config.YAML);
            if (!config.isCorrect()) throw new IOException("Ошибка YAML");
            if (!config.exists("zones")) return result;
            if (!config.isSection("zones")) throw new IOException("zones должен быть разделом");
            ConfigSection root = config.getSection("zones");
            for (String raw : root.getKeys(false)) {
                String id = raw.toLowerCase(Locale.ROOT);
                String p = "zones." + raw + ".";
                if (!config.isSection("zones." + raw)) throw new IOException("Зона " + raw + " не является разделом");
                for (String field : List.of("world", "min-x", "min-y", "min-z", "max-x", "max-y", "max-z"))
                    if (!config.exists(p + field)) throw new IOException("Нет поля " + p + field);
                for (String field : List.of("min-x", "min-y", "min-z", "max-x", "max-y", "max-z"))
                    requireInt(config.get(p + field), p + field);
                if (config.exists(p + "dimension")) requireInt(config.get(p + "dimension"), p + "dimension");
                Zone z = new Zone(id, config.getString(p + "world"), config.getInt(p + "dimension", 0), config.getInt(p + "min-x"), config.getInt(p + "min-y"), config.getInt(p + "min-z"), config.getInt(p + "max-x"), config.getInt(p + "max-y"), config.getInt(p + "max-z"));
                if (result.putIfAbsent(id, z) != null) throw new IOException("Повторное имя зоны: " + id);
            }
            return result;
        } catch (IOException e) { throw e; }
        catch (RuntimeException e) { throw new IOException("Ошибка zones.yml: " + e.getMessage(), e); }
    }
    private static void requireInt(Object raw, String field) throws IOException {
        if (!(raw instanceof Number n)) throw new IOException("Неверное число " + field);
        double value = n.doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IOException("Число вне диапазона или не целое: " + field);
    }
    public void save(Map<String, Zone> zones) throws IOException {
        Path parent = file.toAbsolutePath().getParent(); Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".zones-", ".yml");
        try {
            Config cfg = new Config(temp.toFile(), Config.YAML);
            for (Zone z : zones.values()) {
                String p = "zones." + z.name() + ".";
                cfg.set(p + "world", z.world()); cfg.set(p + "dimension", z.dimension());
                cfg.set(p + "min-x", z.minX()); cfg.set(p + "min-y", z.minY()); cfg.set(p + "min-z", z.minZ());
                cfg.set(p + "max-x", z.maxX()); cfg.set(p + "max-y", z.maxY()); cfg.set(p + "max-z", z.maxZ());
            }
            if (zones.isEmpty()) cfg.set("zones", new ConfigSection());
            if (!cfg.save() || !new ZoneStore(temp).load().equals(zones)) throw new IOException("Не удалось проверить временный файл зон");
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { throw new IOException("Файловая система не поддерживает безопасную запись зон", e); }
        } finally { Files.deleteIfExists(temp); }
    }
}
