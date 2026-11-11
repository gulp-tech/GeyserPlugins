# Маппинги сфер Geyser — какой файл загружать

⚠️ **Никогда не загружайте оба маппинга сфер одновременно** — совпадают
идентификаторы (`geyser_custom:sphere1-4`), применение обоих конфликтует.

- `Geyser-spheres-pdc-mapping.json` — **новый, защищённый**. Условие
  `server_pdc_string` (predicate `voidtalismans:talisman_id` +
  `fallback_key` `faraltalismans:talisman_id`). Работает **только на тестовом
  ядре** Purpurbedrock с поддержкой `server_pdc_string` (v26.2.0-bedrock
  spheres-marker-test и новее). Загружается в `bedrock/custom_mappings/`.
  Первые четыре записи идентичны эталону `spheres-pdc-mapping-test.json`
  из релиза Purpurbedrock.

  В файле есть **иконочные записи-фолбэки** (`geyser_custom:sphereN_menu`)
  для предметов с `item_model voidspheres:sphereN`, но **без** PDC-метки —
  это иконки меню DeluxeMenus (их нельзя снабдить PDC). Они идут ПОСЛЕ
  защищённых записей и дают только плоскую иконку сферы (без 3D-модели):
  - настоящая сфера (PDC есть) → `geyser_custom:sphereN` — полная 3D-модель;
  - иконка меню / подделка без PDC → `geyser_custom:sphereN_menu` — только
    иконка, с настоящими сферами не стакается и в руке не имеет 3D.

  Без этих записей иконки сфер в меню магазина были бы обычной бумагой.

- `Geyser-spheres-mapping.json` — старый, без защитного условия. Только для
  старого ядра, где `server_pdc_string` не поддерживается. Любая бумага с
  подходящим `item_model` получает кастомный вид (подделка возможна).

## Талисманы

- `Geyser-talismans-pdc-mapping.json` — **новый, защищённый** (тестовое ядро).
  12 записей по `minecraft:totem_of_undying` с predicate `server_pdc_string`
  (`voidtalismans:talisman_id` + `fallback_key` `faraltalismans:talisman_id`,
  value = id талисмана: crusher, punisher, absolut, cobra, echidna, grani,
  satan, sources, triton, phoenix, vepr, harmony) → `geyser_custom:talisman_<id>`
  с иконкой из `VoidTalismans.mcpack`.

  **Ванильный тотем без PDC-метки остаётся ванильным** — записей без предиката
  на `minecraft:totem_of_undying` нет. Иконка раздела меню использует
  отдельный `item_model: voidtalismans:menu_icon` (запись на `minecraft:paper`
  без предиката — этот model не встречается у настоящих предметов).

## Правила

1. `item_model` предмета должен совпадать с записью маппинга
   (`voidspheres:sphere1-4`).
2. PDC-метка (`voidtalismans:talisman_id` / легаси `faraltalismans:talisman_id`)
   — единственный источник истины о типе; имя/лор/модель не используются.
3. PDC — не криптозащита: её можно изменить сторонними плагинами/командами.
   Это защита от случайной подделки, не от атаки с правами на команды.
