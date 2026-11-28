package ru.voidpe.pnx.gifts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.powernukkitx.item.Item;
import org.powernukkitx.item.ItemUnknown;
import org.powernukkitx.nbt.tag.CompoundTag;
import org.powernukkitx.utils.ItemHelper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * PNX item snapshots for gift rewards. Methods are synchronized for one server
 * instance; every mutation is committed to disk before it is visible in memory.
 * Corrupt or unregistered items stop loading: the previous file is not overwritten.
 */
public final class GiftLootStore {
    private static final int MAX_ITEM_BYTES = 1024 * 1024;
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private static final int MAX_REWARDS = 20_000;
    private final Path file;
    private Map<String, List<Stored>> groups;

    private record Stored(UUID id, double chance, byte[] nbt) {
        Stored { nbt = nbt.clone(); }
    }
    /** Returned items are always independent clones. */
    public record Reward(UUID id, double chance, Item stored) {
        public Reward { Objects.requireNonNull(id); stored = Objects.requireNonNull(stored).clone(); }
        @Override public Item stored() { return stored.clone(); }
    }

    public GiftLootStore(Path file) throws IOException {
        this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
        groups = load(this.file);
    }
    public synchronized List<Reward> rewards(String type) throws IOException {
        requireType(type); List<Reward> out = new ArrayList<>();
        for (Stored value : groups.getOrDefault(type, List.of())) out.add(new Reward(value.id(),value.chance(),decode(value.nbt())));
        return List.copyOf(out);
    }
    public synchronized UUID add(String type, Item item) throws IOException {
        requireType(type);
        byte[] nbt = encode(item);
        UUID id = UUID.randomUUID();
        List<Stored> items = new ArrayList<>(groups.getOrDefault(type,List.of()));
        items.add(new Stored(id,100.0,nbt));
        replace(type,items); return id;
    }
    public synchronized boolean setChance(String type, UUID id, double chance) throws IOException {
        requireType(type); Objects.requireNonNull(id);
        if (!Double.isFinite(chance)) throw new IllegalArgumentException("Chance must be finite");
        List<Stored> items = new ArrayList<>(groups.getOrDefault(type,List.of()));
        for (int i=0;i<items.size();i++) {
            Stored old = items.get(i);
            if (old.id().equals(id)) { items.set(i,new Stored(id,GiftRewards.clampChance(chance),old.nbt())); replace(type,items); return true; }
        }
        return false;
    }
    public synchronized boolean remove(String type, UUID id) throws IOException {
        requireType(type); Objects.requireNonNull(id);
        List<Stored> items = new ArrayList<>(groups.getOrDefault(type,List.of()));
        if (!items.removeIf(reward -> reward.id().equals(id))) return false;
        replace(type,items); return true;
    }
    private void replace(String type, List<Stored> values) throws IOException {
        Map<String,List<Stored>> next = new LinkedHashMap<>(groups);
        if (values.isEmpty()) next.remove(type); else next.put(type,List.copyOf(values));
        commit(next); groups = next;
    }
    private void commit(Map<String,List<Stored>> data) throws IOException {
        if (Files.isSymbolicLink(file)) throw new IOException("Loot file must not be a symlink");
        int total = data.values().stream().mapToInt(List::size).sum();
        if (total > MAX_REWARDS) throw new IOException("Too many gift rewards");
        byte[] encoded = encodeData(data);
        if (encoded.length > MAX_FILE_BYTES) throw new IOException("Gift loot file exceeds size limit");
        Path parent = file.getParent(); Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent,".gift-loot-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { throw new IOException("Atomic loot writes are unavailable",e); }
        } finally { Files.deleteIfExists(temp); }
    }
    private static byte[] encodeData(Map<String,List<Stored>> data) {
        JsonObject root=new JsonObject(); root.addProperty("format",1); JsonArray list=new JsonArray();
        data.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            JsonObject group=new JsonObject(); group.addProperty("type",entry.getKey()); JsonArray rewards=new JsonArray();
            for (Stored s : entry.getValue()) {
                JsonObject reward=new JsonObject(); reward.addProperty("id",s.id().toString()); reward.addProperty("chance",s.chance());
                reward.addProperty("item_nbt",Base64.getEncoder().encodeToString(s.nbt())); rewards.add(reward);
            }
            group.add("rewards",rewards); list.add(group);
        });
        root.add("groups",list); return root.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static Map<String,List<Stored>> load(Path path) throws IOException {
        Map<String,List<Stored>> loaded=new LinkedHashMap<>();
        if (Files.isSymbolicLink(path)) throw new IOException("Loot file must not be a symlink");
        if (!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) return loaded;
        if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>MAX_FILE_BYTES) throw new IOException("Invalid gift loot file");
        try {
            String json=Files.readString(path,StandardCharsets.UTF_8); GiftLedger.validateJson(json);
            JsonObject root=JsonParser.parseString(json).getAsJsonObject();
            if (integer(root.get("format"))!=1) throw new IOException("Unsupported gift loot format");
            JsonArray groups=root.getAsJsonArray("groups");
            if (groups==null || groups.size()>MAX_REWARDS) throw new IOException("Invalid gift groups");
            Set<UUID> used=new HashSet<>(); int total=0;
            for (JsonElement element : groups) {
                JsonObject group=element.getAsJsonObject(); String type=text(group.get("type")); requireType(type);
                JsonArray rewards=group.getAsJsonArray("rewards");
                if (rewards==null || rewards.size()>MAX_REWARDS || (total+=rewards.size())>MAX_REWARDS) throw new IOException("Invalid gift reward list");
                List<Stored> list=new ArrayList<>();
                for (JsonElement value : rewards) {
                    JsonObject reward=value.getAsJsonObject(); UUID id=UUID.fromString(text(reward.get("id")));
                    if (!used.add(id)) throw new IOException("Duplicate gift reward ID");
                    JsonElement chanceValue=reward.get("chance");
                    if (chanceValue==null || !chanceValue.isJsonPrimitive() || !chanceValue.getAsJsonPrimitive().isNumber()) throw new IOException("Invalid gift reward chance");
                    double chance=chanceValue.getAsDouble();
                    if (!Double.isFinite(chance) || chance<0 || chance>100) throw new IOException("Gift reward chance outside range");
                    String encoded=text(reward.get("item_nbt"));
                    if (encoded.length()>((MAX_ITEM_BYTES+2)/3)*4) throw new IOException("Item NBT too large");
                    byte[] nbt=Base64.getDecoder().decode(encoded); decode(nbt);
                    list.add(new Stored(id,chance,nbt));
                }
                if (loaded.putIfAbsent(type,List.copyOf(list))!=null) throw new IOException("Duplicate gift type");
            }
            return loaded;
        } catch (IOException e) { throw e; }
        catch (RuntimeException e) { throw new IOException("Invalid gift loot; file left untouched",e); }
    }
    private static String text(JsonElement value) throws IOException {
        if (value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Expected text");
        return value.getAsString();
    }
    private static int integer(JsonElement value) throws IOException {
        if (value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IOException("Expected integer");
        try { return new java.math.BigDecimal(value.getAsString()).intValueExact(); }
        catch (ArithmeticException | NumberFormatException e) { throw new IOException("Invalid integer",e); }
    }
    private static void requireType(String type) {
        if (type==null || type.isBlank() || type.length()>256) throw new IllegalArgumentException("Invalid gift type");
    }
    private static byte[] encode(Item item) throws IOException {
        if (item==null || item.isNull() || item instanceof ItemUnknown || item.getCount()<1 || item.getCount()>127) throw new IOException("Invalid, unknown or oversized item stack");
        try {
            CompoundTag tag=ItemHelper.write(item.clone());
            byte[] bytes=item.writeCompoundTag(tag);
            if (bytes.length==0 || bytes.length>MAX_ITEM_BYTES) throw new IOException("Item NBT size invalid");
            Item copy=decode(bytes);
            if (!item.equalsExact(copy) || !tag.toNetwork().equals(ItemHelper.write(copy).toNetwork())) throw new IOException("Item cannot be saved without losing data");
            return bytes;
        } catch (IOException e) { throw e; }
        catch (RuntimeException e) { throw new IOException("Failed to serialize gift item",e); }
    }
    private static Item decode(byte[] bytes) throws IOException {
        if (bytes==null || bytes.length==0 || bytes.length>MAX_ITEM_BYTES) throw new IOException("Item NBT size invalid");
        try {
            CompoundTag raw=Item.parseCompoundTag(bytes);
            if (raw==null) throw new IOException("Empty item NBT");
            var original=raw.toNetwork();
            Item item=ItemHelper.read(raw);
            if (item==null || item.isNull() || item instanceof ItemUnknown || item.getCount()<1 || item.getCount()>127) throw new IOException("Unknown or invalid gift item");
            if (!original.equals(ItemHelper.write(item).toNetwork())) throw new IOException("Item data changed during decoding");
            return item;
        } catch (IOException e) { throw e; }
        catch (RuntimeException e) { throw new IOException("Cannot restore gift item; check custom item registrations",e); }
    }
}
