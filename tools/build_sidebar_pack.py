#!/usr/bin/env python3
"""Собирает VoidSidebarLogo.mcpack из исходников VoidSidebarPack/ (лого скорборда)."""
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'VoidSidebarPack'
OUT = ROOT / 'VoidSidebarLogo.mcpack'

REQUIRED = ['manifest.json', 'pack_icon.png', 'font/glyph_E2.png']


def main() -> int:
    missing = [name for name in REQUIRED if not (SRC / name).exists()]
    if missing:
        print('Отсутствуют файлы исходника:', ', '.join(missing))
        return 1

    manifest = json.loads((SRC / 'manifest.json').read_text(encoding='utf-8'))
    for key in ('header', 'modules'):
        if key not in manifest:
            print(f'manifest.json: нет раздела {key}')
            return 1

    # глиф-страница Bedrock: квадратная PNG, кратная сетке 16x16 ячеек
    from struct import unpack
    tex = SRC / 'font' / 'glyph_E2.png'
    with tex.open('rb') as fh:
        head = fh.read(24)
    if head[:8] != b'\x89PNG\r\n\x1a\n':
        print('glyph_E2.png: не PNG')
        return 1
    w, h = unpack('>II', head[16:24])
    if w != h or w % 16:
        print(f'glyph_E2.png: размер {w}x{h}, нужен квадрат, кратный 16')
        return 1
    providers = 256

    files = sorted(p for p in SRC.rglob('*') if p.is_file())
    with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as z:
        for p in files:
            z.write(p, p.relative_to(SRC).as_posix())

    with zipfile.ZipFile(OUT) as z:
        names = z.namelist()
    print(f'OK: {OUT.name} — {len(names)} файлов, страница глифов: {w}x{h} ({providers} ячеек)')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
