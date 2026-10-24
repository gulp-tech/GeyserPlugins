#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Переносит графику из Java-ресурспака (funtime.zip) в наш Bedrock-пак.

Зачем: FunTime рисует меню «гигантскими глифами» Java-шрифта (один символ = целая
картинка 176×126). Bedrock так не умеет: у него глифы живут в листах glyph_Ex.png
по 16×16 клеток, а ширина символа = ширина его непрозрачных пикселей.
Поэтому здесь картинки переносятся двумя способами:

1. Иконки 16×16 (ключи, кейсы, кнопки) → одна клетка = один символ. 1:1, без потерь.
2. Заголовки меню (полоски 176×7) → накладываются на непрозрачную тёмную плашку
   176×16 и режутся на 11 клеток по 16 px. Плашка нужна, чтобы каждая клетка была
   полностью непрозрачной: тогда шаг символа в Bedrock ровно 16 px и картинка
   не «съезжает» на прозрачных промежутках между буквами.

Результат: VoidResourcePack/font/glyph_E2.png, glyph_E3.png, glyph_E4.png
и configs/glyphs-funtime.yml с готовыми строками для DeluxeMenus и других плагинов.

Запуск:
    python3 tools/import_funtime_pack.py [--src funtime.zip] [--pack /tmp/ft/unpacked]
"""
from __future__ import annotations

import argparse
import io
import json
import re
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
PACK_DIR = ROOT / "VoidResourcePack"
CONFIG_OUT = ROOT / "configs" / "glyphs-funtime.yml"

CELL = 16
PAGE_COLS = 16           # в Bedrock страница глифов — сетка 16×16
PAGE_SIZE = CELL * PAGE_COLS

BANNER_WIDTH = 144                # 9 клеток по 16 px — влезает в заголовок сундука Bedrock
BAR_BG = (28, 23, 34, 255)        # плашка под заголовок
BAR_EDGE = (66, 52, 84, 255)      # тонкая рамка плашки
BAR_INNER = (40, 32, 49, 255)

BANNER_PAGES = ["E2", "E3"]       # заголовки меню
ICON_PAGE = "E4"                  # иконки 16×16


# ------------------------------------------------------------------ источник

class Source:
    """Читает файлы из zip или из распакованной папки — единым интерфейсом."""

    def __init__(self, path: Path):
        self.path = path
        self._zip = None
        self._files = None
        if path.is_dir():
            self._files = {str(p.relative_to(path)).replace("\\", "/"): p
                           for p in path.rglob("*") if p.is_file()}
        else:
            self._zip = zipfile.ZipFile(path)
            self._files = {n: None for n in self._zip.namelist()}

    def names(self):
        return sorted(self._files)

    def read(self, name: str) -> bytes:
        if self._zip is not None:
            return self._zip.read(name)
        return Path(self._files[name]).read_bytes()

    def image(self, name: str) -> Image.Image:
        return Image.open(io.BytesIO(self.read(name))).convert("RGBA")


# ------------------------------------------------------------------ утилиты

def slug(text: str) -> str:
    text = text.lower().replace("\\", "/").split("/")[-1]
    text = re.sub(r"\.png$", "", text)
    return re.sub(r"[^a-z0-9]+", "_", text).strip("_")


def crop_content(img: Image.Image) -> Image.Image:
    box = img.getbbox()
    return img.crop(box) if box else img


def banner_strip(art: Image.Image, width: int = BANNER_WIDTH) -> Image.Image:
    """Полоска заголовка на непрозрачной плашке 144×16 (9 клеток по 16 px).

    Исходник FunTime — 176×6. Вписываем по ширине с полями и растягиваем по высоте,
    но не больше 10 px: выше — буквы начинают «плыть» и мешают читать.
    """
    art = crop_content(art)
    max_w, max_h = width - 8, 10
    k = min(max_w / art.width, max_h / art.height)
    art = art.resize((max(1, round(art.width * k)), max(1, round(art.height * k))), Image.LANCZOS)

    strip = Image.new("RGBA", (width, CELL), BAR_BG)
    for x in range(width):
        strip.putpixel((x, 0), BAR_EDGE)
        strip.putpixel((x, CELL - 1), BAR_EDGE)
    for y in range(1, CELL - 1):
        strip.putpixel((0, y), BAR_EDGE)
        strip.putpixel((width - 1, y), BAR_EDGE)
    for y in range(1, 4):
        for x in range(1, width - 1):
            strip.putpixel((x, y), BAR_INNER)
    strip.alpha_composite(art, ((width - art.width) // 2, (CELL - art.height) // 2))
    return strip


def tile(img: Image.Image) -> list[Image.Image]:
    """Режет картинку на клетки 16×16 (по горизонтали)."""
    cols = max(1, (img.width + CELL - 1) // CELL)
    cells = []
    for i in range(cols):
        cell = Image.new("RGBA", (CELL, CELL), (0, 0, 0, 0))
        piece = img.crop((i * CELL, 0, min((i + 1) * CELL, img.width), img.height))
        cell.alpha_composite(piece, (0, 0))
        cells.append(cell)
    return cells


class Pages:
    """Раскладывает картинки по страницам глифов Bedrock."""

    def __init__(self, page_ids: list[str]):
        self.page_ids = page_ids
        self.slots: list[list[Image.Image | None]] = [[None] * 256 for _ in page_ids]
        self.page = 0
        self.slot = 0

    def put(self, img: Image.Image) -> str:
        """Возвращает символ, которым рисуется клетка."""
        while self.slot >= 256:
            self.page += 1
            self.slot = 0
            if self.page >= len(self.page_ids):
                raise RuntimeError("не хватило страниц глифов — добавьте страницу в список")
        self.slots[self.page][self.slot] = img
        page_id = self.page_ids[self.page]
        code = int(page_id, 16) * 0x100 + self.slot
        self.slot += 1
        return chr(code)

    def add_image(self, img: Image.Image) -> str:
        """Картинка произвольной ширины → строка символов (клетки подряд)."""
        return "".join(self.put(c) for c in tile(img))

    def save(self, folder: Path):
        for idx, page_id in enumerate(self.page_ids):
            sheet = Image.new("RGBA", (PAGE_SIZE, PAGE_SIZE), (0, 0, 0, 0))
            for i, cell in enumerate(self.slots[idx]):
                if cell is not None:
                    sheet.paste(cell, ((i % PAGE_COLS) * CELL, (i // PAGE_COLS) * CELL), cell)
            sheet.save(folder / f"glyph_{page_id}.png")


def esc(text: str) -> str:
    """Строка для YAML в кавычках: символы приватной области — как \\uXXXX."""
    return "".join(f"\\u{ord(ch):04X}" if ord(ch) > 0x7F else ch for ch in text)


# ------------------------------------------------------------------ сборка

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=str(ROOT / "funtime.zip"),
                    help="Java-ресурспак (zip) — по умолчанию funtime.zip в корне репозитория")
    ap.add_argument("--pack", default=None, help="распакованная папка пака (вместо zip)")
    args = ap.parse_args()

    src_path = Path(args.pack) if args.pack else Path(args.src)
    if not src_path.exists():
        raise SystemExit(f"нет файла: {src_path}\nПоложите funtime.zip в корень репозитория "
                         f"или укажите --src/--pack")
    src = Source(src_path)
    names = src.names()

    # ---- 1. заголовки меню ------------------------------------------------
    banner_files = [n for n in names
                    if n.startswith("assets/funtime/textures/menu/") and n.endswith(".png")
                    and "title" in n.lower()]   # header.png/header_ny.png — широкие логотипы,
                                                # в 9 клеток они не влезают (сжимаются в кашу)
    # заголовок → строка символов
    banner_strings: dict[str, str] = {}
    pages = Pages(BANNER_PAGES)
    for name in banner_files:
        img = crop_content(src.image(name))          # в паке листы 256×256, рисунок 176×7
        if img.getbbox() is None:
            continue
        key = slug(name)
        if key in banner_strings:
            continue
        banner_strings[key] = pages.add_image(banner_strip(img))
    banner_page_files = BANNER_PAGES

    # ---- 2. иконки 16×16 --------------------------------------------------
    icon_dirs = ("assets/minecraft/textures/item/funtime/",
                 "assets/funtime/textures/font/icons/")
    icons: dict[str, str] = {}
    ipages = Pages([ICON_PAGE])
    for name in names:
        if not name.endswith(".png") or not name.startswith(icon_dirs):
            continue
        img = src.image(name)
        if img.size != (CELL, CELL):
            continue
        key = slug(name)
        if key in icons:
            continue
        ch = ipages.put(img)
        icons[key] = ch

    # ---- 3. запись страниц ------------------------------------------------
    (PACK_DIR / "font").mkdir(parents=True, exist_ok=True)
    pages.save(PACK_DIR / "font")
    ipages.save(PACK_DIR / "font")

    # ---- 4. YAML с готовыми строками -------------------------------------
    lines = ["# Сгенерировано tools/import_funtime_pack.py — не править руками.",
             "# Строки уже с \\uXXXX: вставляйте их в кавычки \"...\" в конфигах",
             "# (DeluxeMenus, VoidTab и т.п.), YAML сам развернёт escape.",
             "",
             "# Страницы глифов: " + ", ".join(banner_page_files + [ICON_PAGE]),
             "",
             "banners:"]
    for key, text in banner_strings.items():
        lines.append(f'  {key}: "{esc(text)}"')
    lines += ["", "icons:"]
    for key, ch in icons.items():
        lines.append(f'  {key}: "{esc(ch)}"')
    lines.append("")
    CONFIG_OUT.write_text("\n".join(lines), encoding="utf-8")

    print(f"заголовков (banners): {len(banner_strings)}, иконок (icons): {len(icons)}")
    print(f"страницы: {', '.join(banner_page_files + [ICON_PAGE])}")
    print(f"конфиг: {CONFIG_OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
