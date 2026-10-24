#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Собирает конфиги DeluxeMenus с графикой FunTime из configs/glyphs-funtime.yml.

Иконки и заголовки — символы приватной области Unicode (страницы E2–E4 нашего
пака), поэтому в YAML они пишутся escape-последовательностями "\\uE425":
так файл остаётся читаемым и не зависит от кодировки редактора.

Запуск:  python3 tools/make_funtime_menus.py
"""
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
GLYPHS = ROOT / "configs" / "glyphs-funtime.yml"
OUT = ROOT / "configs"

data = yaml.safe_load(GLYPHS.read_text(encoding="utf-8"))
BANNERS, ICONS = data["banners"], data["icons"]


def esc(text: str) -> str:
    return "".join(f"\\u{ord(ch):04X}" if ord(ch) > 0x7F else ch for ch in text)


# Наши собственные иконки из первой серии (страница E1): в паке FunTime нет
# красного креста и «кнопки закрыть», поэтому их берём из E140–E14F.
OWN = {"close": "\uE147", "back": "\uE148", "next": "\uE149", "settings": "\uE146"}


def icon(name: str) -> str:
    if name in OWN and name not in ICONS:
        return esc(OWN[name])
    return esc(ICONS[name])


def banner(name: str) -> str:
    return esc(BANNERS[name])


def item(key, slot, icon_name, label, lore=(), commands=(), material="PAPER", note=""):
    head = f"  {key}:"
    if note:
        head = f"  # {note}\n" + head
    lines = [head, f"    slot: {slot}", f"    material: {material}",
             f'    display_name: "{icon(icon_name)} &#E8D5FF{label}"']
    if lore:
        lines.append("    lore:")
        for line in lore:
            lines.append(f'      - "{line}"')
        lines += ['      - ""', '      - "&#C86BFF➥ &fНажмите"']
    lines.append("    item_flags:")
    lines.append("      - HIDE_ATTRIBUTES")
    if commands:
        lines.append("    click_commands:")
        lines.append("      - \"[sound] UI_BUTTON_CLICK\"")
        for c in commands:
            lines.append(f'      - "{c}"')
    return "\n".join(lines)


def header(comment: str, title_banner: str, size: int) -> str:
    return f"""# {comment}
# Файл: plugins/DeluxeMenus/gui_menus/<имя>.yml
# Графика — из пака FunTime (перенесена tools/import_funtime_pack.py),
# иконки E400–E4FF, заголовок — полоса глифов E2xx.
# Строки "\\uXXXX" — это символы иконок; не заменяйте их на картинки.
# Открывается командой ниже, права — deluxemenus.<имя>.

menu_title: "{banner(title_banner)}"
open_command: {comment.split("|")[-1].strip() if "|" in comment else "menu"}
register_command: true
size: {size}
open_commands:
  - "[sound] UI_BUTTON_CLICK"
items:
"""


FRAME = """  # тёмная рамка как в FunTime: верх/низ и бока закрыты
  'Рамка':
    slots:
    - 0-8
    - 9
    - 17
    - 18
    - 26
    - 27
    - 35
    - 36
    - 44
    - 45-48
    - 50-53
    material: BLACK_STAINED_GLASS_PANE
    display_name: '&8'
    item_flags:
      - HIDE_ATTRIBUTES"""


# ---------------------------------------------------------------- главное меню
main = header("Главное меню VoidPE — графика FunTime | menu", "main_menu_title", 54)
pieces = [FRAME,
          item("info", 13, "info_small", "%player_name%",
               ["",
                ' &#C86BFF• &fМонеты: &#E8D5FF%vault_eco_balance_formatted%$',
                ' &#C86BFF• &fОсколки: &#E8D5FF%playerpoints_points%',
                ' &#C86BFF• &fУбийств: &#E8D5FF%statistic_player_kills%',
                ' &#C86BFF• &fСмертей: &#E8D5FF%statistic_deaths%',
                ' &#C86BFF• &fНаиграно: &#E8D5FF%statistic_hours_played% ч.'],
               material="head-%player_name%", note="голова игрока со статистикой"),
          item("shop", 20, "coins_key", "Магазин",
               ["", "&7Блоки, еда, оружие, талисманы", "&7и зелья за монеты."],
               ["[player] shop", "[close]"], note="категории как в FunTime"),
          item("cases", 21, "usual_key", "Кейсы",
               ["", "&7Обычные, ранговые и боссовые ключи."],
               ["[player] keys", "[close]"]),
          item("donate", 22, "ranks_key", "Донат",
               ["", "&7Привилегии и наборы."],
               ["[player] donate", "[close]"]),
          item("quests", 23, "info_book", "Задания",
               ["", "&7Ежедневные задачи и награды.", "&7Ещё не установлено — кнопку можно спрятать."],
               ["[player] quests", "[close]"]),
          item("clans", 24, "clan1", "Кланы",
               ["", "&7Создать клан и воевать за территорию."],
               ["[player] clan", "[close]"]),
          item("top", 30, "rank", "Топы",
               ["", "&7Лучшие игроки сервера."],
               ["[player] top", "[close]"]),
          item("arena", 31, "weapon_key", "Арена",
               ["", "&7PvP 1×1 и дуэли."],
               ["[player] warp pvp", "[close]"]),
          item("mine", 32, "resource_key", "Автошахта",
               ["", "&7Бесконечная шахта с ресурсами."],
               ["[player] warp mine", "[close]"]),
          item("back", 37, "back", "&7Назад", [], ["[openguimenu] menu"]),
          item("next", 43, "next", "&7Далее", [], ["[openguimenu] menu"]),
          item("close", 49, "close", "&#FF5570Закрыть", [], ["[close]"])]
(OUT / "DeluxeMenus-menu-funtime.yml").write_text(main + "\n\n".join(pieces) + "\n", encoding="utf-8")

# ---------------------------------------------------------------- магазин
shop = header("Магазин VoidPE — заголовок FunTime | shop", "shop_menu_title", 54)
shop_items = [
    FRAME,
    item("blocks", 20, "resource_key", "Блоки", ["", "&7Стройматериалы и декор."], ["[player] shop blocks"]),
    item("tools", 21, "craft_back", "Инструменты", ["", "&7Кирки, топоры, лопаты."], ["[player] shop tools"]),
    item("food", 22, "tangerine", "Еда", ["", "&7Еда и зелья."], ["[player] shop food"]),
    item("weapons", 23, "weapon_key", "Оружие", ["", "&7Мечи, луки, арбалеты."], ["[player] shop weapons"]),
    item("armor", 24, "armor_key", "Снаряжение", ["", "&7Броня и щиты."], ["[player] shop armor"]),
    item("potions", 29, "alcohol_splash_potion", "Зелья", ["", "&7Зелья и варево."], ["[player] shop potions"]),
    item("runes", 30, "rune", "Руны", ["", "&7Руны усиления."], ["[player] shop runes"]),
    item("talismans", 31, "talisman_key", "Талисманы", ["", "&7Талисманы удачи."], ["[player] shop talismans"]),
    item("cases", 32, "free_key", "Ключи", ["", "&7Ключи от кейсов."], ["[player] keys"]),
    item("valuables", 33, "coins_key", "Ценные ресурсы", ["", "&7Драгоценности и слитки."], ["[player] shop valuables"]),
    item("back", 49, "back", "&7Назад", [], ["[openguimenu] menu"]),
]
(OUT / "DeluxeMenus-shop-funtime.yml").write_text(shop + "\n\n".join(shop_items) + "\n", encoding="utf-8")

# ---------------------------------------------------------------- кейсы
case = header("Кейсы VoidPE — заголовок FunTime | keys", "case_title", 54)
case_items = ["""  'Рамка':
    slots:
    - 0-8
    - 9
    - 17
    - 45
    - 53
    material: BLACK_STAINED_GLASS_PANE
    display_name: '&8'
    item_flags:
      - HIDE_ATTRIBUTES"""]
keys = [("usual_key", "Обычный ключ", 20), ("free_key", "Бесплатный ключ", 21),
        ("coins_key", "Ключ монет", 22), ("ranks_key", "Ранговый ключ", 23),
        ("weapon_key", "Ключ оружия", 24), ("armor_key", "Ключ брони", 29),
        ("enchant_key", "Ключ чар", 30), ("resource_key", "Ключ ресурсов", 31),
        ("talisman_key", "Ключ талисманов", 32), ("token_key", "Ключ жетонов", 33),
        ("sphere_key", "Ключ сфер", 40), ("trim_key", "Ключ узоров", 41),
        ("traps_key", "Ключ ловушек", 42), ("eggs_key", "Ключ яиц", 43),
        ("instrument_key", "Ключ инструментов", 44)]
for icon_name, label, slot in keys:
    case_items.append(item(icon_name, slot, icon_name, label,
                           ["", "&7Открыть кейс этим ключом."],
                           [f"[player] keys {icon_name.replace('_key', '')}"]))
case_items.append(item("back", 49, "back", "&7Назад", [], ["[openguimenu] menu"]))
(OUT / "DeluxeMenus-cases-funtime.yml").write_text(case + "\n\n".join(case_items) + "\n", encoding="utf-8")

# ---------------------------------------------------------------- проверка
for name in ("DeluxeMenus-menu-funtime.yml", "DeluxeMenus-shop-funtime.yml", "DeluxeMenus-cases-funtime.yml"):
    text = (OUT / name).read_text(encoding="utf-8")
    parsed = yaml.safe_load(text)
    used = []
    for entry in parsed["items"].values():
        slots = entry.get("slots") or [entry.get("slot")]
        for s in slots:
            if isinstance(s, str) and "-" in s:
                a, b = s.split("-")
                used += list(range(int(a), int(b) + 1))
            else:
                used.append(int(s))
    dup = sorted({x for x in used if used.count(x) > 1})
    print(f"{name}: items={len(parsed['items'])} слотов={len(used)} дубли={dup or 'нет'}")
