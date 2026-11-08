package ru.voidpe.geyser.commands;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure text helpers, kept independent from Bukkit/Geyser for regression tests. */
final class SuggestionText {
    private static final int MAX_VALUE_LENGTH = 64;

    private SuggestionText() {
    }

    static List<String> clean(Iterable<String> raw, int limit) {
        if (raw == null || limit <= 0) return List.of();
        Set<String> unique = new LinkedHashSet<>();
        for (String candidate : raw) {
            if (candidate == null) continue;
            String value = candidate.replace('\r', ' ').replace('\n', ' ').replace("\0", "").trim();
            if (value.isEmpty()) continue;
            if (value.length() > MAX_VALUE_LENGTH) value = value.substring(0, MAX_VALUE_LENGTH).trim();
            if (value.isEmpty()) continue;
            unique.add(value);
            if (unique.size() >= limit) break;
        }
        return List.copyOf(unique);
    }

    static String enumName(String command, int overload, int parameter) {
        String safe = command.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        if (safe.isEmpty()) safe = "command";
        if (safe.length() > 40) {
            safe = safe.substring(0, 32) + "_" + Integer.toHexString(command.hashCode());
        }
        return "voidpe_" + safe + "_" + overload + "_" + parameter;
    }

    static String commandLine(String command, List<String> prefix) {
        StringBuilder result = new StringBuilder("/").append(command);
        for (String token : prefix) result.append(' ').append(quote(token));
        return result.append(' ').toString();
    }

    private static String quote(String token) {
        if (token.indexOf(' ') < 0 && token.indexOf('\t') < 0) return token;
        return '"' + token.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

}
