#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Генератор иконок FaralMC для Bedrock-ресурспака (Geyser).

Каждая иконка — пиксель-арт 16x16, ставится в лист font/glyph_E0.png
(страница Unicode U+E000..U+E0FF, сетка 16x16 ячеек по 16 px, лист 256x256).

В скорборде иконка занимает ровно ОДИН символ (высота = высота строки),
поэтому таб остаётся компактным, как на референсе.

Запуск:  python3 tools/make_icons.py
Результаты:
  FaralResourcePack/font/glyph_E0.png   — лист глифов для пака
  FaralResourcePack/pack_icon.png       — иконка пака (монета)
  preview/icons/*.png                   — каждая иконка отдельно (16x16 и x16)
  preview/icons-preview.png             — общий лист-превью на тёмном фоне
  preview/scoreboard-preview.png        — мокап скорборда FaralMC
"""
from PIL import Image, ImageDraw, ImageFont
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACK = ROOT / "FaralResourcePack"
PREVIEW = ROOT / "preview"

# ---------------------------------------------------------------- палитры
# . = прозрачный
PALETTES = {
    "coin": {  # золотая монета (как у "В игре" на фото)
        "D": (146, 96, 0, 255), "G": (255, 201, 60, 255), "L": (255, 231, 138, 255),
        "S": (240, 165, 0, 255), "W": (255, 255, 255, 255),
    },
    "spark": {  # жёлтая искра-молния (как у "Монеты" на фото)
        "D": (176, 106, 0, 255), "Y": (255, 205, 32, 255), "L": (255, 240, 160, 255),
        "O": (255, 150, 20, 255), "W": (255, 255, 255, 255),
    },
    "sapphire": {  # синий сапфир/алмаз (как у "Сапфиры" на фото)
        "D": (20, 80, 160, 255), "B": (64, 170, 255, 255), "L": (170, 230, 255, 255),
        "C": (30, 200, 255, 255), "W": (255, 255, 255, 255),
    },
    "shard": {  # осколок — донат-валюта (PlayerPoints), голубой кристалл
        "D": (10, 110, 150, 255), "C": (40, 210, 255, 255), "L": (180, 245, 255, 255),
        "B": (90, 160, 255, 255), "W": (255, 255, 255, 255), "P": (190, 120, 255, 255),
    },
    "crown": {  # привилегия
        "D": (146, 96, 0, 255), "G": (255, 201, 60, 255), "L": (255, 231, 138, 255),
        "S": (240, 165, 0, 255),
        "R": (255, 80, 90, 255), "B": (64, 170, 255, 255), "W": (255, 255, 255, 255),
    },
    "sword": {  # убийства
        "D": (70, 80, 100, 255), "S": (215, 230, 245, 255), "L": (255, 255, 255, 255),
        "G": (255, 201, 60, 255), "B": (120, 70, 30, 255), "C": (40, 210, 255, 255),
    },
    "skull": {  # смерти
        "D": (90, 95, 110, 255), "W": (235, 240, 248, 255), "L": (255, 255, 255, 255),
        "K": (25, 25, 35, 255), "P": (200, 107, 255, 255),
    },
    "clock": {  # время в игре
        "D": (146, 96, 0, 255), "G": (255, 201, 60, 255), "F": (255, 244, 200, 255),
        "H": (120, 70, 10, 255), "S": (240, 165, 0, 255), "W": (255, 255, 255, 255),
    },
}

# ---------------------------------------------------------------- рисунки 16x16
ICONS = {
    # кодпоинт, имя, палитра, строки
    "E001": ("coin", "coin", [
        "................",
        ".....DDDDDD.....",
        "...DDGGGGGGDD...",
        "..DGGGGGGGGGGD..",
        ".DGGLGGGGGGSGGD.",
        ".DGLGGSSSSGGSGD.",
        "DGGGGSGGGGSGGSGD",
        "DGGGGSGGGGSGGSGD",
        "DGGGGSGGGGSGGSGD",
        "DGGGGSSSSSSGSGD.",
        ".DGGGGSSSSGGSGD.",
        ".DGGGGGGGGGGSGD.",
        "..DGGGGGGGGDSD..",
        "...DDGGGGDDD....",
        ".....DDDDDD.....",
        "................",
    ]),
    "E002": ("spark", "spark", [
        "................",
        ".......DD.......",
        "......DYYD......",
        "......DYYD......",
        "..D...DYYD...D..",
        "...D.DYYYYD.D...",
        "....DYYLLYYD....",
        ".DDDYYLWWYYDDD..",
        "....DYYLLYYD....",
        "...D.DYYYYD.D...",
        "..D...DYYD...D..",
        "......DYYD......",
        "......DOOD......",
        ".......DD.......",
        "................",
        "................",
    ]),
    "E003": ("sapphire", "sapphire", [
        "................",
        "...DDDDDDDDDD...",
        "..DLLLLLLLLBCD..",
        ".DLLWWLLLBBBBCD.",
        ".DLWLLBBBBBBBCD.",
        "..DLLBBBBBBCD...",
        "...DBBBBBBCD....",
        "....DBBBBCD.....",
        "....DBBBCD......",
        ".....DBCD.......",
        ".....DBD........",
        "......D.........",
        "................",
        "................",
        "................",
        "................",
    ]),
    "E004": ("shard", "shard", [
        "..........DDD...",
        ".........DLWCD..",
        "........DLWCCD..",
        ".......DLWCD....",
        "......DLWCD.....",
        ".....DLWCD......",
        "....DLWCD...DD..",
        "...DLWCD...DLPD.",
        "...DLCD....DLPD.",
        "....DD......DD..",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
    ]),
    "E005": ("crown", "crown", [
        "................",
        ".DD....DD....DD.",
        ".DGD...DGD...DGD",
        ".DGGD..DGD..DGGD",
        ".DGGGD.DGD.DGGGD",
        "..DGGGGGGGGGGD..",
        "..DGLGGGGGGSGD..",
        "..DGGRGGGGBSGD..",
        "..DGGGGGGGGSGD..",
        "..DSSSSSSSSSSD..",
        "..DDDDDDDDDDDD..",
        "................",
        "................",
        "................",
        "................",
        "................",
    ]),
    "E006": ("sword", "sword", [
        ".............DD.",
        "............DLS D".replace(" ", ""),
        "...........DSSD.",
        "..........DSSD..",
        "...C.....DSSD...",
        "....C...DSSD....",
        ".....C.DSSD.....",
        "......DSSD......",
        ".GGGDSSD........",
        "..DGGGD.........",
        ".GD.DGBD........",
        ".DD..DGBBD......",
        "......DGBBD.....",
        ".......DBBD.....",
        "........DDD.....",
        "................",
    ]),
    "E007": ("skull", "skull", [
        "................",
        "....DDDDDDDD....",
        "..DDWWWWWWWWDD..",
        ".DWWWWWWWWWWWD..",
        ".DWWKKWWWWKKWWD.",
        "DWWWKPKWWKPKWWWD",
        "DWWWWWWKKWWWWWWD",
        ".DWWWWWWWWWWWD..",
        ".DWWWDDDDDDWWD..",
        "..DDWDWDWDWDD...",
        "....DWDWDWD.....",
        "....DDDDDDD.....",
        "................",
        "................",
        "................",
        "................",
    ]),
    "E008": ("clock", "clock", [
        "................",
        ".....DDDDDD.....",
        "...DDGGGGGGDD...",
        "..DGFFFFFFFFGD..",
        ".DGFFFFFFFFFFGD.",
        ".DFFFFHHFFFFFGD.",
        "DFFFFFHHFFFFFFGD",
        "DFFFFFHHHHHFFFGD",
        "DFFFFFFFFFFHFGSD",
        "DFFFFFFFFFFFFFFD",
        ".DGFFFFFFFFFFGD.",
        ".DGGFFFFFFFFSGD.",
        "..DGGGGGGGGSGD..",
        "...DDGGGGDDD....",
        ".....DDDDDD.....",
        "................",
    ]),
}

LABELS_RU = {
    "coin": "Монета", "spark": "Искра",
    "sapphire": "Сапфир", "shard": "Осколок",
    "crown": "Привилегия", "sword": "Убийства", "skull": "Смерти", "clock": "Время в игре",
}


def render(code: str, name: str, pal: str, rows: list[str]) -> Image.Image:
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    palette = PALETTES[pal]
    for y, row in enumerate(rows):
        assert len(row) == 16, f"{name} row {y} len {len(row)}"
        for x, ch in enumerate(row):
            if ch != ".":
                px[x, y] = palette[ch]
    return img


def font(size: int):
    for p in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(p, size)
        except Exception:
            continue
    return ImageFont.load_default()


def main() -> None:
    (PACK / "font").mkdir(parents=True, exist_ok=True)
    (PREVIEW / "icons").mkdir(parents=True, exist_ok=True)

    sheet = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    rendered = {}
    for code, (name, pal, rows) in sorted(ICONS.items()):
        img = render(code, name, pal, rows)
        rendered[code] = (name, img)
        idx = int(code[1:], 16)  # E001 -> 1
        x, y = (idx % 16) * 16, (idx // 16) * 16
        sheet.paste(img, (x, y), img)
        img.save(PREVIEW / "icons" / f"{code[1:]}_{name}.png")
        img.resize((256, 256), Image.NEAREST).save(PREVIEW / "icons" / f"{code[1:]}_{name}_x16.png")

    sheet.save(PACK / "font" / "glyph_E0.png")
    rendered["E001"][1].resize((64, 64), Image.NEAREST).save(PACK / "pack_icon.png")

    # ---- общий лист-превью ----------------------------------------------
    cell, pad, label_h = 160, 24, 58
    cols = 4
    rows_n = (len(rendered) + cols - 1) // cols
    W = cols * cell + (cols + 1) * pad
    H = rows_n * (cell + label_h) + (rows_n + 1) * pad + 70
    prev = Image.new("RGBA", (W, H), (24, 24, 32, 255))
    d = ImageDraw.Draw(prev)
    d.text((pad, 22), "FaralMC — иконки скорборда (\\uE001-\\uE008)", font=font(25), fill=(255, 210, 90))
    for i, (code, (name, img)) in enumerate(sorted(rendered.items())):
        cx = pad + (i % cols) * (cell + pad)
        cy = 70 + pad + (i // cols) * (cell + label_h + pad)
        d.rounded_rectangle([cx, cy, cx + cell, cy + cell], 14, fill=(10, 10, 14, 255))
        big = img.resize((cell - 32, cell - 32), Image.NEAREST)
        prev.paste(big, (cx + 16, cy + 16), big)
        d.text((cx + 4, cy + cell + 4), f"\\u{code}", font=font(20), fill=(140, 220, 255))
        d.text((cx + 4, cy + cell + 26), LABELS_RU[name], font=font(18), fill=(235, 235, 245))
    prev.save(PREVIEW / "icons-preview.png")

    # ---- мокап скорборда --------------------------------------------------
    mock = Image.new("RGBA", (760, 560), (58, 82, 52, 255))
    d = ImageDraw.Draw(mock)
    for yy in range(0, 560, 40):  # намёк на "мир" за панелью
        d.line([(0, yy), (760, yy)], fill=(48, 70, 44, 255))
    panel = Image.new("RGBA", (400, 430), (0, 0, 0, 165))
    mock.paste(panel, (24, 24), panel)
    d = ImageDraw.Draw(mock)
    title = "FaralMC"
    grad = [(255, 120, 40), (255, 150, 40), (255, 180, 60), (255, 210, 90),
            (255, 235, 160), (240, 240, 240), (200, 210, 255), (150, 190, 255)]
    x = 44
    f_title = font(46)
    for i, ch in enumerate(title):
        d.text((x, 36), ch, font=f_title, fill=grad[i % len(grad)])
        x += d.textlength(ch, font=f_title)
    lines = [
        ("Имя: ", (255, 255, 255), "MUSD38", (255, 170, 60), None),
        ("Донат: ", (255, 255, 255), "Игрок", (90, 230, 90), None),
        ("Монеты: ", (255, 255, 255), "500", (255, 220, 60), "E002"),
        ("Осколки: ", (255, 255, 255), "150", (90, 210, 255), "E004"),
        ("", None, "", None, None),
        ("У/С: ", (255, 255, 255), "12/3", (205, 110, 255), None),
        ("В игре: ", (255, 255, 255), "27ч", (255, 220, 60), "E001"),
    ]
    f = font(30)
    y = 105
    for label, lc, val, vc, icon in lines:
        if label:
            d.text((44, y), label, font=f, fill=lc)
            vx = 44 + d.textlength(label, font=f)
            d.text((vx, y), val, font=f, fill=vc)
            if icon:
                ic = rendered[icon][1].resize((30, 30), Image.NEAREST)
                mock.paste(ic, (int(vx + d.textlength(val, font=f) + 10), y + 2), ic)
        y += 44
    d.text((44, 486), "Мокап: так скорборд выглядит у Bedrock-игроков (иконка = 1 символ строки)",
           font=font(16), fill=(255, 255, 255))
    mock.save(PREVIEW / "scoreboard-preview.png")
    print("OK:", PACK / "font" / "glyph_E0.png", "| icons:", len(rendered))


if __name__ == "__main__":
    main()
