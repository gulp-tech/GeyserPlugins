"""Boot the supplied PNX in a disposable directory; exercise plugin loading and pack loading."""
import os, pathlib, subprocess, sys, time

runner = pathlib.Path(os.environ['RUNNER_TEMP'])
workspace = pathlib.Path(os.environ['GITHUB_WORKSPACE'])
work = runner / 'pnx-voidmusic-server'
(work / 'plugins').mkdir(parents=True, exist_ok=True)
(work / 'resource_packs').mkdir(exist_ok=True)
plugin = runner / 'pnx-music' / 'VoidMusic-1.1.0-pnx.1.jar'
(work / 'plugins' / plugin.name).write_bytes(plugin.read_bytes())
(work / 'resource_packs' / 'VoidMusicPack.mcpack').write_bytes((workspace / 'VoidMusicPack-v1.0.1.mcpack').read_bytes())
jar = runner / 'pnx' / 'PowerNukkitX-beta' / 'build' / 'powernukkitx.jar'
log = work / 'stdout.log'
cmd = ['java', '-Xmx2g', '-DdisableSentry=true', '-jar', str(jar), '--disable-ansi', '--skip-setup', '--accept-license', '--language=eng', '--port=19198']
with log.open('wb') as stream:
    proc = subprocess.Popen(cmd, cwd=work, stdin=subprocess.PIPE, stdout=stream, stderr=subprocess.STDOUT)
    def output(): return log.read_text(encoding='utf-8', errors='replace')
    def wait_for(predicate, seconds, what):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            txt = output()
            if predicate(txt): return
            if proc.poll() is not None: raise RuntimeError(f'PNX stopped while waiting for {what}')
            time.sleep(1)
        raise RuntimeError(f'timed out waiting for {what}')
    try:
        wait_for(lambda s: 'For help, type' in s and 'Enabling VoidMusic v1.1.0-pnx.1' in s, 180, 'server and plugin startup')
        txt = output()
        if 'An error occurred while enabling the plugin VoidMusic' in txt or 'Disabling VoidMusic' in txt:
            raise RuntimeError('VoidMusic did not enable')
        if 'Successfully loaded 1 resource packs' not in txt:
            raise RuntimeError('music resource pack did not load')
        proc.stdin.write('vmusic status\nvoidmusic reload\nvmusic start\n'.encode()); proc.stdin.flush()
        wait_for(lambda s: 'VoidMusic: мир=world' in s and 'VoidMusic перезагружен:' in s and 'Консоль: укажите игрока.' in s, 30, 'console responses')
        assert (work / 'plugins' / 'VoidMusic' / 'config.yml').is_file(), 'config missing'
        proc.stdin.write(b'stop\n'); proc.stdin.flush()
        proc.wait(timeout=45)
        if proc.returncode != 0: raise RuntimeError(f'PNX exit code {proc.returncode}')
        print('PNX plugin, resource pack, and console-command smoke test passed')
    except BaseException as exc:
        if proc.poll() is None:
            proc.terminate()
            try: proc.wait(timeout=15)
            except subprocess.TimeoutExpired: proc.kill(); proc.wait()
        txt = output()[-18000:]
        print('--- PNX log ---\n' + txt, file=sys.stderr)
        # Check-run annotations are readable from the GitHub API even when log downloads are restricted.
        escaped = (str(exc) + '\n' + txt[-6000:]).replace('%','%25').replace('\r','%0D').replace('\n','%0A')
        print('::error::' + escaped)
        raise
