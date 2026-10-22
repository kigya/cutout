import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('mobile_sast', Path(__file__).with_name('check-mobile-sast.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class MobileSastContractTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.manifest = Path(self.directory.name) / 'AndroidManifest.xml'
        self.xml = '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.kigya.cutout"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>{}</manifest>'
        self.manifest.write_text(self.xml.format(''))
        self.report = {'errors': [], 'mobsfscan_version': '1.0.1', 'results': {}}

    def test_effective_manifest_and_info_only_report_pass(self):
        gate.validate_manifest(self.manifest)
        self.report['results']['android_ssl_pinning'] = {'metadata': {'severity': 'INFO'}}
        gate.validate_report(self.report, self.manifest)

    def test_each_unused_permission_blocks_effective_manifest(self):
        for permission in gate.REMOVED_PERMISSIONS:
            with self.subTest(permission=permission):
                self.manifest.write_text(self.xml.format(f'<uses-permission android:name="{permission}"/>'))
                with self.assertRaisesRegex(ValueError, 'permissions'):
                    gate.validate_manifest(self.manifest)

    def test_unmerged_or_changed_sdk_contract_is_not_a_manifest_pass(self):
        for xml in [self.xml.format('').replace('android:targetSdkVersion="36"', 'android:targetSdkVersion="26"'), '<manifest package="dev.kigya.cutout"/>']:
            self.manifest.write_text(xml)
            with self.assertRaises(ValueError):
                gate.validate_manifest(self.manifest)

    def test_only_exact_nonsecret_locale_key_is_reviewed(self):
        finding = {'metadata': {'severity': 'WARNING'}, 'files': [{'file_path': 'app/src/main/java/dev/kigya/cutout/locale/AppLocales.kt', 'match_string': '    private const val KEY = "tag"'}]}
        self.report['results']['android_kotlin_hardcoded'] = finding
        gate.validate_report(self.report, self.manifest)
        finding['files'][0]['file_path'] = 'other/app/src/main/java/dev/kigya/cutout/locale/AppLocales.kt'
        with self.assertRaisesRegex(ValueError, 'Unreviewed'):
            gate.validate_report(self.report, self.manifest)
        finding['files'][0]['file_path'] = 'app/src/main/java/dev/kigya/cutout/locale/AppLocales.kt'
        finding['files'][0]['match_string'] = 'private const val KEY = "secret-value"'
        with self.assertRaisesRegex(ValueError, 'Unreviewed'):
            gate.validate_report(self.report, self.manifest)

    def test_only_reviewed_manifest_minimum_warning_is_accepted(self):
        self.report['results']['android_manifest_insecure_minsdk_warning'] = {'metadata': {'severity': 'WARNING'}, 'files': [{'file_path': str(self.manifest), 'match_string': 'android:minSdkVersion<29'}]}
        gate.validate_report(self.report, self.manifest)
        self.report['results']['android_manifest_insecure_minsdk_warning']['files'][0]['file_path'] = 'unrelated/AndroidManifest.xml'
        with self.assertRaisesRegex(ValueError, 'Unreviewed'):
            gate.validate_report(self.report, self.manifest)

    def test_unknown_findings_scanner_failures_and_changed_versions_block(self):
        for severity in ['WARNING', 'ERROR', 'UNKNOWN']:
            report = copy.deepcopy(self.report)
            report['results']['new_finding'] = {'metadata': {'severity': severity}}
            with self.assertRaisesRegex(ValueError, 'Unreviewed'):
                gate.validate_report(report, self.manifest)
        for key, value in [('errors', ['scan failed']), ('mobsfscan_version', 'unknown'), ('results', None)]:
            report = copy.deepcopy(self.report)
            report[key] = value
            with self.assertRaises(ValueError):
                gate.validate_report(report, self.manifest)


if __name__ == '__main__':
    unittest.main()
