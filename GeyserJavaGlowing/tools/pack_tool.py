#!/usr/bin/env python3
"""Build or validate the Bedrock pack with early, equivalent glow checks."""
import io
import hashlib
import json
import pathlib
import re
import sys
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
ENTITY = 'entity/player.entity.json'
MANIFEST = 'manifest.json'
GLOW = re.compile(r"^(.+) && (q\.property\('glow:color'\) == [12])$")
VERSION = [0, 2, 14]


def source():
    with zipfile.ZipFile(HERE.parent.parent / 'GeyserJavaGlowing-0.2.13.jar') as jar:
        content = jar.read('GeyserJavaGlowing.mcpack')
    assert hashlib.sha256(content).hexdigest() == 'd63e07dac405b20b6544c5c28a3ba3be2f90208339efe684ece9ebc3441f3b75'
    return zipfile.ZipFile(io.BytesIO(content))


def transform_entity(old):
    data = json.loads(old)
    count = 0
    for entry in data['minecraft:client_entity']['description']['render_controllers']:
        key, value = next(iter(entry.items()))
        match = GLOW.fullmatch(value)
        if match:
            conditions, glow = match.groups()
            # These expressions consist solely of AND terms; grouping protects
            # precedence if the contents of a term are ever changed.
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


def serialize(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(',', ':')).encode('utf-8')


def build(out):
    with source() as original, zipfile.ZipFile(out, 'w') as target:
        for name in original.namelist():
            if name.endswith('/'):
                continue
            data = original.read(name)
            if name == ENTITY:
                data = serialize(transform_entity(data))
            elif name == MANIFEST:
                data = serialize(transform_manifest(data))
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.external_attr = 0o644 << 16
            target.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def validate(path):
    with source() as original, zipfile.ZipFile(path) as target:
        assert target.testzip() is None
        old_names = {x for x in original.namelist() if not x.endswith('/')}
        assert old_names == set(target.namelist())
        for name in old_names - {ENTITY, MANIFEST}:
            assert original.read(name) == target.read(name), name
        assert json.loads(target.read(ENTITY)) == transform_entity(original.read(ENTITY))
        assert json.loads(target.read(MANIFEST)) == transform_manifest(original.read(MANIFEST))
    print('Pack verified: 66 reordered conditions, all other resources unchanged')

if __name__ == '__main__':
    path = pathlib.Path(sys.argv[2])
    if sys.argv[1] == 'build':
        build(path)
    elif sys.argv[1] != 'test':
        raise SystemExit('usage: pack_tool.py build|test pack.mcpack')
    validate(path)
