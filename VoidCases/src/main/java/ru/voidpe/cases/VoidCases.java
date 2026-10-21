package ru.voidpe.cases;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * VoidCases — донат-кейсы для Bedrock-сервера VoidPE:
 * кейс титулов (LuckPerms) и кейс осколков (PlayerPoints),
 * рулетка-анимация, фейерверк и объявления о крупном выигрыше.
 */
public final class VoidCases extends JavaPlugin implements CommandExecutor, TabCompleter {

    private CaseService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        service = new CaseService(this);
        getServer().getPluginManager().registerEvents(service, this);
        if (getCommand("cases") != null) {
            getCommand("cases").setExecutor(this);
            getCommand("cases").setTabCompleter(this);
        }
        if (getCommand("titles") != null) {
            getCommand("titles").setExecutor(this);
        }
        getLogger().info("VoidCases включён, кейсов: " + service.all().size());
    }

    public CaseService service() {
        return service;
    }

    private String prefix() {
        return TextUtil.color(getConfig().getString("messages.prefix", "&#C86BFFVoidPE &8» &f"));
    }

    private void send(CommandSender sender, String legacy) {
        sender.sendMessage(CaseService.component(prefix() + TextUtil.color(legacy)));
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("voidcases.admin")) {
            return true;
        }
        send(sender, getConfig().getString("messages.no-permission", "&cТолько для админов."));
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("titles")) {
            if (sender instanceof Player) {
                service.openTitles((Player) sender);
            } else {
                sender.sendMessage("Команда только для игроков.");
            }
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("list")) {
            if (service.all().isEmpty()) {
                send(sender, "&cКейсы не загрузились — проверь config.yml (секция cases) и лог сервера.");
                return true;
            }
            send(sender, "&fКейсы:");
            for (CaseService.CaseDef def : service.all()) {
                send(sender, "&7- &f" + def.id() + " &8— " + def.displayName());
            }
            return true;
        }
        if (sub.equals("reload")) {
            if (!admin(sender)) {
                return true;
            }
            reloadConfig();
            service.load();
            send(sender, "&aVoidCases перезагружен, кейсов: &f" + service.all().size());
            return true;
        }
        if (sub.equals("set") || sub.equals("remove")) {
            if (!admin(sender)) {
                return true;
            }
            if (!(sender instanceof Player)) {
                send(sender, "&cКоманда только для игроков.");
                return true;
            }
            Player player = (Player) sender;
            Block target = player.getTargetBlockExact(6);
            if (target == null) {
                send(sender, getConfig().getString("messages.no-target-block", "&cПосмотри на блок (до 6 блоков)."));
                return true;
            }
            if (sub.equals("remove")) {
                if (service.removePlaced(target.getLocation()) == null) {
                    send(sender, getConfig().getString("messages.not-a-case", "&cЭтот блок — не кейс."));
                } else {
                    send(sender, getConfig().getString("messages.set-removed", "&aКейс убран."));
                }
                return true;
            }
            if (args.length < 2) {
                send(sender, "&7Использование: &f/cases set <кейс> &8(смотри на блок)");
                return true;
            }
            CaseService.CaseDef def = service.byId(args[1]);
            if (def == null) {
                send(sender, getConfig().getString("messages.case-not-found", "&cТакого кейса нет."));
                return true;
            }
            service.setPlaced(target, def, player.getFacing().getOppositeFace());
            String done = getConfig().getString("messages.set-done", "&aКейс поставлен: {case}")
                    .replace("{case}", def.displayName());
            send(sender, done);
            return true;
        }
        if (sub.equals("give")) {
            if (!admin(sender)) {
                return true;
            }
            if (args.length < 3) {
                send(sender, "&7Использование: &f/cases give <игрок> <кейс> [кол-во]");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                send(sender, getConfig().getString("messages.player-offline", "&cИгрок не в сети."));
                return true;
            }
            CaseService.CaseDef def = service.byId(args[2]);
            if (def == null) {
                send(sender, getConfig().getString("messages.case-not-found", "&cТакого кейса нет."));
                return true;
            }
            int amount = 1;
            if (args.length > 3) {
                try {
                    amount = Math.max(1, Integer.parseInt(args[3]));
                } catch (NumberFormatException ignored) {
                }
            }
            Map<Integer, ItemStack> rest = target.getInventory().addItem(service.createCaseItem(def, amount));
            for (ItemStack drop : rest.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), drop);
            }
            String received = getConfig().getString("messages.received", "&aВыдан кейс: {case}")
                    .replace("{case}", def.displayName())
                    .replace("{amount}", String.valueOf(amount));
            target.sendMessage(CaseService.component(prefix() + TextUtil.color(received)));
            if (!sender.equals(target)) {
                String given = getConfig().getString("messages.given", "&aВыдан кейс игроку {player}")
                        .replace("{player}", target.getName())
                        .replace("{case}", def.displayName())
                        .replace("{amount}", String.valueOf(amount));
                send(sender, given);
            }
            return true;
        }
        help(sender);
        return true;
    }

    private void help(CommandSender sender) {
        send(sender, "&fКейсы VoidPE:");
        send(sender, "&7/cases give <игрок> <кейс> [кол-во] &8— выдать кейс (админ)");
        send(sender, "&7/cases set <кейс> &8— поставить кейс на блок, на который смотришь");
        send(sender, "&7/cases remove &8— убрать кейс с блока, на который смотришь");
        send(sender, "&7/cases list &8— список кейсов");
        send(sender, "&7/cases reload &8— перезагрузка (админ)");
        send(sender, "&7Открытие: держи кейс в руке и нажми ПКМ.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : List.of("give", "list", "reload", "set", "remove")) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    result.add(sub);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    result.add(player.getName());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            for (String id : service.ids()) {
                if (id.startsWith(args[1].toLowerCase())) {
                    result.add(id);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (String id : service.ids()) {
                if (id.startsWith(args[2].toLowerCase())) {
                    result.add(id);
                }
            }
        }
        return result;
    }
}
