package mk.hypertnt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Самотест TntRegistry: запускается в CI как {@code java -ea TntRegistryTest}.
 * Проверяет сохранение/восстановление, поршневое перемещение,
 * очистку устаревших записей и устойчивость к повреждённым строкам.
 */
public final class TntRegistryTest {

    private static int checks = 0;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void checkEquals(Object expected, Object actual, String message) {
        check(java.util.Objects.equals(expected, actual), message + " (ожидалось " + expected + ", получено " + actual + ")");
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("hypertnt-registry-test");
        Path file = directory.resolve("placed-tnts.txt");
        UUID world = UUID.randomUUID();
        UUID nether = UUID.randomUUID();

        TntRegistry.Pos a = new TntRegistry.Pos(world, 10, 64, -3);
        TntRegistry.Pos b = new TntRegistry.Pos(world, 11, 64, -3);
        TntRegistry.Pos otherWorld = new TntRegistry.Pos(nether, 10, 64, -3);

        // put/get/remove
        TntRegistry registry = new TntRegistry(file);
        check(registry.get(a) == null, "пустой реестр: get должен вернуть null");
        registry.put(a, "tnt_basic");
        registry.put(b, "TNT_OBS");
        checkEquals("tnt_basic", registry.get(a), "put/get a");
        checkEquals("tnt_obs", registry.get(b), "put нормализует регистр");
        checkEquals(2, registry.size(), "size после двух put");
        checkEquals("tnt_basic", registry.remove(a), "remove возвращает прежний тип");
        check(registry.get(a) == null, "после remove записи нет");
        checkEquals(1, registry.size(), "size после remove");

        // перезапись: обычный TNT на старые координаты снимает запись
        registry.put(a, "tnt_fire");
        registry.remove(a);
        check(registry.get(a) == null, "обычная установка снимает устаревшую запись");
        registry.put(a, "tnt_fire");
        registry.put(a, "tnt_heavy");
        checkEquals("tnt_heavy", registry.get(a), "повторная установка заменяет запись");

        // save/load round trip
        registry.put(otherWorld, "tnt_basic");
        registry.save();
        TntRegistry reloaded = new TntRegistry(file);
        reloaded.load();
        checkEquals("tnt_heavy", reloaded.get(a), "после перезагрузки тип сохраняется");
        checkEquals("tnt_obs", reloaded.get(b), "после перезагрузки тип сохраняется (b)");
        checkEquals("tnt_basic", reloaded.get(otherWorld), "записи миров независимы");
        checkEquals(3, reloaded.size(), "size после перезагрузки");

        // повреждённые строки пропускаются
        Files.writeString(file, String.join("\n",
                "# заголовок",
                "",
                "мусор без разделителей",
                ";;;;",
                "not-a-uuid;1;2;3;tnt_basic",
                world + ";x;2;3;tnt_basic",
                world + ";1;2;3;типы с пробелами",
                world + ";5;6;7;tnt_poseidon",
                ""));
        TntRegistry tolerant = new TntRegistry(file);
        tolerant.load();
        checkEquals(1, tolerant.size(), "повреждённые строки пропущены");
        checkEquals("tnt_poseidon", tolerant.get(new TntRegistry.Pos(world, 5, 6, 7)), "валидная строка прочитана");

        // некорректный тип не принимается
        boolean threw = false;
        try {
            tolerant.put(a, "bad;type");
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check(threw, "put с ';' в типе должен бросать IllegalArgumentException");
        threw = false;
        try {
            tolerant.put(a, "тип в кириллице");
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check(threw, "put с пробелами/кириллицей должен бросать IllegalArgumentException");

        // перемещение поршнем: запись едет вместе с блоком
        TntRegistry piston = new TntRegistry(directory.resolve("piston.txt"));
        piston.put(a, "tnt_basic");
        String moving = piston.remove(a);
        piston.put(b, moving);
        check(piston.get(a) == null, "после хода поршня старая позиция пуста");
        checkEquals("tnt_basic", piston.get(b), "после хода поршня тип на новой позиции");

        // позиции в чанке
        TntRegistry.Pos inChunk = new TntRegistry.Pos(world, 16, 10, 16);   // чанк 1,1
        TntRegistry.Pos outChunk = new TntRegistry.Pos(world, 31, 10, 16);  // чанк 1,1 (31>>4 == 1)
        TntRegistry.Pos farChunk = new TntRegistry.Pos(world, 32, 10, 16);  // чанк 2,1
        piston.put(inChunk, "tnt_basic");
        piston.put(outChunk, "tnt_basic");
        piston.put(farChunk, "tnt_basic");
        checkEquals(2, piston.positionsIn(world, 1, 1).size(), "positionsIn фильтрует по чанку");
        checkEquals(3, piston.positionsIn(world).size(), "positionsIn(world) без фильтра чанка");
        checkEquals(0, piston.positionsIn(nether).size(), "чужой мир пуст");

        // validateIn: устаревшие записи удаляются, живые остаются
        TntRegistry.Pos stale = new TntRegistry.Pos(world, 48, 10, 16); // чанк 3,1
        piston.put(stale, "tnt_basic");
        int dropped = piston.validateIn(world, 3, 1, (p) -> !p.equals(stale));
        checkEquals(1, dropped, "validateIn удаляет блок, который больше не TNT");
        check(piston.get(stale) == null, "устаревшая запись удалена");
        int droppedFar = piston.validateIn(world, 2, 1, (p) -> false);
        checkEquals(1, droppedFar, "validateIn удаляет запись с не-TNT блоком");
        check(piston.get(farChunk) == null, "запись с не-TNT блоком удалена");
        checkEquals("tnt_basic", piston.get(inChunk), "живая запись осталась");
        checkEquals("tnt_basic", piston.get(outChunk), "живая запись осталась (outChunk)");

        // validateIn устойчив к исключениям предиката
        int droppedAfterError = piston.validateIn(world, 1, 1, (p) -> {
            throw new IllegalStateException("чанк недоступен");
        });
        checkEquals(2, droppedAfterError, "исключение предиката трактуется как устаревшая запись");

        // removeAll пакетно
        TntRegistry batch = new TntRegistry(directory.resolve("batch.txt"));
        batch.put(a, "tnt_basic");
        batch.put(b, "tnt_basic");
        batch.put(farChunk, "tnt_basic");
        int removed = batch.removeAll(List.of(a, b));
        checkEquals(2, removed, "removeAll удаляет пакет");
        checkEquals(1, batch.size(), "после removeAll остаются чужие записи");

        System.out.println("TntRegistryTest OK (" + checks + " checks)");
    }
}
