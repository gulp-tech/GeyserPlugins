#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Превью главного меню (preview/menu-preview.png) по раскладке configs/DeluxeMenus-menu-glyph.yml.

Только для проверки «как это будет выглядеть»: важен порядок операций —
сначала вся отрисовка, потом вставка картинок, иначе ImageDraw теряет буфер.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SHEET = Image.open(ROOT / "VoidResourcePack" / "font" / "glyph_E1.png").convert("RGBA")
S = 44
LAYOUT = {20: "E140", 21: "E141", 22: "E142", 23: "E143", 24: "E144",
          30: "E145", 31: "E14C", 32: "E14D",
          37: "E148", 43: "E149", 49: "E147"}
FRAME = set(range(0, 9)) | {9, 17, 18, 26, 27, 35} | set(range(36, 45)) | set(range(45, 49)) | set(range(50, 54))


def glyph(code):
    idx = int(code[1:], 16) - 0x100  # E140 → 0x40 → строка 4, столбец 0
    cell = SHEET.crop(((idx % 16) * 16, (idx // 16) * 16, (idx % 16) * 16 + 16, (idx // 16) * 16 + 16))
    return cell.resize((S - 12, S - 12), Image.NEAREST)


def slot_xy(i):
    return 20 + (i % 9) * S, 68 + (i // 9) * S


def main():
    w, h = 9 * S + 40, 6 * S + 96
    img = Image.new("RGBA", (w, h), (16, 14, 20, 255))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, w, 56], fill=(32, 25, 42, 255))
    font = ImageFont.truetype(str(ROOT / "tools" / "fonts" / "Minecraftia.ttf"), 20)
    d.text((20, 17), "Меню сервера", font=font, fill=(232, 213, 255, 255))
    for i in range(54):
        x, y = slot_xy(i)
        if i in FRAME:
            d.rectangle([x, y, x + S - 4, y + S - 4], fill=(38, 38, 44, 255), outline=(22, 22, 26, 255))
        else:
            d.rectangle([x, y, x + S - 4, y + S - 4], fill=(150, 150, 150, 255), outline=(60, 60, 60, 255))
            d.rectangle([x, y, x + S - 4, y + 6], fill=(172, 172, 172, 255))
            d.rectangle([x + 3, y + 9, x + S - 7, y + S - 7], fill=(128, 128, 128, 255))
    del d  # дальше идут вставки — свой ImageDraw больше не нужен

    # голова игрока
    head = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    hd = ImageDraw.Draw(head)
    hd.ellipse([6, 6, S - 10, S - 10], fill=(226, 186, 148, 255), outline=(70, 50, 40, 255))
    hd.ellipse([12, 10, 26, 19], fill=(64, 44, 32, 255))
    hd.ellipse([16, 21, 22, 27], fill=(120, 90, 70, 255))
    del hd
    hx, hy = slot_xy(13)
    img.paste(head, (hx, hy), head)

    for slot, code in LAYOUT.items():
        x, y = slot_xy(slot)
        g = glyph(code)
        img.paste(g, (x + 5, y + 5), g)

    img.resize((w * 2, h * 2), Image.NEAREST).save(ROOT / "preview" / "menu-preview.png")
    print("OK: preview/menu-preview.png")


if __name__ == "__main__":
    main()
