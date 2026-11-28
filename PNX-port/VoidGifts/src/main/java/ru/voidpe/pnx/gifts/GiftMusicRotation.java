package ru.voidpe.pnx.gifts;

import java.util.*;
import java.util.random.RandomGenerator;

/** Per-player random rotation: every loaded track plays once before repeating. */
public final class GiftMusicRotation {
    private final Map<UUID, Set<String>> heard = new HashMap<>();
    public synchronized Optional<String> next(UUID player, Collection<String> loadedIds, RandomGenerator random) {
        Objects.requireNonNull(player); Objects.requireNonNull(loadedIds); Objects.requireNonNull(random);
        List<String> tracks = new ArrayList<>(new LinkedHashSet<>(loadedIds));
        if (tracks.stream().anyMatch(id -> id == null || id.isBlank())) throw new IllegalArgumentException("Invalid sound ID");
        if (tracks.isEmpty()) { heard.remove(player); return Optional.empty(); }
        Set<String> played = heard.computeIfAbsent(player, ignored -> new HashSet<>());
        played.retainAll(tracks);
        List<String> remaining = tracks.stream().filter(id -> !played.contains(id)).toList();
        if (remaining.isEmpty()) { played.clear(); remaining = tracks; }
        String selected = remaining.get(random.nextInt(remaining.size()));
        played.add(selected);
        return Optional.of(selected);
    }
    public synchronized void forget(UUID player) { heard.remove(Objects.requireNonNull(player)); }
}
