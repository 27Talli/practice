#!/usr/bin/env python3
"""Stage an operator-owned signing key in CI temporary storage; never print secrets or create a key."""
import base64
import os
from pathlib import Path
import subprocess


def main():
    names = ('POS_KEYSTORE_BASE64', 'POS_STORE_PASSWORD', 'POS_KEY_ALIAS', 'POS_KEY_PASSWORD')
    values = [bool(os.environ.get(name)) for name in names]
    environment = Path(os.environ['GITHUB_ENV'])
    if not any(values):
        with environment.open('a') as out: out.write('POS_SIGNING_READY=false\n')
        print('Retained signing is not configured. Only debug evaluation APKs will be published.')
        return
    if not all(values): raise SystemExit('Signing configuration is incomplete. All four POS signing secrets are required.')
    encoded = os.environ['POS_KEYSTORE_BASE64']
    if len(encoded) > 2_000_000: raise SystemExit('Signing keystore exceeds the allowed size.')
    key = base64.b64decode(encoded, validate=True)
    destination = Path(os.environ['RUNNER_TEMP']) / 'offer-point-retained-signing.p12'
    destination.write_bytes(key); destination.chmod(0o600)
    try:
        subprocess.run(['keytool', '-list', '-keystore', str(destination), '-storepass:env', 'POS_STORE_PASSWORD',
                        '-alias', os.environ['POS_KEY_ALIAS']], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, check=True, timeout=30)
    except Exception:
        destination.unlink(missing_ok=True)
        raise SystemExit('The retained signing key could not be validated. No release APK was signed.')
    with environment.open('a') as out:
        out.write(f'POS_SIGNING_READY=true\nPOS_SIGNING_STORE_FILE={destination}\n')
    print('Retained signing key validated in private temporary storage. No key material is included in artifacts.')


if __name__ == '__main__': main()
