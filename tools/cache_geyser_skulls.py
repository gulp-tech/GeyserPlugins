#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Прогрев кеша скинов черепов Geyser (cache/player_skulls/*.png).

Проблема: при старте Geyser для каждого черепа из маппинга качает скин с
textures.minecraft.net. Если хост сервера не может достучаться до этого
адреса — на каждый череп уходит ~10 секунд таймаута (1453 черепа ≈ 4 часа
старта) и черепа не добавляются как кастомные блоки.

Решение: скачать скины на машине с интернетом и положить готовые PNG в кеш
Geyser. Формат файла повторяет Geyser 2.12.1 (SkullResourcePackManager):
64x64 скин переупаковывается в атлас 48x16 (щёки/лоб/затылок, затем шляпа,
затем верх/низ).

Использование (на своём ПК, нужен Python + pillow: pip install pillow):
    python3 cache_geyser_skulls.py migrated-custom-skulls.json skull_cache
Затем:
  1) скопировать папку skull_cache/player_skulls/ в plugins/Geyser-Spigot/cache/
  2) удалить plugins/Geyser-Spigot/cache/player_skulls.mcpack (если есть)
  3) перезапустить сервер

Повторный запуск безопасен: уже скачанные файлы пропускаются.
"""

import io
import json
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

SKIN_SERVER = "https://textures.minecraft.net/texture/"
THREADS = 8
RETRIES = 3


def load_hashes(path):
    data = json.load(open(path, encoding='utf-8'))
    if isinstance(data, dict) and 'skulls' in data:
        hashes = []
        for key in ('skin_hash', 'username', 'uuid', 'profile'):
            for entry in data['skulls'].get(key, []):
                if isinstance(entry, str) and key == 'skin_hash':
                    hashes.append(entry.strip())
        return hashes
    if isinstance(data, list):
        return [str(h).strip() for h in data]
    raise SystemExit(f'Неизвестный формат файла: {path}')


def repack_48x16(skin):
    """Точная копия перестановки из SkullResourcePackManager.cacheSkullSkin."""
    from PIL import Image
    canvas = Image.new('RGBA', (48, 16), (0, 0, 0, 0))
    # Right, Front, Left, Back головы
    canvas.paste(skin.crop((0, 8, 32, 16)), (0, 0))
    # Right, Front, Left, Back шляпы
    canvas.paste(skin.crop((32, 8, 64, 16)), (0, 8))
    # Верх и низ головы
    canvas.paste(skin.crop((8, 0, 24, 8)), (32, 0))
    # Верх и низ шляпы
    canvas.paste(skin.crop((40, 0, 56, 8)), (32, 8))
    return canvas


def fetch_one(skin_hash, out_dir):
    from PIL import Image
    out = out_dir / f'{skin_hash}.png'
    if out.exists():
        return skin_hash, True, 'уже в кеше'
    url = SKIN_SERVER + skin_hash
    last = ''
    for attempt in range(1, RETRIES + 1):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (skull-cache-warmup)'})
            with urllib.request.urlopen(req, timeout=20) as resp:
                raw = resp.read()
            skin = Image.open(io.BytesIO(raw)).convert('RGBA')
            repack_48x16(skin).save(out, 'PNG')
            return skin_hash, True, f'скачан (попытка {attempt})'
        except Exception as e:  # noqa: BLE001 — сеть капризная, копим ошибки
            last = str(e)[:120]
            time.sleep(min(attempt * 2, 6))
    return skin_hash, False, last


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        raise SystemExit(2)
    mapping = Path(sys.argv[1])
    root = Path(sys.argv[2]) if len(sys.argv) > 2 else Path('skull_cache')
    out_dir = root / 'player_skulls'
    out_dir.mkdir(parents=True, exist_ok=True)

    hashes = load_hashes(mapping)
    print(f'Черепов в маппинге: {len(hashes)}, кеш: {out_dir}')

    ok = 0
    failed = []
    with ThreadPoolExecutor(max_workers=THREADS) as pool:
        futures = [pool.submit(fetch_one, h, out_dir) for h in hashes]
        for i, fut in enumerate(futures, 1):
            h, good, msg = fut.result()
            if good:
                ok += 1
            else:
                failed.append((h, msg))
            if i % 50 == 0 or i == len(futures):
                print(f'  {i}/{len(futures)}: ок {ok}, ошибок {len(failed)}')

    print(f'\nГотово: {ok} из {len(hashes)}')
    if failed:
        report = root / 'failed.txt'
        with open(report, 'w', encoding='utf-8') as f:
            for h, msg in failed:
                f.write(f'{h}\t{msg}\n')
        print(f'Не скачались: {len(failed)} — список в {report} (запусти скрипт повторно, он докачает)')
    print('\nДальше: скопируй папку player_skulls/ в plugins/Geyser-Spigot/cache/,')
    print('удали plugins/Geyser-Spigot/cache/player_skulls.mcpack (если есть) и перезапусти сервер.')


if __name__ == '__main__':
    main()
