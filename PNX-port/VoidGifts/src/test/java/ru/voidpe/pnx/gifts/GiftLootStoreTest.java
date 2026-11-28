package ru.voidpe.pnx.gifts;

import org.powernukkitx.item.Item;
import org.powernukkitx.nbt.tag.CompoundTag;
import org.powernukkitx.utils.ItemHelper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Runs inside an actual PNX instance after item registries have initialized. */
public final class GiftLootStoreTest {
    private static void ok(boolean condition, String description) { if (!condition) throw new AssertionError(description); }
    public static void run() throws Exception {
        Path root=Files.createTempDirectory("gift-loot-test-");
        try {
            Path file=root.resolve("data/loot.json"); GiftLootStore store=new GiftLootStore(file);
            Item sword=Item.get("minecraft:diamond_sword",7,1);
            ok(!sword.isNull(),"diamond sword registered");
            sword.setCustomName("§dПредмет из подарка"); sword.setLore("§eСтрока 1", "§7Строка 2");
            CompoundTag tag=sword.getOrCreateNbt(); tag.putCompound("voidpe_test",new CompoundTag().putString("identity","example").putInt("number",42)); sword.setNbt(tag);
            var originalNbt=ItemHelper.write(sword).toNetwork();
            UUID swordId=store.add("present",sword);
            sword.setCustomName("изменённый оригинал");
            var saved=store.rewards("present");
            ok(saved.size()==1 && saved.get(0).id().equals(swordId),"saved reward");
            Item stored=saved.get(0).stored();
            ok(ItemHelper.write(stored).toNetwork().equals(originalNbt),"all item metadata preserved");
            stored.setCustomName("изменённая копия");
            ok(ItemHelper.write(store.rewards("present").get(0).stored()).toNetwork().equals(originalNbt),"defensive copies");
            ok(store.setChance("present",swordId,105),"chance exists");
            ok(store.rewards("present").get(0).chance()==100,"chance clamp");
            ok(store.setChance("present",swordId,15.5),"fraction chance");
            try { store.setChance("present",swordId,Double.NaN); throw new AssertionError("NaN accepted"); } catch (IllegalArgumentException expected) {}
            Item diamond=Item.get("minecraft:diamond",0,12); UUID diamondId=store.add("present",diamond);
            Item potion=Item.get("minecraft:potion",5,3); UUID potionId=store.add("present",potion);
            Item block=Item.get("minecraft:stone",0,5); UUID blockId=store.add("другой тип",block);
            GiftLootStore reload=new GiftLootStore(file);
            ok(reload.rewards("present").size()==3 && reload.rewards("present").get(0).chance()==15.5,"restart and chance");
            ok(ItemHelper.write(reload.rewards("present").get(0).stored()).toNetwork().equals(originalNbt),"item NBT after restart");
            ok(reload.rewards("present").get(1).stored().equalsExact(diamond),"stack count after restart");
            ok(reload.rewards("present").get(2).id().equals(potionId) && reload.rewards("present").get(2).stored().equalsExact(potion),"potion metadata and count");
            ok(reload.rewards("другой тип").get(0).id().equals(blockId),"separate type");
            ok(reload.remove("present",diamondId) && !reload.remove("present",diamondId),"remove");
            ok(!reload.setChance("present",UUID.randomUUID(),10),"missing id");
            try { reload.add("present",Item.AIR); throw new AssertionError("air accepted"); } catch (java.io.IOException expected) {}

            byte[] good=Files.readAllBytes(file); Files.delete(file); Files.createDirectory(file); Files.writeString(file.resolve("block"),"x");
            try { reload.setChance("present",swordId,0); throw new AssertionError("failed save accepted"); } catch (java.io.IOException expected) {}
            ok(reload.rewards("present").get(0).chance()==15.5,"failed save left state unchanged");
            Files.delete(file.resolve("block")); Files.delete(file); Files.write(file,good);
            ok(new GiftLootStore(file).rewards("present").get(0).chance()==15.5,"persisted state unchanged");
            for(String invalid:List.of("{broken", "{\"format\":1,\"format\":1,\"groups\":[]}", "{\"format\":1,\"groups\":[{\"type\":\"a\",\"rewards\":[{\"id\":\"00000000-0000-0000-0000-000000000001\",\"chance\":100,\"item_nbt\":\"bad\"}]}]}")) {
                Files.writeString(file,invalid,StandardCharsets.UTF_8);
                try { new GiftLootStore(file); throw new AssertionError("invalid file accepted"); } catch (java.io.IOException expected) {}
                ok(Files.readString(file).equals(invalid),"invalid file not overwritten");
            }
            System.out.println("GiftLootStore actual PNX item and persistence tests passed");
        } finally { try (var walk=Files.walk(root)) { for(Path p:walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); } }
    }
}
