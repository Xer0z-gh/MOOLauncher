"""Measure cold launches and catch redundant activity creation. EMULATORS ONLY.

Example:
  python tools/benchmark_cold_start.py --serial emulator-5562 --age expired --expect-creates 1

Starts Settings before force-stop so Android cannot auto-relaunch its home activity
before the measurement begins. Changes only the restart timestamp, restores the
original preferences in finally, and never clears logcat. This is process-cold,
not a disk/page-cache-cold benchmark. Compare APKs using the same launch procedure.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default=shutil.which('adb') or str(
        Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk/platform-tools/adb.exe'))
    parser.add_argument('--age', choices=('expired', 'fresh'), default='expired')
    parser.add_argument('--rounds', type=int, default=7)
    parser.add_argument('--expect-creates', type=int)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial) or args.rounds < 1:
        parser.error('An explicit emulator serial and positive round count are required.')
    package = 'app.olauncher.debug'

    def adb(*cmd):
        return subprocess.run([args.adb, '-s', args.serial, *cmd], check=True,
                              capture_output=True, text=True, errors='replace', timeout=60).stdout

    if adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
        parser.error('Refusing to change preferences on a non-emulator device.')
    home = adb('shell', 'cmd', 'package', 'resolve-activity', '--brief',
               '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
    if package + '/app.olauncher.MainActivity' not in home:
        parser.error('Set the debug build as this emulator\'s default home first.')
    original = adb('shell', 'run-as', package, 'cat', 'shared_prefs/app.olauncher.xml')
    # The 4-hour recreate this timestamp drove was removed on 2026-09-26; builds from before then
    # still read it, so it is aged when present, and an install without it is measured as-is.
    timestamp = re.compile(r'<long name="LAUNCHER_RECREATE_TIMESTAMP" value="[^"]*"\s*/>')
    samples = []
    # Keep scratch beside the requested report (or the repo), not in the user's home.
    scratch = args.output.parent if args.output else Path.cwd() / 'work'
    scratch.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='moo-startup-', dir=scratch) as temp:
        local = Path(temp) / 'prefs.xml'
        remote = '/data/local/tmp/moo-cold-start-prefs.xml'

        def stop():
            adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS')
            time.sleep(0.5)
            adb('shell', 'am', 'force-stop', package)

        def write_prefs(text):
            local.write_text(text, encoding='utf-8')
            adb('push', str(local), remote)
            adb('shell', 'run-as', package, 'cp', remote, 'shared_prefs/app.olauncher.xml')

        try:
            for i in range(args.rounds):
                stop()
                age = 0 if args.age == 'expired' else int(time.time() * 1000)
                write_prefs(timestamp.sub(
                    f'<long name="LAUNCHER_RECREATE_TIMESTAMP" value="{age}" />', original))
                before = set(adb('logcat', '-d', '-v', 'threadtime',
                                 'ActivityTaskManager:I', '*:S').splitlines())
                adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
                time.sleep(4)
                pid = adb('shell', 'pidof', package).strip()
                rows = [line for line in adb('logcat', '-d', '-v', 'threadtime',
                        'ActivityTaskManager:I', '*:S').splitlines()
                        if line not in before and 'Displayed ' + package + '/' in line]
                if len(rows) != 1:
                    raise RuntimeError(f'Expected one fresh Displayed event, got {rows}')
                match = re.search(r'\+(?:(\d+)s)?(\d+)ms', rows[0])
                if not match:
                    raise RuntimeError(f'Unrecognized launch timing: {rows[0]}')
                events = adb('logcat', '-b', 'events', '-d', '--pid', pid, '-v', 'brief')
                creates = sum('wm_on_create_called' in line and 'app.olauncher.MainActivity' in line
                              for line in events.splitlines())
                elapsed = int(match[1] or 0) * 1000 + int(match[2])
                samples.append({'ms': elapsed, 'creates': creates, 'pid': pid})
                print(f'{i+1}: {elapsed} ms, {creates} activity creation(s), pid {pid}', flush=True)
                if args.expect_creates is not None and creates != args.expect_creates:
                    raise RuntimeError(f'Expected {args.expect_creates} creation(s), got {creates}')
        finally:
            stop()
            write_prefs(original)
            adb('shell', 'rm', remote)
            adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    values = [s['ms'] for s in samples]
    result = {'serial': args.serial, 'age': args.age, 'samples': samples,
              'median_ms': statistics.median(values), 'range_ms': [min(values), max(values)]}
    text = json.dumps(result, indent=2)
    if args.output:
        args.output.write_text(text + '\n', encoding='utf-8')
    print(text)


if __name__ == '__main__':
    main()
