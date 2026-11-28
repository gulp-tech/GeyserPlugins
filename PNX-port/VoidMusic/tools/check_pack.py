"""Check the pack used by the default configuration, without modifying its assets."""
import json, sys, zipfile

with zipfile.ZipFile(sys.argv[1]) as pack:
    assert pack.testzip() is None, 'corrupt archive'
    assert len(pack.namelist()) == len(set(pack.namelist())), 'duplicate paths'
    manifest = json.loads(pack.read('manifest.json'))
    assert manifest['header']['uuid'] and manifest['modules'], 'missing manifest metadata'
    definitions = json.loads(pack.read('sounds/sound_definitions.json'))['sound_definitions']
    for key in ('voidpe:spawn_music', 'voidpe.spawn_music'):
        sound = definitions[key]['sounds'][0]
        assert sound['name'] == 'sounds/voidpe/spawn_music' and sound['stream'] is True
        assert pack.read(sound['name'] + '.ogg').startswith(b'OggS'), 'expected Ogg/Vorbis container'
print('Music pack definitions and OGG container validated')
