package ru.voidpe.voidtab;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Все внешние плагины подключаются МЯГКО, через reflection:
 * Vault (монеты + запасной источник группы), PlayerPoints (осколки),
 * LuckPerms (привилегия), Floodgate (Bedrock ли игрок), PlaceholderAPI.
 * Ни одной жёсткой зависимости в pom.xml — плагин стартует и без них,
 * просто соответствующие плейсхолдеры покажут значения по умолчанию из конфига.
 */
public final class Hooks {

    private final Logger log;

    private Object economy;          // Vault Economy provider
    private Method ecoGetBalance;
    private Method ecoFormat;

    private Object vaultPermission;  // Vault Permission provider
    private Method permGetPrimaryGroup;

    private Object luckPerms;        // LuckPerms api
    private Method lpGetUserManager;
    private Method lpGetGroupManager;

    private Object playerPointsApi;  // PlayerPointsAPI
    private Method ppLook;

    private Object floodgateApi;     // FloodgateApi
    private Method fgIsFloodgatePlayer;
    private Method fgGetPlayer;

    private Object geyserApi;        // GeyserApi (запасной источник устройства)
    private Method geyserConnectionByUuid;

    private Method bedrockResolve;   // Purpurbedrock: BedrockPlaceholderHook.resolveOnline(Player, String)
    private boolean bedrockTried;

    private Method papiSetPlaceholders; // PlaceholderAPI.setPlaceholders (static)
    private boolean papiTried;

    public Hooks(Logger log) {
        this.log = log;
        initVault();
        initLuckPerms();
        initPlayerPoints();
        initFloodgate();
        initGeyser();
        initPurpurBedrock();
    }

    // ---------------------------------------------------------------- Vault
    private void initVault() {
        for (Class<?> svc : Bukkit.getServicesManager().getKnownServices()) {
            try {
                if (svc.getName().equals("net.milkbowl.vault.economy.Economy")) {
                    RegisteredServiceProvider<?> reg = Bukkit.getServicesManager().getRegistration(svc);
                    if (reg != null) {
                        economy = reg.getProvider();
                        ecoGetBalance = findMethod(economy.getClass(), "getBalance", OfflinePlayer.class);
                        ecoFormat = findMethod(economy.getClass(), "format", double.class);
                        log.info("Vault: экономика подключена (" + economy.getClass().getSimpleName() + ")");
                    }
                }
                if (svc.getName().equals("net.milkbowl.vault.permission.Permission")) {
                    RegisteredServiceProvider<?> reg = Bukkit.getServicesManager().getRegistration(svc);
                    if (reg != null) {
                        vaultPermission = reg.getProvider();
                        permGetPrimaryGroup = findMethod(vaultPermission.getClass(), "getPrimaryGroup", OfflinePlayer.class);
                        if (permGetPrimaryGroup == null) {
                            permGetPrimaryGroup = findMethod(vaultPermission.getClass(), "getPrimaryGroup", String.class, OfflinePlayer.class);
                        }
                        log.info("Vault: права подключены");
                    }
                }
            } catch (Throwable t) {
                log.warning("Vault hook: " + t.getMessage());
            }
        }
    }

    public boolean hasEconomy() {
        return economy != null && ecoGetBalance != null;
    }

    public double balance(OfflinePlayer player) {
        if (!hasEconomy()) {
            return -1;
        }
        try {
            Object v = ecoGetBalance.invoke(economy, player);
            return v instanceof Number ? ((Number) v).doubleValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    public String economyFormat(double amount) {
        if (economy == null || ecoFormat == null) {
            return null;
        }
        try {
            Object v = ecoFormat.invoke(economy, amount);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ LuckPerms
    private void initLuckPerms() {
        for (Class<?> svc : Bukkit.getServicesManager().getKnownServices()) {
            try {
                if (svc.getName().equals("net.luckperms.api.LuckPerms")) {
                    RegisteredServiceProvider<?> reg = Bukkit.getServicesManager().getRegistration(svc);
                    if (reg != null) {
                        luckPerms = reg.getProvider();
                        lpGetUserManager = findMethod(luckPerms.getClass(), "getUserManager");
                        lpGetGroupManager = findMethod(luckPerms.getClass(), "getGroupManager");
                        log.info("LuckPerms подключён");
                    }
                }
            } catch (Throwable t) {
                log.warning("LuckPerms hook: " + t.getMessage());
            }
        }
    }

    public boolean hasLuckPerms() {
        return luckPerms != null;
    }

    /** @return массив [rawGroup, displayNameOrNull] или null. */
    public String[] luckPermsGroup(UUID uuid) {
        if (luckPerms == null || lpGetUserManager == null) {
            return null;
        }
        try {
            Object userManager = lpGetUserManager.invoke(luckPerms);
            if (userManager == null) {
                return null;
            }
            Method getUser = findMethod(userManager.getClass(), "getUser", UUID.class);
            Object user = getUser == null ? null : getUser.invoke(userManager, uuid);
            if (user == null) {
                return null; // пользователь ещё не загружен LuckPerms
            }
            Method getPrimaryGroup = findMethod(user.getClass(), "getPrimaryGroup");
            String raw = String.valueOf(getPrimaryGroup.invoke(user));
            String display = null;
            if (lpGetGroupManager != null) {
                Object gm = lpGetGroupManager.invoke(luckPerms);
                Method getGroup = gm == null ? null : findMethod(gm.getClass(), "getGroup", String.class);
                Object group = getGroup == null ? null : getGroup.invoke(gm, raw);
                if (group != null) {
                    Method getDisplayName = findMethod(group.getClass(), "getDisplayName");
                    Object dn = getDisplayName == null ? null : getDisplayName.invoke(group);
                    display = dn == null ? null : String.valueOf(dn);
                }
            }
            return new String[]{raw, display};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Действующий префикс LuckPerms (CachedMetaData: свой + наследованные, с приоритетами).
     * Сначала префикс самого игрока, если нет — префикс его основной группы. null — префикса нет.
     */
    public String luckPermsPrefix(UUID uuid) {
        if (luckPerms == null || lpGetUserManager == null) {
            return null;
        }
        try {
            Object userManager = lpGetUserManager.invoke(luckPerms);
            if (userManager == null) {
                return null;
            }
            Method getUser = findMethod(userManager.getClass(), "getUser", UUID.class);
            Object user = getUser == null ? null : getUser.invoke(userManager, uuid);
            if (user == null) {
                return null;
            }
            String prefix = metaPrefix(user);
            if (prefix != null && !prefix.isBlank()) {
                return prefix;
            }
            Method getPrimaryGroup = findMethod(user.getClass(), "getPrimaryGroup");
            String raw = getPrimaryGroup == null ? null : String.valueOf(getPrimaryGroup.invoke(user));
            if (raw != null && lpGetGroupManager != null) {
                Object gm = lpGetGroupManager.invoke(luckPerms);
                Method getGroup = gm == null ? null : findMethod(gm.getClass(), "getGroup", String.class);
                Object group = getGroup == null ? null : getGroup.invoke(gm, raw);
                if (group != null) {
                    prefix = metaPrefix(group);
                    if (prefix != null && !prefix.isBlank()) {
                        return prefix;
                    }
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Сбросить кэш метаданных LuckPerms у всех онлайн-игроков и всех групп.
     * После внешней правки префикса/суффикса (командой LP из консоли, кейсом,
     * донатом) скорборд, TAB и ChatEx должны увидеть новое значение без
     * перезахода игрока.
     */
    public void refreshLuckPermsMeta() {
        if (luckPerms == null || lpGetUserManager == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshLuckPermsMeta(player.getUniqueId());
        }
        if (lpGetGroupManager == null) {
            return;
        }
        try {
            Object groupManager = lpGetGroupManager.invoke(luckPerms);
            if (groupManager == null) {
                return;
            }
            Method getAllGroups = findMethod(groupManager.getClass(), "getAllGroups");
            Object groups = getAllGroups == null ? null : getAllGroups.invoke(groupManager);
            if (groups instanceof Iterable) {
                for (Object group : (Iterable<?>) groups) {
                    invalidateCachedData(group);
                }
            }
        } catch (Throwable ignored) {
            // мягкий хук: если API изменился, обычное чтение префиксов всё равно работает
        }
    }

    /** Сбросить кэш метаданных LuckPerms одного игрока. */
    public void refreshLuckPermsMeta(UUID uuid) {
        if (luckPerms == null || lpGetUserManager == null || uuid == null) {
            return;
        }
        try {
            Object userManager = lpGetUserManager.invoke(luckPerms);
            if (userManager == null) {
                return;
            }
            Method getUser = findMethod(userManager.getClass(), "getUser", UUID.class);
            Object user = getUser == null ? null : getUser.invoke(userManager, uuid);
            invalidateCachedData(user);
        } catch (Throwable ignored) {
            // см. выше
        }
    }

    private void invalidateCachedData(Object holder) {
        if (holder == null) {
            return;
        }
        try {
            Method getCachedData = findMethod(holder.getClass(), "getCachedData");
            if (getCachedData == null) {
                return;
            }
            Object cached = getCachedData.invoke(holder);
            if (cached == null) {
                return;
            }
            for (String name : new String[]{"invalidate", "invalidateMeta"}) {
                Method method = findMethod(cached.getClass(), name);
                if (method != null && method.getParameterCount() == 0) {
                    method.invoke(cached);
                }
            }
        } catch (Throwable ignored) {
            // LuckPerms сам обновляет кэш при своих командах; это только страховка.
        }
    }

    /**
     * Действующий суффикс LuckPerms игрока — это ТИТУЛ из кейса титулов
     * (meta setsuffix). null — титула нет.
     */
    public String luckPermsSuffix(UUID uuid) {
        if (luckPerms == null || lpGetUserManager == null) {
            return null;
        }
        try {
            Object userManager = lpGetUserManager.invoke(luckPerms);
            if (userManager == null) {
                return null;
            }
            Method getUser = findMethod(userManager.getClass(), "getUser", UUID.class);
            Object user = getUser == null ? null : getUser.invoke(userManager, uuid);
            if (user == null) {
                return null;
            }
            return metaValue(user, "getSuffix");
        } catch (Throwable t) {
            return null;
        }
    }

    /** getSuffix()/getPrefix() из CachedMetaData держателя. */
    private String metaValue(Object holder, String getter) {
        try {
            Method getCachedData = findMethod(holder.getClass(), "getCachedData");
            if (getCachedData == null) {
                return null;
            }
            Object cached = getCachedData.invoke(holder);
            if (cached == null) {
                return null;
            }
            Method getMeta = null;
            Object options = null;
            for (Method m : cached.getClass().getMethods()) {
                if (m.getName().equals("getMetaData") && m.getParameterCount() == 0) {
                    getMeta = m;
                    break;
                }
            }
            if (getMeta == null) {
                for (Method m : cached.getClass().getMethods()) {
                    if (m.getName().equals("getMetaData") && m.getParameterCount() == 1) {
                        for (Method sm : m.getParameterTypes()[0].getMethods()) {
                            if (sm.getName().equals("defaultContextualOptions") && sm.getParameterCount() == 0) {
                                sm.setAccessible(true);
                                options = sm.invoke(null);
                                getMeta = m;
                                break;
                            }
                        }
                        break;
                    }
                }
            }
            if (getMeta == null) {
                return null;
            }
            getMeta.setAccessible(true);
            Object meta = getMeta.getParameterCount() == 0 ? getMeta.invoke(cached) : getMeta.invoke(cached, options);
            if (meta == null) {
                return null;
            }
            Method get = findMethod(meta.getClass(), getter);
            Object value = get == null ? null : get.invoke(meta);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable t) {
            return null;
        }
    }

    /** getPrefix() из CachedMetaData держателя (User или Group), QueryOptions — контекстные по умолчанию. */
    private String metaPrefix(Object holder) {
        return metaValue(holder, "getPrefix");
    }

    public String vaultGroup(OfflinePlayer player) {
        if (vaultPermission == null || permGetPrimaryGroup == null) {
            return null;
        }
        try {
            Object v = permGetPrimaryGroup.getParameterCount() == 1
                    ? permGetPrimaryGroup.invoke(vaultPermission, player)
                    : permGetPrimaryGroup.invoke(vaultPermission, player.getPlayer() == null ? null : player.getPlayer().getWorld().getName(), player);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------- PlayerPoints
    private void initPlayerPoints() {
        Plugin pp = Bukkit.getPluginManager().getPlugin("PlayerPoints");
        if (pp == null) {
            return;
        }
        try {
            Method getApi = findMethod(pp.getClass(), "getAPI");
            if (getApi == null) {
                getApi = findMethod(pp.getClass(), "getApi");
            }
            playerPointsApi = getApi == null ? null : getApi.invoke(pp);
            if (playerPointsApi != null) {
                ppLook = findMethod(playerPointsApi.getClass(), "look", UUID.class);
                log.info("PlayerPoints подключён (осколки)");
            }
        } catch (Throwable t) {
            log.warning("PlayerPoints hook: " + t.getMessage());
        }
    }

    public boolean hasPlayerPoints() {
        return playerPointsApi != null && ppLook != null;
    }

    public int points(UUID uuid) {
        if (!hasPlayerPoints()) {
            return -1;
        }
        try {
            Object v = ppLook.invoke(playerPointsApi, uuid);
            return v instanceof Number ? ((Number) v).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------------------------------------- Floodgate
    private void initFloodgate() {
        Plugin fg = Bukkit.getPluginManager().getPlugin("floodgate");
        if (fg == null) {
            return;
        }
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true, fg.getClass().getClassLoader());
            Method getInstance = findMethod(apiClass, "getInstance");
            floodgateApi = getInstance == null ? null : getInstance.invoke(null);
            if (floodgateApi != null) {
                fgIsFloodgatePlayer = findMethod(floodgateApi.getClass(), "isFloodgatePlayer", UUID.class);
                fgGetPlayer = findMethod(floodgateApi.getClass(), "getPlayer", UUID.class);
                log.info("Floodgate подключён (Bedrock-игроки получат иконки пака)");
            }
        } catch (Throwable t) {
            log.warning("Floodgate hook: " + t.getMessage());
        }
    }

    public boolean hasFloodgate() {
        return floodgateApi != null && fgIsFloodgatePlayer != null;
    }

    public boolean isBedrock(UUID uuid) {
        if (bedrockResolve != null) {
            // Ядро Purpurbedrock: все подключения Bedrock по построению.
            // Явное "false" от ядра уважаем, молчание (null) считаем Bedrock.
            Player online = Bukkit.getPlayer(uuid);
            if (online == null) {
                return false;
            }
            String q = bedrockQuery(online, "is_bedrock");
            return q == null || "true".equalsIgnoreCase(q);
        }
        if (!hasFloodgate()) {
            return false;
        }
        try {
            Object v = fgIsFloodgatePlayer.invoke(floodgateApi, uuid);
            return Boolean.TRUE.equals(v);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------- Purpurbedrock (ядро)

    /**
     * Подключение к встроенному Bedrock-движку ядра Purpurbedrock через лёгкий
     * кэшированный Reflection — без Floodgate, без PlaceholderAPI и без внешних jar:
     * org.purpurmc.purpur.bedrock.BedrockPlaceholderHook.resolveOnline(Player, String).
     * Класс ищется и в класслоадере плагина, и в класслоадере сервера.
     */
    private void initPurpurBedrock() {
        if (bedrockTried) {
            return;
        }
        bedrockTried = true;
        try {
            Class<?> hook = null;
            ClassLoader[] loaders = {getClass().getClassLoader(), Bukkit.class.getClassLoader()};
            for (ClassLoader cl : loaders) {
                if (cl == null) {
                    continue;
                }
                try {
                    hook = Class.forName("org.purpurmc.purpur.bedrock.BedrockPlaceholderHook", false, cl);
                    break;
                } catch (Throwable ignored) {
                    // пробуем следующий класслоадер
                }
            }
            if (hook == null) {
                return; // обычное ядро Paper/Purpur — остаются Floodgate/Geyser ниже
            }
            bedrockResolve = findMethod(hook, "resolveOnline", Player.class, String.class);
            if (bedrockResolve != null) {
                log.info("Purpurbedrock подключён (устройство/пинг/версия из ядра)");
            }
        } catch (Throwable t) {
            log.warning("Purpurbedrock hook: " + t.getMessage());
        }
    }

    public boolean hasPurpurBedrock() {
        return bedrockResolve != null;
    }

    /**
     * Сырой запрос к ядру. Ключи: device, device_id, os, input, device_model,
     * version, ping, is_phone_kbm, is_bedrock. Нет ядра/значения — null.
     */
    public String bedrockQuery(Player player, String key) {
        if (player == null || bedrockResolve == null) {
            return null;
        }
        try {
            Object v = bedrockResolve.invoke(null, player, key);
            if (v == null) {
                return null;
            }
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Машинный id устройства: windows/android/ios/xbox/ps4_5/phone_kbm. */
    public String bedrockDeviceId(Player player) {
        return bedrockQuery(player, "device_id");
    }

    /** Отображаемое устройство: Windows/Android/IOS/Xbox/PS 4-5/Клавиатура + мышь. */
    public String bedrockDevice(Player player) {
        return bedrockQuery(player, "device");
    }

    public String bedrockOs(Player player) {
        return bedrockQuery(player, "os");
    }

    public String bedrockInput(Player player) {
        return bedrockQuery(player, "input");
    }

    public String bedrockModel(Player player) {
        return bedrockQuery(player, "device_model");
    }

    public String bedrockVersion(Player player) {
        return bedrockQuery(player, "version");
    }

    /** Реальный RakNet-пинг от ядра (мс); fallback — обычный Player#getPing(). */
    public int bedrockPing(Player player) {
        String raw = bedrockQuery(player, "ping");
        if (raw != null) {
            try {
                return Math.max(0, Integer.parseInt(raw));
            } catch (NumberFormatException ignored) {
                // падаем на ванильный пинг ниже
            }
        }
        return player.getPing();
    }

    /**
     * Устройство Bedrock-игрока из Floodgate: WINDOWS, GOOGLE (Android), IOS, XBOX, PS4, NX и т.д.
     * FloodgatePlayer.getDeviceOs() возвращает enum org.geysermc.geyser.configuration.DeviceOs.
     */
    public String deviceOs(UUID uuid) {
        String os = floodgateDeviceOs(uuid);
        if (os == null || os.isBlank() || os.equals("UNKNOWN")) {
            String g = geyserEnum(uuid, "deviceOs", "getDeviceOs");
            if (g != null && !g.isBlank()) {
                os = g; // Floodgate не ответил — берём напрямую из Geyser
            }
        }
        return os == null || os.isBlank() ? "UNKNOWN" : os;
    }

    /** Сырое значение Floodgate (для /voidtab debug). */
    public String floodgateDeviceOs(UUID uuid) {
        return floodgateEnum(uuid, "getDeviceOs");
    }

    /** Сырое значение Geyser (для /voidtab debug). */
    public String geyserDeviceOs(UUID uuid) {
        return geyserEnum(uuid, "deviceOs", "getDeviceOs");
    }

    public String geyserInputMode(UUID uuid) {
        return geyserEnum(uuid, "inputMode", "getInputMode");
    }

    /** Подключение Geyser напрямую: GeyserApi.api().connectionByUuid(uuid), оттуда deviceOs/inputMode. */
    private void initGeyser() {
        Plugin geyser = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
        if (geyser == null) {
            geyser = Bukkit.getPluginManager().getPlugin("Geyser");
        }
        if (geyser == null) {
            return;
        }
        try {
            Class<?> apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi", true, geyser.getClass().getClassLoader());
            Method api = findMethod(apiClass, "api");
            geyserApi = api == null ? null : api.invoke(null);
            if (geyserApi != null) {
                geyserConnectionByUuid = findMethod(geyserApi.getClass(), "connectionByUuid", UUID.class);
                if (geyserConnectionByUuid == null) {
                    geyserConnectionByUuid = findMethod(geyserApi.getClass(), "playerByUuid", UUID.class);
                }
                if (geyserConnectionByUuid != null) {
                    log.info("Geyser подключён (запасной источник устройства игрока)");
                }
            }
        } catch (Throwable t) {
            log.warning("Geyser hook: " + t.getMessage());
        }
    }

    private String geyserEnum(UUID uuid, String... getters) {
        if (geyserApi == null || geyserConnectionByUuid == null) {
            return null;
        }
        try {
            Object connection = geyserConnectionByUuid.invoke(geyserApi, uuid);
            if (connection == null) {
                return null;
            }
            for (String getter : getters) {
                Method m = findMethod(connection.getClass(), getter);
                if (m == null) {
                    continue;
                }
                Object v = m.invoke(connection);
                if (v == null) {
                    continue;
                }
                try {
                    Method nameMethod = v.getClass().getMethod("name");
                    return String.valueOf(nameMethod.invoke(v)).toUpperCase(java.util.Locale.ROOT);
                } catch (Throwable ignored) {
                    return v.toString().toUpperCase(java.util.Locale.ROOT);
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Режим ввода Bedrock-клиента: KEYBOARD_MOUSE, TOUCH, CONTROLLER, VR, UNKNOWN. */
    public String inputMode(UUID uuid) {
        String mode = floodgateEnum(uuid, "getInputMode");
        if (mode == null || mode.isBlank() || mode.equals("UNKNOWN")) {
            String g = geyserEnum(uuid, "inputMode", "getInputMode");
            if (g != null && !g.isBlank()) {
                mode = g;
            }
        }
        return mode == null || mode.isBlank() ? "UNKNOWN" : mode;
    }

    /** Модель устройства из Floodgate (для диагностики), может быть пустой. */
    public String deviceModel(UUID uuid) {
        if (floodgateApi == null || fgGetPlayer == null) {
            return "";
        }
        try {
            Object floodgatePlayer = fgGetPlayer.invoke(floodgateApi, uuid);
            if (floodgatePlayer == null) {
                return "";
            }
            Method m = findMethod(floodgatePlayer.getClass(), "getDeviceModel");
            Object v = m == null ? null : m.invoke(floodgatePlayer);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * ВАЖНО: DeviceOs.toString() у Floodgate возвращает подпись ("Android", "Windows"),
     * а не имя enum — поэтому берём именно Enum.name() ("GOOGLE", "UWP", ...).
     */
    private String floodgateEnum(UUID uuid, String getter) {
        if (floodgateApi == null || fgGetPlayer == null) {
            return null;
        }
        try {
            Object floodgatePlayer = fgGetPlayer.invoke(floodgateApi, uuid);
            if (floodgatePlayer == null) {
                return null;
            }
            Method m = findMethod(floodgatePlayer.getClass(), getter);
            Object v = m == null ? null : m.invoke(floodgatePlayer);
            if (v == null) {
                return null;
            }
            try {
                Method nameMethod = v.getClass().getMethod("name");
                return String.valueOf(nameMethod.invoke(v)).toUpperCase(java.util.Locale.ROOT);
            } catch (Throwable ignored) {
                return v.toString().toUpperCase(java.util.Locale.ROOT);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // --------------------------------------------------------- PlaceholderAPI
    public String applyPlaceholders(OfflinePlayer player, String text) {
        if (!papiTried) {
            papiTried = true;
            Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
            if (papi != null) {
                try {
                    Class<?> clazz = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, papi.getClass().getClassLoader());
                    papiSetPlaceholders = findMethod(clazz, "setPlaceholders", OfflinePlayer.class, String.class);
                    if (papiSetPlaceholders != null) {
                        log.info("PlaceholderAPI подключён");
                    }
                } catch (Throwable t) {
                    log.warning("PlaceholderAPI hook: " + t.getMessage());
                }
            }
        }
        if (papiSetPlaceholders == null) {
            return text;
        }
        try {
            Object v = papiSetPlaceholders.invoke(null, player, text);
            return v == null ? text : String.valueOf(v);
        } catch (Throwable t) {
            return text;
        }
    }

    // ---------------------------------------------------------------- util
    static Method findMethod(Class<?> clazz, String name, Class<?>... params) {
        Class<?> c = clazz;
        while (c != null) {
            try {
                Method m = c.getMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // ищем этим же именем c любым набором параметров ниже по иерархии
                for (Method m : c.getMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == params.length) {
                        boolean ok = true;
                        for (int i = 0; i < params.length; i++) {
                            if (!m.getParameterTypes()[i].isAssignableFrom(params[i])
                                    && !params[i].isAssignableFrom(m.getParameterTypes()[i])) {
                                ok = false;
                                break;
                            }
                        }
                        if (ok) {
                            m.setAccessible(true);
                            return m;
                        }
                    }
                }
                c = c.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }
}
