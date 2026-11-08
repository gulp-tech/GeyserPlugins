package ru.voidpe.geyser.commands;

import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParam;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A free-form argument backed by one Bedrock soft enum. */
record DynamicParam(String command, int overload, int parameter, String enumName,
                    CommandParamData[] path) {

    /**
     * Resolves a real completion prefix for this overload. Earlier literal/enum
     * arguments come from the command tree; earlier dynamic arguments use values
     * already obtained from Bukkit during this refresh.
     */
    String completionLine(Map<String, List<String>> dynamicValues) {
        List<String> prefix = new ArrayList<>(parameter);
        for (int i = 0; i < parameter; i++) {
            CommandParamData previous = path[i];
            CommandEnumData enumData = previous.getEnumData();
            if (enumData != null) {
                List<String> values;
                if (enumData.isSoft()) {
                    values = dynamicValues.get(enumData.getName());
                } else {
                    values = List.copyOf(enumData.getValues().keySet());
                }
                if (values == null || values.isEmpty()) return null;
                prefix.add(values.get(0));
                continue;
            }

            CommandParam type = previous.getType();
            CommandParamType paramType = type == null ? null : type.getParamType();
            if (paramType == CommandParamType.TARGET || paramType == CommandParamType.STANDALONE_TARGET) {
                prefix.add("@s");
            } else if (isNumeric(paramType)) {
                prefix.add("0");
            } else {
                // Guessing free-form text would call the completer for a different
                // command path. Wait for a real previous suggestion instead.
                return null;
            }
        }
        return SuggestionText.commandLine(command, prefix);
    }

    private static boolean isNumeric(CommandParamType type) {
        return type == CommandParamType.INT || type == CommandParamType.FLOAT
                || type == CommandParamType.VALUE || type == CommandParamType.R_VALUE
                || type == CommandParamType.WILDCARD_INT;
    }
}
