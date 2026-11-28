package ru.voidpe.pnx.music;

public final class MusicPolicyTest {
    public static void main(String[] args) {
        check(MusicPolicy.periodTicks(172, 300) == 9440, "configured cycle");
        check(MusicPolicy.periodTicks(-1, -1) == 100, "minimum cycle");
        check(MusicPolicy.periodTicks(Integer.MAX_VALUE, Integer.MAX_VALUE) == Integer.MAX_VALUE, "overflow clamp");
        check(MusicPolicy.isWorld("world", "WORLD"), "world case");
        check(!MusicPolicy.isWorld("world", "world_nether") && !MusicPolicy.isWorld("world", null), "other worlds");
        check(MusicPolicy.boundedVolume(-1) == 0 && MusicPolicy.boundedVolume(3) == 1, "volume");
        check(MusicPolicy.boundedPitch(-2) == 0 && MusicPolicy.boundedPitch(Double.NaN) == 1, "pitch");
        System.out.println("MusicPolicyTest passed");
    }
    private static void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); }
}
