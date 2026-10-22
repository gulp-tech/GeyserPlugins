package ru.voidpe.geyser.versions;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPreInitializeEvent;
import org.geysermc.geyser.api.extension.Extension;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * VoidVersionsExtension — расширение диапазона версий Bedrock для Geyser.
 *
 * <p>Стоковый Geyser принимает только несколько свежих версий Bedrock
 * (на момент написания — 26.30–26.52). Клиенты постарше получают отказ
 * «Unsupported version» ещё на рукопожатии, хотя библиотека протоколов
 * cloudburst, на которой построен Geyser, содержит кодеки всех версий
 * Bedrock вплоть до 1.7.</p>
 *
 * <p>Расширение при инициализации Geyser (до приёма подключений) находит
 * в classpath кодеки версий от 1.20 и новее, прогоняет их через штатный
 * {@code CodecProcessor.processCodec} Geyser (те же «санитарные» правки
 * сериализаторов, что и у родных версий) и регистрирует в списках
 * {@code GameProtocol}: SUPPORTED_BEDROCK_CODECS, SUPPORTED_BEDROCK_PROTOCOLS
 * и SUPPORTED_BEDROCK_VERSIONS. Единственная проверка версии в Geyser —
 * {@code UpstreamPacketHandler.setCorrectCodec()} — после этого пропускает
 * такие клиенты, и сессия дальше работает на кодеке своей версии.</p>
 *
 * <p>Обращение к ядру — только рефлексией: расширение компилируется против
 * одного Geyser API, а ставится на любой актуальный Geyser. Любая ошибка
 * патча не роняет Geyser: расширение просто не добавит версии и напишет
 * в лог, что именно не получилось.</p>
 */
public final class VoidVersionsExtension implements Extension {

    private static final String GAME_PROTOCOL = "org.geysermc.geyser.network.bedrock.GameProtocol";
    private static final String CODEC_PROCESSOR = "org.geysermc.geyser.network.bedrock.CodecProcessor";
    private static final String MINECRAFT_VERSION_IMPL = "org.geysermc.geyser.impl.MinecraftVersionImpl";
    private static final String CODEC_CLASS_NAME = "org.cloudburstmc.protocol.bedrock.codec.v%d.Bedrock_v%d";

    private volatile boolean patched;

    @Subscribe
    public void onPreInitialize(GeyserPreInitializeEvent event) {
        patchProtocols();
    }

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        // Итоговый диапазон — прямо из GameProtocol, как доказательство патча.
        try {
            Class<?> gameProtocol = Class.forName(GAME_PROTOCOL, false,
                    VoidVersionsExtension.class.getClassLoader());
            Method all = gameProtocol.getMethod("getAllSupportedBedrockVersions");
            logger().info("VoidVersions: поддерживаемые версии Bedrock сейчас: " + all.invoke(null));
        } catch (Throwable t) {
            logger().warning("VoidVersions: не удалось прочитать список версий GameProtocol: " + describe(t));
        }
    }

    // ------------------------------------------------------------ конфиг

    private record Config(boolean enabled, String minVersion, int scanFromProtocol,
                          int scanToProtocol, Set<Integer> disabledProtocols, boolean verbose) {

        static Config load(VoidVersionsExtension ext) {
            Config defaults = new Config(true, "1.20.0", 560, 2500, Set.of(), false);
            try {
                Path folder = ext.dataFolder();
                Files.createDirectories(folder);
                Path file = folder.resolve("config.properties");
                if (!Files.exists(file)) {
                    try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                        w.write("# VoidVersionsExtension — расширение диапазона версий Bedrock\n");
                        w.write("# enabled=false — полностью выключить расширение.\n");
                        w.write("enabled=true\n");
                        w.write("# Самая старая версия Minecraft, которую добавляем (клиенты старше отрезаются).\n");
                        w.write("min-version=1.20.0\n");
                        w.write("# Диапазон номеров протоколов для поиска кодеков в библиотеке cloudburst.\n");
                        w.write("scan-from-protocol=560\n");
                        w.write("scan-to-protocol=2500\n");
                        w.write("# Номера протоколов, которые НЕ добавлять (через запятую), если какой-то клиент ломается.\n");
                        w.write("disabled-protocols=\n");
                        w.write("# Подробный лог по каждой найденной версии.\n");
                        w.write("verbose=false\n");
                    }
                }
                Properties props = new Properties();
                try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    props.load(r);
                }
                Set<Integer> disabled = new HashSet<>();
                String rawDisabled = props.getProperty("disabled-protocols", "");
                if (rawDisabled != null) {
                    for (String part : rawDisabled.split(",")) {
                        part = part.trim();
                        if (!part.isEmpty()) {
                            try {
                                disabled.add(Integer.parseInt(part));
                            } catch (NumberFormatException ignored) {
                                // битое значение в конфиге — просто пропускаем
                            }
                        }
                    }
                }
                return new Config(
                        Boolean.parseBoolean(props.getProperty("enabled", "true").trim()),
                        props.getProperty("min-version", "1.20.0").trim(),
                        parseInt(props.getProperty("scan-from-protocol"), defaults.scanFromProtocol()),
                        parseInt(props.getProperty("scan-to-protocol"), defaults.scanToProtocol()),
                        Set.copyOf(disabled),
                        Boolean.parseBoolean(props.getProperty("verbose", "false").trim()));
            } catch (IOException | RuntimeException e) {
                ext.logger().warning("VoidVersions: конфиг не прочитан (" + describe(e)
                        + "), использую значения по умолчанию.");
                return defaults;
            }
        }

        private static int parseInt(String raw, int fallback) {
            if (raw == null) {
                return fallback;
            }
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    // ------------------------------------------------------------ патч

    private synchronized void patchProtocols() {
        if (patched) {
            return;
        }
        patched = true;
        Config config = Config.load(this);
        if (!config.enabled()) {
            logger().info("VoidVersions: выключено в config.properties — версии не трогаю.");
            return;
        }
        long started = System.currentTimeMillis();
        try {
            ClassLoader cl = VoidVersionsExtension.class.getClassLoader();
            Class<?> gameProtocol = Class.forName(GAME_PROTOCOL, true, cl);

            @SuppressWarnings("unchecked")
            List<Object> codecs = (List<Object>) accessibleField(gameProtocol, "SUPPORTED_BEDROCK_CODECS").get(null);
            Object protocols = gameProtocol.getField("SUPPORTED_BEDROCK_PROTOCOLS").get(null);
            @SuppressWarnings("unchecked")
            List<Object> versions = (List<Object>) gameProtocol.getField("SUPPORTED_BEDROCK_VERSIONS").get(null);
            if (codecs.isEmpty()) {
                logger().error("VoidVersions: список кодеков GameProtocol пуст — патч невозможен.");
                return;
            }

            Method getProtocol = codecs.get(0).getClass().getMethod("getProtocolVersion");
            Method getMcVersion = codecs.get(0).getClass().getMethod("getMinecraftVersion");
            Method protocolVersionOf = versionProtocolMethod(versions);
            Constructor<?> versionCtor = minecraftVersionConstructor(cl);

            Set<Integer> existing = new HashSet<>();
            for (Object codec : codecs) {
                existing.add((Integer) getProtocol.invoke(codec));
            }

            Method processCodec = null;
            try {
                Class<?> processor = Class.forName(CODEC_PROCESSOR, true, cl);
                processCodec = processor.getDeclaredMethod("processCodec", bedrockCodecClass(codecs.get(0)));
                processCodec.setAccessible(true);
            } catch (Throwable t) {
                logger().warning("VoidVersions: CodecProcessor.processCodec не найден (" + describe(t)
                        + ") — кодеки будут зарегистрированы без санитарной обработки.");
            }

            int nativeCount = codecs.size();
            List<String> added = new ArrayList<>();
            int scanned = 0;
            int skippedOld = 0;
            for (int protocol = config.scanFromProtocol(); protocol <= config.scanToProtocol(); protocol++) {
                if (existing.contains(protocol) || config.disabledProtocols().contains(protocol)) {
                    continue;
                }
                Class<?> codecClass;
                try {
                    codecClass = Class.forName(String.format(Locale.ROOT, CODEC_CLASS_NAME, protocol, protocol),
                            false, cl);
                } catch (ClassNotFoundException | LinkageError e) {
                    continue; // такого протокола в библиотеке нет — норма при сканировании
                }
                scanned++;
                final Object rawCodec;
                try {
                    Field codecField = codecClass.getField("CODEC");
                    if (!Modifier.isStatic(codecField.getModifiers())) {
                        continue;
                    }
                    rawCodec = codecField.get(null);
                    if (rawCodec == null) {
                        continue;
                    }
                } catch (Throwable t) {
                    if (config.verbose()) {
                        logger().warning("VoidVersions: кодек протокола " + protocol + " без поля CODEC: " + describe(t));
                    }
                    continue;
                }

                try {
                    int codecProtocol = (Integer) getProtocol.invoke(rawCodec);
                    if (codecProtocol != protocol) {
                        continue; // класс-обманка/алиас — не наш случай
                    }
                    String mcVersion = String.valueOf(getMcVersion.invoke(rawCodec));
                    if (compareVersions(mcVersion, config.minVersion()) < 0) {
                        skippedOld++;
                        continue;
                    }

                    Object codec = rawCodec;
                    boolean processed = false;
                    if (processCodec != null) {
                        try {
                            codec = processCodec.invoke(null, rawCodec);
                            processed = true;
                        } catch (Throwable t) {
                            logger().warning("VoidVersions: processCodec не справился с версией " + mcVersion
                                    + " (протокол " + protocol + "): " + describe(unwrap(t))
                                    + " — регистрирую кодек как есть.");
                        }
                    }

                    int codecIndex = insertionIndex(codecs, getProtocol, protocol);
                    codecs.add(codecIndex, codec);
                    addProtocol(protocols, codecIndex, protocol);

                    if (versionCtor != null && protocolVersionOf != null) {
                        try {
                            Object mv = versionCtor.newInstance(mcVersion, protocol);
                            versions.add(insertionIndex(versions, protocolVersionOf, protocol), mv);
                        } catch (Throwable t) {
                            if (config.verbose()) {
                                logger().warning("VoidVersions: не добавил " + mcVersion
                                        + " в список версий GameProtocol: " + describe(unwrap(t)));
                            }
                        }
                    }

                    existing.add(protocol);
                    added.add(mcVersion + " (протокол " + protocol + (processed ? "" : ", без processCodec") + ")");
                    if (config.verbose()) {
                        logger().info("VoidVersions: + " + mcVersion + " (протокол " + protocol + ")");
                    }
                } catch (Throwable t) {
                    // Один битый кодек не должен хоронить остальные версии.
                    if (config.verbose()) {
                        logger().warning("VoidVersions: протокол " + protocol + " пропущен: " + describe(unwrap(t)));
                    }
                }
            }

            logger().info("VoidVersions: готово за " + (System.currentTimeMillis() - started) + " мс. "
                    + "Родных кодеков: " + nativeCount + ", найдено в библиотеке: " + scanned
                    + ", старше " + config.minVersion() + " пропущено: " + skippedOld
                    + ", добавлено кодеков: " + added.size() + ".");
            if (!added.isEmpty()) {
                logger().info("VoidVersions: теперь принимаются клиенты: " + String.join(", ", added));
            } else {
                logger().warning("VoidVersions: ни одного дополнительного кодека не зарегистрировано. "
                        + "Либо в этой сборке Geyser библиотека cloudburst обрезана, "
                        + "либо диапазон сканирования в config.properties не покрывает нужные протоколы.");
            }
        } catch (Throwable t) {
            logger().error("VoidVersions: патч версий не удался: " + describe(unwrap(t))
                    + " — Geyser продолжит работу со стандартным диапазоном версий.");
        }
    }

    /** Класс BedrockCodec (тип поля CODEC) — нужен, чтобы найти processCodec по сигнатуре. */
    private static Class<?> bedrockCodecClass(Object codec) {
        Class<?> type = codec.getClass();
        while (type != null && !"BedrockCodec".equals(type.getSimpleName())
                && type.getSuperclass() != null && type.getSuperclass() != Object.class) {
            type = type.getSuperclass();
        }
        return type != null ? type : codec.getClass();
    }

    private static Method versionProtocolMethod(List<Object> versions) {
        if (versions.isEmpty()) {
            return null;
        }
        try {
            return versions.get(0).getClass().getMethod("protocolVersion");
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Constructor<?> minecraftVersionConstructor(ClassLoader cl) {
        try {
            Class<?> impl = Class.forName(MINECRAFT_VERSION_IMPL, false, cl);
            Constructor<?> ctor = impl.getDeclaredConstructor(String.class, int.class);
            ctor.setAccessible(true);
            return ctor;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field accessibleField(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** Индекс вставки, чтобы список остался отсортирован по протоколу (как требует GameProtocol). */
    private static int insertionIndex(List<Object> list, Method protocolOf, int protocol) {
        for (int i = 0; i < list.size(); i++) {
            try {
                int existing = (Integer) protocolOf.invoke(list.get(i));
                if (existing > protocol) {
                    return i;
                }
            } catch (ReflectiveOperationException ignored) {
                return list.size();
            }
        }
        return list.size();
    }

    /** fastutil IntList: сначала пробуем вставку по индексу, иначе — в конец. */
    private static void addProtocol(Object intList, int index, int protocol) {
        try {
            Method indexed = intList.getClass().getMethod("add", int.class, int.class);
            indexed.invoke(intList, index, protocol);
            return;
        } catch (Throwable ignored) {
            // метод мог называться иначе — падаем на append ниже
        }
        try {
            intList.getClass().getMethod("add", int.class).invoke(intList, protocol);
        } catch (Throwable ignored) {
            // Список протоколов нужен только для дампов; молча пропускаем.
        }
    }

    // ------------------------------------------------------------ версии

    /** Сравнение версий вида "1.20.0" / "26.52" по числам. */
    static int compareVersions(String a, String b) {
        int[] left = parse(a);
        int[] right = parse(b);
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int l = i < left.length ? left[i] : 0;
            int r = i < right.length ? right[i] : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private static int[] parse(String version) {
        if (version == null) {
            return new int[0];
        }
        String[] parts = version.trim().split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            StringBuilder digits = new StringBuilder();
            for (char c : parts[i].toCharArray()) {
                if (Character.isDigit(c)) {
                    digits.append(c);
                } else {
                    break;
                }
            }
            out[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits.toString());
        }
        return out;
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
