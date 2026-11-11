# Маппинги сфер Geyser — какой файл загружать

⚠️ **Никогда не загружайте оба маппинга сфер одновременно** — совпадают
идентификаторы (`geyser_custom:sphere1-4`), применение обоих конфликтует.

- `Geyser-spheres-pdc-mapping.json` — **новый, защищённый**. Условие
  `server_pdc_string` (predicate `voidtalismans:talisman_id` +
  `fallback_key` `faraltalismans:talisman_id`). Работает **только на тестовом
  ядре** Purpurbedrock с поддержкой `server_pdc_string` (v26.2.0-bedrock
  spheres-marker-test и новее). Загружается в `bedrock/custom_mappings/`.
  Идентичен эталону `spheres-pdc-mapping-test.json` из релиза Purpurbedrock.

- `Geyser-spheres-mapping.json` — старый, без защитного условия. Только для
  старого ядра, где `server_pdc_string` не поддерживается. Любая бумага с
  подходящим `item_model` получает кастомный вид (подделка возможна).

## Правила

1. `item_model` предмета должен совпадать с записью маппинга
   (`voidspheres:sphere1-4`).
2. PDC-метка (`voidtalismans:talisman_id` / легаси `faraltalismans:talisman_id`)
   — единственный источник истины о типе; имя/лор/модель не используются.
3. PDC — не криптозащита: её можно изменить сторонними плагинами/командами.
   Это защита от случайной подделки, не от атаки с правами на команды.
