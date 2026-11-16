#!/usr/bin/env python3
"""Собирает VoidSidebarLogo.mcpack из исходников VoidSidebarPack/ (лого скорборда)."""
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'VoidSidebarPack'
OUT = ROOT / 'VoidSidebarLogo.mcpack'

REQUIRED = ['manifest.json', 'pack_icon.png', 'font/default.json']


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

    font = json.loads((SRC / 'font/default.json').read_text(encoding='utf-8'))
    providers = font.get('providers', [])
    if not providers:
        print('font/default.json: нет провайдеров')
        return 1
    for provider in providers:
        tex = SRC / provider['file'].split(':', 1)[-1]
        if not tex.is_file():
            print('Нет текстуры глифа:', provider['file'])
            return 1

    files = sorted(p for p in SRC.rglob('*') if p.is_file())
    with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as z:
        for p in files:
            z.write(p, p.relative_to(SRC).as_posix())

    with zipfile.ZipFile(OUT) as z:
        names = z.namelist()
    print(f'OK: {OUT.name} — {len(names)} файлов, глифов: {len(providers)}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
