package ru.voidpe.pnx.rtpzone;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Stateful entry detection. Must be used on the server thread. */
public final class EntryTracker {
    private final Set<UUID> inside = new HashSet<>();
    private final Map<UUID, Long> last = new HashMap<>();
    public boolean update(UUID id, boolean inZone, boolean exempt, long nowMillis, long cooldownMillis) {
        if (!inZone) { inside.remove(id); return false; }
        if (!inside.add(id) || exempt) return false;
        Long previous = last.get(id);
        if (previous != null && nowMillis >= previous && nowMillis - previous < Math.max(0, cooldownMillis)) return false;
        last.put(id, nowMillis);
        return true;
    }
    public void leave(UUID id) { inside.remove(id); }
    public void forget(UUID id) { inside.remove(id); last.remove(id); }
    public void clearInside() { inside.clear(); }
}
