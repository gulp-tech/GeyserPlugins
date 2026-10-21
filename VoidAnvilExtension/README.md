# VoidAnvilExtension

Geyser Extension только для Bedrock-игроков VoidPE.

Что делает:

- добавляет Bedrock-only команду `/voidanvil anvil`;
- открывает нативную Bedrock-форму Cumulus;
- кнопка в форме запускает серверную команду `/vench anvil` плагина
  VoidEnchants, где объединение предметов считает Purpur, а не клиент Bedrock.

Зачем это нужно: обычная Java-наковальня у Bedrock-клиента сама проверяет
результат по ванильным правилам и показывает красный X на кастомных книгах
VoidEnchants. Geyser относит custom anvil recipes к ограничениям клиента,
поэтому extension не может безопасно "переубедить" нативный экран наковальни.
Рабочий Bedrock-путь — форма расширения + серверное меню VoidEnchants.

Установка:

1. `VoidEnchants-1.2.0.jar` — в `plugins/` Purpur.
2. `VoidAnvilExtension-1.0.0.jar` — в `plugins/Geyser-Spigot/extensions/`.
3. Полностью перезапустить сервер.
4. Bedrock-игрок нажимает обычную наковальню или пишет `/voidanvil anvil`.
