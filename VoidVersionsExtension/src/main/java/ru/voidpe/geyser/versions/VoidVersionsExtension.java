package ru.voidpe.geyser.versions;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostReloadEvent;
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
 * в classpath кодеки версий от 1.20 и новее, прогоняет их через собственную
 * санитизацию (наборы «пакет → сериализатор» из {@code CodecProcessor}, но
 * без версионных подмен — см. {@link Sanitizer}) и регистрирует в списках
 * {@code GameProtocol}: SUPPORTED_BEDROCK_CODECS, SUPPORTED_BEDROCK_PROTOCOLS
 * и SUPPORTED_BEDROCK_VERSIONS. Единственная проверка версии в Geyser —
 * {@code UpstreamPacketHandler.setCorrectCodec()} — после этого пропускает
 * такие клиенты, и сессия дальше работает на кодеке своей версии.
 * Дополнительно (иначе вход умирал на LoginPacket) в реестры
 * {@code BlockRegistries.BLOCKS}, {@code Registries.ITEMS} и
 * {@code Registries.TAGS} для старых протоколов подставляются маппинги
 * младшей родной версии — см. {@link #patchRegistries()}.</p>
 *
 * <p>Обращение к ядру — только рефлексией: расширение компилируется против
 * одного Geyser API, а ставится на любой актуальный Geyser. Любая ошибка
 * патча не роняет Geyser: расширение просто не добавит версии и напишет
 * в лог, что именно не получилось.</p>
 */
public final class VoidVersionsExtension implements Extension {

    private static final String GAME_PROTOCOL = "org.geysermc.geyser.network.bedrock.GameProtocol";
    private static final String CODEC_PROCESSOR = "org.geysermc.geyser.network.bedrock.CodecProcessor";
    private static final String VERSION_COMMAND = "org.geysermc.geyser.command.defaults.VersionCommand";
    private static final String MINECRAFT_VERSION_IMPL = "org.geysermc.geyser.impl.MinecraftVersionImpl";
    private static final String CODEC_CLASS_NAME = "org.cloudburstmc.protocol.bedrock.codec.v%d.Bedrock_v%d";
    private static final String PACKET_PREFIX = "org.cloudburstmc.protocol.bedrock.packet.";

    /**
     * Serverbound-пакеты, которые Geyser считает незаконными (EDU, отладка,
     * редактор, подклиент) — сериализатор бросает исключение. Список 1-в-1
     * с ветками {@code ILLEGAL_SERIALIZER} в {@code CodecProcessor.processCodec}.
     */
    private static final String[] ILLEGAL_PACKETS = {
            "PhotoTransferPacket", "LabTablePacket", "CodeBuilderSourcePacket", "CreatePhotoPacket",
            "NpcRequestPacket", "PhotoInfoRequestPacket", "ClientCheatAbilityPacket", "CraftingEventPacket",
            "ClientCacheBlobStatusPacket", "SubClientLoginPacket", "SubChunkRequestPacket", "GameTestRequestPacket",
            "DebugInfoPacket", "EditorNetworkPacket", "ScriptMessagePacket"
    };

    /** Пакеты, которые Geyser молча игнорирует ({@code IGNORED_SERIALIZER}). */
    private static final String[] IGNORED_PACKETS = {
            "ClientToServerHandshakePacket", "EntityFallPacket", "MapCreateLockedCopyPacket", "MapInfoRequestPacket",
            "SettingsCommandPacket", "AnvilDamagePacket", "ClientCacheStatusPacket", "SimpleEventPacket",
            "MultiplayerSettingsPacket", "EmoteListPacket"
    };

    /** Игнорируются, только если НЕ включён {@code Geyser.ReceiptPackets} (как в Geyser). */
    private static final String[] RECEIPT_PACKETS = {
            "RefreshEntitlementsPacket", "PurchaseReceiptPacket"
    };

    /** Глушится при выключенных квитанциях, но только с протокола 944 (26.10). */
    private static final String PARTY_PACKET = "PartyChangedPacket";

    /** Верхняя граница перебора номеров пакетов (реальные — до ~400). */
    private static final int MAX_PACKET_ID = 0x7FF;

    private volatile boolean patched;
    /** Последний прочитанный конфиг — нужен выводу /geyser version и логам. */
    private volatile Config config;

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
        updateVersionCommandOutput();
        patchRegistries();
    }

    /**
     * Команда {@code /geyser version} показывает диапазон версий из поля
     * {@code SUPPORTED_BEDROCK_RANGE} класса VersionCommand. Оно считается
     * один раз в статическом инициализаторе (и только как «первая - последняя»),
     * поэтому переписываем его уже после нашего патча. Формат выбирается
     * в конфиге ({@code version-command-format}): {@code full} — весь список
     * версий (видно, что патч сработал), {@code range} — «первая - последняя»,
     * как показывает стоковый Geyser. Поле final, пишем через Unsafe; любая
     * ошибка здесь некритична и просто логируется.
     */
    private void updateVersionCommandOutput() {
        if (config != null && !config.enabled()) {
            return; // расширение выключено — не трогаем даже вывод команды
        }
        try {
            ClassLoader cl = VoidVersionsExtension.class.getClassLoader();
            Class<?> gameProtocol = Class.forName(GAME_PROTOCOL, false, cl);
            String shown = String.valueOf(gameProtocol.getMethod("getAllSupportedBedrockVersions").invoke(null));
            if (config != null && config.versionRangeOnly()) {
                String range = versionRange(cl);
                if (!range.isEmpty()) {
                    shown = range;
                }
            }

            // initialize=true: статический блок VersionCommand должен отработать
            // ДО записи, иначе он потом сам перезапишет поле своим диапазоном.
            Class<?> versionCommand = Class.forName(VERSION_COMMAND, true, cl);
            Field range = versionCommand.getDeclaredField("SUPPORTED_BEDROCK_RANGE");
            range.setAccessible(true);
            String before = String.valueOf(range.get(null));
            try {
                range.set(null, shown);
            } catch (IllegalAccessException | RuntimeException finalField) {
                writeStaticField(range, shown);
            }
            // Самопроверка: перечитываем поле — команда покажет именно это значение.
            String after = String.valueOf(range.get(null));
            Field codecsField = gameProtocol.getDeclaredField("SUPPORTED_BEDROCK_CODECS");
            codecsField.setAccessible(true);
            int codecCount = ((List<?>) codecsField.get(null)).size();
            if (shown.equals(after)) {
                logger().info("VoidVersions: /geyser version: было '" + before
                        + "', теперь команда покажет: " + shown
                        + ". Всего кодеков в GameProtocol: " + codecCount + ".");
            } else {
                logger().warning("VoidVersions: запись в VersionCommand не применилась: "
                        + "поле как было '" + before + "', так и осталось '" + after + "'.");
            }
        } catch (Throwable t) {
            logger().warning("VoidVersions: не удалось обновить вывод /geyser version: " + describe(unwrap(t))
                    + " — команда покажет диапазон Geyser по умолчанию, на сам вход старых клиентов это не влияет.");
        }
    }

    // ------------------------------------------------- патч реестров

    /**
     * Одних кодеков для входа старого клиента мало: сразу после LoginPacket
     * Geyser делает {@code BlockRegistries.BLOCKS.forVersion(protocol)} и
     * {@code Registries.ITEMS.forVersion(protocol)}, а в этих реестрах ключи
     * есть только под родные версии. Для протокола ниже минимального
     * {@code forVersion} бросает «No appropriate value for version» — и сессия
     * умирает с «An internal error occurred!» ещё до входа в мир.
     *
     * <p>Поэтому для каждого добавленного нами протокола регистрируем в
     * BLOCKS / ITEMS / TAGS те же маппинги, что у самой старой родной версии
     * Geyser. Старый клиент получит палитру блоков и предметы «как у 26.30»:
     * блоки и предметы новее его версии могут отображаться не тем, чем
     * являются, но вход и обычная игра работают.</p>
     */
    private void patchRegistries() {
        try {
            ClassLoader cl = VoidVersionsExtension.class.getClassLoader();
            List<Integer> protocols = supportedProtocols(cl);
            if (protocols.isEmpty()) {
                logger().warning("VoidVersions: список протоколов GameProtocol пуст — реестры не патчу.");
                return;
            }
            patchOneRegistry(cl, "org.geysermc.geyser.registry.BlockRegistries", "BLOCKS", protocols);
            patchOneRegistry(cl, "org.geysermc.geyser.registry.Registries", "ITEMS", protocols);
            patchOneRegistry(cl, "org.geysermc.geyser.registry.Registries", "TAGS", protocols);
            if (config == null || config.legacyPalette()) {
                applyLegacyPalettes(cl, protocols);
            }
        } catch (Throwable t) {
            logger().warning("VoidVersions: патч реестров не удался: " + describe(unwrap(t))
                    + " — старые клиенты, скорее всего, не смогут зайти (internal error при входе).");
        }
    }

    /** Все протоколы из GameProtocol.SUPPORTED_BEDROCK_PROTOCOLS (fastutil IntList — читаем рефлексией). */
    private static List<Integer> supportedProtocols(ClassLoader cl) throws Exception {
        Class<?> gameProtocol = Class.forName(GAME_PROTOCOL, false, cl);
        Object list = gameProtocol.getField("SUPPORTED_BEDROCK_PROTOCOLS").get(null);
        List<Integer> out = new ArrayList<>();
        try {
            int size = (Integer) list.getClass().getMethod("size").invoke(list);
            Method getInt = list.getClass().getMethod("getInt", int.class);
            for (int i = 0; i < size; i++) {
                out.add((Integer) getInt.invoke(list, i));
            }
        } catch (Throwable t) {
            if (list instanceof Iterable<?> iterable) {
                for (Object o : iterable) {
                    if (o instanceof Number n) {
                        out.add(n.intValue());
                    }
                }
            }
        }
        return out;
    }

    /** Дописывает в один VersionedRegistry старые протоколы со значением младшего родного ключа. */
    private void patchOneRegistry(ClassLoader cl, String className, String fieldName, List<Integer> protocols) {
        String name = className.substring(className.lastIndexOf('.') + 1) + "." + fieldName;
        try {
            Object registry = Class.forName(className, false, cl).getField(fieldName).get(null);
            @SuppressWarnings("unchecked")
            java.util.Map<Object, Object> map =
                    (java.util.Map<Object, Object>) registry.getClass().getMethod("get").invoke(registry);
            if (map == null || map.isEmpty()) {
                logger().warning("VoidVersions: реестр " + name + " пуст — пропускаю (патч применён слишком рано?).");
                return;
            }
            int minKey = Integer.MAX_VALUE;
            Object baseValue = null;
            for (java.util.Map.Entry<Object, Object> e : map.entrySet()) {
                int key = ((Number) e.getKey()).intValue();
                if (key < minKey) {
                    minKey = key;
                    baseValue = e.getValue();
                }
            }
            if (baseValue == null) {
                logger().warning("VoidVersions: реестр " + name + ": не нашёл базовых маппингов.");
                return;
            }
            Method register = registry.getClass().getMethod("register", Object.class, Object.class);
            int added = 0;
            for (int protocol : protocols) {
                if (protocol < minKey && !map.containsKey(protocol)) {
                    register.invoke(registry, protocol, baseValue);
                    added++;
                }
            }
            if (added > 0) {
                logger().info("VoidVersions: реестр " + name + ": добавлено старых протоколов: " + added
                        + " (маппинги — от младшей родной версии, протокол " + minKey + ").");
            } else {
                // Повторный патч (например, после /geyser reload) — всё уже на месте.
                logger().debug("VoidVersions: реестр " + name + ": старые протоколы уже зарегистрированы.");
            }
        } catch (Throwable t) {
            logger().warning("VoidVersions: реестр " + name + " не пропатчен: " + describe(unwrap(t)));
        }
    }

    /** Запись в static final поле через sun.misc.Unsafe. */
    private static void writeStaticField(Field field, Object value) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Object base = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, field);
        long offset = (Long) unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(unsafe, field);
        unsafeClass.getMethod("putObject", Object.class, long.class, Object.class)
                .invoke(unsafe, base, offset, value);
    }

    // ------------------------------------------------------------ конфиг

    /**
     * Шаблон {@code config.properties}. Он же используется, чтобы дописать
     * в существующий конфиг ключи, появившиеся в новой версии расширения.
     */
    private static final List<String> CONFIG_TEMPLATE = List.of(
            "# VoidVersionsExtension — расширение диапазона версий Bedrock",
            "# enabled=false — полностью выключить расширение.",
            "enabled=true",
            "# Самая старая версия Minecraft, которую добавляем (клиенты старше отрезаются).",
            "min-version=1.20.0",
            "# Диапазон номеров протоколов для поиска кодеков в библиотеке cloudburst.",
            "scan-from-protocol=560",
            "scan-to-protocol=2500",
            "# Номера протоколов, которые НЕ добавлять (через запятую), если какой-то клиент ломается.",
            "disabled-protocols=",
            "# Подробный лог по каждой найденной версии.",
            "verbose=false",
            "# Санитизация кодеков старых версий: глушилки Geyser (ILLEGAL/IGNORED)",
            "# для EDU/отладочных/подозрительных пакетов. false — ставить кодеки как есть.",
            "sanitize=true",
            "# Как /geyser version показывает Bedrock-версии: full — весь список,",
            "# range — только «первая - последняя» (как у стокового Geyser).",
            "version-command-format=full",
            "# Пересчитывать маппинги блоков по палитре старой версии (иначе мир у старых",
            "# клиентов рисуется «пластинами»).",
            "legacy-palette=true",
            "# Регистрировать только те старые протоколы, для которых есть палитра блоков.",
            "# false — пускать всех (мир у непокрытых версий будет сломан).",
            "legacy-palette-only=true"
    );

    private record Config(boolean enabled, String minVersion, int scanFromProtocol,
                          int scanToProtocol, Set<Integer> disabledProtocols, boolean verbose,
                          boolean sanitize, boolean versionRangeOnly,
                          boolean legacyPalette, boolean legacyPaletteOnly) {

        static Config load(VoidVersionsExtension ext) {
            Config defaults = new Config(true, "1.20.0", 560, 2500, Set.of(), false, true, false, true, true);
            try {
                Path folder = ext.dataFolder();
                Files.createDirectories(folder);
                Path file = folder.resolve("config.properties");
                boolean created = !Files.exists(file);
                if (created) {
                    Files.write(file, CONFIG_TEMPLATE, StandardCharsets.UTF_8);
                }
                Properties props = new Properties();
                try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    props.load(r);
                }
                if (!created) {
                    // Файл от прошлой версии: новые ключи дописываем, чтобы их было видно.
                    appendMissingKeys(ext, file, props);
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
                        Boolean.parseBoolean(props.getProperty("verbose", "false").trim()),
                        Boolean.parseBoolean(props.getProperty("sanitize", "true").trim()),
                        "range".equalsIgnoreCase(props.getProperty("version-command-format", "full").trim()),
                        Boolean.parseBoolean(props.getProperty("legacy-palette", "true").trim()),
                        Boolean.parseBoolean(props.getProperty("legacy-palette-only", "true").trim()));
            } catch (IOException | RuntimeException e) {
                ext.logger().warning("VoidVersions: конфиг не прочитан (" + describe(e)
                        + "), использую значения по умолчанию.");
                return defaults;
            }
        }

        /** Дописывает в существующий конфиг ключи, которых в нём ещё нет. */
        private static void appendMissingKeys(VoidVersionsExtension ext, Path file, Properties props) {
            List<String> missing = new ArrayList<>();
            List<String> comments = new ArrayList<>();
            for (String line : CONFIG_TEMPLATE) {
                if (line.startsWith("#")) {
                    comments.add(line);
                    continue;
                }
                String key = line.substring(0, line.indexOf('='));
                if (!props.containsKey(key)) {
                    missing.add("");
                    missing.addAll(comments);
                    missing.add(line);
                }
                comments.clear();
            }
            if (missing.isEmpty()) {
                return;
            }
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND)) {
                w.write("\n# --- добавлено в новой версии расширения ---\n");
                for (String line : missing) {
                    w.write(line + "\n");
                }
            } catch (IOException e) {
                ext.logger().warning("VoidVersions: не удалось дописать новые ключи в config.properties: "
                        + describe(e));
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
        this.config = config;
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

            Sanitizer sanitizer = Sanitizer.create(this, cl, codecs.get(0), config.sanitize());
            if (sanitizer == null && config.sanitize()) {
                logger().warning("VoidVersions: санитайзер CodecProcessor недоступен — "
                        + "кодеки будут зарегистрированы без санитарной обработки.");
            }
            // Самый свежий кодек — эталон «какие пакеты вообще бывают»: всё, что
            // есть у него, но отсутствует в старом кодеке, Geyser может попытаться
            // отправить старому клиенту (так падало на VoxelShapesPacket: он с
            // протокола 924, а Geyser шлёт его при входе безусловно).
            Object referenceCodec = codecs.get(codecs.size() - 1);

            // Библиотека cloudburst может опережать сам Geyser: кодек новее самой
            // свежей родной версии не имеет в core ни переводчиков, ни палитр,
            // поэтому такой протокол регистрировать нельзя (клиент зашёл бы, но
            // игра сломалось бы непредсказуемо).
            int newestNative = newestNativeProtocol(cl);

            int nativeCount = codecs.size();
            List<String> added = new ArrayList<>();
            int scanned = 0;
            int skippedOld = 0;
            int skippedNewer = 0;
            int skippedNoPalette = 0;
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
                if (protocol > newestNative) {
                    skippedNewer++;
                    continue; // новее самой свежей родной версии этой сборки Geyser
                }
                if (protocol < newestNative && config.legacyPaletteOnly()
                        && !LegacyBlockPalette.covers(protocol)) {
                    // Клиент зашёл бы, но палитры блоков под его версию у нас нет —
                    // мир рисовался бы «пластинами». Лучше честный отказ с просьбой
                    // обновить игру, чем сломанный мир.
                    skippedNoPalette++;
                    continue;
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
                    if (sanitizer != null) {
                        try {
                            codec = sanitizer.apply(rawCodec, protocol, referenceCodec);
                            processed = true;
                        } catch (Throwable t) {
                            logger().warning("VoidVersions: санитизация версии " + mcVersion
                                    + " (протокол " + protocol + ") не удалась: " + describe(unwrap(t))
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
                    added.add(mcVersion + " (протокол " + protocol + (processed ? "" : ", без санитизации") + ")");
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
                    + ", новее этой сборки Geyser (" + newestNative + ") пропущено: " + skippedNewer
                    + ", без палитры блоков пропущено: " + skippedNoPalette
                    + ", добавлено кодеков: " + added.size() + ".");
            if (!added.isEmpty()) {
                logger().info("VoidVersions: теперь принимаются клиенты: " + String.join(", ", added));
            } else if (scanned == 0 && skippedNewer > 0) {
                logger().info("VoidVersions: в библиотеке cloudburst есть только кодеки новее этой сборки Geyser "
                        + "(" + newestNative + ") — они не нужны, добавлять нечего.");
            } else if (scanned == 0) {
                logger().info("VoidVersions: все доступные кодеки уже зарегистрированы в GameProtocol — добавлять нечего.");
            } else {
                logger().warning("VoidVersions: ни одного дополнительного кодека не зарегистрировано. "
                        + "Либо в этой сборке Geyser библиотека cloudburst обрезана, "
                        + "либо диапазон сканирования в config.properties не покрывает нужные протоколы.");
            }
        } catch (Throwable t) {
            logger().error("VoidVersions: патч версий не удался: " + describe(unwrap(t))
                    + " — Geyser продолжит работу со стандартным диапазоном версий.");
        }
        updateVersionCommandOutput();
    }

    @Subscribe
    public void onPostReload(GeyserPostReloadEvent event) {
        // После /geyser reload повторяем патч: он идемпотентен (уже внесённые
        // кодеки пропускаются), а вывод /geyser version обновляем заново.
        patched = false;
        patchProtocols();
        patchRegistries();
    }

    // ------------------------------------------- палитра блоков старой версии

    /**
     * Пересчитывает маппинги блоков для старых протоколов по палитре их версии.
     *
     * <p>Без этого шага клиент получает runtime id блоков из палитры 26.30 и рисует
     * мир «пластинами» — ровно то, что видел игрок 1.21.130 до этой правки. Здесь
     * для каждого протокола, под который есть палитра, собирается НОВЫЙ экземпляр
     * {@code BlockMappings}: тот же набор java-состояний, но runtime id пересчитаны
     * по старой палитре. Исходный объект (палитра 26.30) не меняется — актуальные
     * клиенты продолжают работать как раньше.</p>
     */
    private void applyLegacyPalettes(ClassLoader cl, List<Integer> protocols) {
        try {
            LegacyBlockPalette palette = LegacyBlockPalette.load(cl);
            if (palette == null) {
                logger().warning("VoidVersions: палитра блока старой версии не прочиталась — "
                        + "старые клиенты увидят мир «пластинами».");
                return;
            }
            Constructor<?> blockCtor = LegacyBlockPalette.blockConstructor(cl);
            if (blockCtor == null) {
                logger().warning("VoidVersions: GeyserBedrockBlock не найден — палитру не подменяю.");
                return;
            }
            Object registry = Class.forName("org.geysermc.geyser.registry.BlockRegistries", false, cl)
                    .getField("BLOCKS").get(null);
            @SuppressWarnings("unchecked")
            java.util.Map<Object, Object> map =
                    (java.util.Map<Object, Object>) registry.getClass().getMethod("get").invoke(registry);
            Method register = registry.getClass().getMethod("register", Object.class, Object.class);

            int remapped = 0;
            List<String> done = new ArrayList<>();
            for (int protocol : protocols) {
                if (!LegacyBlockPalette.covers(protocol)) {
                    continue;
                }
                Object original = map.get(protocol);
                if (original == null) {
                    continue;
                }
                try {
                    Object copy = remapMappings(original, palette, blockCtor);
                    if (copy != null) {
                        register.invoke(registry, protocol, copy);
                        remapped++;
                        done.add(String.valueOf(protocol));
                    }
                } catch (Throwable t) {
                    logger().warning("VoidVersions: палитра для протокола " + protocol
                            + " не пересчитана: " + describe(unwrap(t)));
                }
            }
            if (remapped > 0) {
                logger().info("VoidVersions: палитра блоков " + LegacyBlockPalette.MIN_PROTOCOL + "–"
                        + LegacyBlockPalette.MAX_PROTOCOL + " применена к протоколам: "
                        + String.join(", ", done) + " (блоков в палитре: " + palette.size() + ").");
            } else {
                logger().info("VoidVersions: старых протоколов под палитру нет — пересчёт не нужен.");
            }
        } catch (Throwable t) {
            logger().warning("VoidVersions: подмена палитры блоков не удалась: " + describe(unwrap(t)));
        }
    }

    /** Копия BlockMappings с пересчитанными runtime id блоков. */
    private Object remapMappings(Object original, LegacyBlockPalette palette, Constructor<?> blockCtor)
            throws Exception {
        Class<?> type = original.getClass();
        Object copy = allocate(type);
        if (copy == null) {
            return null;
        }
        Object runtimeMapOriginal = LegacyBlockPalette.field(type, "bedrockRuntimeMap") != null
                ? LegacyBlockPalette.field(type, "bedrockRuntimeMap").get(original) : null;
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(original);
            switch (field.getName()) {
                case "javaToBedrockBlocks", "javaToVanillaBedrockBlocks" ->
                        value = remapBlockArray(field.getType(), value, palette, blockCtor);
                case "bedrockRuntimeMap" -> {
                    // Оставляем как есть: массив индексируется runtime id палитры 26.30,
                    // в рантайме Geyser его не использует (только дамп/кастомные блоки),
                    // а собрать его заново нельзя — конструктор блока принимает NbtMap,
                    // а палитра 1.21.110 у нас разобрана в обычные Map.
                }
                case "bedrockAir", "bedrockWater", "bedrockMovingBlock", "commandBlock",
                     "mobSpawnerBlock", "netherPortalBlock" ->
                        value = remapDefinition(value, palette, blockCtor);
                // Всё, что хранит блоки по состоянию/ключу: рамки, структуры, кастомные блоки.
                // Здесь значения — BlockDefinition, их runtime id тоже из палитры 26.30.
                case "stateDefinitionMap", "itemFrames", "structureBlockStates",
                     "customBlockStateDefinitions", "extendedCollisionBoxes" ->
                        value = remapDefinitions(value, palette, blockCtor);
                case "jigsawStates" -> value = remapDefinitionSet(value, palette, blockCtor);
                case "remappedVanillaIds" ->
                        value = remapVanillaIds(value, runtimeMapOriginal, palette, blockCtor);
                default -> {
                    // прочие поля (идентификаторы, наборы, свойства) не зависят от версии
                }
            }
            if (!putField(copy, field, value)) {
                return null;
            }
        }
        return copy;
    }

    /**
     * Новый массив ТОГО ЖЕ компонентного типа, что у поля: записать Object[]
     * в {@code GeyserBedrockBlock[]} нельзя (проверка типов JVM не пропустит).
     */
    private Object remapBlockArray(Class<?> arrayType, Object value, LegacyBlockPalette palette,
                                   Constructor<?> blockCtor) {
        if (!(value instanceof Object[] source)) {
            return value;
        }
        Class<?> component = arrayType.isArray() ? arrayType.getComponentType() : Object.class;
        Object out = java.lang.reflect.Array.newInstance(component, source.length);
        for (int i = 0; i < source.length; i++) {
            java.lang.reflect.Array.set(out, i, remapBlock(source[i], palette, blockCtor));
        }
        return out;
    }

    /** Один блок: то же состояние, но runtime id по старой палитре. */
    private Object remapBlock(Object block, LegacyBlockPalette palette, Constructor<?> blockCtor) {
        if (block == null || !blockCtor.getDeclaringClass().isInstance(block)) {
            return block;
        }
        try {
            Method getState = LegacyBlockPalette.method(block, "getState");
            if (getState == null) {
                return block;
            }
            Object state = getState.invoke(block);
            return blockCtor.newInstance(palette.remap(state), state);
        } catch (Throwable t) {
            return block;
        }
    }

    private Object remapDefinition(Object value, LegacyBlockPalette palette, Constructor<?> blockCtor) {
        return value == null ? null : remapBlock(value, palette, blockCtor);
    }

    /** Map/Int2ObjectMap: пересчитываем значения-блоки, ключи оставляем как есть. */
    private Object remapDefinitions(Object value, LegacyBlockPalette palette, Constructor<?> blockCtor) {
        if (!(value instanceof java.util.Map<?, ?> source)) {
            return value;
        }
        java.util.Map<Object, Object> out = mutableCopy(value, source.size());
        if (out == null) {
            return value;
        }
        for (java.util.Map.Entry<?, ?> e : source.entrySet()) {
            Object remapped = remapBlock(e.getValue(), palette, blockCtor);
            if (remapped != e.getValue()) {
                out.put(e.getKey(), remapped);
            }
        }
        return out;
    }

    /**
     * Копия коллекции того же класса (fastutil-мапы умеют clone()), иначе — LinkedHashMap.
     * Возвращает null, если копию сделать не удалось: тогда поле лучше не трогать.
     */
    @SuppressWarnings("unchecked")
    private java.util.Map<Object, Object> mutableCopy(Object value, int size) {
        try {
            Method clone = value.getClass().getMethod("clone");
            Object copy = clone.invoke(value);
            if (copy instanceof java.util.Map<?, ?>) {
                return (java.util.Map<Object, Object>) copy;
            }
        } catch (Throwable ignored) {
            // не клонируется — пробуем обычную мапу ниже
        }
        return new java.util.LinkedHashMap<>(size);
    }

    private Object remapDefinitionSet(Object value, LegacyBlockPalette palette, Constructor<?> blockCtor) {
        if (!(value instanceof java.util.Set<?> source)) {
            return value;
        }
        java.util.Set<Object> out = new java.util.LinkedHashSet<>(source.size());
        for (Object element : source) {
            out.add(remapBlock(element, palette, blockCtor));
        }
        return out;
    }

    /** Старые номера блоков (для кастомных блоков) — через ту же таблицу. */
    private Object remapVanillaIds(Object value, Object runtimeMapOriginal,
                                   LegacyBlockPalette palette, Constructor<?> blockCtor) {
        if (!(value instanceof int[] ids) || ids.length == 0 || !(runtimeMapOriginal instanceof Object[] oldMap)) {
            return value;
        }
        int[] out = new int[ids.length];
        for (int i = 0; i < ids.length; i++) {
            int id = ids[i];
            if (id < 0 || id >= oldMap.length || oldMap[id] == null) {
                out[i] = palette.airId();
                continue;
            }
            out[i] = runtimeIdOf(oldMap[id], palette, blockCtor, id);
        }
        return out;
    }

    private int runtimeIdOf(Object block, LegacyBlockPalette palette, Constructor<?> blockCtor, int fallback) {
        try {
            Method getState = LegacyBlockPalette.method(block, "getState");
            if (getState == null) {
                return fallback;
            }
            return palette.remap(getState.invoke(block));
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static Object allocate(Class<?> type) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            return unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean putField(Object target, Field field, Object value) {
        try {
            field.set(target, value);
            return true;
        } catch (Throwable ignored) {
            // final-поле не поддалось обычной записи — пишем через Unsafe
        }
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            long offset = (Long) unsafeClass.getMethod("objectFieldOffset", Field.class).invoke(unsafe, field);
            unsafeClass.getMethod("putObject", Object.class, long.class, Object.class)
                    .invoke(unsafe, target, offset, value);
            unsafeClass.getMethod("putInt", Object.class, long.class, int.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** «Первая - последняя» версия из GameProtocol — формат, которым Geyser показывает диапазон сам. */
    private static String versionRange(ClassLoader cl) throws Exception {
        Class<?> gameProtocol = Class.forName(GAME_PROTOCOL, false, cl);
        @SuppressWarnings("unchecked")
        List<Object> versions = (List<Object>) gameProtocol.getField("SUPPORTED_BEDROCK_VERSIONS").get(null);
        if (versions.isEmpty()) {
            return "";
        }
        Method versionString = versions.get(0).getClass().getMethod("versionString");
        String first = String.valueOf(versionString.invoke(versions.get(0)));
        if (versions.size() == 1) {
            return first;
        }
        return first + " - " + versionString.invoke(versions.get(versions.size() - 1));
    }

    /** Самая свежая родная версия протокола этой сборки Geyser ({@code DEFAULT_BEDROCK_PROTOCOL}). */
    private static int newestNativeProtocol(ClassLoader cl) {
        try {
            Field field = Class.forName(GAME_PROTOCOL, false, cl).getField("DEFAULT_BEDROCK_PROTOCOL");
            return (Integer) field.get(null);
        } catch (Throwable t) {
            // Не прочитали — считаем, что ограничения сверху нет, но не падаем.
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Санитайзер кодеков для добавляемых (старых) протоколов.
     *
     * <p>Штатный {@code CodecProcessor.processCodec} для этого не подходит: он
     * рассчитан ровно на родные версии и, кроме безобидных «глушилок»,
     * подменяет сериализаторы на номера версий Geyser (v291/v712/v748/v975…).
     * Для чужой версии протокола это чужой формат пакетов — битый инвентарь
     * и экипировка. Поэтому здесь берутся только версионно-независимые наборы
     * {@code ILLEGAL_SERIALIZER} / {@code IGNORED_SERIALIZER} и применяются
     * поштучно: пакета нет в этой версии — правило молча пропускается.</p>
     *
     * <p>Что теряется: версионные оптимизации чтения (hotbar, entity data,
     * skin, move player…) — они лишь ускоряют разбор, безопасность даёт
     * как раз пара ILLEGAL/IGNORED.</p>
     */
    private static final class Sanitizer {

        private final Extension ext;
        private final ClassLoader cl;
        private final Method updateSerializer;
        private final Method build;
        private final Object illegal;
        private final Object ignored;
        private final boolean receiptsEnabled;

        // «Заглушки» для пакетов, которых в старом протоколе ещё нет.
        private final Method definitionOfClass;
        private final Method definitionOfId;
        private final Method definitionFactory;
        private final Method registerPacket;
        private final Class<?> serializerType;
        private final Object clientRecipient;

        private Sanitizer(Extension ext, ClassLoader cl, Method updateSerializer, Method build,
                          Object illegal, Object ignored, boolean receiptsEnabled,
                          Method definitionOfClass, Method definitionOfId, Method definitionFactory,
                          Method registerPacket, Class<?> serializerType, Object clientRecipient) {
            this.ext = ext;
            this.cl = cl;
            this.updateSerializer = updateSerializer;
            this.build = build;
            this.illegal = illegal;
            this.ignored = ignored;
            this.receiptsEnabled = receiptsEnabled;
            this.definitionOfClass = definitionOfClass;
            this.definitionOfId = definitionOfId;
            this.definitionFactory = definitionFactory;
            this.registerPacket = registerPacket;
            this.serializerType = serializerType;
            this.clientRecipient = clientRecipient;
        }

        /** {@code null}, если включено в конфиге, но нужных полей в Geyser нет. */
        static Sanitizer create(Extension ext, ClassLoader cl, Object sampleCodec, boolean enabled) {
            if (!enabled) {
                return null;
            }
            try {
                Class<?> processor = Class.forName(CODEC_PROCESSOR, true, cl);
                Field illegalField = processor.getDeclaredField("ILLEGAL_SERIALIZER");
                Field ignoredField = processor.getDeclaredField("IGNORED_SERIALIZER");
                illegalField.setAccessible(true);
                ignoredField.setAccessible(true);
                Object illegal = illegalField.get(null);
                Object ignored = ignoredField.get(null);
                if (illegal == null || ignored == null) {
                    return null;
                }
                // Сигнатура updateSerializer(Class, BedrockPacketSerializer) — тип
                // второго параметра берём у самого поля, чтобы не тянуть cloudburst.
                Class<?> serializerType = illegalField.getType();
                Object sampleBuilder = sampleCodec.getClass().getMethod("toBuilder").invoke(sampleCodec);
                Method update = sampleBuilder.getClass().getMethod("updateSerializer", Class.class, serializerType);
                Method build = sampleBuilder.getClass().getMethod("build");

                // Метаданные для заглушек отсутствующих пакетов — необязательные:
                // если в этой сборке cloudburst API другой, санитизация всё равно
                // должна остаться (иначе теряем и глушилки ILLEGAL/IGNORED).
                Method defOfClass = null;
                Method defOfId = null;
                Method defFactory = null;
                Method register = null;
                Object client = null;
                try {
                    Class<?> codecType = sampleCodec.getClass();
                    defOfClass = codecType.getMethod("getPacketDefinition", Class.class);
                    defOfId = codecType.getMethod("getPacketDefinition", int.class);
                    defFactory = defOfId.getReturnType().getMethod("getFactory");
                    Class<?> recipientType = Class.forName(
                            "org.cloudburstmc.protocol.bedrock.data.PacketRecipient", true, cl);
                    for (Object constant : recipientType.getEnumConstants()) {
                        if ("CLIENT".equals(((Enum<?>) constant).name())) {
                            client = constant;
                        }
                    }
                    register = sampleBuilder.getClass().getMethod("registerPacket",
                            java.util.function.Supplier.class, serializerType, int.class, recipientType);
                } catch (Throwable shimUnavailable) {
                    defOfClass = null;
                    defOfId = null;
                    defFactory = null;
                    register = null;
                    client = null;
                }
                return new Sanitizer(ext, cl, update, build, illegal, ignored,
                        Boolean.getBoolean("Geyser.ReceiptPackets"),
                        defOfClass, defOfId, defFactory, register, serializerType, client);
            } catch (Throwable t) {
                return null;
            }
        }

        /** Возвращает новый кодек с применёнными правилами; отсутствующие пакеты пропускаются. */
        Object apply(Object codec, int protocol, Object referenceCodec) throws Exception {
            Object builder = codec.getClass().getMethod("toBuilder").invoke(codec);
            for (String packet : ILLEGAL_PACKETS) {
                applyRule(builder, packet, illegal);
            }
            for (String packet : IGNORED_PACKETS) {
                applyRule(builder, packet, ignored);
            }
            if (!receiptsEnabled) {
                for (String packet : RECEIPT_PACKETS) {
                    applyRule(builder, packet, ignored);
                }
                // Как в Geyser: PartyChangedPacket глушится только с 26.10 (протокол 944).
                if (protocol >= 944) {
                    applyRule(builder, PARTY_PACKET, ignored);
                }
            }
            addMissingPackets(builder, codec, protocol, referenceCodec);
            return build.invoke(builder);
        }

        /**
         * Регистрирует в старом кодеке пустышки для пакетов, которых в его версии
         * протокола ещё нет, но которые Geyser умеет отправлять безусловно.
         *
         * <p>Без этого вход падал на {@code VoxelShapesPacket} (появился в 1.21.110,
         * протокол 924): {@code BedrockPacketCodec.getPacketId} получал
         * {@code null} из {@code getPacketDefinition(Class)} и ронял сессию
         * {@code NullPointerException} прямо при входе в мир.</p>
         *
         * <p>Пустышка = свободный номер пакета (< 0x7FF, реальные версии его не
         * занимают) + сериализатор-пустышка, который ничего не пишет. Старый клиент
         * такой пакет молча игнорирует: тело нулевой длины. Для текущего клиента
         * ничего не меняется — у него эти пакеты есть по-настоящему.</p>
         */
        private void addMissingPackets(Object builder, Object codec, int protocol, Object referenceCodec)
                throws Exception {
            if (definitionOfClass == null || definitionOfId == null || definitionFactory == null
                    || registerPacket == null || clientRecipient == null) {
                return;
            }
            int freeId = freePacketId(codec);
            if (freeId < 0) {
                return;
            }
            int added = 0;
            for (int id = 0; id <= MAX_PACKET_ID; id++) {
                Object definition = definitionOfId.invoke(referenceCodec, id);
                if (definition == null) {
                    continue;
                }
                Object instance = definitionFactory.invoke(definition);
                if (!(instance instanceof java.util.function.Supplier<?> factory)) {
                    continue;
                }
                Object sample = factory.get();
                if (sample == null) {
                    continue;
                }
                Class<?> packetClass = sample.getClass();
                if (definitionOfClass.invoke(codec, packetClass) != null) {
                    continue; // такой пакет в этой версии есть — не трогаем
                }
                Object supplier = java.lang.reflect.Proxy.newProxyInstance(cl,
                        new Class<?>[]{java.util.function.Supplier.class},
                        (proxy, method, args) -> "get".equals(method.getName()) ? sample : null);
                Object noop = java.lang.reflect.Proxy.newProxyInstance(cl,
                        new Class<?>[]{serializerType},
                        (proxy, method, args) -> null);
                try {
                    registerPacket.invoke(builder, supplier, noop, freeId, clientRecipient);
                    added++;
                } catch (Throwable alreadyRegistered) {
                    // Класс уже зарегистрирован алиасом — нам и не нужно.
                }
            }
            if (added > 0) {
                ext.logger().info("VoidVersions: протокол " + protocol + ": заглушено пакетов,"
                        + " которых нет в этой версии (Geyser их не должен слать): " + added
                        + " (номер-пустышка " + freeId + ").");
            }
        }

        /** Свободный номер пакета: реальные версии занимают только младшие id. */
        private int freePacketId(Object codec) {
            try {
                for (int id = MAX_PACKET_ID; id > 0; id--) {
                    if (definitionOfId.invoke(codec, id) == null) {
                        return id;
                    }
                }
            } catch (Throwable ignored) {
                // не смогли — заглушки просто не поставим
            }
            return -1;
        }

        private void applyRule(Object builder, String packetName, Object serializer) {
            try {
                Class<?> packet = Class.forName(PACKET_PREFIX + packetName, false, cl);
                updateSerializer.invoke(builder, packet, serializer);
            } catch (Throwable missingPacket) {
                // Такого пакета в этой версии протокола нет — правило не нужно.
            }
        }
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
