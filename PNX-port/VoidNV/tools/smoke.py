"""Boot an isolated PNX server, load the built plugin and try its console commands."""
import os, pathlib, subprocess, sys, time

runner = pathlib.Path(os.environ['RUNNER_TEMP'])
work = runner / 'pnx-voidnv-server'
(work / 'plugins').mkdir(parents=True, exist_ok=True)
plugin = runner / 'pnx-plugin' / 'VoidNV-1.0.0-pnx.1.jar'
(work / 'plugins' / plugin.name).write_bytes(plugin.read_bytes())
jar = runner / 'pnx' / 'PowerNukkitX-beta' / 'build' / 'powernukkitx.jar'
log = work / 'stdout.log'
cmd = ['java', '-Xmx2g', '-DdisableSentry=true', '-jar', str(jar), '--disable-ansi', '--skip-setup', '--accept-license', '--language=eng', '--port=19199']
with log.open('wb') as stream:
    proc = subprocess.Popen(cmd, cwd=work, stdin=subprocess.PIPE, stdout=stream, stderr=subprocess.STDOUT)
    def output():
        return log.read_text(encoding='utf-8', errors='replace')
    def wait_for(predicate, seconds, what):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            txt = output()
            if predicate(txt): return
            if proc.poll() is not None: raise RuntimeError(f'PNX stopped early waiting for {what}')
            time.sleep(1)
        raise RuntimeError(f'timed out waiting for {what}')
    try:
        wait_for(lambda s: 'For help, type' in s and 'Enabling VoidNV v1.0.0-pnx.1' in s, 180, 'server and plugin startup')
        if 'An error occurred while enabling the plugin VoidNV' in output() or 'Disabling VoidNV' in output():
            raise RuntimeError('VoidNV did not enable successfully')
        proc.stdin.write('nv\nvoidnv reload\n'.encode()); proc.stdin.flush()
        wait_for(lambda s: 'Команда только для игроков.' in s and 'VoidNV перезагружен.' in s, 30, 'command responses')
        assert (work / 'plugins' / 'VoidNV' / 'config.yml').is_file(), 'config missing'
        proc.stdin.write(b'stop\n'); proc.stdin.flush()
        proc.wait(timeout=45)
        if proc.returncode != 0: raise RuntimeError(f'PNX exit code {proc.returncode}')
        print('PNX plugin load and console command smoke test passed')
    except BaseException:
        if proc.poll() is None:
            proc.terminate()
            try: proc.wait(timeout=15)
            except subprocess.TimeoutExpired: proc.kill(); proc.wait()
        print('--- PNX log ---\n' + output()[-25000:], file=sys.stderr)
        raise
