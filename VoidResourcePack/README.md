# VoidPE Resource Pack 1.18.0

Пак для `plugins/Geyser-Spigot/packs/`. Сохранены UUID исходного VoidPE-пака, поэтому новая версия заменяет старую без изменения остальной конфигурации Geyser.

Изменение 1.18.0: `ui/scoreboards.json` отключает только коллекцию красных значений `scoreboard_sidebar/main/lists/scores`. Сам sidebar, заголовок и строки TAB остаются видимыми. Числовые score в Java-пакетах не изменяются, поэтому сортировка строк сохраняется.

Сборка и проверка из корня репозитория:

```bash
python3 tools/test_voidresource_pack.py
python3 tools/build_voidresource_pack.py
```

После замены `.mcpack` нужен полный перезапуск сервера и повторный вход Bedrock-игрока. У клиента не должно быть другого пака выше по приоритету, который снова включает этот UI-элемент.
