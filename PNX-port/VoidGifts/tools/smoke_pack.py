"""CI only: exercise cold-start activation, runtime refusal, and persistent version."""
import hashlib, json, os, pathlib, subprocess, sys, time, zipfile
runner = pathlib.Path(os.environ['RUNNER_TEMP']); workspace = pathlib.Path(os.environ['GITHUB_WORKSPACE'])
work = runner / 'pnx-giftpack-server'
plugin_dir = work / 'plugins'; music = plugin_dir / 'VoidGifts' / 'music'; packs = work / 'resource_packs'
music.mkdir(parents=True, exist_ok=True); packs.mkdir(parents=True, exist_ok=True)
(plugin_dir / 'GiftPackProbe.jar').write_bytes((runner/'pnx-giftpack/GiftPackProbe.jar').read_bytes())
source = workspace/'VoidMusicPack/sounds/voidpe/spawn_music.ogg'
(music/'first.ogg').write_bytes(source.read_bytes())
active = packs/'VoidGiftsPack.mcpack'
jar = runner/'pnx/PowerNukkitX-beta/build/powernukkitx.jar'

def digest(): return hashlib.sha256(active.read_bytes()).hexdigest()
def verify(version, count):
    with zipfile.ZipFile(active) as z:
        assert z.testzip() is None
        assert json.loads(z.read('manifest.json'))['header']['version'] == [1,0,version]
        sounds = json.loads(z.read('sounds/sound_definitions.json'))['sound_definitions']
        assert len(sounds) == count
        assert all(z.read(item['sounds'][0]['name'] + '.ogg').startswith(b'OggS') for item in sounds.values())
def run(number, ready, modify=False):
    log=work/f'pack-smoke-{number}.log'
    with log.open('wb') as stream:
        proc=subprocess.Popen(['java','-Xmx2g','-DdisableSentry=true','-jar',str(jar),'--disable-ansi','--skip-setup','--accept-license','--language=eng','--port=19195'],cwd=work,stdin=subprocess.PIPE,stdout=stream,stderr=subprocess.STDOUT)
        def text(): return log.read_text(encoding='utf8',errors='replace')
        def wait(pred, seconds, what):
            end=time.monotonic()+seconds
            while time.monotonic()<end:
                s=text()
                if pred(s): return
                if proc.poll() is not None: raise RuntimeError(f'PNX stopped during {what}')
                time.sleep(1)
            raise RuntimeError(f'timed out during {what}')
        try:
            wait(lambda s: 'For help, type' in s and ready in s,180,'startup and pack activation')
            if 'Disabling GiftPackProbe' in text(): raise RuntimeError('probe disabled')
            if modify:
                previous=digest(); (music/'second.OGG').write_bytes(source.read_bytes())
                proc.stdin.write(b'giftpackprobe\n'); proc.stdin.flush()
                wait(lambda s: 'GIFT_PROBE_RESTART_REQUIRED' in s or 'GIFT_PROBE_UNSAFE_ACTIVATION' in s or 'GIFT_PROBE_ERROR' in s,30,'runtime activation check')
                if 'GIFT_PROBE_RESTART_REQUIRED' not in text() or digest()!=previous: raise RuntimeError('active pack changed during runtime')
            proc.stdin.write(b'stop\n'); proc.stdin.flush(); proc.wait(timeout=45)
            if proc.returncode != 0: raise RuntimeError(f'PNX exit {proc.returncode}')
        except BaseException as exc:
            if proc.poll() is None:
                proc.terminate()
                try: proc.wait(timeout=15)
                except subprocess.TimeoutExpired: proc.kill(); proc.wait()
            tail=text()[-7000:]; print(tail,file=sys.stderr)
            print('::error::'+(str(exc)+'\n'+tail[-5000:]).replace('%','%25').replace('\r','%0D').replace('\n','%0A'))
            raise
run(1,'GIFT_PROBE_READY changed=true version=1 tracks=1',modify=True); verify(1,1)
run(2,'GIFT_PROBE_READY changed=true version=2 tracks=2'); verify(2,2); previous=digest()
run(3,'GIFT_PROBE_READY changed=false version=2 tracks=2'); verify(2,2); assert digest()==previous
print('Gift pack cold startup, live refusal, restart and version tests passed')
