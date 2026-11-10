# Александр Пистолетов Pack

Bedrock-текстурпак, который превращает сущность `minecraft:zombie_horse` в
анимированный «экран» с GIF `pistoletov-sigma.gif` (Александр Пистолетов
в пиратской шляпе, 76 кадров, 25 кадров/с). Сущность редкая и в ваниле не встречается
сама по себе, поэтому переопределение ничего не ломает.

## Как установить

1. Скопировать `PistoletovPack.mcpack` в `plugins/Geyser-Spigot/packs/`
   (рядом с `VoidResourcePack.mcpack`).
2. Полностью перезапустить сервер.
3. Перезайти в игру (Bedrock-клиент примет оба пака; порядок паков не важен:
   паки не пересекаются по файлам).

Java-игрокам пак не нужен — анимация рисуется на стороне Bedrock-клиента.

## Как призвать

```
/summon zombie_horse ~ ~ ~ {NoAI:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b}
```

`NoAI` — чтобы стоял на месте, `Silent` — без звуков, `Invulnerable` — чтобы
случайно не убили, `PersistenceRequired` — чтобы не исчез.

Именованный вариант (1.21.5+/26.x) — табличка с именем над головой:

```
/summon zombie_horse ~ ~ ~ {NoAI:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,CustomName:{text:"Александр Пистолетов",color:"gold"}}
```

Экран двусторонний — видно с обеих сторон.

## Как удалить

Одну ближайшую:

```
/kill @e[type=zombie_horse,limit=1,sort=nearest]
```

Все сразу:

```
/kill @e[type=zombie_horse]
```

Команда `/kill` работает даже с `Invulnerable:1b`.

## Песня

В пак встроена песня «Я российский новый пират» (Александр Пистолетов).
Пистолетов на экране танцует, а песню включаете командой.

Включить (у всех игроков):

```
/playsound pistoletov.pirates music @a ~ ~ ~ 1 1
```

Только себе: `/playsound pistoletov.pirates music @s ~ ~ ~ 1 1`

Остановить:

```
/stopsound @a music pistoletov.pirates
```

Или всё звучание сразу: `/stopsound @a`. Громкость зависит от ползунка
«Музыка» в настройках клиента. Geyser пробрасывает кастомные имена
звуков в Bedrock-клиент как есть, поэтому звук играет именно из этого пака.

## Как это устроено

- `entity/zombie_horse.entity.json` — клиентское определение
  `minecraft:zombie_horse` c `min_engine_version: "1.20.0"` — выигрывает
  у ванильных определений (1.17.10 и ниже) и заменяет отрисовку целиком.
- `models/entity/pistoletov.geo.json` — плоский экран 2×1.5 блока,
  UV по граням (формат `1.12.0`, как в ванильных `fishing_hook`/`ghast`).
- `render_controllers/pistoletov.render_controllers.json` — покадровая
  анимация: массив текстур `Array.frames[0..75]` с индексом
  `math.mod(math.floor(query.life_time * 25.0), 76.0)`. Это тот же механизм,
  что у ванильного лука (`array.bow_texture_frames[query.get_animation_frame]`),
  работает без кастомных материалов и на Render Dragon.
- `textures/entity/pistoletov/f000.png … f075.png` — кадры GIF
  (320×242, 256 цветов — как в исходнике).

## Если сущность не анимируется

Клиент выбрал другое определение `minecraft:zombie_horse` — значит, поверх
этого пака лежит пак с `min_engine_version` выше `1.20.0`, который тоже
переопределяет эту сущность. Проверьте, что в `packs/` нет других паков,
меняющих `zombie_horse`, и перезайдите после перезапуска сервера.

## Сборка из исходников

```
python3 tools/build_pistoletov_pack.py
python3 tools/test_pistoletov_pack.py --require-output
```
