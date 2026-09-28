"""Emulator-only drawer/panel frame benchmark. Set icons/theme before comparing builds.

Counts Android gfxinfo janky frames, not end-to-end interaction latency. Alternating
flings keep exercising the list instead of repeatedly flinging its bottom edge.
No preference writes, notification clearing, or log clearing. Requires debug as home,
swipe-down mapped to the panel, automatic keyboard off, and the standard
1080x2400 test display. The preparation is checked before measuring.
"""
import argparse
import json
import os
from pathlib import Path
import re
import statistics
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

try:
    import psutil
except ImportError:
    psutil = None

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--adb', default=shutil.which('adb') or str(Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk/platform-tools/adb.exe'))
p.add_argument('--drawer-layout', choices=('categories', 'legacy'), default='categories')
p.add_argument('--rounds', type=int, default=7)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--drawer-only', action='store_true')
a = p.parse_args()
if not a.serial.startswith('emulator-') or a.rounds < 1:
    p.error('Use an explicit emulator serial and at least one round')
adb = a.adb
pkg = 'app.olauncher.debug'

def call(*args):
    return subprocess.check_output([str(adb), '-s', a.serial, *args], text=True,
                                   encoding='utf-8', timeout=60)

def shell(*args):
    return call('shell', *args)

assert shell('getprop', 'ro.kernel.qemu').strip() == '1'
assert pkg in shell('cmd', 'package', 'resolve-activity', '--brief', '-a',
                    'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
assert re.findall(r'\d+x\d+', shell('wm', 'size'))[-1] == '1080x2400'
assert shell('settings', 'get', 'secure', 'accessibility_enabled').strip() == '0'
preferences = ET.fromstring(shell('run-as', pkg, 'cat', 'shared_prefs/app.olauncher.xml'))
values = {node.get('name'): node.get('value') for node in preferences}
assert values.get('AUTO_SHOW_KEYBOARD') == 'false'
assert a.drawer_only or values.get('GESTURE_SWIPE_DOWN_ACTION') == '7'

def verify_screen(resource):
    shell('uiautomator', 'dump', '/sdcard/moo-benchmark.xml')
    root = ET.fromstring(shell('cat', '/sdcard/moo-benchmark.xml'))
    assert any(n.get('resource-id', '').endswith('/' + resource) for n in root.iter()), resource

def home():
    shell('input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(1.5)

def frame_result():
    raw = shell('dumpsys', 'gfxinfo', pkg)
    total = re.search(r'Total frames rendered: (\d+)', raw)
    jank = re.search(r'Janky frames: (\d+) \(([\d.]+)%\)', raw)
    assert total and jank and int(total[1]) > 0, raw
    return {'frames': int(total[1]), 'janky_frames': int(jank[1]), 'janky_percent': float(jank[2]),
            'percentiles_ms': dict(re.findall(r'(\d+)th percentile: (\d+)ms', raw))}

results = {'serial': a.serial, 'rounds': a.rounds,
           'third_party_packages': len(shell('pm', 'list', 'packages', '-3').splitlines()),
           'drawer': [], 'panel': []}
if psutil:
    psutil.cpu_percent()
    results['host_samples'] = []
for i in range(a.rounds):
    home()
    shell('input', 'swipe', '540', '1600', '540', '600', '220')
    time.sleep(2)
    verify_screen('drawerTitle' if a.drawer_layout == 'categories' else 'search')
    shell('dumpsys', 'gfxinfo', pkg, 'reset')
    for fling in range(8):
        start, end = ('1900', '500') if fling % 2 == 0 else ('500', '1900')
        shell('input', 'swipe', '540', start, '540', end, '110')
        time.sleep(0.4)
    time.sleep(1.5)
    result = frame_result()
    assert result['frames'] >= 80, result
    results['drawer'].append(result)
    print('drawer', i + 1, result, flush=True)
    home()
    if not a.drawer_only:
        shell('dumpsys', 'gfxinfo', pkg, 'reset')
        shell('input', 'swipe', '540', '500', '540', '1500', '220')
        time.sleep(2.5)
        result = frame_result()
        verify_screen('panelTitle')
        results['panel'].append(result)
        print('panel', i + 1, result, flush=True)
    if psutil:
        results['host_samples'].append({'cpu_percent': psutil.cpu_percent(),
                                       'available_memory_mb': psutil.virtual_memory().available // 1048576})

results['memory_after_interactions'] = shell('dumpsys', 'meminfo', pkg)
home()
shell('dumpsys', 'gfxinfo', pkg, 'reset')
time.sleep(3)
results['idle_gfxinfo'] = shell('dumpsys', 'gfxinfo', pkg)
for name in ('drawer', 'panel'):
    if results[name]:
        values = [row['janky_percent'] for row in results[name]]
        results[name + '_summary'] = {'median_percent': statistics.median(values),
                                     'range_percent': [min(values), max(values)]}
a.output.parent.mkdir(parents=True, exist_ok=True)
a.output.write_text(json.dumps(results, indent=2), encoding='utf-8')
print(json.dumps({k: v for k, v in results.items() if k.endswith('_summary')}, indent=2))
