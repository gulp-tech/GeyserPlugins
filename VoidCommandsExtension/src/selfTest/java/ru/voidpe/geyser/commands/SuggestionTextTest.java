package ru.voidpe.geyser.commands;

import java.util.List;

public final class SuggestionTextTest {
    public static void main(String[] args) {
        List<String> cleaned = SuggestionText.clean(List.of(
                "  base  ", "base", "mine\nhome", "", "   ", "abc\0def"), 10);
        assert cleaned.equals(List.of("base", "mine home", "abcdef")) : cleaned;

        assert SuggestionText.clean(List.of("a", "b", "c"), 2).equals(List.of("a", "b"));
        assert SuggestionText.clean(null, 10).isEmpty();
        assert SuggestionText.enumName("Essentials:Home", 2, 1)
                .equals("voidpe_essentials_home_2_1");
        assert SuggestionText.commandLine("home", List.of()).equals("/home ");
        assert SuggestionText.commandLine("home", List.of("my home"))
                .equals("/home \"my home\" ");

        String longValue = "x".repeat(100);
        assert SuggestionText.clean(List.of(longValue), 1).get(0).length() == 64;
        System.out.println("SuggestionTextTest passed");
    }
}
