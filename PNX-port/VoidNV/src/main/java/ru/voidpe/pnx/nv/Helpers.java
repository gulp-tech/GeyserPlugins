package ru.voidpe.pnx.nv;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bedrock formatting and overflow-safe cooldown arithmetic. */
final class Helpers {
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final int[] COLORS = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
    private static final String KEYS = "0123456789abcdef";

    static String color(String text) {
        if (text == null) return "";
        Matcher matcher = HEX.matcher(text);
        StringBuffer converted = new StringBuffer();
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1), 16);
            int best = 0, distance = Integer.MAX_VALUE;
            for (int i = 0; i < COLORS.length; i++) {
                int r = ((value >> 16) & 255) - ((COLORS[i] >> 16) & 255);
                int g = ((value >> 8) & 255) - ((COLORS[i] >> 8) & 255);
                int b = (value & 255) - (COLORS[i] & 255);
                int d = r * r + g * g + b * b;
                if (d < distance) { distance = d; best = i; }
            }
            matcher.appendReplacement(converted, Matcher.quoteReplacement("§" + KEYS.charAt(best)));
        }
        matcher.appendTail(converted);
        StringBuilder result = new StringBuilder(converted.length());
        String allowed = "0123456789abcdef ghijmnpqstuklor".replace(" ", "");
        for (int i = 0; i < converted.length(); i++) {
            char c = converted.charAt(i);
            if (c == '&' && i + 1 < converted.length()) {
                char next = Character.toLowerCase(converted.charAt(i + 1));
                if (allowed.indexOf(next) >= 0) { result.append('§').append(next); i++; continue; }
            }
            result.append(c);
        }
        return result.toString();
    }

    static long deadline(long now, long seconds) {
        if (seconds <= 0) return now;
        if (seconds > (Long.MAX_VALUE - now) / 1000) return Long.MAX_VALUE;
        return now + seconds * 1000;
    }

    static long remainingSeconds(long now, long deadline) {
        if (deadline <= now) return 0;
        long delta = deadline - now;
        return delta / 1000 + (delta % 1000 == 0 ? 0 : 1);
    }

    private Helpers() {}
}
