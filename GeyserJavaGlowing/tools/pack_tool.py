#!/usr/bin/env python3
"""Rebuild/test a conservative outline rig optimization from the original pack.

Only non-animated, unreferenced, unrotated leaf bones are folded into their
parent. Cubes, UVs, inflation, player animation bones and armor are unchanged.
"""
import collections
import copy
import hashlib
import io
import json
import pathlib
import re
import sys
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
ENTITY = 'entity/player.entity.json'
MANIFEST = 'manifest.json'
MODELS = ('models/entity/player_outline.geo.json', 'models/entity/wide_player_outline.geo.json')
CHANGED = {ENTITY, MANIFEST, *MODELS}
GLOW = re.compile(r"^(.+) && (q\.property\('glow:color'\) == [12])$")
VERSION = [0, 2, 15]
BASE_HASH = 'd63e07dac405b20b6544c5c28a3ba3be2f90208339efe684ece9ebc3441f3b75'


def source():
    with zipfile.ZipFile(HERE.parent.parent / 'GeyserJavaGlowing-0.2.13.jar') as jar:
        content = jar.read('GeyserJavaGlowing.mcpack')
    if hashlib.sha256(content).hexdigest() != BASE_HASH:
        raise ValueError('Unexpected original resource pack')
    return zipfile.ZipFile(io.BytesIO(content))


def reference_tokens(original):
    tokens = set()
    for name in original.namelist():
        if name in MODELS or not name.endswith(('.json', '.material', '.txt', '.lang')):
            continue
        text = original.read(name).decode('utf-8', errors='strict')
        tokens.update(t.casefold() for t in re.findall(r'[A-Za-z0-9_]+', text))
    # The only wildcard part selector in this baseline is '*', which affects all
    # bones equally. A future partial wildcard needs explicit analysis first.
    for name in original.namelist():
        if not name.startswith('render_controllers/') or not name.endswith('.json'):
            continue
        text = original.read(name).decode('utf-8')
        selectors = re.findall(r'"([^"\\]*\*[^"\\]*)"\s*:', text)
        if any(s != '*' for s in selectors):
            raise ValueError('Unexpected wildcard selector: ' + name)
    return tokens


def transform_entity(old):
    data = json.loads(old)
    count = 0
    for entry in data['minecraft:client_entity']['description']['render_controllers']:
        key, value = next(iter(entry.items()))
        match = GLOW.fullmatch(value)
        if match:
            conditions, glow = match.groups()
            assert ' || ' not in conditions
            entry[key] = glow + ' && (' + conditions + ')'
            count += 1
    assert count == 66, count
    return data


def transform_manifest(old):
    data = json.loads(old)
    data['header']['version'] = VERSION
    for module in data['modules']:
        module['version'] = VERSION
    return data


def to_merge(bones, tokens):
    by_name = {b['name']: b for b in bones}
    assert len(by_name) == len(bones)
    child_counts = collections.Counter(b.get('parent') for b in bones)
    return {b['name'] for b in bones
            if b.get('parent') in by_name and not child_counts[b['name']]
            and 'rotation' not in b and b.get('cubes')
            and set(b) <= {'name', 'parent', 'pivot', 'cubes'}
            and b['name'].casefold() not in tokens}


def transform_model(old, tokens):
    data = json.loads(old)
    assert len(data['minecraft:geometry']) == 1
    geometry = data['minecraft:geometry'][0]
    bones = geometry['bones']
    by_name = {b['name']: b for b in bones}
    removed = to_merge(bones, tokens)
    assert len(removed) >= 400, len(removed)
    for bone in bones:
        if bone['name'] in removed:
            by_name[bone['parent']].setdefault('cubes', []).extend(copy.deepcopy(bone['cubes']))
    geometry['bones'] = [b for b in bones if b['name'] not in removed]
    return data


def cube_signatures(geometry, remove_leaf_names=()):
    """Compare every cube's unchanged geometry and retained transform chain."""
    bones = geometry['bones']
    by_name = {b['name']: b for b in bones}
    result = collections.Counter()
    for bone in bones:
        anchor = bone['parent'] if bone['name'] in remove_leaf_names else bone['name']
        chain = []
        seen = set()
        while anchor:
            assert anchor in by_name and anchor not in seen
            seen.add(anchor)
            node = by_name[anchor]
            # An ancestor's exact rotation/pivot and properties must stay intact.
            chain.append(json.dumps({k: v for k, v in node.items() if k != 'cubes'}, sort_keys=True, separators=(',', ':')))
            anchor = node.get('parent')
        for cube in bone.get('cubes', []):
            signature = (json.dumps(cube, sort_keys=True, separators=(',', ':')), tuple(chain))
            result[signature] += 1
    return result


def serialize(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(',', ':')).encode('utf-8')


def build(out):
    with source() as original, zipfile.ZipFile(out, 'w') as target:
        tokens = reference_tokens(original)
        for name in original.namelist():
            if name.endswith('/'):
                continue
            data = original.read(name)
            if name == ENTITY:
                data = serialize(transform_entity(data))
            elif name == MANIFEST:
                data = serialize(transform_manifest(data))
            elif name in MODELS:
                data = serialize(transform_model(data, tokens))
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.external_attr = 0o644 << 16
            target.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def validate(path):
    with source() as original, zipfile.ZipFile(path) as target:
        assert target.testzip() is None
        names = {x for x in original.namelist() if not x.endswith('/')}
        assert names == set(target.namelist())
        for name in names - CHANGED:
            assert original.read(name) == target.read(name), name
        assert json.loads(target.read(ENTITY)) == transform_entity(original.read(ENTITY))
        assert json.loads(target.read(MANIFEST)) == transform_manifest(original.read(MANIFEST))
        tokens = reference_tokens(original)
        for name in MODELS:
            before = json.loads(original.read(name))['minecraft:geometry'][0]
            after = json.loads(target.read(name))['minecraft:geometry'][0]
            assert json.loads(target.read(name)) == transform_model(original.read(name), tokens)
            assert before['description'] == after['description']
            removed = to_merge(before['bones'], tokens)
            assert cube_signatures(before, removed) == cube_signatures(after)
            assert len(before['bones']) - len(after['bones']) == len(removed)
            print(name, 'bones:', len(before['bones']), '->', len(after['bones']),
                  'cubes:', sum(len(b.get('cubes', [])) for b in after['bones']))
    print('Original armor, materials, textures, animations and all cube transform chains verified.')

if __name__ == '__main__':
    path = pathlib.Path(sys.argv[2])
    if sys.argv[1] == 'build':
        build(path)
    elif sys.argv[1] != 'test':
        raise SystemExit('usage: pack_tool.py build|test pack.mcpack')
    validate(path)
