package ru.voidpe.geyser.commands;

import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumConstraint;
import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOverloadData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamType;
import org.geysermc.geyser.api.event.downstream.ServerDefineCommandsEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Mutates Geyser's command tree before it is serialized to Bedrock. */
final class CommandGraph {
    private CommandGraph() {
    }

    static List<DynamicParam> attach(Collection<? extends ServerDefineCommandsEvent.CommandInfo> source,
                                     Map<String, List<String>> cachedValues,
                                     CommandConfig config,
                                     VoidCommandsExtension extension) {
        List<ServerDefineCommandsEvent.CommandInfo> commands = new ArrayList<>(source);
        commands.sort(Comparator.comparing(info -> info.name().toLowerCase(Locale.ROOT)));
        List<DynamicParam> targets = new ArrayList<>();
        boolean limitLogged = false;

        for (ServerDefineCommandsEvent.CommandInfo command : commands) {
            CommandOverloadData[] overloads = overloads(command);
            if (overloads == null) continue;
            String commandName = command.name().toLowerCase(Locale.ROOT);
            for (int overloadIndex = 0; overloadIndex < overloads.length; overloadIndex++) {
                CommandParamData[] path = overloads[overloadIndex].getOverloads();
                int depth = Math.min(path.length, config.maxDepth());
                for (int parameterIndex = 0; parameterIndex < depth; parameterIndex++) {
                    CommandParamData parameter = path[parameterIndex];
                    if (!isFreeForm(parameter)) continue;
                    if (targets.size() >= config.maxTargets()) {
                        if (!limitLogged) {
                            extension.logger().warning("VoidCommands: достигнут max-targets=" + config.maxTargets()
                                    + "; увеличьте предел в config.properties, если нужны оставшиеся аргументы.");
                            limitLogged = true;
                        }
                        return targets;
                    }

                    String enumName = SuggestionText.enumName(commandName, overloadIndex, parameterIndex);
                    List<String> values = cachedValues.getOrDefault(enumName, List.of());
                    parameter.setEnumData(new CommandEnumData(enumName, enumValues(values), true));
                    targets.add(new DynamicParam(commandName, overloadIndex, parameterIndex, enumName, path));
                }
            }
        }
        return targets;
    }

    static Map<String, Set<CommandEnumConstraint>> enumValues(List<String> values) {
        Map<String, Set<CommandEnumConstraint>> result = new LinkedHashMap<>();
        for (String value : values) result.put(value, EnumSet.noneOf(CommandEnumConstraint.class));
        return result;
    }

    private static boolean isFreeForm(CommandParamData parameter) {
        if (parameter.getEnumData() != null || parameter.getPostfix() != null || parameter.getType() == null) {
            return false;
        }
        CommandParamType type = parameter.getType().getParamType();
        return type == CommandParamType.STRING || type == CommandParamType.TEXT
                || type == CommandParamType.MESSAGE || type == CommandParamType.FILE_PATH;
    }

    private static CommandOverloadData[] overloads(ServerDefineCommandsEvent.CommandInfo command) {
        try {
            Method method = command.getClass().getDeclaredMethod("paramData");
            method.setAccessible(true);
            return (CommandOverloadData[]) method.invoke(command);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Public CommandInfo intentionally hides overloads. Custom third-party
            // CommandInfo implementations can therefore be left untouched safely.
            return null;
        }
    }
}
