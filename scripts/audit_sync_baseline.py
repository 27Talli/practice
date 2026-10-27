"""Read-only, reproducible inventory of the pre-sync Android application.

Default: compare the runtime/schema/dependency inventory with the checked-in
checkpoint. --write records that inventory; it never opens a shop database.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import tomllib

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'docs/sync/baseline-inventory.json'
CHECKPOINT = '335808a9df7d59feeb05a0d2904a150a58d8988a'


def inventory():
    schema_root = ROOT / 'app/schemas/com.offerpoint.pos.data.local.database.PosDatabase'
    schemas = []
    for version in range(1, 7):
        path = schema_root / f'{version}.json'
        data = json.loads(path.read_text())['database']
        schemas.append({'version': data['version'], 'identityHash': data['identityHash'],
                        'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                        'tables': [e['tableName'] for e in data['entities']]})
    current = json.loads((schema_root / '6.json').read_text())['database']
    config = (ROOT / 'app/build.gradle.kts').read_text()
    paths = set((ROOT / 'app/src/main').rglob('*'))
    paths.update(schema_root.glob('*.json'))
    paths.update(ROOT / name for name in ('app/build.gradle.kts', 'build.gradle.kts',
        'settings.gradle.kts', 'gradle.properties', 'gradle/libs.versions.toml',
        'gradle/wrapper/gradle-wrapper.properties'))
    files = [{'path': p.relative_to(ROOT).as_posix(),
              'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
             for p in sorted(paths) if p.is_file()]
    backup = (ROOT / 'app/src/main/java/com/offerpoint/pos/data/backup/DatabaseSnapshot.kt').read_text()
    tables = re.search(r'val tables = listOf\((.*?)\)', backup, re.S).group(1)
    return {
        'stage': '1-audit-only', 'checkpointCommit': CHECKPOINT,
        'appVersion': re.search(r'versionName = "([^"]+)"', config).group(1),
        'appVersionCode': int(re.search(r'versionCode = (\d+)', config).group(1)),
        'module': ':app', 'runtimeKotlinFiles': len(list((ROOT / 'app/src/main').rglob('*.kt'))),
        'dependencyVersions': tomllib.loads((ROOT / 'gradle/libs.versions.toml').read_text())['versions'],
        'schemas': schemas, 'currentTables': current['entities'],
        'businessBackupTables': re.findall(r'"([a-z_]+)"', tables),
        'runtimeAndSchemaFiles': files,
        'limits': ['No device database or credentials read.',
                   'Generated Android rescue-test ciphertext excluded from runtime hashes.',
                   'This inventories schema and source, not the contents of a phone backup.'],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    current = inventory()
    if args.write:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_text(json.dumps(current, indent=2) + '\n')
        print('Recorded source/schema inventory for checkpoint', CHECKPOINT)
    else:
        expected = json.loads(OUTPUT.read_text())
        if current != expected:
            raise SystemExit('Pre-sync runtime/schema/dependency inventory changed. Review the stage transition; do not overwrite this checkpoint silently.')
        print('PASS: pre-sync runtime, schemas and dependency pins match the checkpoint inventory.')
    print(f"{len(current['currentTables'])} tables; {len(current['businessBackupTables'])} business-backup tables; {current['runtimeKotlinFiles']} Kotlin runtime files.")


if __name__ == '__main__':
    main()
