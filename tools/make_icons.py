#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Генератор графики FaralMC (v3).

Иконки: чёткий пиксель-арт в майнкрафт-стиле (как иконки на референсе).
Лист glyph_E0.png — стандартного ванильного размера 256x256 (ячейка 16x16),
иконка ужата до 13x13 по центру ячейки: в игре она меньше высоты строки
и не раздувает скорборд. (Лист 512 Bedrock рендерит крупнее ванили — не использовать.)

Логотип: textures/ui/title.png — текст FaralMC игровым шрифтом Minecraft
(Minecraftia, tools/fonts/Minecraftia.ttf): объёмная экструзия вниз,
синий градиент, блик сверху, чёрная обводка и тень — как логотип NEVERTIME
на референсе, только синий и читаемым FaralMC (с маленькими буквами).

Запуск: python3 tools/make_icons.py
"""
from PIL import Image, ImageDraw, ImageFont
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACK = ROOT / "FaralResourcePack"
PREVIEW = ROOT / "preview"
FONT_LOGO = ROOT / "tools" / "fonts" / "Minecraftia.ttf"

PALETTES = {
    "coin": {"D": (146, 96, 0, 255), "G": (255, 201, 60, 255), "L": (255, 231, 138, 255),
             "S": (240, 165, 0, 255), "W": (255, 255, 255, 255)},
    "spark": {"D": (176, 106, 0, 255), "Y": (255, 205, 32, 255), "L": (255, 240, 160, 255),
              "O": (255, 150, 20, 255), "W": (255, 255, 255, 255)},
    "sapphire": {"D": (20, 80, 160, 255), "B": (64, 170, 255, 255), "L": (170, 230, 255, 255),
                 "C": (30, 200, 255, 255), "W": (255, 255, 255, 255)},
    "shard": {"D": (10, 110, 150, 255), "C": (40, 210, 255, 255), "L": (180, 245, 255, 255),
              "B": (90, 160, 255, 255), "W": (255, 255, 255, 255), "P": (190, 120, 255, 255)},
    "crown": {"D": (146, 96, 0, 255), "G": (255, 201, 60, 255), "L": (255, 231, 138, 255),
              "S": (240, 165, 0, 255), "R": (255, 80, 90, 255), "B": (64, 170, 255, 255),
              "W": (255, 255, 255, 255)},
    "sword": {"D": (70, 80, 100, 255), "S": (215, 230, 245, 255), "L": (255, 255, 255, 255),
              "G": (255, 201, 60, 255), "B": (120, 70, 30, 255), "C": (40, 210, 255, 255)},
    "skull": {"D": (90, 95, 110, 255), "W": (235, 240, 248, 255), "L": (255, 255, 255, 255),
              "K": (25, 25, 35, 255), "P": (200, 107, 255, 255)},
    "clock": {"D": (146, 96, 0, 255), "G": (255, 201, 60, 255), "F": (255, 244, 200, 255),
              "H": (120, 70, 10, 255), "S": (240, 165, 0, 255), "W": (255, 255, 255, 255)},
}

ICONS = {
    "E001": ("coin", [
        "................",
        ".....DDDDDD.....",
        "...DDGGGGGGDD...",
        "..DGLLLLGGGGGD..",
        ".DGLWWLLGGGGSGD.",
        ".DLWLLGGGGGGSGD.",
        "DGLLGGGSSGGGGSGD",
        "DGLLGGSSSSGGGSGD",
        "DGLLGGSSSSGGGSGD",
        "DGLLGGGSSGGGGSGD",
        ".DGLLGGGGGGSSGD.",
        ".DGGGGGGGGGSSSD.",
        "..DGGGGGGGSSSD..",
        "...DDGGGGDDD....",
        ".....DDDDDD.....",
        "................",
    ]),
    "E002": ("spark", [  # жёлтая молния, как значок монет на референсе
        "................",
        "......DDD.......",
        "......DYYD......",
        ".....DYYYD......",
        ".....DYYYD......",
        "....DYYYYYD.....",
        "...DYYYYYYDD....",
        ".....DYYYD......",
        "....DYYYD.......",
        "....DYYD........",
        "...DYYD.........",
        "...DD...........",
        "................",
        "................",
        "................",
        "................",
    ]),
    "E003": ("sapphire", [
        "................",
        "...DDDDDDDDDD...",
        "..DLWLLLLLLBCD..",
        ".DLWWLLLBBBBBCD.",
        ".DLLLLBBBBBBBCD.",
        "..DDDLLLBDDBCD..",
        "....DBBBBBBCD...",
        "....DLBBBBCCD...",
        ".....DBBCCCD....",
        ".....DLBBCD.....",
        "......DBCD......",
        "......DDD.......",
        "................",
        "................",
        "................",
        "................",
    ]),
    "E004": ("shard", [
        ".......DD.......",
        "......DLCD......",
        ".....DLWCDD.....",
        ".....DLWCD......",
        "....DLWCD.......",
        "....DLCD........",
        "...DLWCD...DD...",
        "...DLCD...DLPD..",
        "..DLWCD...DLPD..",
        "..DLCD.....DD...",
        ".DLWCD..........",
        ".DLCD...........",
        "..DD............",
        "................",
        "................",
        "................",
    ]),
    "E005": ("crown", [
        "................",
        "..DD...DD...DD..",
        "..DGD..DGD..DGD.",
        "..DGGD.DGD.DGGD.",
        ".DGGGGDGGGDGGGD.",
        ".DGGGGGGGGGGGGD.",
        ".DGLLGGGGGGSSGD.",
        ".DGGRGGGGGBGSGD.",
        ".DGGGGGGGGGGSGD.",
        "..DSSSSSSSSSSD..",
        "..DDDDDDDDDDDD..",
        "................",
        "................",
        "................",
        "................",
        "................",
    ]),
    "E006": ("sword", [
        ".............DD.",
        "............DLSD",
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
    "E007": ("skull", [
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
    "E008": ("clock", [
        "................",
        ".....DDDDDD.....",
        "...DDGGGGGGDD...",
        "..DGFFFFFFFFGD..",
        ".DGFFFFFFFFFFGD.",
        ".DFFFFFHHFFFFGD.",
        "DFFFFFFHHFFFFFFD",
        "DFFFFFFHHHHHFFFD",
        "DFFFFFFFFFFFFFFD",
        "DFFFFFFFFFFFFFSD",
        ".DGFFFFFFFFFFSD.",
        ".DGGFFFFFFFFSSD.",
        "..DGGGGGGGGSSD..",
        "...DDGGGGDDD....",
        ".....DDDDDD.....",
        "................",
    ]),
}

LABELS_RU = {
    "coin": "Монета", "spark": "Молния", "sapphire": "Сапфир", "shard": "Осколок",
    "crown": "Привилегия", "sword": "Убийства", "skull": "Смерти", "clock": "Время в игре",
}
CELL = 16   # ячейка на листе 256x256 (как ванильный шрифт Bedrock)
OFF = 1     # иконка ужата до 13x13 по центру ячейки — меньше высоты строки


def render(rows, pal_name):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    pal = PALETTES[pal_name]
    for y, row in enumerate(rows):
        assert len(row) == 16, f"{pal_name} row {y}: {len(row)}"
        for x, ch in enumerate(row):
            if ch != ".":
                px[x, y] = pal[ch]
    return img


def font(size):
    for p in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(p, size)
        except Exception:
            continue
    return ImageFont.load_default()


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def make_logo():
    """FaralMC игровым шрифтом: объём, синий градиент, блик, обводка, тень."""
    text = "FaralMC"
    f = ImageFont.truetype(str(FONT_LOGO), 300)
    stroke = 12
    tmp = Image.new("RGBA", (10, 10))
    d = ImageDraw.Draw(tmp)
    bbox = d.textbbox((0, 0), text, font=f, stroke_width=stroke)
    w, h = bbox[2] - bbox[0] + 64, bbox[3] - bbox[1] + 96
    ox, oy = 32 - bbox[0], 28 - bbox[1]

    def text_layer(fill, dx=0, dy=0, stroke_fill=None):
        lay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        ImageDraw.Draw(lay).text((ox + dx, oy + dy), text, font=f, fill=fill,
                                 stroke_width=stroke, stroke_fill=stroke_fill or fill)
        return lay

    logo = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    logo.alpha_composite(text_layer((0, 0, 0, 160), 10, 26))                    # мягкая тень
    for dy in range(18, 0, -2):                                                  # объём вниз
        logo.alpha_composite(text_layer((7, 32, 110, 255), 0, dy))
    logo.alpha_composite(text_layer((0, 0, 0, 255)))                             # чёрная обводка

    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).text((ox, oy), text, font=f, fill=255)
    grad = Image.new("RGBA", (w, h))
    px = grad.load()
    for y in range(h):
        for x in range(w):
            t = min(1.0, max(0.0, (x / w) * 0.35 + (y / h) * 0.65))
            px[x, y] = lerp((165, 228, 255), (24, 96, 238), t) + (255,)
    shine = Image.new("L", (w, h), 0)                                            # блик сверху букв
    spx = shine.load()
    for y in range(h):
        a = max(0, int(120 * (1 - y / (h * 0.55)))) if y < h * 0.55 else 0
        for x in range(w):
            spx[x, y] = a
    white = Image.new("RGBA", (w, h), (255, 255, 255, 255))
    grad.paste(white, (0, 0), Image.composite(shine, Image.new("L", (w, h), 0), mask))
    letters = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    letters.paste(grad, (0, 0), mask)
    logo.alpha_composite(letters)
    box = logo.getbbox()  # обрезать прозрачные поля — иначе Bedrock ужимает логотип
    if box:
        pad = 6
        logo = logo.crop((max(0, box[0] - pad), max(0, box[1] - pad),
                          min(w, box[2] + pad), min(h, box[3] + pad)))
    return logo


def main():
    (PACK / "font").mkdir(parents=True, exist_ok=True)
    (PACK / "textures" / "ui").mkdir(parents=True, exist_ok=True)
    (PREVIEW / "icons").mkdir(parents=True, exist_ok=True)

    sheet = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    rendered = {}
    for code, (name, rows) in sorted(ICONS.items()):
        img = render(rows, name)
        rendered[code] = (name, img)
        idx = int(code[1:], 16)
        small = img.resize((13, 13), Image.NEAREST)
        sheet.paste(small, ((idx % 16) * CELL + OFF, (idx // 16) * CELL + OFF), small)
        img.save(PREVIEW / "icons" / f"{code[1:]}_{name}.png")
        img.resize((256, 256), Image.NEAREST).save(PREVIEW / "icons" / f"{code[1:]}_{name}_x16.png")
    sheet.save(PACK / "font" / "glyph_E0.png")
    rendered["E001"][1].resize((64, 64), Image.NEAREST).save(PACK / "pack_icon.png")

    logo = make_logo()
    logo.save(PACK / "textures" / "ui" / "title.png")
    logo.save(PREVIEW / "logo-title.png")
    bg = Image.new("RGBA", (logo.width + 80, logo.height + 140), (40, 90, 40, 255))
    bg.alpha_composite(logo, (40, 60))
    bg.convert("RGB").save(PREVIEW / "logo-title-on-bg.jpg", quality=90)

    # ---- превью иконок (иконки показаны и в реальном относительном размере) ----
    cell, pad, label_h = 160, 24, 58
    cols, rows_n = 4, 2
    W = cols * cell + (cols + 1) * pad
    H = rows_n * (cell + label_h) + (rows_n + 1) * pad + 70
    prev = Image.new("RGBA", (W, H), (24, 24, 32, 255))
    d = ImageDraw.Draw(prev)
    d.text((pad, 22), "FaralMC — иконки (\\uE001-\\uE008), в игре меньше строки", font=font(24), fill=(255, 210, 90))
    for i, (code, (name, img)) in enumerate(sorted(rendered.items())):
        cx = pad + (i % cols) * (cell + pad)
        cy = 70 + pad + (i // cols) * (cell + label_h + pad)
        d.rounded_rectangle([cx, cy, cx + cell, cy + cell], 14, fill=(10, 10, 14, 255))
        big = img.resize((cell - 32, cell - 32), Image.NEAREST)
        prev.paste(big, (cx + 16, cy + 16), big)
        d.text((cx + 4, cy + cell + 4), f"\\u{code}", font=font(20), fill=(140, 220, 255))
        d.text((cx + 4, cy + cell + 26), LABELS_RU[name], font=font(18), fill=(235, 235, 245))
    prev.save(PREVIEW / "icons-preview.png")

    # ---- мокап скорборда в РЕАЛЬНОМ масштабе (иконка = половина строки) ----
    mock = Image.new("RGBA", (680, 470), (58, 82, 52, 255))
    panel = Image.new("RGBA", (350, 380), (0, 0, 0, 165))
    mock.paste(panel, (24, 24), panel)
    d = ImageDraw.Draw(mock)
    title, grad = "FaralMC", [(255, 120, 40), (255, 150, 40), (255, 180, 60), (255, 210, 90),
                              (255, 235, 160), (240, 240, 240), (200, 210, 255)]
    x, f_title = 40, font(40)
    for i, ch in enumerate(title):
        d.text((x, 34), ch, font=f_title, fill=grad[i % len(grad)])
        x += d.textlength(ch, font=f_title)
    lines = [
        ("Имя: ", "MUSD38", (255, 170, 60), None),
        ("Донат: ", "Игрок", (90, 230, 90), None),
        ("Монеты: ", "500", (255, 220, 60), "E002"),
        ("Осколки: ", "150", (90, 210, 255), "E004"),
        ("", "", None, None),
        ("У/С: ", "12/3", (205, 110, 255), None),
        ("В игре: ", "27ч", (255, 220, 60), "E001"),
    ]
    f, y = font(27), 96
    for label, val, vc, icon in lines:
        if label:
            d.text((40, y), label, font=f, fill=(255, 255, 255))
            vx = 40 + d.textlength(label, font=f)
            d.text((vx, y), val, font=f, fill=vc)
            if icon:  # иконка 15px при строке 27px — как в игре
                ic = rendered[icon][1].resize((15, 15), Image.NEAREST)
                mock.paste(ic, (int(vx + d.textlength(val, font=f) + 8), y + 7), ic)
        y += 40
    mock.save(PREVIEW / "scoreboard-preview.png")
    print("OK icons + logo", logo.size)


if __name__ == "__main__":
    main()
