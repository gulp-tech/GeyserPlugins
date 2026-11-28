package ru.voidpe.pnx.gifts;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class GiftLedgerTest {
    private static void ok(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("gift-ledger-test-");
        try {
            Path path = root.resolve("data/gifts.json");
            GiftLedger ledger = new GiftLedger(path);
            var location = new GiftLedger.Location("world.with.dots", 0, -2, 64, 5);
            var nether = new GiftLedger.Location("world.with.dots", 1, -2, 64, 5);
            UUID user = UUID.randomUUID(), other = UUID.randomUUID();
            ok(ledger.claim(location,user) == GiftLedger.ClaimResult.NOT_A_GIFT);
            ledger.register(location, "present"); ledger.register(nether, "nether_present");
            ok(ledger.claim(location,user) == GiftLedger.ClaimResult.RECORDED);
            ok(ledger.claim(location,user) == GiftLedger.ClaimResult.ALREADY_CLAIMED);
            ok(!ledger.hasClaimed(nether,user));
            var reloaded = new GiftLedger(path);
            ok(reloaded.giftType(location).orElseThrow().equals("present") && reloaded.hasClaimed(location,user));
            ok(reloaded.unregister(location)); ok(!reloaded.unregister(location));
            ok(reloaded.claim(location,other) == GiftLedger.ClaimResult.NOT_A_GIFT);
            reloaded.register(location,"new_present");
            ok(reloaded.claim(location,user) == GiftLedger.ClaimResult.ALREADY_CLAIMED);
            ok(new GiftLedger(path).giftType(location).orElseThrow().equals("new_present"));

            var pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<GiftLedger.ClaimResult>> futures = new ArrayList<>();
                for (int i=0;i<30;i++) futures.add(pool.submit(() -> reloaded.claim(nether,other)));
                int accepted=0;
                for (var f : futures) if (f.get()==GiftLedger.ClaimResult.RECORDED) accepted++;
                ok(accepted==1 && new GiftLedger(path).hasClaimed(nether,other));
            } finally { pool.shutdownNow(); }

            byte[] good=Files.readAllBytes(path);
            Files.delete(path); Files.createDirectory(path); Files.writeString(path.resolve("not-empty"),"x");
            UUID failurePlayer=UUID.randomUUID();
            try { reloaded.claim(location,failurePlayer); throw new AssertionError("failed write was accepted"); }
            catch (java.io.IOException expected) {}
            ok(!reloaded.hasClaimed(location,failurePlayer));
            Files.delete(path.resolve("not-empty")); Files.delete(path); Files.write(path,good);
            ok(!new GiftLedger(path).hasClaimed(location,failurePlayer));
            for (String invalid : List.of("{bad", "{\"format\":1,\"format\":1,\"gifts\":[]}", "{\"format\":1,\"gifts\":[{\"world\":\"x\",\"dimension\":0,\"x\":0,\"y\":0,\"z\":0,\"type\":\"x\",\"claimed\":[\"not-uuid\"]}]}")) {
                Files.writeString(path,invalid,StandardCharsets.UTF_8);
                try { new GiftLedger(path); throw new AssertionError("corrupt data accepted"); }
                catch (java.io.IOException expected) {}
                ok(Files.readString(path).equals(invalid));
            }
            System.out.println("GiftLedger persistence and concurrency tests passed");
        } finally { try (var walk = Files.walk(root)) { for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); } }
    }
}
