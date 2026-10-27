#!/usr/bin/env python3
"""CI-only signing verification with a disposable TEST key. Never upload these APKs as a release."""
import os
from pathlib import Path
import secrets
import subprocess
import tempfile


def main():
    with tempfile.TemporaryDirectory(prefix='offer-point-signing-test-', dir=os.environ['RUNNER_TEMP']) as temporary:
        key = Path(temporary) / 'test-only.p12'
        environment = dict(os.environ, POS_TEST_SIGNING_PASSWORD=secrets.token_urlsafe(32))
        subprocess.run(['keytool', '-genkeypair', '-keystore', str(key), '-storetype', 'PKCS12', '-storepass:env', 'POS_TEST_SIGNING_PASSWORD',
                        '-keypass:env', 'POS_TEST_SIGNING_PASSWORD', '-alias', 'test-only', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '1',
                        '-dname', 'CN=CI TEST ONLY'], env=environment, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, check=True)
        environment.update(POS_SIGNING_STORE_FILE=str(key), POS_STORE_PASSWORD=environment['POS_TEST_SIGNING_PASSWORD'],
                           POS_KEY_ALIAS='test-only', POS_KEY_PASSWORD=environment['POS_TEST_SIGNING_PASSWORD'])
        apk = Path('app/build/outputs/apk/demo/release/app-demo-release.apk')
        try:
            subprocess.run(['./gradlew', '--no-daemon', '--max-workers=2', ':app:assembleDemoRelease'], env=environment, check=True)
            signer = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0/apksigner'
            subprocess.run([str(signer), 'verify', '--verbose', str(apk)], check=True)
            print('PASS: release signing configuration built and verified using an isolated test key. No production key was configured or generated.')
        finally:
            apk.unlink(missing_ok=True)


if __name__ == '__main__': main()
