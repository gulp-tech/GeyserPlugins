package ru.voidpe.shulker;

/** Pure transfer calculation. The caller commits both arrays on the server thread. */
final class StackTransfer {
    interface Stacks<T> {
        boolean empty(T stack);
        int amount(T stack);
        int max(T stack);
        boolean similar(T a, T b);
        T copy(T stack, int amount);
    }

    private StackTransfer() {
    }

    /**
     * Moves up to limit items to matching stacks first, then free slots. Does not
     * mutate the stack objects passed in; source and destination must be distinct.
     */
    static <T> int move(T[] source, int sourceSlot, T[] destination,
                        int destinationSlots, int excludedSlot, int limit,
                        int inventoryMax, Stacks<T> stacks) {
        if (source == destination || sourceSlot < 0 || sourceSlot >= source.length || limit <= 0) {
            return 0;
        }
        T item = source[sourceSlot];
        if (stacks.empty(item)) {
            return 0;
        }
        int total = stacks.amount(item);
        int remaining = Math.min(total, limit);
        int targetCount = Math.min(destination.length, Math.max(0, destinationSlots));
        int cap = Math.max(0, Math.min(inventoryMax, stacks.max(item)));
        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            for (int i = 0; i < targetCount && remaining > 0; i++) {
                if (i == excludedSlot) {
                    continue;
                }
                T target = destination[i];
                boolean empty = stacks.empty(target);
                if ((pass == 0 && (empty || !stacks.similar(item, target)))
                        || (pass == 1 && !empty)) {
                    continue;
                }
                int current = empty ? 0 : stacks.amount(target);
                int moved = Math.min(remaining, Math.max(0, cap - current));
                if (moved != 0) {
                    destination[i] = stacks.copy(empty ? item : target, current + moved);
                    remaining -= moved;
                }
            }
        }
        int moved = Math.min(total, limit) - remaining;
        if (moved > 0) {
            source[sourceSlot] = total == moved ? null : stacks.copy(item, total - moved);
        }
        return moved;
    }
}
