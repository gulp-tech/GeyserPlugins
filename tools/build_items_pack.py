#!/usr/bin/env python3
"""Собирает VoidItems.mcpack из исходников VoidItemsPack/ (как build_talismans_pack)."""
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'VoidItemsPack'
OUT = ROOT / 'VoidItems.mcpack'

REQUIRED = [
    'manifest.json',
    'pack_icon.png',
    'textures/item_texture.json',
]


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

    atlas = json.loads((SRC / 'textures/item_texture.json').read_text(encoding='utf-8'))
    texture_data = atlas.get('texture_data', {})
    if not texture_data:
        print('item_texture.json: пустой texture_data')
        return 1

    files = sorted(p for p in SRC.rglob('*') if p.is_file())
    textures = {p.stem for p in (SRC / 'textures/item').glob('*.png')}
    for shortname, entry in texture_data.items():
        tex = Path(entry['textures']).name
        if tex not in textures:
            print(f'item_texture.json: нет текстуры для {shortname} ({tex})')
            return 1

    with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as zf:
        for path in files:
            zf.write(path, path.relative_to(SRC).as_posix())

    print(f'OK: {OUT.name} — {len(files)} файлов, {len(texture_data)} иконок')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
