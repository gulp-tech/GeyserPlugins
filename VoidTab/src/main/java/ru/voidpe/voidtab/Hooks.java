package ru.voidpe.voidtab;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

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

    private Method papiSetPlaceholders; // PlaceholderAPI.setPlaceholders (static)
    private boolean papiTried;

    public Hooks(Logger log) {
        this.log = log;
        initVault();
        initLuckPerms();
        initPlayerPoints();
        initFloodgate();
        initGeyser();
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
