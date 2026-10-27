#!/usr/bin/env python3
"""Run on the owner's private computer. The key is never generated in CI or uploaded by this script."""
import argparse
import getpass
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination', type=Path, help='New private .p12 file outside the source repository')
    args = parser.parse_args()
    destination = args.destination.expanduser().resolve()
    if destination.exists(): parser.error('Destination already exists. Preserve existing signing keys.')
    repository = Path(__file__).resolve().parents[1]
    if destination.is_relative_to(repository): parser.error('Store the signing key outside the source repository.')
    password = getpass.getpass('New keystore password (at least 12 characters): ')
    if len(password) < 12 or '\n' in password or '\r' in password: parser.error('Use at least 12 characters without line breaks')
    if password != getpass.getpass('Repeat password: '): parser.error('Passwords do not match')
    destination.parent.mkdir(parents=True, exist_ok=True)
    environment = dict(os.environ, POS_NEW_SIGNING_PASSWORD=password)
    subprocess.run(['keytool', '-genkeypair', '-keystore', str(destination), '-storetype', 'PKCS12',
                    '-storepass:env', 'POS_NEW_SIGNING_PASSWORD', '-keypass:env', 'POS_NEW_SIGNING_PASSWORD',
                    '-alias', 'offer-point-pos', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
                    '-dname', 'CN=Offer Point POS,O=Offer Point,C=PK'], env=environment, check=True)
    destination.chmod(0o600)
    print('Created private signing key:', destination)
    print('Alias: offer-point-pos. Keep encrypted offline copies of the key and password. Read docs/SAFE_UPGRADES.md.')


if __name__ == '__main__': main()
