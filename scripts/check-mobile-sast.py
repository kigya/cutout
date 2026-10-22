#!/usr/bin/env python3
"""Validate local mobile scan results against the effective Android manifest."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ANDROID = '{http://schemas.android.com/apk/res/android}'
PROJECT_ROOT = Path(__file__).resolve().parents[1]
REMOVED_PERMISSIONS = {
    'com.google.android.gms.permission.AD_ID',
    'android.permission.ACCESS_ADSERVICES_AD_ID',
    'android.permission.ACCESS_ADSERVICES_ATTRIBUTION',
    'com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE',
}


def validate_manifest(path):
    root = ET.parse(path).getroot()
    sdk = root.find('uses-sdk')
    if root.get('package') != 'dev.kigya.cutout' or sdk is None:
        raise ValueError('Missing effective Cutout manifest')
    if sdk.get(ANDROID + 'minSdkVersion') != '26' or sdk.get(ANDROID + 'targetSdkVersion') != '36':
        raise ValueError('Manifest SDK bounds differ from the reviewed API26/target36 contract')
    permissions = {p.get(ANDROID + 'name') for p in root.findall('uses-permission')}
    if permissions & REMOVED_PERMISSIONS:
        raise ValueError('Unused advertising/install-referrer permissions reappeared')


def validate_report(report, manifest):
    if report.get('errors') != [] or report.get('mobsfscan_version') != '1.0.1':
        raise ValueError('Failed or unreviewed mobile scanner version')
    results = report.get('results')
    if not isinstance(results, dict):
        raise ValueError('Malformed mobile scan results')
    for rule, finding in results.items():
        severity = finding.get('metadata', {}).get('severity')
        if severity == 'INFO':
            continue
        files = finding.get('files')
        if severity == 'WARNING' and files:
            if rule == 'android_kotlin_hardcoded' and all(
                Path(f['file_path']).resolve() == PROJECT_ROOT / 'app/src/main/java/dev/kigya/cutout/locale/AppLocales.kt'
                and f.get('match_string', '').strip() == 'private const val KEY = "tag"'
                for f in files
            ):
                continue  # Locale preference key, no credential.
            if rule == 'android_manifest_insecure_minsdk_warning' and all(
                Path(f['file_path']).resolve() == Path(manifest).resolve()
                and f.get('match_string') == 'android:minSdkVersion<29'
                for f in files
            ):
                continue  # Explicitly supported API26; manifest bounds checked above.
        raise ValueError(f'Unreviewed mobile finding: {rule} ({severity})')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('reports', nargs='+')
    args = parser.parse_args()
    validate_manifest(args.manifest)
    for path in args.reports:
        validate_report(json.loads(Path(path).read_text()), args.manifest)
    print('PASS: mobile scan has no unreviewed ERROR/WARNING; effective permissions and SDK bounds match')


if __name__ == '__main__':
    main()
