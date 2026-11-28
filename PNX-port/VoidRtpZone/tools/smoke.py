"""Run PNX twice with fixture zones; test console commands and persistent deletion."""
import os, pathlib, subprocess, sys, time

root = pathlib.Path(os.environ['RUNNER_TEMP'])
work = root / 'pnx-rtpzone-server'
(work / 'plugins' / 'VoidRtpZone').mkdir(parents=True, exist_ok=True)
jar = root / 'pnx' / 'PowerNukkitX-beta' / 'build' / 'powernukkitx.jar'
plugin = root / 'pnx-rtpzone' / 'VoidRtpZone-1.0.0-pnx.1.jar'
(work / 'plugins' / plugin.name).write_bytes(plugin.read_bytes())
(work / 'plugins' / 'VoidRtpZone' / 'zones.yml').write_text('''zones:
  spawn:
    world: world
    min-x: -5
    min-y: 0
    min-z: -5
    max-x: 5
    max-y: 80
    max-z: 5
''', encoding='utf8')

def run(number, commands, expectations):
    log = work / f'smoke-{number}.log'
    with log.open('wb') as out:
        proc = subprocess.Popen(['java','-Xmx2g','-DdisableSentry=true','-jar',str(jar),'--disable-ansi','--skip-setup','--accept-license','--language=eng','--port=19196'], cwd=work, stdin=subprocess.PIPE, stdout=out, stderr=subprocess.STDOUT)
        def text(): return log.read_text(encoding='utf8', errors='replace')
        def wait(pred, seconds, name):
            end = time.monotonic() + seconds
            while time.monotonic() < end:
                s = text()
                if pred(s): return
                if proc.poll() is not None: raise RuntimeError(f'PNX stopped during {name}')
                time.sleep(1)
            raise RuntimeError(f'timed out waiting for {name}')
        try:
            wait(lambda s: 'For help, type' in s and 'VoidRtpZone включён, зон:' in s, 180, 'startup')
            if 'Disabling VoidRtpZone' in text(): raise RuntimeError('plugin disabled')
            for command, expected in zip(commands, expectations):
                proc.stdin.write((command+'\n').encode()); proc.stdin.flush()
                wait(lambda s: expected in s, 30, command)
            proc.stdin.write(b'stop\n'); proc.stdin.flush()
            proc.wait(timeout=45)
            if proc.returncode != 0: raise RuntimeError(f'PNX exit {proc.returncode}')
        except BaseException as exc:
            if proc.poll() is None:
                proc.terminate()
                try: proc.wait(timeout=15)
                except subprocess.TimeoutExpired: proc.kill(); proc.wait()
            tail = text()[-8000:]
            print(tail, file=sys.stderr)
            print('::error::' + (str(exc)+'\n'+tail[-5000:]).replace('%','%25').replace('\n','%0A').replace('\r','%0D'))
            raise
run(1, ['rtpzone list','rtpz reload','rtpz remove spawn','rtpzone list'], ['мир', 'перезагружен', 'удалена', 'Зон пока нет'])
run(2, ['rtpz list'], ['Зон пока нет'])
print('VoidRtpZone console, reload, removal and restart persistence passed')
