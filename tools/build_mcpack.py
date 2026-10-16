#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Собирает FaralResourcePack.mcpack (обычный zip) из папки FaralResourcePack/.
В пак попадают manifest.json, font/ (иконки), textures/ (логотип загрузки) и pack_icon.png.
Запуск: python3 tools/build_mcpack.py  ->  FaralResourcePack.mcpack в корне репозитория.
"""
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "FaralResourcePack"
OUT = ROOT / "FaralResourcePack.mcpack"
INCLUDE_FILES = ["manifest.json", "pack_icon.png"]
INCLUDE_DIRS = ["font", "textures"]


def main() -> None:
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
        for name in INCLUDE_FILES:
            z.write(SRC / name, name)
        for d in INCLUDE_DIRS:
            for f in sorted((SRC / d).rglob("*")):
                if f.is_file():
                    z.write(f, str(f.relative_to(SRC)))
    print(f"OK: {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
