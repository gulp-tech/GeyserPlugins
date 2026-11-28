package ru.voidpe.pnx.rtpzone;

import java.nio.file.*;
import java.util.*;

public final class ZoneStoreTest {
    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory("rtpzone-store-");
        try {
            Path path = folder.resolve("zones.yml"); ZoneStore store = new ZoneStore(path);
            check(store.load().isEmpty());
            Zone spawn = Zone.between("spawn", "world", 0, -4,-5,-6, 1,2,3);
            Zone end = Zone.between("end", "world", 2, -1,0,0, 1,5,8);
            Map<String,Zone> desired = new LinkedHashMap<>(); desired.put(spawn.name(),spawn); desired.put(end.name(),end);
            store.save(desired); check(store.load().equals(desired));
            store.save(Map.of()); check(store.load().isEmpty());
            Files.writeString(path, "zones:\n  old:\n    world: world\n    min-x: -2\n    min-y: 0\n    min-z: 0\n    max-x: 4\n    max-y: 5\n    max-z: 6\n");
            check(store.load().get("old").dimension() == 0);
            Files.writeString(path, "zones:\n  bad:\n    world: world\n    min-x: 0\n");
            String before = Files.readString(path);
            try { store.load(); throw new AssertionError("invalid data accepted"); } catch (java.io.IOException expected) {}
            check(Files.readString(path).equals(before));
            System.out.println("VoidRtpZone storage passed");
        } finally {
            try (var files = Files.walk(folder)) { for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
    private static void check(boolean b) { if (!b) throw new AssertionError(); }
}
