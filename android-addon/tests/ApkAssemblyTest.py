import pathlib
import subprocess
import tempfile
import unittest
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]

class AssemblyTest(unittest.TestCase):
    def test_case_distinct_resources_are_preserved(self):
        with tempfile.TemporaryDirectory(prefix='turbo-apk-test-') as temporary:
            root = pathlib.Path(temporary)
            source, assembled, addon, output = [root / x for x in ('source.apk','assembled.apk','addon.dex','output.apk')]
            files = {'classes.dex': b'dex\noriginal1', 'classes2.dex': b'dex\noriginal2',
                     'classes3.dex': b'dex\noriginal3', 'res/A.xml': b'UPPER',
                     'res/a.xml': b'lower', 'AndroidManifest.xml': b'manifest',
                     'resources.arsc': b'arsc', 'lib/arm64-v8a/libflutter.so': b'native',
                     'META-INF/VENDOR.SF': b'old signature', 'META-INF/services/keep': b'keep'}
            with zipfile.ZipFile(source, 'w') as archive:
                for name, data in files.items(): archive.writestr(name, data)
            with zipfile.ZipFile(assembled, 'w') as archive: archive.writestr('classes2.dex', b'dex\npatched')
            addon.write_bytes(b'dex\naddon')
            subprocess.run(['python3',str(ROOT/'assemble_apk.py'),str(source),str(assembled),str(addon),str(output)],check=True,capture_output=True)
            with zipfile.ZipFile(output) as archive:
                for name, data in files.items():
                    if name in ('classes2.dex', 'META-INF/VENDOR.SF'): continue
                    self.assertEqual(archive.read(name), data)
                self.assertEqual(archive.read('classes2.dex'), b'dex\npatched')
                self.assertEqual(archive.read('classes4.dex'), b'dex\naddon')
                self.assertNotIn('META-INF/VENDOR.SF',archive.namelist())
            subprocess.run(['python3',str(ROOT/'verify_apk.py'),str(source),str(output)],check=True,capture_output=True)
            failure = subprocess.run(['python3',str(ROOT/'assemble_apk.py'),str(source),str(assembled),str(addon),str(source)],capture_output=True)
            self.assertNotEqual(failure.returncode,0)

    def test_addon_dex_takes_next_free_slot_when_classes4_exists(self):
        with tempfile.TemporaryDirectory(prefix='turbo-apk-test-') as temporary:
            root = pathlib.Path(temporary)
            source, assembled, addon, output = [root / x for x in ('source.apk','assembled.apk','addon.dex','output.apk')]
            files = {'classes.dex': b'dex\n1', 'classes2.dex': b'dex\n2', 'classes3.dex': b'dex\n3',
                     'classes4.dex': b'dex\n4', 'AndroidManifest.xml': b'manifest'}
            with zipfile.ZipFile(source, 'w') as archive:
                for name, data in files.items(): archive.writestr(name, data)
            with zipfile.ZipFile(assembled, 'w') as archive: archive.writestr('classes2.dex', b'dex\npatched')
            addon.write_bytes(b'dex\naddon')
            subprocess.run(['python3',str(ROOT/'assemble_apk.py'),str(source),str(assembled),str(addon),str(output)],check=True,capture_output=True)
            with zipfile.ZipFile(output) as archive:
                self.assertEqual(archive.read('classes4.dex'), b'dex\n4')
                self.assertEqual(archive.read('classes5.dex'), b'dex\naddon')
            subprocess.run(['python3',str(ROOT/'verify_apk.py'),str(source),str(output)],check=True,capture_output=True)
            with zipfile.ZipFile(assembled, 'w') as archive:
                archive.writestr('classes2.dex', b'dex\npatched'); archive.writestr('classes5.dex', b'dex\nstray')
            failure = subprocess.run(['python3',str(ROOT/'assemble_apk.py'),str(source),str(assembled),str(addon),str(root/'other.apk')],capture_output=True)
            self.assertNotEqual(failure.returncode,0)

    def test_relocated_host_dex_precedes_addon(self):
        with tempfile.TemporaryDirectory(prefix='turbo-apk-test-') as temporary:
            root = pathlib.Path(temporary)
            source, assembled, addon, output = [root / x for x in ('source.apk','assembled.apk','addon.dex','output.apk')]
            files = {'classes.dex': b'dex\n1', 'classes2.dex': b'dex\n2', 'classes3.dex': b'dex\n3',
                     'classes4.dex': b'dex\n4', 'AndroidManifest.xml': b'manifest'}
            with zipfile.ZipFile(source, 'w') as archive:
                for name, data in files.items(): archive.writestr(name, data)
            with zipfile.ZipFile(assembled, 'w') as archive:
                archive.writestr('classes2.dex', b'dex\npatched'); archive.writestr('classes5.dex', b'dex\nrelocated')
            addon.write_bytes(b'dex\naddon')
            undeclared = subprocess.run(['python3',str(ROOT/'assemble_apk.py'),str(source),str(assembled),str(addon),str(output)],capture_output=True)
            self.assertNotEqual(undeclared.returncode,0)  # an extra apktool dex is a collision unless declared
            subprocess.run(['python3',str(ROOT/'assemble_apk.py'),str(source),str(assembled),str(addon),str(output),'--relocated','classes5.dex'],check=True,capture_output=True)
            with zipfile.ZipFile(output) as archive:
                self.assertEqual(archive.read('classes4.dex'), b'dex\n4')
                self.assertEqual(archive.read('classes5.dex'), b'dex\nrelocated')
                self.assertEqual(archive.read('classes6.dex'), b'dex\naddon')
            strict = subprocess.run(['python3',str(ROOT/'verify_apk.py'),str(source),str(output)],capture_output=True)
            self.assertNotEqual(strict.returncode,0)  # verifier without the declaration rejects the extra dex
            report = subprocess.run(['python3',str(ROOT/'verify_apk.py'),str(source),str(output),'--relocated','classes5.dex'],check=True,capture_output=True,text=True).stdout
            self.assertIn('"relocated_host_dex": ["classes5.dex"]', report)
            self.assertIn('"addon_dex": ["classes6.dex"]', report)
            # A gap in the dex run (classes7 without classes6) is rejected by the verifier.
            with zipfile.ZipFile(output, 'a') as archive: archive.writestr('classes8.dex', b'dex\nstray')
            failure = subprocess.run(['python3',str(ROOT/'verify_apk.py'),str(source),str(output),'--relocated','classes5.dex'],capture_output=True)
            self.assertNotEqual(failure.returncode,0)
            with zipfile.ZipFile(assembled, 'w') as archive:
                archive.writestr('classes2.dex', b'dex\npatched'); archive.writestr('classes5.dex', b'not a dex')
            failure = subprocess.run(['python3',str(ROOT/'assemble_apk.py'),str(source),str(assembled),str(addon),str(root/'other.apk'),'--relocated','classes5.dex'],capture_output=True)
            self.assertNotEqual(failure.returncode,0)

    def test_guarded_profile_pins_marker_and_library(self):
        """bill branch: --guarded accepts only the pinned marker text; libapp.so must equal the pinned patched hash."""
        import re
        prep = (ROOT / 'package-official-ota-preparation.py').read_text()
        verify = (ROOT / 'verify_apk.py').read_text()
        for pin in ('96cc9a9f4634a2321e06790d0f062cd36e6420ef69c6166eb5bab41608fb917e', 'ANDROID-106-OFFICIAL-OTA-GUARD-01-TMIG1',
                    'assets/turboio/official-ota-guarded.txt'):
            self.assertIn(pin, prep); self.assertIn(pin, verify)
        # The phone-side check (NavReflect.guarded) must read the same marker path and compare the same full text.
        nav = (ROOT / 'src/com/turboio/addon/NavReflect.java').read_text()
        self.assertIn('"turboio/official-ota-guarded.txt"', nav)
        self.assertIn('"ANDROID-106-OFFICIAL-OTA-GUARD-01-TMIG1\\n"', nav)
        self.assertNotIn('getAssets().list(', nav)
        with tempfile.TemporaryDirectory(prefix='turbo-apk-test-') as temporary:
            root = pathlib.Path(temporary)
            source = root / 'source.apk'
            files = {'classes.dex': b'dex\n1', 'classes2.dex': b'dex\n2', 'AndroidManifest.xml': b'manifest',
                     'lib/arm64-v8a/libapp.so': b'stock library'}
            with zipfile.ZipFile(source, 'w') as archive:
                for name, data in files.items(): archive.writestr(name, data)
            def derivative(name, marker=None, lib=None):
                out = root / name
                with zipfile.ZipFile(out, 'w') as archive:
                    for n, data in files.items(): archive.writestr(n, lib if (n == 'lib/arm64-v8a/libapp.so' and lib is not None) else data)
                    archive.writestr('classes3.dex', b'dex\naddon')
                    if marker is not None: archive.writestr('assets/turboio/official-ota-guarded.txt', marker)
                return out
            def run(out, *flags):
                r = subprocess.run(['python3', str(ROOT / 'verify_apk.py'), str(source), str(out), *flags], capture_output=True, text=True)
                return r.returncode, __import__('json').loads(r.stdout)
            # Without --guarded the marker and a changed library stay unexpected (existing behaviour).
            code, report = run(derivative('a.apk', b'ANDROID-106-OFFICIAL-OTA-GUARD-01-TMIG1\n', b'patched library'))
            self.assertEqual(code, 1); self.assertEqual(report['unexpected_paths'], ['assets/turboio/official-ota-guarded.txt', 'lib/arm64-v8a/libapp.so'])
            # --guarded: correct marker accepted, but a library whose hash is not the pin is still rejected.
            code, report = run(derivative('b.apk', b'ANDROID-106-OFFICIAL-OTA-GUARD-01-TMIG1\n', b'patched library'), '--guarded')
            self.assertEqual(code, 1); self.assertEqual(report['unexpected_paths'], ['lib/arm64-v8a/libapp.so']); self.assertTrue(report['guarded']['marker'])
            # --guarded: an unpatched library is reported even though nothing changed.
            code, report = run(derivative('c.apk', b'ANDROID-106-OFFICIAL-OTA-GUARD-01-TMIG1\n'), '--guarded')
            self.assertEqual(code, 1); self.assertEqual(report['unexpected_paths'], ['lib/arm64-v8a/libapp.so (not the pinned patched library)'])
            # --guarded: wrong marker text and missing marker are both rejected.
            code, report = run(derivative('d.apk', b'ANDROID-106-OFFICIAL-OTA-PREPARE-01\n'), '--guarded')
            self.assertEqual(code, 1); self.assertIn('assets/turboio/official-ota-guarded.txt', report['unexpected_paths']); self.assertFalse(report['guarded']['marker'])
            code, report = run(derivative('e.apk'), '--guarded')
            self.assertEqual(code, 1); self.assertIn('assets/turboio/official-ota-guarded.txt (missing or wrong text)', report['unexpected_paths'])
            # Plain derivative without --guarded still passes untouched.
            code, report = run(derivative('f.apk'))
            self.assertEqual(code, 0); self.assertEqual(report['unexpected_paths'], [])

if __name__ == '__main__': unittest.main()
