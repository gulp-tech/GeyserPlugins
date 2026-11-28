package ru.voidpe.pnx.gifts;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class GiftMusicPackTest {
    private static void ok(boolean condition) { if (!condition) throw new AssertionError(); }
    private static byte[] fake(String suffix) { return ("OggS" + suffix).getBytes(StandardCharsets.UTF_8); }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("gift-pack-test-");
        try {
            Path music=root.resolve("music"), active=root.resolve("resource_packs/VoidGiftsPack.mcpack"), stage=root.resolve("staging");
            Files.createDirectories(music); Files.createDirectories(active.getParent());
            Files.write(music.resolve("A!.OGG"), fake("first")); Files.write(music.resolve("A?.ogg"), fake("second"));
            var one=GiftMusicPack.prepare(music,active,stage);
            ok(one.changed() && one.version()==1 && !Files.exists(active) && one.tracks().size()==2);
            ok(one.tracks().get(0).soundId().equals("voidpe:gift_a_"));
            ok(one.tracks().get(1).soundId().equals("voidpe:gift_a__2"));
            GiftMusicPack.verifyCandidate(one);
            try (ZipFile z=new ZipFile(one.candidate().toFile())) {
                var d=JsonParser.parseString(new String(z.getInputStream(z.getEntry("sounds/sound_definitions.json")).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("sound_definitions");
                ok(d.size()==2 && z.getEntry(one.tracks().get(1).packPath()+".ogg")!=null);
            }
            Files.copy(one.candidate(),active);
            byte[] original = Files.readAllBytes(active);
            var unchanged=GiftMusicPack.prepare(music,active,stage);
            ok(!unchanged.changed() && unchanged.candidate()==null && unchanged.uuid().equals(one.uuid()));
            Files.write(music.resolve("A!.OGG"),fake("changed"));
            var two=GiftMusicPack.prepare(music,active,stage);
            ok(two.changed() && two.version()==2 && two.uuid().equals(one.uuid()));
            ok(Arrays.equals(original,Files.readAllBytes(active)));
            byte[] previousCandidate=Files.readAllBytes(two.candidate());
            Files.write(music.resolve("broken.ogg"), new byte[]{1,2,3,4});
            try { GiftMusicPack.prepare(music,active,stage); throw new AssertionError("invalid OGG accepted"); } catch (java.io.IOException expected) {}
            ok(Arrays.equals(original,Files.readAllBytes(active)) && Arrays.equals(previousCandidate,Files.readAllBytes(two.candidate())));
            Files.delete(music.resolve("broken.ogg"));
            Files.write(two.candidate(), new byte[]{1,2,3});
            try { GiftMusicPack.verifyCandidate(two); throw new AssertionError("corrupt candidate accepted"); } catch (java.io.IOException expected) {}
            Files.write(active, new byte[]{1,2,3});
            try { GiftMusicPack.prepare(music,active,stage); throw new AssertionError("corrupt active pack accepted"); } catch (java.io.IOException expected) {}
            System.out.println("GiftMusicPack test passed");
        } finally { try (var walk=Files.walk(root)) { for(Path p:walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); } }
    }
}
