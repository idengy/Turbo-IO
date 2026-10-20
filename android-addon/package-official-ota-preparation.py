"""Private preparation derivative. Preserve every audited member except two URL strings.

bill branch 2026-10-19: re-pinned to the official Android 1.0.6 (216) libapp.so and the TMIG1 guard profile.

Requires the ordinary package's existing allowlist audit first. Never signs,
installs, alters firmware, reads credentials or starts an OTA operation.
"""
import hashlib
import json
import pathlib
import subprocess
import sys
import zipfile

ORIGINAL = 'e9ab4326681c0b53892550b04e03284b2bba2e30adcf22033b8e23e5b3bc66af'
PATCHED = '96cc9a9f4634a2321e06790d0f062cd36e6420ef69c6166eb5bab41608fb917e'
NATIVE = 'lib/arm64-v8a/libapp.so'
MARKER = 'assets/turboio/official-ota-preparation.txt'
VALUE = b'ANDROID-106-OFFICIAL-OTA-PREPARE-01\n'
SLOTS = [(0x885c9a, b'/xrlauncherapi/v1/signApi/gray/upgrade', b'http://127.0.0.1:18794/g/xxxxxxxA78p2M'),
         (0xe1ffc, b'/xrlauncherhwapi/v1/signApi/gray/upgrade', b'http://127.0.0.1:18794/g/xxxxxxxxxxCCXhB')]
root = pathlib.Path(__file__).resolve().parent
arguments = sys.argv[1:]
guarded = arguments and arguments[0] == '--guarded'
if guarded:
    arguments = arguments[1:]
    MARKER = 'assets/turboio/official-ota-guarded.txt'
    VALUE = b'ANDROID-106-OFFICIAL-OTA-GUARD-01-TMIG1\n'
original, base, patched_lib, out = map(pathlib.Path, arguments)
if out.exists() or out.resolve() in {p.resolve() for p in (original, base, patched_lib)}:
    raise SystemExit('Refuse to overwrite an existing artifact or an input')
# bill branch: the base may carry host classes relocated into classes{n}.. before the addon dex (verify_apk --relocated).
with zipfile.ZipFile(original) as o, zipfile.ZipFile(base) as b:
    slot = 2
    while f'classes{slot}.dex' in o.namelist():
        slot += 1
    extra_dex = []
    while f'classes{slot}.dex' in b.namelist():
        extra_dex.append(f'classes{slot}.dex')
        slot += 1
relocated = extra_dex[:-1]
subprocess.run([sys.executable, str(root/'verify_apk.py'), str(original), str(base)]
               + (['--relocated', ','.join(relocated)] if relocated else []) + ['--public-assets'], check=True)
new = patched_lib.read_bytes()
with zipfile.ZipFile(base) as z:
    before = z.read(NATIVE)
    assert hashlib.sha256(before).hexdigest() == ORIGINAL
    assert hashlib.sha256(new).hexdigest() == PATCHED
    expected = bytearray(before)
    for offset, old, replacement in SLOTS:
        assert len(old) == len(replacement) and before[offset:offset+len(old)] == old
        assert before[offset-1] == 128+len(old)*2
        expected[offset:offset+len(old)] = replacement
    assert bytes(expected) == new  # exact whole-file allowlist, no instruction changes
    assert not any(n in z.namelist() for n in ('assets/turboio/official-ota-guarded.txt', 'assets/turboio/official-ota-preparation.txt'))
    with zipfile.ZipFile(out, 'x') as dest:
        for info in z.infolist():
            dest.writestr(info, new if info.filename == NATIVE else z.read(info))
        dest.writestr(MARKER, VALUE)
with zipfile.ZipFile(base) as z, zipfile.ZipFile(out) as d:
    assert d.testzip() is None
    assert set(d.namelist()) == set(z.namelist()) | {MARKER}
    assert d.read(MARKER) == VALUE
    assert [name for name in z.namelist() if z.read(name) != d.read(name)] == [NATIVE]
report = {'profile':'ANDROID-106-OFFICIAL-OTA-GUARD-01-TMIG1' if guarded else 'ANDROID-106-OFFICIAL-OTA-PREPARE-01', 'firmware':'TMIG1 (Strix OS 1.0.5.5)', 'relocatedHostDex':relocated, 'sha256':hashlib.sha256(out.read_bytes()).hexdigest(),
          'modifiedLibrary':NATIVE, 'librarySHA256':PATCHED, 'changesOnlyTwoSerializedURLs':True,
          'marker':MARKER, 'upgradeSendingEnabled':False, 'oneTimeAuthorizationAvailable':bool(guarded), 'installed':False, 'deviceValidated':False}
out.with_suffix('.audit.json').write_text(json.dumps(report, indent=2)+'\n')
print(json.dumps(report, indent=2))
