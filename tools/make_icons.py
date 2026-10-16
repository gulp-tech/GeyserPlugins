#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Генератор графики FaralMC (v2 — высокое разрешение).

1) Иконки скорборда: лист font/glyph_E0.png 512x512 (сетка 16x16, ячейка 32x32).
   Иконки рисуются векторно на холсте 128x128 и даунскейлятся до 32x32 —
   получаются гладкие, с градиентами и бликами, а не грубый пиксель-арт 16x16.
   Иконка занимает ~75% ячейки (остальное прозрачная подложка) — в скорборде
   она визуально МЕНЬШЕ строки, как на референсе.

2) Логотип экрана загрузки/титульного экрана: textures/ui/title.png
   (Bedrock рисует его вместо стандартного "MINECRAFT" при загрузке мира).
   Синий FaralMC с чёрной обводкой и тёмной тенью.

Запуск: python3 tools/make_icons.py
"""
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from pathlib import Path
import math

ROOT = Path(__file__).resolve().parent.parent
PACK = ROOT / "FaralResourcePack"
PREVIEW = ROOT / "preview"
S = 128  # холст одной иконки до даунскейла


# ------------------------------------------------------------------ утилиты
def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def vgradient(size, top, bottom):
    w, h = size
    img = Image.new("RGB", size)
    px = img.load()
    for y in range(h):
        c = lerp(top, bottom, y / max(1, h - 1))
        for x in range(w):
            px[x, y] = c
    return img


def apply_mask(shape_mask, colored):
    out = Image.new("RGBA", colored.size, (0, 0, 0, 0))
    out.paste(colored, (0, 0), shape_mask)
    return out


def outlined(draw_fn, top, bottom, outline=(30, 25, 10, 255), grow=7):
    """Форма с тёмной обводкой: рисуем увеличенную форму цветом обводки под основной."""
    base = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    mask_big = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(mask_big)
    draw_fn(d, grow)
    base = apply_mask(mask_big, Image.new("RGBA", (S, S), outline))
    mask = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(mask)
    draw_fn(d, 0)
    grad = vgradient((S, S), top, bottom).convert("RGBA")
    img = apply_mask(mask, grad)
    base.alpha_composite(img)
    return base


def glint(img, cx, cy, r=16, color=(255, 255, 255, 235)):
    """Четырёхконечная блёстка-блик."""
    d = ImageDraw.Draw(img, "RGBA")
    pts = [(cx, cy - r), (cx + r * 0.22, cy - r * 0.22), (cx + r, cy), (cx + r * 0.22, cy + r * 0.22),
           (cx, cy + r), (cx - r * 0.22, cy + r * 0.22), (cx - r, cy), (cx - r * 0.22, cy - r * 0.22)]
    d.polygon(pts, fill=color)


# ------------------------------------------------------------------ иконки
def icon_coin():
    def circ(d, g):
        d.ellipse([18 - g, 14 - g, 110 + g, 118 + g], fill=255)
    img = outlined(circ, (255, 222, 110), (238, 158, 10), outline=(122, 74, 0, 255))
    d = ImageDraw.Draw(img, "RGBA")
    d.ellipse([32, 28, 96, 104], outline=(196, 116, 0, 255), width=6)
    d.ellipse([40, 36, 88, 96], outline=(255, 236, 160, 200), width=3)
    d.arc([18, 60, 110, 118], 20, 160, fill=(214, 130, 0, 160), width=7)
    glint(img, 42, 38, 15)
    return img


def icon_spark():
    cx, cy = 64, 64
    def star(d, g):
        pts = []
        for ang, rad in [(90, 46), (180, 13), (270, 46), (0, 13)]:
            pass
        p = [(cx, cy - 48 - g), (cx + 12, cy - 12), (cx + 48 + g, cy), (cx + 12, cy + 12),
             (cx, cy + 48 + g), (cx - 12, cy + 12), (cx - 48 - g, cy), (cx - 12, cy - 12)]
        d.polygon(p, fill=255)
    img = outlined(star, (255, 226, 84), (255, 138, 10), outline=(150, 84, 0, 255), grow=5)
    d = ImageDraw.Draw(img, "RGBA")
    d.ellipse([52, 52, 76, 76], fill=(255, 252, 220, 255))
    for (x, y, r) in [(24, 26, 9), (104, 30, 8), (100, 100, 9), (26, 102, 8)]:
        glint(img, x, y, r, (255, 214, 90, 230))
    return img


def icon_sapphire():
    def gem(d, g):
        d.polygon([(26 - g, 34), (102 + g, 34), (116 + g, 58), (64, 116 + g), (12 - g, 58)], fill=255)
    img = outlined(gem, (140, 222, 255), (18, 118, 235), outline=(8, 60, 140, 255), grow=5)
    d = ImageDraw.Draw(img, "RGBA")
    d.polygon([(26, 34), (102, 34), (116, 58), (12, 58)], fill=(190, 238, 255, 120))
    d.line([26, 34, 64, 116], fill=(220, 246, 255, 170), width=4)
    d.line([102, 34, 64, 116], fill=(10, 84, 190, 170), width=4)
    d.line([12, 58, 116, 58], fill=(230, 250, 255, 190), width=4)
    glint(img, 42, 46, 13)
    return img


def icon_shard():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    def main_crystal(d, g):
        d.polygon([(78, 6 - g), (98 + g, 34), (72, 96 + g), (50 - g, 62), (58, 30)], fill=255)
    part = outlined(main_crystal, (150, 240, 255), (24, 140, 235), outline=(8, 84, 145, 255), grow=5)
    img.alpha_composite(part)
    d = ImageDraw.Draw(img, "RGBA")
    d.line([78, 8, 66, 88], fill=(235, 252, 255, 200), width=5)
    def small(d, g):
        d.polygon([(104, 72 - g), (116 + g, 88), (106, 114 + g), (94 - g, 92)], fill=255)
    part2 = outlined(small, (226, 178, 255), (140, 70, 235), outline=(84, 30, 150, 255), grow=4)
    img.alpha_composite(part2)
    glint(img, 76, 26, 11)
    return img


def icon_crown():
    def crown(d, g):
        d.polygon([(16 - g, 96 + g), (16 - g, 44), (40, 66), (64, 32 - g), (88, 66), (112 + g, 44), (112 + g, 96 + g)], fill=255)
    img = outlined(crown, (255, 224, 110), (240, 158, 10), outline=(122, 74, 0, 255), grow=5)
    d = ImageDraw.Draw(img, "RGBA")
    for x in (16, 64, 112):
        d.ellipse([x - 9, 22 if x == 64 else 34, x + 9, 40 if x == 64 else 52], fill=(255, 236, 150, 255), outline=(122, 74, 0, 255), width=3)
    d.rectangle([16, 84, 112, 96], fill=(214, 130, 0, 255))
    d.ellipse([56, 68, 72, 84], fill=(255, 84, 96, 255))
    d.ellipse([26, 70, 38, 82], fill=(64, 180, 255, 255))
    d.ellipse([90, 70, 102, 82], fill=(96, 230, 140, 255))
    return img


def icon_sword():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    lay = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay, "RGBA")
    d.polygon([(58, 4), (70, 4), (70, 72), (58, 72)], fill=(226, 238, 250, 255))  # клинок
    d.polygon([(58, 4), (64, 0), (70, 4), (70, 72), (58, 72)], outline=(90, 102, 124, 255))
    d.line([64, 8, 64, 70], fill=(150, 168, 194, 255), width=3)
    d.rounded_rectangle([38, 72, 90, 84], 5, fill=(255, 201, 60, 255), outline=(122, 74, 0, 255), width=3)  # гарда
    d.rectangle([58, 84, 70, 108], fill=(128, 76, 34, 255))  # рукоять
    d.line([58, 92, 70, 92], fill=(84, 48, 18, 255), width=3)
    d.line([58, 100, 70, 100], fill=(84, 48, 18, 255), width=3)
    d.ellipse([56, 106, 72, 122], fill=(64, 190, 255, 255), outline=(8, 84, 145, 255), width=3)  # навершие
    lay = lay.rotate(38, resample=Image.BICUBIC, center=(64, 64))
    img.alpha_composite(lay)
    return img


def icon_skull():
    def skull(d, g):
        d.ellipse([24 - g, 16 - g, 104 + g, 84 + g], fill=255)
        d.rounded_rectangle([42 - g, 70, 86 + g, 108 + g], 10, fill=255)
    img = outlined(skull, (252, 253, 255), (196, 204, 220), outline=(88, 94, 110, 255), grow=5)
    d = ImageDraw.Draw(img, "RGBA")
    d.ellipse([36, 48, 56, 68], fill=(28, 26, 40, 255))
    d.ellipse([72, 48, 92, 68], fill=(28, 26, 40, 255))
    d.ellipse([43, 55, 50, 62], fill=(205, 130, 255, 255))
    d.ellipse([79, 55, 86, 62], fill=(205, 130, 255, 255))
    d.polygon([(64, 68), (58, 80), (70, 80)], fill=(28, 26, 40, 255))
    for x in (50, 64, 78):
        d.line([x, 92, x, 104], fill=(120, 128, 146, 255), width=4)
    return img


def icon_clock():
    def circ(d, g):
        d.ellipse([16 - g, 16 - g, 112 + g, 112 + g], fill=255)
    img = outlined(circ, (255, 224, 110), (240, 158, 10), outline=(122, 74, 0, 255), grow=5)
    d = ImageDraw.Draw(img, "RGBA")
    d.ellipse([28, 28, 100, 100], fill=(255, 246, 208, 255))
    for ang in range(0, 360, 90):
        x = 64 + 30 * math.cos(math.radians(ang))
        y = 64 + 30 * math.sin(math.radians(ang))
        d.ellipse([x - 3, y - 3, x + 3, y + 3], fill=(160, 104, 20, 255))
    d.line([64, 64, 64, 40], fill=(110, 62, 8, 255), width=7)
    d.line([64, 64, 82, 72], fill=(110, 62, 8, 255), width=7)
    d.ellipse([58, 58, 70, 70], fill=(110, 62, 8, 255))
    return img


DRAW = {
    "E001": ("coin", icon_coin), "E002": ("spark", icon_spark),
    "E003": ("sapphire", icon_sapphire), "E004": ("shard", icon_shard),
    "E005": ("crown", icon_crown), "E006": ("sword", icon_sword),
    "E007": ("skull", icon_skull), "E008": ("clock", icon_clock),
}
LABELS_RU = {
    "coin": "Монета", "spark": "Искра", "sapphire": "Сапфир", "shard": "Осколок",
    "crown": "Привилегия", "sword": "Убийства", "skull": "Смерти", "clock": "Время в игре",
}
CELL = 32          # ячейка глифа на листе 512x512
PAD = 4            # прозрачная подложка: иконка занимает 24/32 ячейки — в игре меньше строки


# ------------------------------------------------------------------ логотип
def font(size):
    for p in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(p, size)
        except Exception:
            continue
    return ImageFont.load_default()


def make_logo():
    """textures/ui/title.png — FaralMC вместо MINECRAFT на загрузке и главном экране."""
    text = "FaralMC"
    f = font(300)
    tmp = Image.new("RGBA", (10, 10))
    d = ImageDraw.Draw(tmp)
    stroke = 16
    bbox = d.textbbox((0, 0), text, font=f, stroke_width=stroke)
    w, h = bbox[2] - bbox[0] + 40, bbox[3] - bbox[1] + 60
    # тёмная тень (как красная у NEVERTIME, только тёмно-синяя)
    shadow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).text((20 - bbox[0], 26 - bbox[1]), text, font=f,
                                fill=(6, 26, 92, 255), stroke_width=stroke, stroke_fill=(6, 26, 92, 255))
    # чёрная обводка
    outline = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(outline).text((20 - bbox[0], 20 - bbox[1]), text, font=f,
                                 fill=(0, 0, 0, 255), stroke_width=stroke, stroke_fill=(0, 0, 0, 255))
    # градиентная заливка букв
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).text((20 - bbox[0], 20 - bbox[1]), text, font=f, fill=255)
    grad = vgradient((w, h), (140, 224, 255), (22, 92, 235)).convert("RGBA")
    letters = apply_mask(mask, grad)

    logo = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    logo.alpha_composite(shadow, (0, 8))
    logo.alpha_composite(outline)
    logo.alpha_composite(letters)
    return logo


# ------------------------------------------------------------------ main
def main():
    (PACK / "font").mkdir(parents=True, exist_ok=True)
    (PACK / "textures" / "ui").mkdir(parents=True, exist_ok=True)
    (PREVIEW / "icons").mkdir(parents=True, exist_ok=True)

    sheet = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    rendered = {}
    for code, (name, fn) in sorted(DRAW.items()):
        img128 = fn()
        icon = img128.resize((CELL - 2 * PAD, CELL - 2 * PAD), Image.LANCZOS)
        cell_img = Image.new("RGBA", (CELL, CELL), (0, 0, 0, 0))
        cell_img.alpha_composite(icon, (PAD, PAD))
        rendered[code] = (name, cell_img)
        idx = int(code[1:], 16)
        sheet.paste(cell_img, ((idx % 16) * CELL, (idx // 16) * CELL), cell_img)
        cell_img.resize((256, 256), Image.NEAREST).save(PREVIEW / "icons" / f"{code[1:]}_{name}_x8.png")
        img128.save(PREVIEW / "icons" / f"{code[1:]}_{name}.png")
    sheet.save(PACK / "font" / "glyph_E0.png")
    rendered["E001"][1].resize((64, 64), Image.NEAREST).save(PACK / "pack_icon.png")

    logo = make_logo()
    logo.save(PACK / "textures" / "ui" / "title.png")
    logo.save(PREVIEW / "logo-title.png")

    # ---- превью иконок ----
    cell, pad, label_h = 160, 24, 58
    cols, rows_n = 4, 2
    W = cols * cell + (cols + 1) * pad
    H = rows_n * (cell + label_h) + (rows_n + 1) * pad + 70
    prev = Image.new("RGBA", (W, H), (24, 24, 32, 255))
    d = ImageDraw.Draw(prev)
    d.text((pad, 22), "FaralMC — иконки скорборда (\\uE001-\\uE008)", font=font(25), fill=(255, 210, 90))
    for i, (code, (name, img)) in enumerate(sorted(rendered.items())):
        cx = pad + (i % cols) * (cell + pad)
        cy = 70 + pad + (i // cols) * (cell + label_h + pad)
        d.rounded_rectangle([cx, cy, cx + cell, cy + cell], 14, fill=(10, 10, 14, 255))
        big = img.resize((cell - 24, cell - 24), Image.NEAREST)
        prev.paste(big, (cx + 12, cy + 12), big)
        d.text((cx + 4, cy + cell + 4), f"\\u{code}", font=font(20), fill=(140, 220, 255))
        d.text((cx + 4, cy + cell + 26), LABELS_RU[name], font=font(18), fill=(235, 235, 245))
    prev.save(PREVIEW / "icons-preview.png")
    print("OK icons:", PACK / "font" / "glyph_E0.png", "| logo:", PACK / "textures" / "ui" / "title.png", logo.size)


if __name__ == "__main__":
    main()
