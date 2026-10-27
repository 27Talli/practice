#!/usr/bin/env python3
"""Rescue older debuggable POS data WITHOUT uninstalling, clearing storage, or modifying business rows.
Requires Python 3, JDK 17+, adb and USB debugging. See docs/SAFE_UPGRADES.md first.
"""
import argparse
import getpass
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tarfile
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
LIMIT = 128 * 1024 * 1024
TABLES = ['shops', 'products', 'product_barcodes', 'shop_products', 'inventory', 'sales', 'sale_items',
          'returns', 'return_items', 'stock_movements', 'expenses', 'audit_events', 'app_settings',
          'sku_sequences', 'sales_sequences', 'cost_code_settings', 'product_labels', 'receipt_settings']


def write_payload(connection, destination, demo, created_at=None):
    """Explicitly lift schema 3/4 into format-1/schema-5; all original business values are unchanged."""
    version = connection.execute('PRAGMA user_version').fetchone()[0]
    if version not in (3, 4, 5):
        raise ValueError('Only database versions 3, 4 and 5 are supported by this rescue tool.')
    if connection.execute('PRAGMA quick_check').fetchall() != [('ok',)] or connection.execute('PRAGMA foreign_key_check').fetchall():
        raise ValueError('Database integrity checks failed. Keep the original installation intact.')
    schema = json.loads((ROOT / 'app/schemas/com.offerpoint.pos.data.local.database.PosDatabase/4.json').read_text())['database']
    entities = {entry['tableName']: entry for entry in schema['entities']}
    existing = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    snapshot = dict(format=1, databaseVersion=5, backupId=str(uuid.uuid4()),
                    createdAt=created_at if created_at is not None else int(time.time() * 1000), variant='DEMO' if demo else 'LIVE')
    def dump(value, output):
        json.dump(value, output, ensure_ascii=True, separators=(',', ':'), allow_nan=False)
    total = 0
    with destination.open('x', encoding='utf-8') as output:
        output.write('{')
        for index, (name, value) in enumerate(snapshot.items()):
            if index: output.write(',')
            dump(name, output); output.write(':'); dump(value, output)
        output.write(',"tables":[')
        for index, table in enumerate(TABLES):
            if index: output.write(',')
            columns = [field['columnName'] for field in entities[table]['fields']]
            if table == 'app_settings': columns.append('dataGeneration')
            output.write('{"name":'); dump(table, output); output.write(',"columns":'); dump(columns, output); output.write(',"rows":[')
            count = 0
            if table in existing:
                source_columns = [row[1] for row in connection.execute(f'PRAGMA table_info(`{table}`)')]
                if source_columns != columns and not (version in (3, 4) and table == 'app_settings' and source_columns == columns[:-1]):
                    raise ValueError(f'Unexpected columns in {table}; no conversion was attempted.')
                keys = entities[table]['primaryKey']['columnNames']
                for row in connection.execute(f'SELECT * FROM `{table}` ORDER BY ' + ','.join(f'`{key}`' for key in keys)):
                    row = list(row)
                    if table == 'app_settings' and len(row) < len(columns): row.append('')
                    if any(value is not None and not isinstance(value, (int, str)) for value in row): raise ValueError('Unsupported database value')
                    if any(isinstance(value, str) and len(value) > 100_000 for value in row): raise ValueError('A text field exceeds the backup limit')
                    if count: output.write(',')
                    dump(row, output); count += 1; total += 1
                    if total > 2_000_000 or output.tell() > LIMIT: raise ValueError('Database exceeds backup limits')
            elif not (version == 3 and table == 'receipt_settings'):
                raise ValueError(f'Missing required table: {table}')
            output.write('],"rowCount":'); dump(count, output); output.write('}')
        output.write('],"sequences":'); dump(connection.execute('SELECT name,seq FROM sqlite_sequence ORDER BY name').fetchall(), output); output.write('}')
    if destination.stat().st_size > LIMIT: raise ValueError('Backup exceeds 128 MiB')


def crypto(mode, source, destination, password):
    subprocess.run(['java', str(ROOT / 'scripts/BackupCrypto.java'), mode, str(source), str(destination)],
                   input=password + '\n', encoding='utf-8', check=True, timeout=180, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def encrypt_verified(payload, destination, password):
    if destination.exists(): raise ValueError('Destination already exists. Choose a new filename.')
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='offer-point-verify-', dir=destination.parent) as temporary:
        folder = Path(temporary); encrypted = folder / 'backup.opbackup'; verified = folder / 'verified.json'
        crypto('encrypt', payload, encrypted, password); crypto('decrypt', encrypted, verified, password)
        expected = hashlib.sha256(payload.read_bytes()).digest()
        if hashlib.sha256(verified.read_bytes()).digest() != expected: raise ValueError('Backup verification failed')
        # Exclusive final creation never replaces an earlier backup.
        created = False
        try:
            with destination.open('xb') as output, encrypted.open('rb') as source:
                created = True
                os.chmod(destination, 0o600); shutil.copyfileobj(source, output); output.flush(); os.fsync(output.fileno())
        except BaseException:
            if created: destination.unlink(missing_ok=True)
            raise
    return hashlib.sha256(destination.read_bytes()).hexdigest()


def rescue(args, password):
    package = 'com.offerpoint.pos.demo' if args.demo else 'com.offerpoint.pos'
    database = 'offer_point_demo.db' if args.demo else 'offer_point_pos.db'
    adb = ['adb'] + (['-s', args.serial] if args.serial else [])
    def command(*parts, **kwargs): return subprocess.run(adb + list(parts), check=True, timeout=60, **kwargs)
    command('get-state', stdout=subprocess.DEVNULL)
    command('shell', 'run-as', package, 'id', stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    # Force-stop only. This tool never invokes uninstall, clear, or any write to the device's database.
    command('shell', 'am', 'force-stop', package, stdout=subprocess.DEVNULL)
    with tempfile.TemporaryDirectory(prefix='offer-point-rescue-') as temporary:
        folder = Path(temporary); archive = folder / 'databases.tar'
        process = subprocess.Popen(adb + ['exec-out', 'run-as', package, 'tar', '-cf', '-', 'databases'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            total = 0
            with archive.open('wb') as output:
                while True:
                    chunk = process.stdout.read(65_536)
                    if not chunk: break
                    total += len(chunk)
                    if total > LIMIT * 2: raise ValueError('Device archive exceeds the rescue limit')
                    output.write(chunk)
            if process.wait(timeout=10) != 0: raise ValueError('Device database copy failed; keep the original installation intact.')
        finally:
            if process.poll() is None: process.kill()
            process.communicate(timeout=10)
        if subprocess.run(adb + ['shell', 'pidof', package], capture_output=True, timeout=10).stdout.strip():
            raise ValueError('The app restarted during copying. Close it and repeat; keep the existing installation.')
        # SQLite rebuilds SHM on the host; its transient shared-memory index is not portable.
        allowed = {f'databases/{database}{suffix}' for suffix in ('', '-wal')}
        with tarfile.open(archive) as tar:
            for member in tar:
                normalized = member.name.removeprefix('./')
                if normalized not in allowed: continue
                if not member.isfile() or member.size > LIMIT: raise ValueError('Invalid database archive entry')
                target = folder / Path(normalized).name
                if target.exists(): raise ValueError('Duplicate archive entry')
                with tar.extractfile(member) as source, target.open('xb') as output: shutil.copyfileobj(source, output)
        local = folder / database
        if not local.exists(): raise ValueError('Expected POS database was not found')
        # SQLite reads the copied WAL; backup() creates a coherent local snapshot without device writes.
        clean = folder / 'coherent.db'
        with sqlite3.connect(local) as source, sqlite3.connect(clean) as target: source.backup(target)
        payload = folder / 'payload.json'
        with sqlite3.connect(clean) as connection: write_payload(connection, payload, args.demo)
        checksum = encrypt_verified(payload, args.output.resolve(), password)
        print('Encrypted rescue backup verified:', args.output.resolve())
        print('SHA-256:', checksum)
        print('The original app and its data remain installed. Validate this file in the new app before any removal.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path, help='New encrypted .opbackup file on this computer')
    parser.add_argument('--demo', action='store_true', help='Rescue Demo instead of Live')
    parser.add_argument('--serial', help='Specific adb device serial')
    parser.add_argument('--acknowledge-stop', action='store_true', help='Acknowledge that the app is force-stopped; keep it closed until copying finishes')
    args = parser.parse_args()
    if not args.acknowledge_stop: parser.error('Read docs/SAFE_UPGRADES.md, then add --acknowledge-stop. This does not authorize uninstall or clearing data.')
    if args.output.exists(): parser.error('The output file exists. Choose a new path.')
    password = getpass.getpass('New backup passphrase (at least 10 characters): ')
    if len(password) < 10 or len(password) > 1024 or '\n' in password or '\r' in password: parser.error('Invalid passphrase length or line break')
    if password != getpass.getpass('Repeat passphrase: '): parser.error('Passphrases do not match')
    rescue(args, password)


if __name__ == '__main__': main()
