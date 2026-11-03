package ru.voidpe.voidtab;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Оформление ника над головой:
 *  - перед ником префикс привилегии из LuckPerms;
 *  - под ником здоровье (objective below-name).
 *
 * КАК ЭТО СДЕЛАНО И ПОЧЕМУ ТАК.
 * У каждого игрока свой борд сайдбара, поэтому «команды» (Scoreboard Team) ника
 * приходится создавать на борде КАЖДОГО зрителя. Раньше команда была одна на
 * игрока: 40 игроков = 40 команд на каждом из 40 бордов = 1600 команд, и при
 * появлении игрока летела пачка пакетов (создать команду, добавить запись,
 * выставить префикс) каждому зрителю — клиенты Bedrock в этот момент
 * перерисовывали все неймтеги, отсюда фриз «подхожу к игроку — лагает».
 *
 * Теперь команда ОБЩАЯ для всех игроков с одинаковым префиксом: у игроков одного
 * ранга префикс одинаковый, значит команд на борде не 40, а столько, сколько
 * разных привилегий. Появление игрока = одна запись в уже существующую команду
 * (один маленький пакет) либо ничего, если префикс и команда уже есть.
 */
public final class NametagManager {

    private static final String HEALTH_OBJECTIVE = "faral_hp";
    /** Длина имени команды в 1.13+: до 16 символов. */
    private static final int MAX_TEAM_NAME = 16;

    private final VoidTab plugin;
    // "префикс|титул" на игрока — чтобы понимать, изменилось ли что-то.
    private final Map<UUID, String> appliedPrefix = new HashMap<>();
    // Игрок -> имя его команды (общей по префиксу). Нужно, чтобы при смене
    // привилегии убрать запись из старой команды и при выходе тоже.
    private final Map<UUID, String> teamOfPlayer = new HashMap<>();
    // Префикс -> имя общей команды.
    private final Map<String, String> teamByPrefix = new HashMap<>();
    // Какой префикс уже стоит в команде на конкретном борде: если тот же —
    // team.prefix() НЕ зовём (это пакет и перерисовка неймтега у клиента).
    private final Map<Scoreboard, Map<String, String>> boardPrefixCache =
            Collections.synchronizedMap(new WeakHashMap<>());
    // Борды, где объектив здоровья уже создан.
    private final Set<Scoreboard> boardsWithHealth =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    // Троттлинг принудительной пересылки display/list name.
    private final Map<UUID, Long> lastForceResend = new HashMap<>();
    private int teamCounter;

    public NametagManager(VoidTab plugin) {
        this.plugin = plugin;
    }

    private boolean teamsEnabled() {
        return plugin.getConfig().getBoolean("nametag.use-teams", true);
    }

    private void ensureHealthObjective(Scoreboard board) {
        if (!plugin.getConfig().getBoolean("nametag.health-below-name", true)) {
            return;
        }
        if (boardsWithHealth.contains(board)) {
            return;
        }
        Objective objective = board.getObjective(HEALTH_OBJECTIVE);
        if (objective == null) {
            objective = board.registerNewObjective(HEALTH_OBJECTIVE, Criteria.HEALTH,
                    component(TextUtil.color(plugin.getConfig().getString("nametag.health-suffix", "&c❤"))));
            objective.setDisplaySlot(DisplaySlot.BELOW_NAME);
        }
        boardsWithHealth.add(board);
    }

    /**
     * Имя команды для префикса. Одинаковый префикс — одна команда на всех
     * игроков, поэтому число команд ограничено числом привилегий, а не онлайном.
     */
    private String teamIdFor(String prefix) {
        String existing = teamByPrefix.get(prefix);
        if (existing != null) {
            return existing;
        }
        String id = "vp" + Integer.toString(++teamCounter, 36);
        if (id.length() > MAX_TEAM_NAME) {
            id = id.substring(0, MAX_TEAM_NAME);
        }
        teamByPrefix.put(prefix, id);
        return id;
    }

    /** Заполнить НОВЫЙ борд оформлением всех онлайн-игроков ДО его назначения. */
    public void prefill(Scoreboard board) {
        if (board == null || !teamsEnabled()) {
            return;
        }
        ensureHealthObjective(board);
        Map<String, String> boardCache = boardPrefixCache.computeIfAbsent(board, b -> new HashMap<>());
        for (Player target : Bukkit.getOnlinePlayers()) {
            String applied = appliedPrefix.get(target.getUniqueId());
            if (applied == null) {
                continue;
            }
            String nickPrefix = nickPrefix(applied);
            String teamId = teamIdFor(nickPrefix);
            Team team = board.getTeam(teamId);
            if (team == null) {
                team = board.registerNewTeam(teamId);
            }
            if (!team.hasEntry(target.getName())) {
                team.addEntry(target.getName());
            }
            if (!nickPrefix.equals(boardCache.get(teamId))) {
                team.prefix(component(TextUtil.color(
                        plugin.getConfig().getString("nametag.prefix-color", "&f")) + nickPrefix));
                boardCache.put(teamId, nickPrefix);
            }
        }
    }

    public String appliedPrefix(UUID uuid) {
        return appliedPrefix.getOrDefault(uuid, "<нет>");
    }

    private static String nickPrefix(String applied) {
        int sep = applied.indexOf('|');
        return sep >= 0 ? applied.substring(0, sep) : applied;
    }

    public void apply(Player player) {
        apply(player, false);
    }

    /** Поставить/обновить оформление ника игрока на бордах всех зрителей. */
    public void apply(Player player, boolean forceNames) {
        if (!plugin.getConfig().getBoolean("nametag.enabled", true)) {
            return;
        }
        String prefix = "";
        if (plugin.getConfig().getBoolean("nametag.device-icon", true)) {
            String icon = plugin.sidebar().deviceIconChar(player);
            if (!icon.isBlank()) {
                prefix += icon + " ";
            }
        }
        boolean light = plugin.getConfig().getBoolean("performance.light-nametags", false);
        if (!light && plugin.getConfig().getBoolean("nametag.rank-icon", true)) {
            String rankIcon = plugin.sidebar().rankIconChar(player);
            if (!rankIcon.isBlank()) {
                prefix += rankIcon + " ";
            }
        }
        if (!light && plugin.getConfig().getBoolean("nametag.rank-name", true)) {
            prefix += TextUtil.color(plugin.sidebar().rankDisplayName(player)) + " ";
        }
        String titleSuffix = "";
        if (plugin.getConfig().getBoolean("nametag.title-in-tab", true) && plugin.hooks().hasLuckPerms()) {
            String suffix = plugin.hooks().luckPermsSuffix(player.getUniqueId());
            if (suffix != null && !suffix.isBlank()) {
                titleSuffix = suffix;
            }
        }
        boolean changed = !(prefix + "|" + titleSuffix).equals(appliedPrefix.get(player.getUniqueId()));
        appliedPrefix.put(player.getUniqueId(), prefix + "|" + titleSuffix);

        boolean teams = teamsEnabled();
        if (teams) {
            applyTeams(player, prefix);
        }

        // Bedrock: неймтег берётся из display-name — дублируем при изменениях.
        long now = System.currentTimeMillis();
        long forceIntervalMs = Math.max(0L,
                plugin.getConfig().getLong("luckperms.force-name-resend-interval-seconds", 0L)) * 1000L;
        boolean forceDue = forceNames && forceIntervalMs > 0
                && now - lastForceResend.getOrDefault(player.getUniqueId(), 0L) >= forceIntervalMs;
        if ((changed || forceDue) && plugin.getConfig().getBoolean("nametag.use-display-name", true)) {
            if (forceDue && !changed) {
                lastForceResend.put(player.getUniqueId(), now);
            }
            Component name = Component.text(prefix).append(Component.text(player.getName()));
            player.displayName(name);
            String tabName = TextUtil.color(
                    plugin.getConfig().getString("nametag.prefix-color", "&f")) + prefix + player.getName()
                    + TextUtil.color(titleSuffix);
            if (plugin.getConfig().getBoolean("nametag.device-name-in-tab", true)) {
                tabName += TextUtil.color(" &7(" + plugin.sidebar().deviceName(player) + ")");
            }
            player.setPlayerListName(tabName);
        }
    }

    /** Запись игрока в ОБЩУЮ команду его префикса на бордах всех зрителей того же мира. */
    private void applyTeams(Player player, String prefix) {
        String teamId = teamIdFor(prefix);
        String previous = teamOfPlayer.put(player.getUniqueId(), teamId);
        if (previous != null && !previous.equals(teamId)) {
            // Привилегию сменили — убираем запись из старой команды.
            dropEntry(player, previous);
        }
        Component prefixComponent = null;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard board = viewer.getScoreboard();
            if (!viewer.getWorld().equals(player.getWorld())) {
                continue; // из другого мира ник не видно — пакет не нужен
            }
            ensureHealthObjective(board);
            Team team = board.getTeam(teamId);
            if (team == null) {
                team = board.registerNewTeam(teamId);
            }
            if (!team.hasEntry(player.getName())) {
                team.addEntry(player.getName());
            }
            Map<String, String> boardCache =
                    boardPrefixCache.computeIfAbsent(board, b -> new HashMap<>());
            if (!prefix.equals(boardCache.get(teamId))) {
                if (prefixComponent == null) {
                    prefixComponent = component(TextUtil.color(
                            plugin.getConfig().getString("nametag.prefix-color", "&f")) + prefix);
                }
                team.prefix(prefixComponent);
                boardCache.put(teamId, prefix);
            }
        }
        // Главный борд: там оформление нужно, пока им пользуется хоть кто-то.
        Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
        ensureHealthObjective(mainBoard);
        Team mainTeam = mainBoard.getTeam(teamId);
        if (mainTeam == null) {
            mainTeam = mainBoard.registerNewTeam(teamId);
        }
        if (!mainTeam.hasEntry(player.getName())) {
            mainTeam.addEntry(player.getName());
        }
        Map<String, String> mainCache = boardPrefixCache.computeIfAbsent(mainBoard, b -> new HashMap<>());
        if (!prefix.equals(mainCache.get(teamId))) {
            if (prefixComponent == null) {
                prefixComponent = component(TextUtil.color(
                        plugin.getConfig().getString("nametag.prefix-color", "&f")) + prefix);
            }
            mainTeam.prefix(prefixComponent);
            mainCache.put(teamId, prefix);
        }
    }

    /** Убрать запись игрока из одной команды на всех бордах; пустую команду удалить. */
    private void dropEntry(Player player, String teamId) {
        String name = player.getName();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard board = viewer.getScoreboard();
            Team team = board.getTeam(teamId);
            if (team == null) {
                continue;
            }
            if (team.hasEntry(name)) {
                team.removeEntry(name);
            }
            if (team.getEntries().isEmpty()) {
                team.unregister();
                Map<String, String> cache = boardPrefixCache.get(board);
                if (cache != null) {
                    cache.remove(teamId);
                }
            }
        }
        Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team mainTeam = mainBoard.getTeam(teamId);
        if (mainTeam != null) {
            if (mainTeam.hasEntry(name)) {
                mainTeam.removeEntry(name);
            }
            if (mainTeam.getEntries().isEmpty()) {
                mainTeam.unregister();
                Map<String, String> cache = boardPrefixCache.get(mainBoard);
                if (cache != null) {
                    cache.remove(teamId);
                }
            }
        }
    }

    /** Игрок вышел — убрать его запись из общей команды (сама команда остаётся). */
    public void remove(Player player) {
        appliedPrefix.remove(player.getUniqueId());
        lastForceResend.remove(player.getUniqueId());
        String teamId = teamOfPlayer.remove(player.getUniqueId());
        if (teamId != null) {
            dropEntry(player, teamId);
        }
    }

    private Component component(String legacySectionText) {
        return LegacyComponentSerializer.legacySection().deserialize(
                legacySectionText == null ? "" : legacySectionText);
    }
}
