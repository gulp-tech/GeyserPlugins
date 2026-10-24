#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Генератор графики FaralMC (v3).

Иконки: чёткий пиксель-арт в майнкрафт-стиле (как иконки на референсе).
Лист glyph_E1.png — стандартного ванильного размера 256x256 (ячейка 16x16),
иконка ужата до 8x8 по центру ячейки: в Bedrock глиф крупнее букв текста
и не раздувает скорборд. (Лист 512 Bedrock рендерит крупнее ванили — не использовать.)

Логотип: textures/ui/title.png — в стиле NEVERTIME: жирный блочный шрифт
Minecraft Ten (все буквы одной высоты), градиент голубой->белый слева направо,
тёмная объёмная экструзия и тень снизу, чёрная обводка. Сам логотип занимает
~62% ширины холста (прозрачные поля), чтобы на экране загрузки не был огромным.

Запуск: python3 tools/make_icons.py
"""
from PIL import Image, ImageDraw, ImageFont
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACK = ROOT / "VoidResourcePack"
PREVIEW = ROOT / "preview"
FONT_LOGO = ROOT / "tools" / "fonts" / "MinecraftTen.ttf"
FONT_GAME = ROOT / "tools" / "fonts" / "Minecraftia.ttf"
TITLE_START = 0x20   # глифы E120.. — полоса логотипа в заголовок скорборда (страница E1)
TITLE_GLYPHS = 8

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
    "clock": {"D": (110, 72, 16, 255), "G": (255, 201, 60, 255), "F": (255, 250, 230, 255), "K": (35, 30, 25, 255),
              "H": (120, 70, 10, 255), "S": (240, 165, 0, 255), "W": (255, 255, 255, 255)},
    "windows": {"D": (0, 90, 140, 255), "B": (0, 173, 239, 255), "L": (140, 220, 255, 255)},
    "android": {"D": (60, 90, 20, 255), "G": (164, 198, 57, 255), "L": (210, 235, 150, 255),
                "K": (25, 25, 35, 255), "W": (255, 255, 255, 255)},
    "apple": {"D": (90, 95, 110, 255), "W": (240, 242, 245, 255), "S": (190, 196, 205, 255),
              "G": (96, 200, 90, 255)},
    "device": {"D": (50, 55, 70, 255), "C": (90, 200, 235, 255), "F": (120, 128, 145, 255), "K": (30, 32, 40, 255), "L": (200, 240, 255, 255),
               "W": (230, 230, 235, 255)},
    "coins": {"D": (146, 96, 0, 255), "G": (255, 201, 60, 255), "L": (255, 231, 138, 255),
              "S": (240, 165, 0, 255), "W": (255, 255, 255, 255)},
    "kbmouse": {"D": (45, 50, 65, 255), "W": (235, 238, 245, 255), "B": (64, 170, 255, 255)},
    "mac": {"D": (60, 63, 72, 255), "W": (170, 175, 185, 255), "S": (120, 126, 138, 255),
            "G": (96, 200, 90, 255)},
    "amazon": {"D": (20, 20, 25, 255), "O": (255, 153, 0, 255), "W": (255, 255, 255, 255)},
    "xbox": {"D": (10, 90, 10, 255), "G": (16, 160, 16, 255), "W": (255, 255, 255, 255)},
    "playstation": {"D": (0, 40, 110, 255), "B": (0, 85, 200, 255), "W": (255, 255, 255, 255)},
    "switch": {"D": (15, 15, 18, 255), "B": (0, 195, 227, 255), "R": (255, 70, 85, 255), "W": (255, 255, 255, 255)},
    "vr": {"D": (35, 38, 48, 255), "W": (232, 234, 238, 255), "S": (200, 203, 210, 255)},
    "tv": {"D": (25, 28, 36, 255), "C": (40, 180, 230, 255), "W": (255, 255, 255, 255)},
    "controller": {"D": (30, 32, 42, 255), "W": (255, 255, 255, 255), "R": (255, 90, 90, 255),
                   "G": (90, 220, 120, 255), "B": (90, 160, 255, 255), "Y": (255, 210, 80, 255)},
    "strannik": {"D": (52, 58, 68, 255), "S": (140, 148, 160, 255), "C": (90, 200, 235, 255), "W": (255, 255, 255, 255)},
    "geroy": {"D": (30, 130, 60, 255), "G": (46, 190, 90, 255), "W": (255, 255, 255, 255)},
    "rycar": {"D": (160, 110, 10, 255), "G": (240, 180, 40, 255), "W": (240, 244, 250, 255), "B": (120, 70, 30, 255)},
    "baron": {"D": (160, 95, 0, 255), "G": (255, 165, 0, 255), "R": (255, 90, 90, 255)},
    "lord": {"D": (25, 120, 160, 255), "C": (64, 190, 230, 255), "G": (64, 190, 230, 255), "W": (255, 255, 255, 255)},
    "knyaz": {"D": (150, 40, 130, 255), "P": (230, 90, 200, 255), "G": (255, 220, 120, 255)},
    "imperator": {"D": (170, 120, 0, 255), "G": (255, 195, 40, 255), "P": (170, 60, 255, 255), "L": (255, 240, 170, 255)},
    "youtuber": {"D": (170, 20, 20, 255), "R": (255, 40, 40, 255), "W": (255, 255, 255, 255)},
    "tiktoker": {"D": (12, 12, 16, 255), "C": (60, 225, 235, 255), "P": (255, 90, 160, 255)},
    "helper": {"D": (25, 140, 70, 255), "G": (40, 200, 105, 255), "W": (255, 255, 255, 255)},
    "moderator": {"D": (30, 75, 170, 255), "B": (50, 110, 230, 255), "G": (120, 170, 255, 255), "W": (255, 255, 255, 255)},
    "sozdatel": {"D": (170, 120, 0, 255), "G": (255, 190, 40, 255), "R": (255, 70, 70, 255)},
    "_extra": {"F": (255, 250, 230, 255), "K": (25, 25, 35, 255), "H": (120, 70, 10, 255),
               "L": (255, 240, 170, 255), "S": (200, 200, 205, 255), "B": (90, 160, 255, 255),
               "C": (90, 200, 235, 255), "R": (255, 90, 90, 255), "P": (200, 107, 255, 255)},
}

ICONS = {
    "E101": ("coin", [
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
    "E102": ("spark", [  # жёлтая молния, как значок монет на референсе
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
    "E103": ("sapphire", [
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
    "E104": ("shard", [
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
    "E105": ("crown", [
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
    "E106": ("sword", [
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
    "E107": ("skull", [
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
    "E108": ("clock", [
        "......DDDD......",
        ".....DDDDDD.....",
        "...DDFFFFFFDD...",
        "..DFFFFFFFFFD...",
        ".DFFFFFKKFFFFFD.",
        ".DFFFFFKKFFFFFD.",
        "DFFFFFFKKFFFFFFD",
        "DFFFKFFKKKKKKFFD",
        "DFFFFFFFFFFFFFFD",
        ".DFFFFFFFFFFFFD.",
        ".DFFFFFKKFFFFFD.",
        "..DFFFFFFFFFD...",
        "...DDFFFFFFDD...",
        ".....DDDDDD.....",
        "................",
        "................",
    ]),
    "E109": ("windows", [
        "................",
        "................",
        ".DDDD..DDDDDD...",
        ".DBBBD.DBBBBBD..",
        ".DBBBD.DBBBBBD..",
        ".DBBBD.DBBBBBD..",
        ".DDDDD.DDDDDDD..",
        "................",
        ".DDDDD.DDDDDDD..",
        ".DBBBD.DBBBBBD..",
        ".DBBBD.DBBBBBD..",
        ".DBBBD.DBBBBBD..",
        ".DDDDD.DDDDDDD..",
        "................",
        "................",
        "................",
    ]),
    "E10A": ("android", [
        "..D..........D..",
        "...D........D...",
        "....DDDDDDDD....",
        "..DDGGGGGGGGDD..",
        ".DGGWWGGGGWWGGD.",
        ".DGGWWGGGGWWGGD.",
        ".DGGGGGGGGGGGGD.",
        "DDDGGGGGGGGDDD..",
        "D.DDGGGGGGDD.D..",
        "D..DGGGGGGD..D..",
        "....DGGGGGD.....",
        "....DGGGGGD.....",
        "....DDD.DDD.....",
        "....DD...DD.....",
        "................",
        "................",
    ]),
    "E10B": ("apple", [
        "................",
        ".........GG.....",
        ".......DGG......",
        ".....DDWWDD.....",
        "....DWWWWWWD....",
        "...DWWWWWWWD....",
        "...DWWWWWWSD....",
        "...DWWWWWS......",
        "...DWWWWWWSD....",
        "....DWWWWSSD....",
        ".....DDWWDD.....",
        ".......DD.......",
        "................",
        "................",
        "................",
        "................",
    ]),
    "E10C": ("device", [
        "................",
        "................",
        "...DDDDDDDDDD...",
        "..DFFFFFFFFFFD..",
        ".DFFKFFFFFCCDCD.",
        ".DFKKKFFCCCCCCD.",
        ".DFFKFFFFFCCDCD.",
        "..DFFFFFFFFFFD..",
        "...DDDDDDDDDD...",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
    ]),
    "E10D": ("coins", [
        "................",
        ".......DDDDDD...",
        ".....DDGGGGGGD..",
        "....DGLGGGGGSD..",
        ".....DDGGGGDD...",
        "....DDDDDDDD....",
        "..DDDDDDDDDD....",
        ".DGLGGGGGGGGSD..",
        ".DGGGGGGGGGGSD..",
        ".DGGGGDDDDGGSD..",
        "..DGGGGGGGGSD...",
        "...DDDDDDDDD....",
        "................",
        "................",
        "................",
        "................",
    ]),
    "E10E": ("kbmouse", [
        ".........DDDD...",
        "........DWWBBD..",
        "........DWWBBD..",
        "........DWWBBD..",
        ".........DDDD...",
        "................",
        ".DDDDDDDDDDDDDD.",
        ".DWDWDWDWDWDWDD.",
        ".DWWWWWWWWWWWWD.",
        ".DDDDDDDDDDDDDD.",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
    ]),
    "E10F": ("mac", [
        "................",
        ".........GG.....",
        ".......DGG......",
        ".....DDWWDD.....",
        "....DWWWWWWD....",
        "...DWWWWWWWD....",
        "...DWWWWWWSD....",
        "...DWWWWWS......",
        "...DWWWWWWSD....",
        "....DWWWWSSD....",
        ".....DDWWDD.....",
        ".......DD.......",
        "................",
        "................",
        "................",
        "................",
    ]),
    "E110": ("amazon", [
        "................",
        "..DDDDDDDDDDDD..",
        ".DDDDDDDDDDDDDD.",
        ".DDD..OOO...DDD.",
        ".DD..O...O..DDD.",
        ".DD..OOOOO..DDD.",
        ".DD..O...O..DDD.",
        ".DDD..OOO.O.DDD.",
        "..DDD......DDD..",
        "...D.OOOOOO.D...",
        "....D......D....",
        ".....DDDDDD.....",
        "................",
        "................",
        "................",
        "................",
    ]),
    "E111": ("xbox", [
        "................",
        ".....DDDDDD.....",
        "...DDGGGGGGDD...",
        "..DGWWGGGGWWDG..",
        "..DG.WWGGWW.DG..",
        "..DGG.WWWG.GGD..",
        "..DGGG.WW.GGGD..",
        "..DGG.WWWG.GGD..",
        "..DG.WWGGWW.DG..",
        "..DGWWGGGGWWDG..",
        "...DDGGGGGGDD...",
        ".....DDDDDD.....",
        "................",
        "................",
        "................",
        "................",
    ]),
    "E112": ("playstation", [
        "................",
        ".....DDDDDD.....",
        "...DDBBBBBBDD...",
        "..DBBWWBBBBWBD..",
        "..DBWWBBBWBBBD..",
        "..DBWWBBBBWBBD..",
        "..DBWWBBBWWBBD..",
        "..DBWWBBBBWBBD..",
        "...DBBBBBBBBD...",
        "....DDDDDDDD....",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
    ]),
    "E113": ("switch", [
        "................",
        "...DD..DD..DD...",
        "..DBBD.DD.DRRD..",
        "..DBBD.DD.DRRD..",
        "..DWBD.DD.DRRD..",
        "..DBBD.DD.DRRD..",
        "..DBBD.DD.DRRD..",
        "..DBBD.DD.DRWD..",
        "..DBBD.DD.DRRD..",
        "...DD..DD..DD...",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
    ]),
    "E114": ("vr", [
        "................",
        "................",
        "................",
        "..DDDDDDDDDDDD..",
        ".DWWWWWWWWWWWWD.",
        ".DWDDDDDDDDDDWD.",
        ".DWDDDDDDDDDDWD.",
        "..DDDDDDDDDDDD..",
        "......D..D......",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
    ]),
    "E115": ("tv", [
        "................",
        "..DDDDDDDDDDDD..",
        ".DCCCCCCCCCCCCD.",
        ".DCCCCCCCCCCCCD.",
        ".DCCCCCCCCCCCCD.",
        ".DCCCCCCCCCCCCD.",
        "..DDDDDDDDDDDD..",
        ".......DD.......",
        ".....DDDDDD.....",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
    ]),
    "E116": ("controller", [
        "................",
        "................",
        "...DDDDDDDDDD...",
        "..DWWDDDDDDYYD..",
        "..DWWDDDRDDDYD..",
        "..DWWDDDDGDDDD..",
        "...DDDDDDDDDD...",
        "....D......D....",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
    ]),
}

# --- Значки привилегий (неймтег перед ником). Коды E117.. и E128.. (E120-E127 — полоса логотипа).
ICONS.update({
    "E117": ("strannik", ICONS["E108"][1]),
    "E118": ("geroy", ICONS["E105"][1]),
    "E119": ("rycar", ICONS["E106"][1]),
    "E11A": ("baron", ICONS["E105"][1]),
    "E11B": ("lord", ICONS["E105"][1]),
    "E11C": ("knyaz", ICONS["E105"][1]),
    "E11D": ("imperator", ICONS["E105"][1]),
    "E11E": ("youtuber", ICONS["E10C"][1]),
    "E11F": ("tiktoker", ICONS["E10C"][1]),
    "E128": ("helper", ICONS["E10C"][1]),
    "E129": ("moderator", ICONS["E105"][1]),
    "E12A": ("sozdatel", ICONS["E105"][1]),
})

LABELS_RU = {
    "coin": "Монета", "spark": "Молния", "sapphire": "Сапфир", "shard": "Осколок",
    "crown": "Привилегия", "sword": "Убийства", "skull": "Смерти", "clock": "Время в игре",
    "windows": "ПК (Windows)", "android": "Android", "apple": "iPhone (iOS)", "device": "Другое устройство",
    "coins": "Стопка монет", "kbmouse": "Клавиатура+мышь",
    "mac": "Mac OS", "amazon": "Amazon Fire", "xbox": "Xbox", "playstation": "PlayStation",
    "switch": "Nintendo Switch", "vr": "VR-гарнитура", "tv": "TV", "controller": "Геймпад",
    "strannik": "Странник", "geroy": "Герой", "rycar": "Рыцарь", "baron": "Барон",
    "lord": "Лорд", "knyaz": "Князь", "imperator": "Император", "youtuber": "Ютубер",
    "tiktoker": "Тиктокер", "helper": "Хелпер", "moderator": "Модератор", "sozdatel": "Создатель",
}
DEVICE_CODES = {"E109", "E10A", "E10B", "E10C", "E10E"}  # windows/android/apple/device/kbmouse
CELL = 16   # ячейка на листе 256x256 (как ванильный шрифт Bedrock)
OFF = 4     # иконка 8x8 по центру ячейки 16x16: в Bedrock глиф крупнее букв, 13x13 было слишком много


def render(rows, pal_name):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    pal = dict(PALETTES.get("_extra", {}))
    pal.update(PALETTES[pal_name])
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
    """VoidPE в стиле NEVERTIME: жирный Minecraft Ten, градиент, объём, тень. Небольшой."""
    text = "VoidPE"
    f = ImageFont.truetype(str(FONT_LOGO), 300)
    stroke = 16
    tmp = Image.new("RGBA", (10, 10))
    d = ImageDraw.Draw(tmp)
    bbox = d.textbbox((0, 0), text, font=f, stroke_width=stroke)
    w, h = bbox[2] - bbox[0] + 64, bbox[3] - bbox[1] + 110
    ox, oy = 32 - bbox[0], 28 - bbox[1]

    def text_layer(fill, dx=0, dy=0):
        lay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        ImageDraw.Draw(lay).text((ox + dx, oy + dy), text, font=f, fill=fill,
                                 stroke_width=stroke, stroke_fill=fill)
        return lay

    logo = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    logo.alpha_composite(text_layer((0, 0, 0, 150), 8, 30))                       # тень
    for dy in range(20, 0, -2):                                                    # объём вниз (у NEVERTIME он красный, у нас тёмно-синий)
        logo.alpha_composite(text_layer((8, 30, 105, 255), 0, dy))
    logo.alpha_composite(text_layer((0, 0, 0, 255)))                               # чёрная обводка

    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).text((ox, oy), text, font=f, fill=255)
    grad = Image.new("RGBA", (w, h))
    px = grad.load()
    for y in range(h):
        for x in range(w):
            base = lerp((96, 200, 255), (238, 248, 255), x / max(1, w - 1))        # голубой -> белый слева направо
            shade = 1.0 - 0.45 * (y / max(1, h - 1))                               # низ букв темнее
            deep = (26, 96, 235)
            c = tuple(int(round(base[i] * shade + deep[i] * (1 - shade) * 0.55)) for i in range(3))
            px[x, y] = c + (255,)
    shine = Image.new("L", (w, h), 0)
    spx = shine.load()
    for y in range(h):
        a = max(0, int(110 * (1 - y / (h * 0.5)))) if y < h * 0.5 else 0
        for x in range(w):
            spx[x, y] = a
    grad.paste(Image.new("RGBA", (w, h), (255, 255, 255, 255)), (0, 0),
               Image.composite(shine, Image.new("L", (w, h), 0), mask))
    letters = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    letters.paste(grad, (0, 0), mask)
    logo.alpha_composite(letters)

    box = logo.getbbox()
    if box:
        pad = 6
        logo = logo.crop((max(0, box[0] - pad), max(0, box[1] - pad),
                          min(w, box[2] + pad), min(h, box[3] + pad)))
    # небольшой логотип: текст ~62% ширины холста, остальное прозрачные поля
    canvas = Image.new("RGBA", (1456, 360), (0, 0, 0, 0))
    target_w = 900
    scale = target_w / logo.width
    logo_small = logo.resize((target_w, int(logo.height * scale)), Image.LANCZOS)
    canvas.alpha_composite(logo_small, ((1456 - target_w) // 2, (360 - logo_small.height) // 2))
    return canvas


def make_title_strip():
    """Маленький логотип VoidPE одной строкой: полоса 128x16 для глифов E010-E017."""
    strip = Image.new("RGBA", (TITLE_GLYPHS * 16, 16), (0, 0, 0, 0))
    text = "VoidPE"
    size = 18
    while size > 8:
        f = ImageFont.truetype(str(FONT_GAME), size)
        d = ImageDraw.Draw(strip)
        if d.textlength(text, font=f) <= TITLE_GLYPHS * 16 - 6:
            break
        size -= 1
    f = ImageFont.truetype(str(FONT_GAME), size)
    d = ImageDraw.Draw(strip)
    tw = d.textlength(text, font=f)
    bb = d.textbbox((0, 0), text, font=f)
    x = int((TITLE_GLYPHS * 16 - tw) // 2) - bb[0]
    y = int((16 - (bb[3] - bb[1])) // 2) - bb[1]
    mask = Image.new("L", strip.size, 0)
    ImageDraw.Draw(mask).text((x, y), text, font=f, fill=255)
    grad = Image.new("RGBA", strip.size)
    px = grad.load()
    for yy in range(16):
        for xx in range(strip.width):
            t = min(1.0, max(0.0, (xx / strip.width) * 0.3 + (yy / 16) * 0.7))
            px[xx, yy] = lerp((165, 228, 255), (24, 96, 238), t) + (255,)
    shadow = Image.new("RGBA", strip.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).text((x + 1, y + 1), text, font=f, fill=(8, 26, 84, 255))
    strip.alpha_composite(shadow)
    letters = Image.new("RGBA", strip.size, (0, 0, 0, 0))
    letters.paste(grad, (0, 0), mask)
    strip.alpha_composite(letters)
    return strip


def main():
    (PACK / "font").mkdir(parents=True, exist_ok=True)
    (PACK / "textures" / "ui").mkdir(parents=True, exist_ok=True)
    (PREVIEW / "icons").mkdir(parents=True, exist_ok=True)

    sheet = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    rendered = {}
    for code, (name, rows) in sorted(ICONS.items()):
        img = render(rows, name)
        rendered[code] = (name, img)
        idx = int(code[1:], 16) - 0x100  # страница E1: ячейка = код - E100
        asset = ROOT / "tools" / "assets" / f"{name}.png"
        if asset.exists():
            # Гладкие иконки высокого разрешения (tools/assets), ужатие LANCZOS —
            # выглядят лучше пиксель-арта. Нет ассета — старый пиксель-арт ниже.
            art = Image.open(asset).convert("RGBA")
            art.putalpha(art.getchannel("A").point(lambda v: 0 if v < 40 else v))
            size = 13 if code in DEVICE_CODES else 12
            big = art.resize((size, size), Image.LANCZOS)
            off = (CELL - size) // 2
            sheet.paste(big, ((idx % 16) * CELL + off, (idx // 16) * CELL + off), big)
        elif code in DEVICE_CODES:  # значки устройств — в ник над головой, там надо покрупнее
            big = img.resize((12, 12), Image.NEAREST)
            sheet.paste(big, ((idx % 16) * CELL + 2, (idx // 16) * CELL + 2), big)
        else:
            small = img.resize((8, 8), Image.NEAREST)
            sheet.paste(small, ((idx % 16) * CELL + OFF, (idx // 16) * CELL + OFF), small)
        img.save(PREVIEW / "icons" / f"{code[1:]}_{name}.png")
        img.resize((256, 256), Image.NEAREST).save(PREVIEW / "icons" / f"{code[1:]}_{name}_x16.png")
    # --- иконки меню (страница E140–E14F, рисуются отдельным модулем) ---
    try:
        from make_menu_icons import build_all as build_menu_icons
        for code, (name, img16) in build_menu_icons().items():
            idx = int(code[1:], 16) - 0x100
            sheet.paste(img16, ((idx % 16) * CELL, (idx // 16) * CELL), img16)
            img16.resize((256, 256), Image.NEAREST).save(PREVIEW / "icons" / f"{code[1:]}_{name}.png")
    except Exception as error:  # иконки меню не должны ломать сборку пака
        print("иконки меню пропущены:", error)

    strip = make_title_strip()
    strip_small = strip.resize((40, 5), Image.NEAREST)
    strip_canvas = Image.new("RGBA", (TITLE_GLYPHS * 16, 16), (0, 0, 0, 0))
    strip_canvas.alpha_composite(strip_small, (44, 5))
    strip = strip_canvas
    for i in range(TITLE_GLYPHS):
        idx = TITLE_START + i
        cell_img = strip.crop((i * 16, 0, i * 16 + 16, 16))
        sheet.paste(cell_img, ((idx % 16) * CELL, (idx // 16) * CELL), cell_img)
    strip.save(PREVIEW / "title-logo-strip.png")
    strip.resize((512, 64), Image.NEAREST).save(PREVIEW / "title-logo-strip_x4.png")
    sheet.save(PACK / "font" / "glyph_E1.png")
    rendered["E101"][1].resize((64, 64), Image.NEAREST).save(PACK / "pack_icon.png")

    logo = make_logo()
    logo.save(PACK / "textures" / "ui" / "title.png")
    logo.save(PREVIEW / "logo-title.png")
    bg = Image.new("RGBA", (logo.width + 80, logo.height + 140), (40, 90, 40, 255))
    bg.alpha_composite(logo, (40, 60))
    bg.convert("RGB").save(PREVIEW / "logo-title-on-bg.jpg", quality=90)

    # ---- превью иконок (иконки показаны и в реальном относительном размере) ----
    cell, pad, label_h = 160, 24, 58
    cols = 4
    rows_n = (len(rendered) + cols - 1) // cols
    W = cols * cell + (cols + 1) * pad
    H = rows_n * (cell + label_h) + (rows_n + 1) * pad + 70
    prev = Image.new("RGBA", (W, H), (24, 24, 32, 255))
    d = ImageDraw.Draw(prev)
    d.text((pad, 22), "VoidPE — иконки (\\uE101-\\uE108), в игре меньше строки", font=font(24), fill=(255, 210, 90))
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
    strip_img = make_title_strip().resize((176, 22), Image.NEAREST)
    mock.paste(strip_img, (40, 40), strip_img)  # заголовок картинкой из пака, не текстом
    lines = [
        ("Имя: ", "MUSD38", (255, 170, 60), None),
        ("Донат: ", "Игрок", (90, 230, 90), None),
        ("Монеты: ", "500", (255, 220, 60), "E102"),
        ("Осколки: ", "150", (90, 210, 255), "E104"),
        ("", "", None, None),
        ("У/С: ", "12/3", (205, 110, 255), None),
        ("В игре: ", "27ч", (255, 220, 60), "E101"),
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
