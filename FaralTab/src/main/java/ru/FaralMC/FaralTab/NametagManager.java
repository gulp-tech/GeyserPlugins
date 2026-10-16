package ru.FaralMC.FaralTab;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Оформление ника над головой (главный скорборд, виден всем):
 *  - префикс неймтега — значок устройства игрока из Floodgate
 *    (Windows / Android / iPhone / другое), глиф из ресурспака;
 *  - под ником — здоровье (objective BELOW_NAME, число ставит сама игра,
 *    от нас только красное сердечко после него).
 * В невидимости ник и здоровье скрывает сама игра — как и просили.
 */
public final class NametagManager {

    private static final String HEALTH_OBJECTIVE = "faral_hp";

    private final FaralTab plugin;
    private final Map<UUID, String> appliedPrefix = new HashMap<>();

    public NametagManager(FaralTab plugin) {
        this.plugin = plugin;
        setupHealthObjective();
    }

    private void setupHealthObjective() {
        if (!plugin.getConfig().getBoolean("nametag.enabled", true)
                || !plugin.getConfig().getBoolean("nametag.health-below-name", true)) {
            return;
        }
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective objective = main.getObjective(HEALTH_OBJECTIVE);
        if (objective == null) {
            objective = main.registerNewObjective(HEALTH_OBJECTIVE, Criteria.HEALTH,
                    component(TextUtil.color(plugin.getConfig().getString("nametag.health-suffix", "&c❤"))));
            objective.setDisplaySlot(DisplaySlot.BELOW_NAME);
        }
    }

    /** Поставить/обновить значок устройства в неймтеге игрока. */
    public void apply(Player player) {
        if (!plugin.getConfig().getBoolean("nametag.enabled", true)) {
            return;
        }
        String prefix = "";
        if (plugin.getConfig().getBoolean("nametag.device-icon", true)) {
            String icon = plugin.sidebar().deviceIconChar(player);
            if (!icon.isBlank()) {
                prefix = icon + " ";
            }
        }
        if (prefix.equals(appliedPrefix.get(player.getUniqueId()))) {
            return; // уже стоит такая же — не трогаем, чтобы не дёргать клиентов
        }
        appliedPrefix.put(player.getUniqueId(), prefix);

        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = "ft" + player.getUniqueId().toString().replace("-", "").substring(0, 14);
        Team team = main.getTeam(teamName);
        if (team == null) {
            team = main.registerNewTeam(teamName);
        }
        if (!team.hasEntry(player.getName())) {
            team.addEntry(player.getName());
        }
        team.prefix(component(TextUtil.color(plugin.getConfig().getString("nametag.prefix-color", "&f"))
                + prefix));
    }

    /** Игрок вышел — убрать его команду неймтега. */
    public void remove(Player player) {
        appliedPrefix.remove(player.getUniqueId());
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = "ft" + player.getUniqueId().toString().replace("-", "").substring(0, 14);
        Team team = main.getTeam(teamName);
        if (team != null) {
            team.unregister();
        }
    }

    private Component component(String legacySectionText) {
        return LegacyComponentSerializer.legacySection().deserialize(
                legacySectionText == null ? "" : legacySectionText);
    }
}
