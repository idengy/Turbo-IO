"""Offline derivative audit; never reads account state or prints asset contents."""
import hashlib
import json
import sys
import zipfile

# bill branch: pins for the guarded (official OTA, TMIG1) derivative; must equal package-official-ota-preparation.py.
GUARD_MARKER = 'assets/turboio/official-ota-guarded.txt'
GUARD_VALUE = b'ANDROID-106-OFFICIAL-OTA-GUARD-01-TMIG1\n'
GUARD_NATIVE = 'lib/arm64-v8a/libapp.so'
GUARD_PATCHED = '96cc9a9f4634a2321e06790d0f062cd36e6420ef69c6166eb5bab41608fb917e'

source, output = sys.argv[1:3]
options = sys.argv[3:]
relocated_allowed = []
if options[:1] == ['--relocated'] and len(options) >= 2:
    relocated_allowed = options[1].split(',')
    options = options[2:]
guarded = False
if options[-1:] == ['--guarded']:
    guarded = True
    options = options[:-1]
extra = {}
if options == ['--public-assets']:
    from addon_assets import assets
    from background_manifest import patch as patch_manifest
    extra = assets()
elif options:
    raise SystemExit('Unknown asset profile')
with zipfile.ZipFile(source) as original, zipfile.ZipFile(output) as derivative:
    before = {f.filename for f in original.infolist() if not f.is_dir()}
    after = {f.filename for f in derivative.infolist() if not f.is_dir()}
    for archive in (original, derivative):
        names = [f.filename for f in archive.infolist()]
        if len(names) != len(set(names)):
            raise SystemExit('Duplicate ZIP entries')
        if archive.testzip() is not None:
            raise SystemExit('ZIP integrity failed')
    removed = sorted(before - after)
    added = sorted(after - before)
    changed = sorted(name for name in before & after
                     if hashlib.sha256(original.read(name)).digest()
                     != hashlib.sha256(derivative.read(name)).digest())
    # Same free-slot rule as assemble_apk.py: the host may already ship classes4.dex.
    n = 2
    while f'classes{n}.dex' in before:
        n += 1
    # bill branch: relocated host classes may occupy classes{n}.. before the addon dex; the run must be contiguous.
    added_dex = sorted((name for name in added if name.startswith('classes') and name.endswith('.dex')),
                       key=lambda name: int(name[7:-4]))
    expected_dex = [f'classes{n + i}.dex' for i in range(len(relocated_allowed) + 1)]
    if added_dex != expected_dex or relocated_allowed != expected_dex[:-1]:
        unexpected_dex = added_dex or ['(no addon dex)']
    else:
        unexpected_dex = []
    # bill branch --guarded: exactly one marker asset with the pinned text, and libapp.so equal to the pinned patched hash.
    guard = {}
    if guarded:
        guard['marker'] = GUARD_MARKER in after and derivative.read(GUARD_MARKER) == GUARD_VALUE
        guard['library_sha256_pinned'] = (GUARD_NATIVE in after
                                          and hashlib.sha256(derivative.read(GUARD_NATIVE)).hexdigest() == GUARD_PATCHED)
    unexpected = [name for name in removed if not name.startswith('META-INF/')]
    unexpected += unexpected_dex
    unexpected += [name for name in added if name not in added_dex and name not in extra and not name.startswith('META-INF/')
                   and not (guarded and name == GUARD_MARKER and guard['marker'])]
    unexpected += [name for name, path in extra.items() if name not in after or derivative.read(name) != path.read_bytes()]
    expected_manifest = bool(extra) and derivative.read('AndroidManifest.xml') == patch_manifest(original.read('AndroidManifest.xml'))
    unexpected += [name for name in changed if name != 'classes2.dex' and not (name == 'AndroidManifest.xml' and expected_manifest)
                   and not name.startswith('META-INF/')
                   and not (guarded and name == GUARD_NATIVE and guard['library_sha256_pinned'])]
    if guarded:
        unexpected += [f'{GUARD_MARKER} (missing or wrong text)'] if not guard['marker'] and GUARD_MARKER not in unexpected else []
        unexpected += [f'{GUARD_NATIVE} (not the pinned patched library)'] if not guard['library_sha256_pinned'] and GUARD_NATIVE not in unexpected else []
    report = {'added': added, 'changed': changed, 'relocated_host_dex': added_dex[:-1], 'addon_dex': added_dex[-1:], 'removed_signature_entries': len(removed),
              'resources_native_libraries_preserved': not unexpected,
              'manifest_only_tts_and_background_added': expected_manifest,
              'unexpected_paths': unexpected}
    if guarded:
        report['guarded'] = guard
    print(json.dumps(report, ensure_ascii=False))
    raise SystemExit(bool(unexpected))
