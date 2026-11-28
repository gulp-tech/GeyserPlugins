package ru.voidpe.pnx.gifts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Single-server instance, synchronized and fail-closed persistent gift ledger.
 * A successful claim is persisted before a caller may give any rewards.
 * This guarantees at-most-once acceptance, not exactly-once reward delivery.
 */
public final class GiftLedger {
    private static final int MAX_RECORDS = 200_000;
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private final Path file;
    private Map<Location, State> records;

    public record Location(String world, int dimension, int x, int y, int z) {
        public Location {
            if (world == null || world.isBlank() || world.length() > 256) throw new IllegalArgumentException("Invalid world name");
        }
    }
    public enum ClaimResult { RECORDED, ALREADY_CLAIMED, NOT_A_GIFT }
    private record State(String type, Set<UUID> claimed) {
        State { claimed = Set.copyOf(claimed); }
    }

    public GiftLedger(Path file) throws IOException {
        this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
        records = load(this.file);
    }

    public synchronized Optional<String> giftType(Location location) {
        State state = records.get(Objects.requireNonNull(location));
        return state == null ? Optional.empty() : Optional.ofNullable(state.type());
    }
    public synchronized boolean hasClaimed(Location location, UUID player) {
        State state = records.get(Objects.requireNonNull(location));
        return state != null && state.claimed().contains(Objects.requireNonNull(player));
    }
    public synchronized void register(Location location, String type) throws IOException {
        Objects.requireNonNull(location);
        if (type == null || type.isBlank() || type.length() > 256) throw new IllegalArgumentException("Invalid gift type");
        State old = records.get(location);
        Map<Location,State> next = new LinkedHashMap<>(records);
        // Match the old plugin: removing and replacing a gift at the same coordinates
        // does not erase the history of players who already claimed that position.
        next.put(location, new State(type, old == null ? Set.of() : old.claimed()));
        commit(next);
    }
    public synchronized boolean unregister(Location location) throws IOException {
        Objects.requireNonNull(location);
        State old = records.get(location);
        if (old == null || old.type() == null) return false;
        Map<Location,State> next = new LinkedHashMap<>(records);
        if (old.claimed().isEmpty()) next.remove(location); else next.put(location, new State(null, old.claimed()));
        commit(next);
        return true;
    }
    public synchronized ClaimResult claim(Location location, UUID player) throws IOException {
        Objects.requireNonNull(location); Objects.requireNonNull(player);
        State state = records.get(location);
        if (state == null || state.type() == null) return ClaimResult.NOT_A_GIFT;
        if (state.claimed().contains(player)) return ClaimResult.ALREADY_CLAIMED;
        Set<UUID> claimants = new LinkedHashSet<>(state.claimed()); claimants.add(player);
        Map<Location,State> next = new LinkedHashMap<>(records);
        next.put(location, new State(state.type(), claimants));
        commit(next);
        return ClaimResult.RECORDED;
    }

    private void commit(Map<Location,State> next) throws IOException {
        if (next.size() > MAX_RECORDS) throw new IOException("Too many gift locations");
        if (Files.isSymbolicLink(file)) throw new IOException("Gift ledger must not be a symlink");
        byte[] bytes = encode(next);
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("Gift ledger exceeds size limit");
        Path parent = file.getParent(); Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".gift-ledger-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { throw new IOException("Atomic gift ledger writes are unavailable", e); }
            records = next;
        } finally { Files.deleteIfExists(temp); }
    }
    private static byte[] encode(Map<Location,State> data) {
        JsonObject root = new JsonObject(); root.addProperty("format",1); JsonArray list = new JsonArray();
        // Canonical order makes it easier to inspect/compare snapshots.
        var ordered = data.entrySet().stream().sorted(Comparator
            .comparing((Map.Entry<Location,State> e) -> e.getKey().world())
            .thenComparingInt(e -> e.getKey().dimension()).thenComparingInt(e -> e.getKey().x())
            .thenComparingInt(e -> e.getKey().y()).thenComparingInt(e -> e.getKey().z())).toList();
        for (var entry : ordered) {
            Location l = entry.getKey(); State s = entry.getValue(); JsonObject obj = new JsonObject();
            obj.addProperty("world",l.world()); obj.addProperty("dimension",l.dimension());
            obj.addProperty("x",l.x()); obj.addProperty("y",l.y()); obj.addProperty("z",l.z());
            if (s.type() == null) obj.add("type",com.google.gson.JsonNull.INSTANCE); else obj.addProperty("type",s.type());
            JsonArray claims = new JsonArray(); s.claimed().stream().map(UUID::toString).sorted().forEach(claims::add);
            obj.add("claimed",claims); list.add(obj);
        }
        root.add("gifts",list); return root.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static Map<Location,State> load(Path file) throws IOException {
        Map<Location,State> result = new LinkedHashMap<>();
        if (Files.isSymbolicLink(file)) throw new IOException("Gift ledger must not be a symlink");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return result;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE_BYTES) throw new IOException("Gift ledger is not a valid file");
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            validateJson(json);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (strictInt(root.get("format")) != 1) throw new IOException("Unsupported gift ledger format");
            JsonArray entries = root.getAsJsonArray("gifts");
            if (entries == null || entries.size() > MAX_RECORDS) throw new IOException("Invalid gift location list");
            for (JsonElement element : entries) {
                JsonObject obj = element.getAsJsonObject();
                String world = strictString(obj.get("world"));
                Location loc = new Location(world, strictInt(obj.get("dimension")), strictInt(obj.get("x")), strictInt(obj.get("y")), strictInt(obj.get("z")));
                JsonElement typeElement = obj.get("type");
                if (typeElement == null) throw new IOException("Missing gift type field");
                String type = typeElement.isJsonNull() ? null : strictString(typeElement);
                if (type != null && (type.isBlank() || type.length() > 256)) throw new IOException("Invalid gift type");
                JsonArray raw = obj.getAsJsonArray("claimed");
                if (raw == null || raw.size() > MAX_RECORDS) throw new IOException("Invalid gift claimants");
                Set<UUID> players = new HashSet<>();
                for (JsonElement claim : raw) {
                    UUID uuid = UUID.fromString(strictString(claim));
                    if (!players.add(uuid)) throw new IOException("Duplicate claimant");
                }
                if (type == null && players.isEmpty()) throw new IOException("Empty removed gift record");
                if (result.putIfAbsent(loc,new State(type,players)) != null) throw new IOException("Duplicate gift location");
            }
            return result;
        } catch (IOException e) { throw e; }
        catch (RuntimeException e) { throw new IOException("Invalid gift ledger; file left untouched", e); }
    }
    static void validateJson(String json) throws IOException {
        try (var reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(json))) {
            reader.setStrictness(com.google.gson.Strictness.STRICT);
            validateValue(reader, 0);
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) throw new IOException("Extra data in gift ledger");
        }
    }
    private static void validateValue(com.google.gson.stream.JsonReader reader, int depth) throws IOException {
        if (depth > 16) throw new IOException("Gift ledger nesting too deep");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); Set<String> keys = new HashSet<>();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (!keys.add(key)) throw new IOException("Duplicate JSON field: " + key);
                    validateValue(reader, depth + 1);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); while (reader.hasNext()) validateValue(reader, depth + 1); reader.endArray();
            }
            case STRING, NUMBER -> reader.nextString();
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> reader.nextNull();
            default -> throw new IOException("Invalid gift ledger JSON");
        }
    }
    private static String strictString(JsonElement value) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Expected text");
        return value.getAsString();
    }
    private static int strictInt(JsonElement value) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IOException("Expected integer");
        try { return new java.math.BigDecimal(value.getAsString()).intValueExact(); }
        catch (NumberFormatException | ArithmeticException e) { throw new IOException("Integer out of range",e); }
    }
}
