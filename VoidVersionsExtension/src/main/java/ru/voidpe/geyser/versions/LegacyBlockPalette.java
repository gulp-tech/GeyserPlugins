package ru.voidpe.geyser.versions;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * Старая палитра блоков Bedrock (1.21.110) для клиентов протоколов 844–898.
 *
 * <p>Зачем: Geyser знает палитры только для своих родных версий (26.30 и новее).
 * Когда мы регистрируем старый протокол, {@code BlockRegistries.BLOCKS.forVersion()}
 * отдаёт ему маппинги 26.30 — то есть runtime id блоков по палитре 26.30. Клиент
 * раскладывает эти номера по своей палитре, и мир рисуется «пластинами».</p>
 *
 * <p>Здесь лежит палитра 1.21.110 (из открытых ресурсов Geyser, MIT — см.
 * {@code palette/README.txt}) и метод {@link #remap(Object)}, который по состоянию
 * блока находит его runtime id в старой палитре. Блоки, которых в 1.21.110 нет
 * (циннабар, сера и прочее новое), заменяются блоком с тем же именем, а если нет
 * и его — воздухом.</p>
 *
 * <p>NBT читается своим минимальным ридером (файл — gzip + big-endian NBT): так
 * расширение не зависит от версии библиотеки cloudburst, а разбор можно проверить
 * локальным тестом без запуска Geyser.</p>
 */
final class LegacyBlockPalette {

    /** Файл в jar расширения. */
    static final String RESOURCE = "/palette/block_palette.1_21_110.nbt";

    /** Первый протокол, которому подходит эта палитра (Bedrock 1.21.110). */
    static final int MIN_PROTOCOL = 844;
    /** Последний: 898 = 1.21.130 (в Geyser 4d1c5f3e она же применяется и к 844–860). */
    static final int MAX_PROTOCOL = 898;

    /** «Имя|k=v,k=v» → runtime id в старой палитре. */
    private final Map<String, Integer> byState;
    /** Имя блока → runtime id варианта с пустыми states (запасной вариант). */
    private final Map<String, Integer> byName;
    /** Состояния блоков по порядку старой палитры (индекс = runtime id). */
    private final List<Object> states;
    private final int airId;

    private LegacyBlockPalette(Map<String, Integer> byState, Map<String, Integer> byName,
                               List<Object> states, int airId) {
        this.byState = byState;
        this.byName = byName;
        this.states = states;
        this.airId = airId;
    }

    static boolean covers(int protocol) {
        return protocol >= MIN_PROTOCOL && protocol <= MAX_PROTOCOL;
    }

    int size() {
        return states.size();
    }

    int airId() {
        return airId;
    }

    /** Состояние блока по runtime id старой палитры. */
    Object stateAt(int runtimeId) {
        return runtimeId >= 0 && runtimeId < states.size() ? states.get(runtimeId) : null;
    }

    /**
     * Runtime id блока в старой палитре: точное совпадение состояния, иначе блок
     * с тем же именем, иначе воздух. {@code state} — NbtMap из Geyser или узел
     * из {@link #parse(InputStream)}; работает через обычный {@link Map}.
     */
    int remap(Object state) {
        if (!(state instanceof Map<?, ?> map)) {
            return airId;
        }
        Object name = map.get("name");
        Integer exact = byState.get(keyOf(map));
        if (exact != null) {
            return exact;
        }
        if (name != null) {
            Integer sameName = byName.get(String.valueOf(name));
            if (sameName != null) {
                return sameName;
            }
        }
        return airId;
    }

    /** Канонический ключ состояния: имя блока + отсортированные свойства. */
    static String keyOf(Map<?, ?> state) {
        StringBuilder sb = new StringBuilder(String.valueOf(state.get("name")));
        sb.append('|');
        Object props = state.get("states");
        if (props instanceof Map<?, ?> map && !map.isEmpty()) {
            Map<String, String> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), canonicalValue(e.getValue()));
            }
            boolean first = true;
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
        }
        return sb.toString();
    }

    /** Числа разных типов (byte/short/int/long) считаем одним значением. */
    private static String canonicalValue(Object value) {
        if (value instanceof Number n) {
            return "n" + n.longValue();
        }
        if (value instanceof Boolean b) {
            return "n" + (b ? 1 : 0);
        }
        return "s" + value;
    }

    /** Палитра из ресурсов расширения; {@code null}, если файла нет или он битый. */
    static LegacyBlockPalette load(ClassLoader cl) {
        try (InputStream raw = LegacyBlockPalette.class.getResourceAsStream(RESOURCE)) {
            return raw == null ? null : fromStream(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    static LegacyBlockPalette fromStream(InputStream raw) {
        try {
            Object root = readNbt(new DataInputStream(new GZIPInputStream(raw)));
            if (!(root instanceof Map<?, ?> rootMap)) {
                return null;
            }
            Object blocks = rootMap.get("blocks");
            if (!(blocks instanceof List<?> list)) {
                return null;
            }
            Map<String, Integer> byState = new HashMap<>(list.size() * 2);
            Map<String, Integer> byName = new HashMap<>(list.size());
            List<Object> states = new ArrayList<>(list.size());
            int airId = -1;
            for (int i = 0; i < list.size(); i++) {
                if (!(list.get(i) instanceof Map<?, ?> entry)) {
                    states.add(null);
                    continue;
                }
                // Служебные теги Geyser при построении палитры отбрасывает, states — оставляет.
                Map<String, Object> trimmed = new HashMap<>(3);
                trimmed.put("name", String.valueOf(entry.get("name")));
                Object props = entry.get("states");
                trimmed.put("states", props instanceof Map<?, ?> ? props : Map.of());
                states.add(trimmed);
                String key = keyOf(trimmed);
                byState.put(key, i);
                String name = String.valueOf(trimmed.get("name"));
                if (((Map<?, ?>) trimmed.get("states")).isEmpty()) {
                    byName.putIfAbsent(name, i);
                    if (airId < 0 && "minecraft:air".equals(name)) {
                        airId = i;
                    }
                }
            }
            if (airId < 0) {
                airId = 0;
            }
            return new LegacyBlockPalette(byState, byName, states, airId);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------- NBT

    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;

    /** Читает NBT-тег (корень палитры — compound с ключом {@code blocks}). */
    static Object readNbt(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        if (type == TAG_END) {
            return null;
        }
        readUtf(in); // имя корневого тега
        return readPayload(in, type);
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case TAG_BYTE -> {
                return in.readByte();
            }
            case TAG_SHORT -> {
                return in.readShort();
            }
            case TAG_INT -> {
                return in.readInt();
            }
            case TAG_LONG -> {
                return in.readLong();
            }
            case TAG_FLOAT -> {
                return in.readFloat();
            }
            case TAG_DOUBLE -> {
                return in.readDouble();
            }
            case TAG_BYTE_ARRAY -> {
                byte[] bytes = new byte[in.readInt()];
                in.readFully(bytes);
                return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            }
            case TAG_STRING -> {
                return readUtf(in);
            }
            case TAG_LIST -> {
                int elementType = in.readUnsignedByte();
                int size = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, size));
                for (int i = 0; i < size; i++) {
                    list.add(readPayload(in, elementType));
                }
                return list;
            }
            case TAG_COMPOUND -> {
                Map<String, Object> map = new HashMap<>();
                while (true) {
                    int childType = in.readUnsignedByte();
                    if (childType == TAG_END) {
                        return map;
                    }
                    String name = readUtf(in);
                    map.put(name, readPayload(in, childType));
                }
            }
            case TAG_INT_ARRAY -> {
                int[] ints = new int[in.readInt()];
                for (int i = 0; i < ints.length; i++) {
                    ints[i] = in.readInt();
                }
                return ints;
            }
            case TAG_LONG_ARRAY -> {
                long[] longs = new long[in.readInt()];
                for (int i = 0; i < longs.length; i++) {
                    longs[i] = in.readLong();
                }
                return longs;
            }
            default -> throw new EOFException("неизвестный NBT-тег: " + type);
        }
    }

    /** UTF-8 строка с 2-байтовой длиной (Java modified UTF-8 здесь не нужна). */
    private static String readUtf(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------- служебное для Geyser

    /** Класс GeyserBedrockBlock и его конструктор (int runtimeId, NbtMap state). */
    static Constructor<?> blockConstructor(ClassLoader cl) {
        try {
            Class<?> blockClass = Class.forName("org.geysermc.geyser.registry.type.GeyserBedrockBlock", true, cl);
            Class<?> nbtMap = Class.forName("org.cloudburstmc.nbt.NbtMap", false, cl);
            Constructor<?> ctor = blockClass.getConstructor(int.class, nbtMap);
            ctor.setAccessible(true);
            return ctor;
        } catch (Throwable t) {
            return null;
        }
    }

    static Field field(Class<?> owner, String name) {
        Class<?> type = owner;
        while (type != null && type != Object.class) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    static Method method(Object target, String name) {
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }
}
