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
        return move(source, sourceSlot, destination, destinationSlots, excludedSlot,
                limit, inventoryMax, -1, stacks);
    }

    /**
     * Same transfer with an optional preferred destination slot. A compatible
     * preferred slot is filled first; otherwise the usual two passes are used.
     */
    static <T> int move(T[] source, int sourceSlot, T[] destination,
                        int destinationSlots, int excludedSlot, int limit,
                        int inventoryMax, int preferredSlot, Stacks<T> stacks) {
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
        if (preferredSlot >= 0 && preferredSlot < targetCount && preferredSlot != excludedSlot) {
            T target = destination[preferredSlot];
            boolean empty = stacks.empty(target);
            if (empty || stacks.similar(item, target)) {
                int current = empty ? 0 : stacks.amount(target);
                int moved = Math.min(remaining, Math.max(0, cap - current));
                if (moved != 0) {
                    destination[preferredSlot] = stacks.copy(empty ? item : target, current + moved);
                    remaining -= moved;
                }
            }
        }
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

    /**
     * Applies a normal left/right click to one slot and a one-element cursor array.
     * Every mutation is calculated from the supplied copies; item count is conserved.
     */
    static <T> boolean click(T[] slots, int slot, T[] cursor, boolean rightClick,
                             int inventoryMax, Stacks<T> stacks) {
        if (slots == cursor || slot < 0 || slot >= slots.length || cursor.length == 0) return false;
        T current = slots[slot];
        T held = cursor[0];
        if (stacks.empty(held)) {
            if (stacks.empty(current)) return false;
            int amount = stacks.amount(current);
            int taken = rightClick ? (amount + 1) / 2 : amount;
            cursor[0] = stacks.copy(current, taken);
            slots[slot] = taken == amount ? null : stacks.copy(current, amount - taken);
            return true;
        }
        int heldAmount = stacks.amount(held);
        if (stacks.empty(current)) {
            int cap = Math.max(0, Math.min(inventoryMax, stacks.max(held)));
            int placed = Math.min(rightClick ? 1 : heldAmount, cap);
            if (placed == 0) return false;
            slots[slot] = stacks.copy(held, placed);
            cursor[0] = placed == heldAmount ? null : stacks.copy(held, heldAmount - placed);
            return true;
        }
        if (stacks.similar(held, current)) {
            int currentAmount = stacks.amount(current);
            int cap = Math.max(0, Math.min(inventoryMax, stacks.max(current)));
            int placed = Math.min(rightClick ? 1 : heldAmount, Math.max(0, cap - currentAmount));
            if (placed == 0) return false;
            slots[slot] = stacks.copy(current, currentAmount + placed);
            cursor[0] = placed == heldAmount ? null : stacks.copy(held, heldAmount - placed);
            return true;
        }
        if (!rightClick) {
            // A left click on two different stacks swaps them without creating a copy.
            int currentAmount = stacks.amount(current);
            if (heldAmount > Math.min(inventoryMax, stacks.max(held))
                    || currentAmount > Math.min(inventoryMax, stacks.max(current))) return false;
            slots[slot] = stacks.copy(held, heldAmount);
            cursor[0] = stacks.copy(current, currentAmount);
            return true;
        }
        return false;
    }
}
