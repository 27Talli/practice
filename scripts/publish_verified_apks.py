"""Publish the exact tested debug APKs as durable, direct GitHub downloads.

This does not rebuild or re-sign the app. It verifies the successful run,
source, artifact ZIP hashes and inner APK hashes before publishing a prerelease.
"""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

REPOSITORY = '27Talli/practice'
SOURCE = '83b2976f71632cc1461ee9811d6e45d0a1e709c4'
RUN = 40541624294
TAG = 'v1.2.0-rc1'
ARTIFACTS = (
    (12752855177, 'OfferPointPOS-1.2.0-rc1-live.apk',
     'a11d6920d52ae157dbfee213915f51105d4c8610286c6cbce23a24f0e60e15e0',
     '7ab8859b1e3f60fd5237dcae3cb3782b1f1f8e2ff605025417e533e9737d69e4'),
    (12752496448, 'OfferPointPOS-1.2.0-rc1-demo.apk',
     '1ed71f43872ef62e2a1220238bb4bccdc5f4de80924753ad0876f61f8bb21f87',
     'bc89a45c4b72a3fcd306b32c9dcfb05a76b8fda75f66f96ab7d60ba6d43c7072'),
)


def gh(*args, output=None):
    return subprocess.run(['gh', *args], check=True, stdout=output,
                          capture_output=output is None, timeout=180)


def api(path, missing_ok=False):
    result = subprocess.run(['gh', 'api', path], capture_output=True,
                            text=True, timeout=90)
    if missing_ok and result.returncode and 'HTTP 404' in result.stderr:
        return None
    result.check_returncode()
    return json.loads(result.stdout)


def verify_archive(archive_path, filename, zip_hash, apk_hash, destination):
    if hashlib.sha256(archive_path.read_bytes()).hexdigest() != zip_hash:
        raise ValueError('The downloaded artifact ZIP failed verification.')
    with zipfile.ZipFile(archive_path) as archive:
        apks = [entry for entry in archive.infolist() if entry.filename.endswith('.apk')]
        if len(apks) != 1 or apks[0].file_size > 32 * 1024 * 1024:
            raise ValueError('The artifact must contain one bounded APK.')
        data = archive.read(apks[0])
    if hashlib.sha256(data).hexdigest() != apk_hash:
        raise ValueError('The APK does not match the verified build.')
    target = destination / filename
    target.write_bytes(data)
    return target


def main():
    if os.environ.get('GITHUB_REPOSITORY') != REPOSITORY:
        raise RuntimeError('Run this publisher only in the intended repository.')
    run = api(f'repos/{REPOSITORY}/actions/runs/{RUN}')
    if (run['status'], run['conclusion'], run['head_sha']) != ('completed', 'success', SOURCE):
        raise RuntimeError('The original Android verification run must have succeeded.')

    with tempfile.TemporaryDirectory(prefix='offer-point-release-') as temporary:
        folder = Path(temporary)
        files = []
        hashes = {}
        for artifact_id, filename, zip_hash, apk_hash in ARTIFACTS:
            metadata = api(f'repos/{REPOSITORY}/actions/artifacts/{artifact_id}')
            if (metadata['expired'] or metadata['workflow_run']['id'] != RUN or
                    metadata['workflow_run']['head_sha'] != SOURCE):
                raise RuntimeError('The artifact does not belong to the verified source/run.')
            archive_path = folder / f'{artifact_id}.zip'
            with archive_path.open('wb') as stream:
                gh('api', f'repos/{REPOSITORY}/actions/artifacts/{artifact_id}/zip', output=stream)
            files.append(verify_archive(archive_path, filename, zip_hash, apk_hash, folder))
            hashes[filename] = apk_hash
        checksums = folder / 'SHA256SUMS.txt'
        checksums.write_text(''.join(f'{hashes[name]}  {name}\n' for name in sorted(hashes)))
        files.append(checksums)

        notes = folder / 'release-notes.md'
        notes.write_text(f'''Offer Point POS **1.2.0-rc1**, version code 8, Room schema 6.

Download the **Live APK** for real shop use or **Demo APK** for testing. These are direct APK files: no ZIP extraction or GitHub sign-in is needed to download from this public release.

Adds private staff prices/profit, barcode capture in Add/Edit product, receipt-number QR codes and receipt lookup exclusively in Returns & refunds.

The exact files passed both builds, lint, **160 JVM tests per variant** and **139 Android tests** in [run {RUN}](https://github.com/{REPOSITORY}/actions/runs/{RUN}), source `{SOURCE}`. The publisher verifies both ZIP and APK SHA-256 hashes and does not rebuild or re-sign them.

**Debug evaluation builds, Android 8.0 or newer.** Their signing key differs from the earlier 1.1.0-rc1 build. Android blocks an in-place update across those keys. Keep the existing business app/data and follow the [safe upgrade guide](https://github.com/{REPOSITORY}/blob/main/docs/SAFE_UPGRADES.md) before migrating; never uninstall or clear business data just to bypass a signature error. Permanent owner signing and phone/printer acceptance are still required for production deployment.
''')

        release = api(f'repos/{REPOSITORY}/releases/tags/{TAG}', missing_ok=True)
        if release is None:
            gh('release', 'create', TAG, '--repo', REPOSITORY, '--target', SOURCE,
               '--title', 'Offer Point POS 1.2.0-rc1', '--notes-file', str(notes), '--draft', '--prerelease')
            release = api(f'repos/{REPOSITORY}/releases/tags/{TAG}')
        if release['target_commitish'] != SOURCE or not release['prerelease']:
            raise RuntimeError('An existing release has different provenance; it was not overwritten.')

        existing = {asset['name']: asset for asset in release['assets']}
        for file in files:
            if file.name in existing:
                # Reruns preserve matching assets and refuse to replace different bytes.
                check = folder / ('existing-' + file.name)
                with check.open('wb') as stream:
                    gh('api', '-H', 'Accept: application/octet-stream',
                       f'repos/{REPOSITORY}/releases/assets/{existing[file.name]["id"]}', output=stream)
                if hashlib.sha256(check.read_bytes()).digest() != hashlib.sha256(file.read_bytes()).digest():
                    raise RuntimeError('An existing release asset differs; it was not overwritten.')
            else:
                gh('release', 'upload', TAG, str(file), '--repo', REPOSITORY)
        if release['draft']:
            gh('release', 'edit', TAG, '--repo', REPOSITORY, '--draft=false', '--prerelease')
        for file in files:
            print(f'https://github.com/{REPOSITORY}/releases/download/{TAG}/{file.name}', flush=True)


if __name__ == '__main__':
    main()
