#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Иконки для меню VoidPE (в стиле FunTime): тёмная плашка + яркая объёмная иконка.

Рисуются в 16×16 (как остальные глифы пака) с 8-кратным суперсэмплингом,
поэтому на экране выглядят гладко, а не «зубцами».

Занятые коды страницы E1 пака VoidResourcePack:
  E101–E11F, E128–E12A — иконки скорборда/устройств/рангов
  E120–E127 — полоса логотипа в заголовок
  E140–E14F — ЭТОТ набор (меню)

Запуск: python3 tools/make_icons.py (встроено в основной генератор пака)
"""
from PIL import Image, ImageDraw

S = 8                       # суперсэмплинг: 16 * 8 = 128 px
UNIT = 16 * S

# Палитра NEVERTIME: тёмная база, фиолетовый и голубой акценты.
INK = (24, 17, 32, 255)         # тёмная обводка
PANEL = (44, 33, 58, 255)       # подложка
PANEL_HI = (66, 50, 86, 255)    # верхний блик подложки
PURPLE = (200, 107, 255, 255)
PURPLE_D = (123, 63, 209, 255)
PURPLE_L = (231, 183, 255, 255)
CYAN = (64, 216, 255, 255)
CYAN_D = (22, 132, 190, 255)
GOLD = (255, 201, 60, 255)
GOLD_D = (176, 106, 0, 255)
GOLD_L = (255, 231, 138, 255)
GREEN = (53, 224, 122, 255)
GREEN_D = (18, 140, 74, 255)
RED = (255, 90, 110, 255)
RED_D = (170, 30, 55, 255)
WHITE = (255, 255, 255, 255)
SOFT = (196, 178, 214, 255)


def canvas():
    return Image.new("RGBA", (UNIT, UNIT), (0, 0, 0, 0))


def box(x0, y0, x1, y1):
    """Координаты в единицах 16×16 → пиксели холста."""
    return [x0 * S, y0 * S, x1 * S - 1, y1 * S - 1]


def plate(img):
    """Тёмная круглая плашка под иконкой — «кнопка» как в меню FunTime."""
    d = ImageDraw.Draw(img)
    d.rounded_rectangle(box(0.6, 0.6, 15.4, 15.4), radius=4.6 * S, fill=PANEL, outline=INK, width=int(0.7 * S))
    d.rounded_rectangle(box(1.4, 1.1, 14.6, 8.2), radius=3.8 * S, fill=PANEL_HI)
    d.rounded_rectangle(box(0.6, 0.6, 15.4, 15.4), radius=4.6 * S, outline=(255, 255, 255, 26), width=int(0.5 * S))
    return d


def _poly(d, points, fill, outline=INK, width=0.6):
    pts = [(x * S, y * S) for x, y in points]
    d.polygon(pts, fill=fill, outline=outline, width=max(1, int(width * S)))


def _rr(d, x0, y0, x1, y1, r, fill, outline=INK, width=0.6):
    d.rounded_rectangle(box(x0, y0, x1, y1), radius=r * S, fill=fill,
                        outline=outline, width=max(1, int(width * S)))


def _line(d, points, color, width=1.0):
    pts = [(x * S, y * S) for x, y in points]
    d.line(pts, fill=color, width=int(width * S), joint="curve")


# ---------------------------------------------------------------- иконки

def icon_shop():
    img = canvas()
    d = plate(img)
    # ручки сумки
    d.arc(box(5.0, 3.0, 11.0, 8.4), 180, 360, fill=GOLD_D, width=int(0.9 * S))
    # корпус
    _rr(d, 3.2, 5.6, 12.8, 13.4, 1.6, PURPLE)
    _rr(d, 4.1, 6.4, 11.9, 8.6, 1.0, PURPLE_L, outline=None, width=0)
    # монета
    d.ellipse(box(6.4, 8.0, 9.6, 11.2), fill=GOLD, outline=GOLD_D, width=int(0.5 * S))
    _line(d, [(8.0, 8.9), (8.0, 10.3)], GOLD_D, 0.5)
    return img


def icon_case():
    img = canvas()
    d = plate(img)
    _rr(d, 2.6, 6.2, 13.4, 13.2, 1.4, (108, 72, 190, 255))          # корпус
    _rr(d, 2.6, 4.4, 13.4, 7.2, 1.2, (150, 106, 235, 255))          # крышка
    _rr(d, 3.4, 4.9, 12.6, 6.0, 0.6, (198, 165, 255, 255), outline=None, width=0)
    d.rectangle(box(2.6, 7.1, 13.4, 7.9), fill=GOLD)                # золотая полоса
    _rr(d, 6.9, 6.6, 9.1, 9.4, 0.6, GOLD, outline=GOLD_D)           # замок
    _line(d, [(8.0, 7.5), (8.0, 8.6)], GOLD_D, 0.4)
    _poly(d, [(11.6, 2.4), (12.4, 3.2), (11.6, 4.0), (10.8, 3.2)], WHITE)  # искра
    return img


def icon_donate():
    img = canvas()
    d = plate(img)
    _poly(d, [(2.8, 10.8), (3.6, 5.0), (6.4, 7.6), (8.0, 4.0), (9.6, 7.6), (12.4, 5.0), (13.2, 10.8)], GOLD)
    d.rectangle(box(2.8, 10.8, 13.2, 12.4), fill=GOLD_D)
    d.rectangle(box(2.8, 10.8, 13.2, 11.2), fill=GOLD_L, width=0)
    d.ellipse(box(7.0, 8.2, 9.0, 10.2), fill=PURPLE, outline=PURPLE_D, width=int(0.4 * S))  # камень
    _poly(d, [(12.6, 2.2), (13.3, 3.3), (12.6, 4.4), (11.9, 3.3)], WHITE)
    return img


def icon_quest():
    img = canvas()
    d = plate(img)
    _rr(d, 4.0, 2.8, 12.4, 13.2, 1.2, (240, 236, 226, 255))         # свиток
    _rr(d, 4.0, 2.8, 12.4, 4.4, 0.8, (208, 200, 186, 255), outline=None, width=0)
    for y in (6.2, 8.2):
        _line(d, [(5.6, y), (10.8, y)], (150, 142, 130, 255), 0.55)
    _line(d, [(5.8, 10.6), (7.2, 12.0), (11.2, 8.0)], GREEN, 1.0)   # галочка
    _rr(d, 2.4, 3.4, 4.4, 12.6, 0.9, CYAN, outline=CYAN_D)          # корешок
    return img


def icon_clan():
    img = canvas()
    d = plate(img)
    _poly(d, [(8.0, 2.2), (13.4, 4.4), (13.0, 9.6), (8.0, 13.8), (3.0, 9.6), (2.6, 4.4)], CYAN)
    _poly(d, [(8.0, 3.6), (12.2, 5.2), (11.9, 9.0), (8.0, 12.2), (4.1, 9.0), (3.8, 5.2)],
          (28, 96, 140, 255), outline=None, width=0)
    _poly(d, [(8.0, 5.4), (10.2, 7.4), (8.0, 10.6), (5.8, 7.4)], WHITE)
    return img


def icon_top():
    img = canvas()
    d = plate(img)
    # чаша
    _poly(d, [(4.0, 3.4), (12.0, 3.4), (11.2, 8.6), (8.0, 10.4), (4.8, 8.6)], GOLD)
    d.rectangle(box(4.0, 3.4, 12.0, 4.2), fill=GOLD_L)
    # ручки
    d.arc(box(1.6, 3.6, 5.2, 7.6), 90, 270, fill=GOLD_D, width=int(0.8 * S))
    d.arc(box(10.8, 3.6, 14.4, 7.6), 270, 90, fill=GOLD_D, width=int(0.8 * S))
    # ножка и основание
    d.rectangle(box(7.2, 10.0, 8.8, 11.8), fill=GOLD_D)
    _rr(d, 5.0, 11.6, 11.0, 13.4, 0.7, GOLD, outline=GOLD_D)
    _poly(d, [(8.0, 5.0), (8.6, 6.2), (8.0, 7.4), (7.4, 6.2)], GOLD_L, outline=None, width=0)
    return img


def icon_settings():
    """Три ползунка: на 16×16 читается лучше шестерёнки."""
    img = canvas()
    d = plate(img)
    rows = [(4.6, 5.6), (8.0, 10.4), (11.4, 4.4)]   # (y, x knob)
    for y, knob in rows:
        _line(d, [(3.2, y), (12.8, y)], INK, 1.05)
        _line(d, [(3.2, y), (12.8, y)], (208, 200, 222, 255), 0.55)
        accent = PURPLE if knob > 8 else CYAN
        _line(d, [(3.2, y), (knob, y)], accent, 0.55)
        d.ellipse(box(knob - 1.9, y - 1.9, knob + 1.9, y + 1.9), fill=WHITE,
                  outline=INK, width=int(0.42 * S))
        d.ellipse(box(knob - 1.2, y - 1.2, knob + 1.2, y + 1.2), fill=accent, outline=None, width=0)
    return img


def icon_close():
    img = canvas()
    d = plate(img)
    d.ellipse(box(2.4, 2.4, 13.6, 13.6), fill=RED, outline=RED_D, width=int(0.7 * S))
    d.ellipse(box(3.2, 2.9, 12.8, 7.2), fill=(255, 140, 150, 140), outline=None, width=0)
    _line(d, [(5.6, 5.6), (10.4, 10.4)], WHITE, 1.3)
    _line(d, [(10.4, 5.6), (5.6, 10.4)], WHITE, 1.3)
    return img


def _arrow(flip=False):
    img = canvas()
    d = plate(img)
    pts = [(10.4, 4.2), (6.0, 8.0), (10.4, 11.8)]
    if flip:
        pts = [(16 - x, y) for x, y in pts]
    _line(d, pts, PURPLE_L, 2.0)
    _line(d, [(pts[0][0], pts[0][1] - 0.15), (pts[1][0], pts[1][1] - 0.15), (pts[2][0], pts[2][1] - 0.15)], WHITE, 0.7)
    return img


def icon_arrow_left():
    return _arrow(flip=False)


def icon_arrow_right():
    return _arrow(flip=True)


def icon_key():
    img = canvas()
    d = plate(img)
    d.ellipse(box(3.4, 3.4, 9.0, 9.0), fill=GOLD, outline=GOLD_D, width=int(0.6 * S))
    d.ellipse(box(4.9, 4.9, 7.5, 7.5), fill=PANEL, outline=GOLD_D, width=int(0.4 * S))
    _line(d, [(8.2, 8.2), (12.6, 12.6)], GOLD, 1.1)
    _line(d, [(11.0, 11.0), (12.4, 9.6)], GOLD, 0.9)
    _line(d, [(12.0, 12.0), (13.4, 10.6)], GOLD, 0.9)
    d.ellipse(box(4.2, 4.2, 6.0, 6.0), fill=GOLD_L, outline=None, width=0)
    return img


def icon_gift():
    img = canvas()
    d = plate(img)
    _rr(d, 3.0, 6.6, 13.0, 13.2, 0.9, (232, 78, 120, 255))
    _rr(d, 2.4, 4.9, 13.6, 7.0, 0.7, (255, 112, 152, 255))
    d.rectangle(box(7.2, 4.9, 8.8, 13.2), fill=GOLD)
    d.rectangle(box(7.2, 4.9, 8.8, 5.4), fill=GOLD_L)
    # бант
    d.ellipse(box(5.0, 2.2, 8.0, 5.0), fill=GOLD, outline=GOLD_D, width=int(0.4 * S))
    d.ellipse(box(8.0, 2.2, 11.0, 5.0), fill=GOLD, outline=GOLD_D, width=int(0.4 * S))
    d.ellipse(box(4.8, 3.2, 6.0, 4.4), fill=GOLD_L, outline=None, width=0)
    return img


def icon_arena():
    img = canvas()
    d = plate(img)
    blades = [((3.4, 12.6), (12.4, 3.6)), ((12.6, 12.6), (3.6, 3.6))]
    for (x0, y0), (x1, y1) in blades:
        _line(d, [(x0, y0), (x1, y1)], INK, 1.35)          # тёмная обводка клинка
        _line(d, [(x0, y0), (x1, y1)], (222, 232, 248, 255), 0.85)
        _line(d, [(x0 + (x1 - x0) * 0.06, y0 + (y1 - y0) * 0.06),
                  (x1 - (x1 - x0) * 0.05, y1 - (y1 - y0) * 0.05)], WHITE, 0.3)
    # гарды
    _line(d, [(2.7, 10.2), (5.6, 13.1)], GOLD, 0.95)
    _line(d, [(13.3, 10.2), (10.4, 13.1)], GOLD, 0.95)
    # рукояти и навершия
    _line(d, [(2.9, 13.1), (4.0, 12.0)], (120, 74, 40, 255), 0.9)
    _line(d, [(13.1, 13.1), (12.0, 12.0)], (120, 74, 40, 255), 0.9)
    d.ellipse(box(2.1, 12.6, 3.4, 13.9), fill=GOLD, outline=GOLD_D, width=int(0.3 * S))
    d.ellipse(bbox := None or box(12.6, 12.6, 13.9, 13.9), fill=GOLD, outline=GOLD_D, width=int(0.3 * S))
    _poly(d, [(12.8, 3.0), (13.9, 2.2), (13.2, 4.0)], WHITE, outline=None, width=0)
    return img


def icon_mine():
    img = canvas()
    d = plate(img)
    # рукоять по диагонали
    _line(d, [(5.0, 13.6), (9.6, 5.4)], INK, 1.35)
    _line(d, [(5.0, 13.6), (9.6, 5.4)], (168, 118, 74, 255), 0.95)
    # голова кирки: толстая дуга с загнутыми концами
    d.arc(box(1.9, 1.9, 13.9, 11.9), 200, 340, fill=INK, width=int(1.5 * S))
    d.arc(box(1.9, 1.9, 13.9, 11.9), 200, 340, fill=CYAN, width=int(1.05 * S))
    d.arc(box(2.6, 2.6, 13.2, 11.2), 205, 335, fill=(150, 240, 255, 255), width=int(0.32 * S))
    # заострённые концы
    _poly(d, [(2.2, 5.2), (3.9, 8.6), (2.0, 8.2)], CYAN, outline=INK, width=0.35)
    _poly(d, [(13.8, 5.2), (12.1, 8.6), (14.0, 8.2)], CYAN, outline=INK, width=0.35)
    return img


def icon_star():
    img = canvas()
    d = plate(img)
    import math
    pts = []
    for i in range(10):
        a = math.radians(-90 + i * 36)
        r = 5.6 if i % 2 == 0 else 2.5
        pts.append((8.0 + math.cos(a) * r, 8.2 + math.sin(a) * r))
    _poly(d, pts, GOLD)
    _poly(d, [(8.0, 4.6), (9.0, 7.0), (8.0, 9.0), (7.0, 7.0)], GOLD_L, outline=None, width=0)
    return img


def icon_info():
    img = canvas()
    d = plate(img)
    d.ellipse(box(2.6, 2.6, 13.4, 13.4), fill=CYAN, outline=CYAN_D, width=int(0.7 * S))
    d.ellipse(box(3.4, 3.1, 12.6, 7.4), fill=(170, 240, 255, 120), outline=None, width=0)
    d.ellipse(box(7.1, 4.6, 8.9, 6.4), fill=WHITE, outline=None, width=0)      # точка
    d.rectangle(box(7.2, 7.4, 8.8, 11.4), fill=WHITE)                          # ножка
    return img


# Код → (имя, функция). Коды специально в блоке E140–E14F.
MENU_ICONS = {
    "E140": ("menu_shop", icon_shop),
    "E141": ("menu_case", icon_case),
    "E142": ("menu_donate", icon_donate),
    "E143": ("menu_quest", icon_quest),
    "E144": ("menu_clan", icon_clan),
    "E145": ("menu_top", icon_top),
    "E146": ("menu_settings", icon_settings),
    "E147": ("menu_close", icon_close),
    "E148": ("menu_left", icon_arrow_left),
    "E149": ("menu_right", icon_arrow_right),
    "E14A": ("menu_key", icon_key),
    "E14B": ("menu_gift", icon_gift),
    "E14C": ("menu_arena", icon_arena),
    "E14D": ("menu_mine", icon_mine),
    "E14E": ("menu_star", icon_star),
    "E14F": ("menu_info", icon_info),
}


def build_all():
    """Возвращает {код: (имя, картинка 16×16)} — для вставки в общий лист."""
    result = {}
    for code, (name, fn) in MENU_ICONS.items():
        big = fn()
        result[code] = (name, big.resize((16, 16), Image.LANCZOS))
    return result


if __name__ == "__main__":
    # Отдельная проверка: соберём превью набора без всего пака.
    from pathlib import Path
    root = Path(__file__).resolve().parent.parent
    icons = build_all()
    cell, pad = 128, 16
    cols = 4
    rows = (len(icons) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * (cell + pad) + pad, rows * (cell + pad) + pad), (18, 16, 24, 255))
    for i, (code, (name, img)) in enumerate(sorted(icons.items())):
        x = pad + (i % cols) * (cell + pad)
        y = pad + (i // cols) * (cell + pad)
        big = img.resize((cell, cell), Image.NEAREST)
        sheet.paste(big, (x, y), big)
    sheet.save(root / "preview" / "menu-icons-preview.png")
    print("OK: preview/menu-icons-preview.png")
