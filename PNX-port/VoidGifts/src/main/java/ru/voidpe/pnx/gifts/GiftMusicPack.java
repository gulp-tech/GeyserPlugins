package ru.voidpe.pnx.gifts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Prepares an immutable candidate pack in a staging directory, never in the
 * live pack path. The caller may activate it ONLY before networking starts.
 */
public final class GiftMusicPack {
    private static final long MAX_TRACK = 64L * 1024 * 1024;
    private static final long MAX_TOTAL = 256L * 1024 * 1024;
    private static final int MAX_TRACKS = 1024;
    private static final String FINGERPRINT = "voidgifts-content-sha256.txt";
    private static final byte[] OGG = { 'O', 'g', 'g', 'S' };

    public record Track(String fileName, String soundId, String packPath, Path source, long size, String sha256) {}
    public record Result(boolean changed, UUID uuid, int version, List<Track> tracks, Path candidate) {
        public Result { tracks = List.copyOf(tracks); }
    }
    private record Identity(UUID uuid, UUID module, int patch, String fingerprint) {}
    private GiftMusicPack() {}

    /**
     * Builds a candidate if music changed. active is only read, never modified.
     * If unchanged, candidate is null; caller must not activate any old staging file.
     * Returns sound IDs matching the exact contents of the resulting pack.
     */
    public static Result prepare(Path musicDir, Path active, Path stagingDir) throws IOException {
        Path music = musicDir.toAbsolutePath().normalize();
        Path current = active.toAbsolutePath().normalize();
        Path stage = stagingDir.toAbsolutePath().normalize();
        if (current.startsWith(stage) || stage.startsWith(current) || music.startsWith(current) || current.startsWith(music))
            throw new IOException("Music, active pack and staging paths must be separate");
        Files.createDirectories(music);
        Files.createDirectories(stage);
        if (Files.isSymbolicLink(current)) throw new IOException("Active pack must not be a symlink");
        List<Track> tracks = scan(music);
        String fingerprint = fingerprint(tracks);
        Identity identity = readIdentity(current);
        if (identity != null && fingerprint.equals(identity.fingerprint()) && matchesActive(current, tracks))
            return new Result(false, identity.uuid(), identity.patch(), tracks, null);
        UUID uuid = identity == null ? UUID.randomUUID() : identity.uuid();
        UUID module = identity == null ? UUID.randomUUID() : identity.module();
        if (identity != null && identity.patch() == Integer.MAX_VALUE) throw new IOException("Gift pack version exhausted");
        int patch = identity == null ? 1 : identity.patch() + 1;
        Path temp = Files.createTempFile(stage, "gift-pack-", ".tmp");
        Path candidate = stage.resolve("VoidGiftsPack.pending.mcpack");
        try {
            build(temp, uuid, module, patch, fingerprint, tracks);
            try { Files.move(temp, candidate, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { throw new IOException("Staging directory does not support atomic replacement", e); }
            return new Result(true, uuid, patch, tracks, candidate);
        } finally { Files.deleteIfExists(temp); }
    }

    private static List<Track> scan(Path music) throws IOException {
        List<Path> paths;
        try (var stream = Files.list(music)) {
            paths = stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg"))
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(p -> p.getFileName().toString())).toList();
        }
        if (paths.size() > MAX_TRACKS) throw new IOException("Too many music files");
        List<Track> result = new ArrayList<>(); Set<String> used = new HashSet<>(); long total = 0;
        for (Path path : paths) {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular music file: " + path.getFileName());
            String file = path.getFileName().toString();
            String base = file.substring(0, file.length() - 4).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
            if (base.isEmpty()) base = "track";
            String id = base; int suffix = 1;
            while (!used.add(id)) id = base + "_" + (++suffix);
            long size = Files.size(path); total = Math.addExact(total, size);
            if (size < 4 || size > MAX_TRACK || total > MAX_TOTAL) throw new IOException("Music size limit exceeded or file empty: " + file);
            String digest = digestFile(path);
            result.add(new Track(file, "voidpe:gift_" + id, "sounds/gifts/" + id, path, size, digest));
        }
        return List.copyOf(result);
    }
    private static String digestFile(Path path) throws IOException {
        MessageDigest md = digest();
        try (InputStream in = Files.newInputStream(path)) {
            byte[] first = in.readNBytes(4);
            if (!Arrays.equals(first, OGG)) throw new IOException("Expected Ogg file: " + path.getFileName());
            md.update(first); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) md.update(buf,0,n);
        }
        return HexFormat.of().formatHex(md.digest());
    }
    private static String fingerprint(List<Track> tracks) {
        MessageDigest md = digest();
        // Format marker makes later changes to pack metadata invalidate the cache.
        md.update("voidgifts-pack-v1\0".getBytes(StandardCharsets.UTF_8));
        for (Track t : tracks) {
            for (String part : List.of(t.fileName(), t.soundId(), t.packPath(), t.sha256())) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                md.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); md.update(bytes);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }
    private static Identity readIdentity(Path active) throws IOException {
        if (!Files.exists(active, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(active, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Active pack is not a regular file");
        try (ZipFile zip = new ZipFile(active.toFile())) {
            JsonObject manifest = JsonParser.parseString(readText(zip, "manifest.json", 65536)).getAsJsonObject();
            JsonObject header = manifest.getAsJsonObject("header");
            if (!"VoidPE Gifts".equals(header.get("name").getAsString())) throw new IOException("Existing pack is not a VoidPE Gifts pack");
            UUID uuid = UUID.fromString(header.get("uuid").getAsString());
            JsonArray version = header.getAsJsonArray("version");
            if (version.size() != 3 || version.get(0).getAsInt() != 1 || version.get(1).getAsInt() != 0 || version.get(2).getAsInt() < 0)
                throw new IOException("Unsupported gift pack version");
            JsonArray modules = manifest.getAsJsonArray("modules");
            UUID module = null;
            for (JsonElement element : modules) {
                JsonObject m = element.getAsJsonObject();
                if ("resources".equals(m.get("type").getAsString())) { module = UUID.fromString(m.get("uuid").getAsString()); break; }
            }
            if (module == null) throw new IOException("Gift pack has no resources module");
            String old = zip.getEntry(FINGERPRINT) == null ? "" : readText(zip, FINGERPRINT, 128).trim();
            return new Identity(uuid, module, version.get(2).getAsInt(), old);
        } catch (IOException e) { throw e; }
        catch (RuntimeException e) { throw new IOException("Invalid existing gift pack, refusing to replace it", e); }
    }
    static void verifyCandidate(Result result) throws IOException {
        if (!result.changed() || result.candidate() == null || Files.isSymbolicLink(result.candidate())) throw new IOException("No valid candidate pack");
        Identity id = readIdentity(result.candidate());
        if (id == null || !id.uuid().equals(result.uuid()) || id.patch() != result.version()
                || !id.fingerprint().equals(fingerprint(result.tracks())) || !matchesActive(result.candidate(), result.tracks()))
            throw new IOException("Candidate pack changed or is invalid");
    }
    private static boolean matchesActive(Path path, List<Track> tracks) {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            JsonObject root = JsonParser.parseString(readText(zip, "sounds/sound_definitions.json", 1024 * 1024)).getAsJsonObject();
            JsonObject definitions = root.getAsJsonObject("sound_definitions");
            if (definitions.size() != tracks.size()) return false;
            for (Track track : tracks) {
                JsonObject sound = definitions.getAsJsonObject(track.soundId());
                JsonArray list = sound.getAsJsonArray("sounds");
                if (list.size() != 1) return false;
                JsonObject entry = list.get(0).getAsJsonObject();
                if (!track.packPath().equals(entry.get("name").getAsString()) || !entry.get("stream").getAsBoolean()) return false;
                ZipEntry audio = zip.getEntry(track.packPath() + ".ogg");
                if (audio == null || audio.getSize() != track.size()) return false;
                MessageDigest md = digest(); long count = 0; byte[] buffer = new byte[8192];
                try (InputStream in = zip.getInputStream(audio)) {
                    int n; while ((n = in.read(buffer)) != -1) { count += n; if (count > track.size()) return false; md.update(buffer,0,n); }
                }
                if (count != track.size() || !HexFormat.of().formatHex(md.digest()).equals(track.sha256())) return false;
            }
            return true;
        } catch (IOException | RuntimeException e) { return false; }
    }
    private static String readText(ZipFile zip, String name, int max) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.getSize() > max) throw new IOException("Invalid gift pack entry: " + name);
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(max + 1);
            if (bytes.length > max) throw new IOException("Gift pack entry too large: " + name);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
    private static void build(Path target, UUID uuid, UUID module, int patch, String fingerprint, List<Track> tracks) throws IOException {
        JsonObject manifest = new JsonObject(); manifest.addProperty("format_version", 2);
        JsonObject header = new JsonObject(); header.addProperty("name", "VoidPE Gifts");
        header.addProperty("description", "Музыка подарков VoidPE"); header.addProperty("uuid", uuid.toString());
        header.add("version", version(patch)); header.add("min_engine_version", array(1,20,0)); manifest.add("header", header);
        JsonObject mod = new JsonObject(); mod.addProperty("type", "resources"); mod.addProperty("uuid", module.toString()); mod.add("version", version(patch));
        JsonArray modules = new JsonArray(); modules.add(mod); manifest.add("modules",modules);
        JsonObject definitions = new JsonObject(); definitions.addProperty("format_version", "1.14.0"); JsonObject sounds = new JsonObject();
        for (Track t : tracks) {
            JsonObject s = new JsonObject(); s.addProperty("category", "music"); JsonObject audio = new JsonObject();
            audio.addProperty("name", t.packPath()); audio.addProperty("stream", true); JsonArray list = new JsonArray(); list.add(audio); s.add("sounds",list); sounds.add(t.soundId(),s);
        }
        definitions.add("sound_definitions", sounds);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            entry(zip,"manifest.json",manifest.toString().getBytes(StandardCharsets.UTF_8));
            entry(zip,"sounds/sound_definitions.json",definitions.toString().getBytes(StandardCharsets.UTF_8));
            entry(zip,FINGERPRINT,(fingerprint+"\n").getBytes(StandardCharsets.UTF_8));
            byte[] buf = new byte[8192];
            for (Track track : tracks) {
                if (Files.isSymbolicLink(track.source()) || !Files.isRegularFile(track.source(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Music changed during build: " + track.fileName());
                MessageDigest md = digest(); long count = 0;
                zip.putNextEntry(new ZipEntry(track.packPath() + ".ogg"));
                try (InputStream in = Files.newInputStream(track.source())) {
                    int n; while ((n=in.read(buf)) != -1) { count += n; if (count > track.size()) throw new IOException("Music changed during build: " + track.fileName()); md.update(buf,0,n); zip.write(buf,0,n); }
                }
                zip.closeEntry();
                if (count != track.size() || !HexFormat.of().formatHex(md.digest()).equals(track.sha256())) throw new IOException("Music changed during build: " + track.fileName());
            }
        }
    }
    private static JsonArray version(int patch) { return array(1,0,patch); }
    private static JsonArray array(int a, int b, int c) { JsonArray arr = new JsonArray(); arr.add(a); arr.add(b); arr.add(c); return arr; }
    private static void entry(ZipOutputStream zip,String name,byte[] bytes) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry(); }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); } }
}
