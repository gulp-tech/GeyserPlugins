/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
 *  net.md_5.bungee.api.ChatColor
 */
package com.musd348.lmtops;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.md_5.bungee.api.ChatColor;

public class ColorUtil {
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    public static String color(String message) {
        if (message == null) {
            return "";
        }
        Matcher matcher = HEX_PATTERN.matcher(message);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, ChatColor.of((String)("#" + matcher.group(1))).toString());
        }
        return ChatColor.translateAlternateColorCodes((char)'&', (String)matcher.appendTail(buffer).toString());
    }

    public static String colorize(String message) {
        return ColorUtil.color(message);
    }

    public static String colorizeNoFont(String message) {
        return ColorUtil.color(message);
    }

    public static Component toComponent(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(ColorUtil.color(message));
    }
}

