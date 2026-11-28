package ru.voidpe.pnx.nv;

public final class HelpersTest {
    public static void main(String[] args) {
        check(Helpers.color("&#7CFC00test &7text").equals("§atest §7text"), "hex and legacy");
        check(Helpers.color("&#FFFFFF&ltext &?").equals("§f§ltext &?"), "format and literal");
        check(Helpers.color(null).isEmpty(), "null");
        check(Helpers.deadline(1000, 5) == 6000, "normal deadline");
        check(Helpers.deadline(1000, -1) == 1000, "negative cooldown");
        check(Helpers.deadline(1000, Long.MAX_VALUE) == Long.MAX_VALUE, "overflow");
        check(Helpers.remainingSeconds(1000, 6000) == 5, "5 seconds");
        check(Helpers.remainingSeconds(1001, 6000) == 5, "round up");
        check(Helpers.remainingSeconds(6000, 6000) == 0, "expired");
        System.out.println("HelpersTest passed");
    }
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
}
