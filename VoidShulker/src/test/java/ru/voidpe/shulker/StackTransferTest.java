package ru.voidpe.shulker;

import java.util.Arrays;
import java.util.Random;

/** Run by GitHub Actions without a Bukkit server. */
public final class StackTransferTest {
    record Stack(String type, int amount, int max) {}
    private static final StackTransfer.Stacks<Stack> OPS = new StackTransfer.Stacks<>() {
        public boolean empty(Stack s) { return s == null || s.amount() <= 0; }
        public int amount(Stack s) { return s.amount(); }
        public int max(Stack s) { return s.max(); }
        public boolean similar(Stack a, Stack b) { return a.type().equals(b.type()) && a.max() == b.max(); }
        public Stack copy(Stack s, int amount) { return new Stack(s.type(), amount, s.max()); }
    };
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static int total(Stack[] a, String type) {
        return Arrays.stream(a).filter(s -> s != null && s.type().equals(type)).mapToInt(Stack::amount).sum();
    }
    public static void main(String[] args) {
        Stack[] src = { new Stack("stone", 32, 64) };
        Stack[] dst = { new Stack("stone", 60, 64), null, new Stack("locked", 1, 1) };
        check(StackTransfer.move(src, 0, dst, 3, 2, 32, 64, OPS) == 32, "full move");
        check(src[0] == null && dst[0].amount() == 64 && dst[1].amount() == 28, "merge before empty");
        check(dst[2].type().equals("locked"), "anchor must not change");
        src[0] = new Stack("a", 7, 16);
        dst = new Stack[]{ new Stack("a", 15, 16), new Stack("b", 64, 64), null };
        check(StackTransfer.move(src, 0, dst, 2, -1, 7, 64, OPS) == 1, "partial when full");
        check(src[0].amount() == 6 && dst[0].amount() == 16 && dst[2] == null, "capacity/slot limit");
        check(StackTransfer.move(src, 0, dst, 3, 2, 1, 64, OPS) == 0, "excluded empty slot");
        check(StackTransfer.move(src, 0, dst, 3, -1, 1, 64, OPS) == 1, "one-item move");
        check(src[0].amount() == 5 && dst[2].amount() == 1, "single amount");
        check(StackTransfer.move(src, 0, src, 1, -1, 1, 64, OPS) == 0, "same array rejected");
        src[0] = new Stack("a", 8, 64);
        dst = new Stack[]{ new Stack("a", 60, 64), null, null, null, null, null };
        check(StackTransfer.move(src, 0, dst, 6, -1, 8, 64, 5, OPS) == 8, "preferred slot move");
        check(src[0] == null && dst[0].amount() == 60 && dst[5].amount() == 8,
                "preferred empty slot wins over earlier matching stack");
        src[0] = new Stack("a", 8, 64);
        dst[5] = new Stack("b", 1, 64);
        check(StackTransfer.move(src, 0, dst, 6, -1, 8, 64, 5, OPS) == 8, "preferred slot fallback move");
        check(dst[0].amount() == 64 && dst[1].amount() == 4 && dst[5].type().equals("b"),
                "occupied preferred slot falls back to matching stacks then empty slots");
        Random random = new Random(8478);
        for (int iteration = 0; iteration < 10000; iteration++) {
            int max = random.nextBoolean() ? 16 : 64;
            Stack[] from = { new Stack("a", 1 + random.nextInt(max), max) };
            Stack[] to = new Stack[12];
            for (int i = 0; i < to.length; i++) {
                int kind = random.nextInt(3);
                if (kind == 0) to[i] = new Stack("a", 1 + random.nextInt(max), max);
                if (kind == 1) to[i] = new Stack("b", 1 + random.nextInt(64), 64);
            }
            int blocked = random.nextInt(to.length);
            Stack beforeBlocked = to[blocked];
            int beforeA = total(from, "a") + total(to, "a");
            int beforeB = total(to, "b");
            int moved = StackTransfer.move(from, 0, to, 12, blocked,
                    1 + random.nextInt(64), 64, OPS);
            check(moved >= 0 && total(from, "a") + total(to, "a") == beforeA, "conservation a");
            check(total(to, "b") == beforeB && java.util.Objects.equals(to[blocked], beforeBlocked), "isolation");
            for (Stack s : to) if (s != null) check(s.amount() <= s.max(), "stack max");
        }
        System.out.println("StackTransfer: all tests passed");
    }
}
