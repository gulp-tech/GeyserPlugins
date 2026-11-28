package ru.voidpe.pnx.gifts;
import java.util.*;
public final class GiftPolicyTest {
    private static void ok(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) {
        ok(!GiftRewards.roll(0,0) && !GiftRewards.roll(-1,0) && !GiftRewards.roll(Double.NaN,0));
        ok(GiftRewards.roll(100,0.999999) && GiftRewards.roll(200,0.999999));
        ok(GiftRewards.roll(50,0.499999) && !GiftRewards.roll(50,0.5));
        try { GiftRewards.roll(100,1); throw new AssertionError(); } catch (IllegalArgumentException expected) {}
        var rotation = new GiftMusicRotation(); var random = new Random(42); UUID a=UUID.randomUUID(), b=UUID.randomUUID();
        List<String> tracks=List.of("a","b","c"); Set<String> first=new HashSet<>();
        for (int i=0;i<3;i++) first.add(rotation.next(a,tracks,random).orElseThrow());
        ok(first.equals(Set.of("a","b","c")));
        ok(rotation.next(a,tracks,random).isPresent());
        ok(rotation.next(b,List.of("z"),random).orElseThrow().equals("z"));
        ok(rotation.next(b,List.of("z"),random).orElseThrow().equals("z"));
        ok(rotation.next(a,List.of(),random).isEmpty());
        ok(rotation.next(a,List.of("new"),random).orElseThrow().equals("new"));
        rotation.forget(a); ok(rotation.next(a,List.of("new"),random).orElseThrow().equals("new"));
        System.out.println("Gift independent rewards and music rotation passed");
    }
}
