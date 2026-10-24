#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Превью меню с графикой FunTime — читает готовый YAML, а не рисует по памяти.

Так видно ровно то, что получит игрок: заголовок-полосу из глифов E2xx
и иконки E4xx в слотах. Если в конфиге сломается код иконки, превью это покажет.

Запуск: python3 tools/make_funtime_preview.py
"""
import re
from pathlib import Path

import yaml
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
FONT_DIR = ROOT / "VoidResourcePack" / "font"
S = 44
SLOT_BG = (150, 150, 150, 255)
SLOT_DARK = (38, 38, 44, 255)


def page_sheet(page: str) -> Image.Image:
    return Image.open(FONT_DIR / f"glyph_{page}.png").convert("RGBA")


def glyph(char: str) -> Image.Image | None:
    """Символ приватной области → клетка листа глифов."""
    code = ord(char)
    page = (code >> 8) & 0xFF
    if not 0xE0 <= page <= 0xF8:
        return None
    path = FONT_DIR / f"glyph_E{page - 0xE0:X}.png"
    if not path.exists():
        return None
    index = code & 0xFF
    sheet = Image.open(path).convert("RGBA")
    return sheet.crop(((index % 16) * 16, (index // 16) * 16,
                       (index % 16) * 16 + 16, (index // 16) * 16 + 16))


def draw_glyph_run(img: Image.Image, text: str, x: int, y: int, zoom: int = 1):
    """Рисует строку глифов подряд (16 px за символ)."""
    cx = x
    for ch in text:
        cell = glyph(ch)
        if cell is None:
            cx += 16 * zoom
            continue
        cell = cell.resize((16 * zoom, 16 * zoom), Image.NEAREST)
        img.paste(cell, (cx, y), cell)
        cx += 16 * zoom
    return cx


def preview_menu(config_path: Path, out_path: Path, zoom: int = 2):
    data = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    rows = data["size"] // 9
    w, h = 9 * S + 40, rows * S + 96
    img = Image.new("RGBA", (w, h), (16, 14, 20, 255))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, w, 56], fill=(32, 25, 42, 255))

    # заголовок — глифы из конфига
    title = data.get("menu_title", "")
    draw_glyph_run(img, title, 20, 20 if chr(0xE2) in title[:1] else 18, zoom=2)

    slots = {}
    for entry in data["items"].values():
        names = entry.get("slots") or [entry.get("slot")]
        for name in names:
            if isinstance(name, str) and "-" in name:
                a, b = name.split("-")
                for i in range(int(a), int(b) + 1):
                    slots[i] = entry
            else:
                slots[int(name)] = entry

    for i in range(rows * 9):
        x, y = 20 + (i % 9) * S, 68 + (i // 9) * S
        entry = slots.get(i)
        glass = entry is not None and "GLASS_PANE" in str(entry.get("material", ""))
        d.rectangle([x, y, x + S - 4, y + S - 4],
                    fill=SLOT_DARK if glass else SLOT_BG,
                    outline=(22, 22, 26, 255) if glass else (60, 60, 60, 255))
        if glass or entry is None:
            continue
        d.rectangle([x, y, x + S - 4, y + 6], fill=(172, 172, 172, 255))
        d.rectangle([x + 3, y + 9, x + S - 7, y + S - 7], fill=(128, 128, 128, 255))
        display = entry.get("display_name", "")
        if display:
            cell = glyph(display[0])
            if cell is not None:
                art = cell.resize((S - 12, S - 12), Image.NEAREST)
                img.paste(art, (x + 5, y + 5), art)
            else:                       # не символ-иконка: пишем первую букву
                try:
                    font = ImageFont.truetype(str(ROOT / "tools" / "fonts" / "Minecraftia.ttf"), 18)
                except OSError:
                    font = ImageFont.load_default()
                d.text((x + 12, y + 12), display[0], font=font, fill=(240, 240, 240, 255))

    img.resize((w * zoom, h * zoom), Image.NEAREST).save(out_path)
    print("OK:", out_path.relative_to(ROOT), f"({len(slots)} слотов заполнено)")


def main():
    preview = ROOT / "preview"
    preview.mkdir(exist_ok=True)
    preview_menu(ROOT / "configs" / "DeluxeMenus-menu-funtime.yml",
                 preview / "menu-funtime-preview.png")

    # весь лист иконок
    sheet = page_sheet("E4")
    bg = Image.new("RGBA", sheet.size, (26, 22, 34, 255))
    bg.alpha_composite(sheet)
    bg.resize((sheet.width * 3, sheet.height * 3), Image.NEAREST).save(preview / "funtime-icons.png")

    # все заголовки
    data = yaml.safe_load((ROOT / "configs" / "glyphs-funtime.yml").read_text(encoding="utf-8"))
    banners = list(data["banners"].values())
    rows = (len(banners) + 1) // 2
    canvas = Image.new("RGBA", (144 * 2 + 24, (16 + 6) * rows + 8), (16, 14, 20, 255))
    for i, text in enumerate(banners):
        x = 8 + (i % 2) * (144 + 8)
        y = 8 + (i // 2) * (16 + 6)
        draw_glyph_run(canvas, text, x, y, zoom=1)
    canvas.resize((canvas.width * 2, canvas.height * 2), Image.NEAREST).save(preview / "funtime-banners.png")
    print("OK: preview/funtime-icons.png, preview/funtime-banners.png")


if __name__ == "__main__":
    main()
