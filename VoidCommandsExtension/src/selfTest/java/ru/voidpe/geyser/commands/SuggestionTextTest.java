package ru.voidpe.geyser.commands;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
        Map<String, List<String>> cache = new HashMap<>();
        AtomicInteger calls = new AtomicInteger();
        assert SuggestionText.cachedCompletion(cache, "/home ", () -> {
            calls.incrementAndGet(); return List.of(" base ", "base", "spawn");
        }, 10).equals(List.of("base", "spawn"));
        assert SuggestionText.cachedCompletion(cache, "/home ", () -> {
            calls.incrementAndGet(); return List.of("wrong");
        }, 10).equals(List.of("base", "spawn"));
        assert calls.get() == 1 : calls;
        assert SuggestionText.cachedCompletion(cache, "/warp ", () -> {
            calls.incrementAndGet(); return null;
        }, 10).isEmpty();
        assert SuggestionText.cachedCompletion(cache, "/warp ", () -> {
            calls.incrementAndGet(); return List.of("wrong");
        }, 10).isEmpty();
        assert calls.get() == 2 : calls;
        assert SuggestionText.cachedCompletion(new HashMap<>(), "/home ", () -> {
            calls.incrementAndGet(); return List.of("new");
        }, 10).equals(List.of("new"));
        assert calls.get() == 3 : calls;
        try {
            SuggestionText.cachedCompletion(cache, "/failed ", () -> { throw new IllegalStateException(); }, 10);
            throw new AssertionError("failure must propagate");
        } catch (IllegalStateException expected) { }
        assert !cache.containsKey("/failed ");
        System.out.println("SuggestionTextTest passed");
    }
}
