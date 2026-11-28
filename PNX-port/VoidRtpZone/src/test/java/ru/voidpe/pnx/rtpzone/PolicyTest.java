package ru.voidpe.pnx.rtpzone;
import java.util.UUID;
public final class PolicyTest {
    private static void ok(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        Zone z = Zone.between("spawn", "world", 0, 10, 5, -2, -4, -1, 2);
        ok(z.contains(-4,-1,-2) && z.contains(10,5,2) && !z.contains(-5,0,0));
        ok(z.volume().equals("525"));
        try { Zone.between("bad.name", "world", 0, 0,0,0, 1,1,1); throw new AssertionError(); } catch (IllegalArgumentException expected) {}
        ok(Zone.between("huge", "w", 0, Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE).volume().equals("79228162514264337593543950336"));
        EntryTracker t = new EntryTracker(); UUID id = UUID.randomUUID();
        ok(t.update(id, true, false, 10000, 1000));
        ok(!t.update(id, true, false, 11000, 1000));
        ok(!t.update(id, false, false, 11000, 1000));
        ok(!t.update(id, true, false, 10500, 1000));
        ok(!t.update(id, true, false, 12000, 1000));
        t.leave(id); ok(t.update(id, true, false, 12000, 1000));
        t.leave(id); ok(!t.update(id, true, true, 14000, 1000));
        t.leave(id); ok(t.update(id, true, false, 14000, 1000));
        t.forget(id); ok(t.update(id, true, false, 1, 1000));
        System.out.println("VoidRtpZone policy passed");
    }
}
