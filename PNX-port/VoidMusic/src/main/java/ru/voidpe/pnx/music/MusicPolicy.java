package ru.voidpe.pnx.music;

/** Pure scheduling and world-selection rules; actual sounds are sent only to the player. */
final class MusicPolicy {
    static int periodTicks(int trackSeconds, int pauseSeconds) {
        long track = Math.max(5L, trackSeconds);
        long pause = Math.max(0L, pauseSeconds);
        return (int) Math.min(Integer.MAX_VALUE, (track + pause) * 20L);
    }

    static boolean isWorld(String configured, String actual) {
        return configured != null && actual != null && configured.equalsIgnoreCase(actual);
    }

    static float boundedVolume(double volume) {
        return !Double.isFinite(volume) ? 1.0f : (float) Math.max(0.0, Math.min(1.0, volume));
    }

    static float boundedPitch(double pitch) {
        return !Double.isFinite(pitch) ? 1.0f : (float) Math.max(0.0, Math.min(Float.MAX_VALUE, pitch));
    }

    private MusicPolicy() {}
}
