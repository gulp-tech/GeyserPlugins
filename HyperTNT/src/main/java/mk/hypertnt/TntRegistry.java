package mk.hypertnt;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Серверный источник истины «какие координаты занимает кастомный TNT».
 *
 * <p>Ключ {@code {UUID мира, x, y, z}}, значение — тип TNT из tnts/*.yml.
 * Файл данных: одна запись на строку вида {@code worldUUID;x;y;z;type}.
 * Каждое изменение сразу сохраняется атомарной заменой файла, поэтому
 * перезапуск сервера не теряет типы. Устаревшие записи (блок больше не TNT)
 * удаляются при загрузке чанка; потерянные типы не угадываются.</p>
 *
 * <p>Класс не зависит от Bukkit и покрывается юнит-тестами.</p>
 */
public final class TntRegistry {

    /** Позиция блока: UUID мира + координаты. */
    public record Pos(UUID world, int x, int y, int z) {
    }

    private static final Pattern TYPE_ID = Pattern.compile("[a-z0-9_.\\-]+");
    private static final String HEADER = "# HyperTNT placed TNT registry (worldUUID;x;y;z;type)";

    private final Object lock = new Object();
    private final Map<Pos, String> entries = new LinkedHashMap<>();
    private final Path file;
    private final Consumer<String> log;

    public TntRegistry(Path file) {
        this(file, message -> {
        });
    }

    public TntRegistry(Path file, Consumer<String> log) {
        this.file = file;
        this.log = log == null ? message -> {
        } : log;
    }

    public static boolean isValidType(String type) {
        return type != null && TYPE_ID.matcher(type).matches();
    }

    /** Читает реестр с диска. Повреждённые строки пропускаются с предупреждением. */
    public void load() {
        synchronized (lock) {
            entries.clear();
            if (!Files.exists(file)) {
                return;
            }
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                long number = 0;
                while ((line = reader.readLine()) != null) {
                    number++;
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    String[] parts = trimmed.split(";", 5);
                    if (parts.length != 5) {
                        log.accept("placed-tnts: строка " + number + " пропущена (ожидалось 5 полей).");
                        continue;
                    }
                    try {
                        Pos pos = new Pos(
                                UUID.fromString(parts[0].trim()),
                                Integer.parseInt(parts[1].trim()),
                                Integer.parseInt(parts[2].trim()),
                                Integer.parseInt(parts[3].trim()));
                        String type = parts[4].trim().toLowerCase(Locale.ROOT);
                        if (!isValidType(type)) {
                            log.accept("placed-tnts: строка " + number + " пропущена (неизвестный тип '" + parts[4].trim() + "').");
                            continue;
                        }
                        entries.put(pos, type);
                    } catch (IllegalArgumentException bad) {
                        log.accept("placed-tnts: строка " + number + " пропущена (неверные данные).");
                    }
                }
            } catch (IOException exception) {
                throw new UncheckedIOException("Не удалось прочитать " + file, exception);
            }
        }
    }

    /** Атомарно сохраняет реестр на диск (tmp-файл + замена). */
    public void save() {
        synchronized (lock) {
            try {
                Path directory = file.getParent();
                if (directory != null) {
                    Files.createDirectories(directory);
                }
                Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
                try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    writer.write(HEADER);
                    writer.newLine();
                    for (Map.Entry<Pos, String> entry : entries.entrySet()) {
                        Pos pos = entry.getKey();
                        writer.write(pos.world() + ";" + pos.x() + ";" + pos.y() + ";" + pos.z() + ";" + entry.getValue());
                        writer.newLine();
                    }
                }
                try {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException fallback) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                throw new UncheckedIOException("Не удалось сохранить " + file, exception);
            }
        }
    }

    /**
     * Ставит/заменяет запись и сохраняет файл. Возвращает прежний тип или null.
     */
    public String put(Pos pos, String type) {
        String normalized = type == null ? null : type.toLowerCase(Locale.ROOT);
        if (!isValidType(normalized)) {
            throw new IllegalArgumentException("Недопустимый тип TNT: " + type);
        }
        String previous;
        synchronized (lock) {
            previous = entries.put(pos, normalized);
        }
        save();
        return previous;
    }

    /**
     * Удаляет запись и сохраняет файл. Возвращает удалённый тип или null.
     */
    public String remove(Pos pos) {
        String removed;
        synchronized (lock) {
            removed = entries.remove(pos);
        }
        if (removed != null) {
            save();
        }
        return removed;
    }

    /** Удаляет набор записей одним сохранением. Возвращает число удалённых. */
    public int removeAll(Collection<Pos> positions) {
        int removed = 0;
        synchronized (lock) {
            for (Pos pos : positions) {
                if (entries.remove(pos) != null) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            save();
        }
        return removed;
    }

    /** Возвращает тип в позиции или null. */
    public String get(Pos pos) {
        synchronized (lock) {
            return entries.get(pos);
        }
    }

    public boolean contains(Pos pos) {
        synchronized (lock) {
            return entries.containsKey(pos);
        }
    }

    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    /** Все записи реестра (копия) — для отладки и тестов. */
    public Map<Pos, String> snapshot() {
        synchronized (lock) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        }
    }

    /** Позиции реестра внутри указанного чанка. */
    public Set<Pos> positionsIn(UUID world, int chunkX, int chunkZ) {
        Set<Pos> result = new LinkedHashSet<>();
        synchronized (lock) {
            for (Pos pos : entries.keySet()) {
                if (pos.world().equals(world) && (pos.x() >> 4) == chunkX && (pos.z() >> 4) == chunkZ) {
                    result.add(pos);
                }
            }
        }
        return result;
    }

    /** Позиции реестра в указанном мире. */
    public List<Pos> positionsIn(UUID world) {
        List<Pos> result = new ArrayList<>();
        synchronized (lock) {
            for (Pos pos : entries.keySet()) {
                if (pos.world().equals(world)) {
                    result.add(pos);
                }
            }
        }
        return result;
    }

    /**
     * Удаляет устаревшие записи чанка: где блок больше не TNT.
     * Возвращает число удалённых записей. Один вызов = одно сохранение.
     */
    public int validateIn(UUID world, int chunkX, int chunkZ, Predicate<Pos> stillTnt) {
        Set<Pos> stale = new LinkedHashSet<>();
        for (Pos pos : positionsIn(world, chunkX, chunkZ)) {
            boolean valid;
            try {
                valid = stillTnt.test(pos);
            } catch (RuntimeException exception) {
                valid = false;
            }
            if (!valid) {
                stale.add(pos);
            }
        }
        return removeAll(stale);
    }
}
