"""Preserve original ZIP payloads; replace only the reviewed hook DEX and add ours.

Never round-trip vendor resources through a case-insensitive filesystem: res/A.xml
and res/a.xml are different ZIP members even on macOS. Signing happens separately.
"""
import pathlib
import re
import sys
import zipfile

source, assembled, addon, destination = map(pathlib.Path, sys.argv[1:5])
options = sys.argv[5:]
# bill branch: --relocated classes5.dex[,classes6.dex] declares host classes moved into new DEX slots by apktool
# (host-profiles.mjs relocate). Undeclared extra DEX files are still a collision error.
relocated_allowed = []
if options[:1] == ['--relocated'] and len(options) >= 2:
    relocated_allowed = options[1].split(',')
    options = options[2:]
extra = {}
if options == ['--public-assets']:
    from addon_assets import assets
    from background_manifest import patch as patch_manifest
    extra = assets()
elif options:
    raise SystemExit('Unknown asset profile')
if destination.resolve() in {source.resolve(), assembled.resolve(), addon.resolve()}:
    raise SystemExit('Output must not overwrite input')
temporary = destination.with_suffix('.assembling.apk')
if temporary.exists():
    raise SystemExit('Temporary output exists; inspect before retrying')
with zipfile.ZipFile(source) as original, zipfile.ZipFile(assembled) as modified:
    if len(original.namelist()) != len(set(original.namelist())):
        raise SystemExit('Duplicate original ZIP members')
    # The host may already ship classes4.dex (1.0.6 does); use the next free Android DEX slot of the
    # original package. verify_apk.py derives the same slot from the original alone.
    n = 2
    while f'classes{n}.dex' in original.namelist():
        n += 1
    # bill branch: apktool may emit extra classes{n}.dex.. for host classes relocated out of a full dex
    # (host-profiles.mjs relocate). They are taken from the assembled APK; the addon follows them.
    relocated = []
    for name in relocated_allowed:
        if name != f'classes{n}.dex' or name not in modified.namelist():
            raise SystemExit('Relocated DEX missing or out of order: ' + name)
        relocated.append(name)
        n += 1
    addon_name = f'classes{n}.dex'
    if addon_name in modified.namelist():
        raise SystemExit('Addon DEX collision')
    for name in relocated:
        if not modified.read(name).startswith(b'dex\n'):
            raise SystemExit('Invalid relocated DEX')
    if set(extra) & set(original.namelist()):
        raise SystemExit('Addon asset collision')
    patched = modified.read('classes2.dex')
    if not patched.startswith(b'dex\n') or not addon.read_bytes().startswith(b'dex\n'):
        raise SystemExit('Invalid DEX input')
    with zipfile.ZipFile(temporary, 'x', allowZip64=True) as output:
        for info in original.infolist():
            if re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', info.filename, re.I):
                continue
            content = patched if info.filename == 'classes2.dex' else original.read(info)
            if extra and info.filename == 'AndroidManifest.xml': content = patch_manifest(content)
            output.writestr(info, content)
        for name in relocated:
            info = zipfile.ZipInfo(name, (2026, 9, 26, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            output.writestr(info, modified.read(name))
        info = zipfile.ZipInfo(addon_name, (2026, 9, 26, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o100644 << 16
        output.writestr(info, addon.read_bytes())
        for name, path in sorted(extra.items()):
            info = zipfile.ZipInfo(name, (2026, 9, 26, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            output.writestr(info, path.read_bytes())
temporary.replace(destination)
print('Original resource/native payloads retained; hook DEX, %d relocated host DEX, addon DEX/assets and scoped TTS/background manifest additions reviewed.' % len(relocated))
