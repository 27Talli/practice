#!/usr/bin/env python3
"""Host integration checks using actual exported legacy schemas; no phone or network required."""
import base64
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import rescue_debug_data as rescue


def fixture(path, version):
    schema = json.loads((rescue.ROOT / f'app/schemas/com.offerpoint.pos.data.local.database.PosDatabase/{version}.json').read_text())['database']
    db = sqlite3.connect(path)
    db.execute('PRAGMA foreign_keys=ON')
    for entity in schema['entities']:
        table = entity['tableName']; db.execute(entity['createSql'].replace('${TABLE_NAME}', table))
        for index in entity['indices']: db.execute(index['createSql'].replace('${TABLE_NAME}', table))
    db.execute(f'PRAGMA user_version={version}')
    db.execute("INSERT INTO shops VALUES(1,'OP','Offer Point','Mardan',1,1)")
    db.execute("INSERT INTO shops VALUES(2,'FF','Fancy Footwears','Mardan',1,1)")
    db.execute('INSERT INTO app_settings VALUES(1,1,0)')
    db.execute("INSERT INTO products VALUES(1,'OP-000001','RESCUE TEST ITEM','Cosmetics','Rose',1,1,1)")
    db.execute("INSERT INTO product_barcodes VALUES(10,1,'OP-000001','INTERNAL_CODE128',1)")
    db.execute("INSERT INTO product_barcodes VALUES(11,1,'OP-000001~LBB','COST_LABEL',1)")
    db.execute('INSERT INTO shop_products VALUES(1,1,100,200,1,1,1)')
    db.execute('INSERT INTO inventory VALUES(1,1,2,2)')
    fingerprint = hashlib.sha256(b'1|0|500;1,1,200,0').hexdigest()
    db.execute("""INSERT INTO sales(id,shopId,receiptNumber,status,subtotalPaise,discountPaise,totalPaise,amountPaidPaise,changePaise,paymentMethod,createdAt,completedAt,updatedAt,checkoutRequestId,requestFingerprint,orderDiscountPaise)
       VALUES(100,1,'OP-S-000001','COMPLETED',200,0,200,500,300,'CASH',2,2,2,'rescue-fixture',?,0)""", (fingerprint,))
    db.execute("INSERT INTO sale_items VALUES(200,100,1,1,'OP-000001','RESCUE TEST ITEM','Rose','OP-000001',1,200,100,0,200,2)")
    db.execute("INSERT INTO stock_movements VALUES(1,1,1,3,3,'OPENING','Opening',1,NULL,NULL)")
    db.execute("INSERT INTO stock_movements VALUES(2,1,1,-1,2,'SALE','Sale',2,200,NULL)")
    db.execute("INSERT INTO cost_code_settings VALUES(1,'BLACKHORSE',1,1)")
    db.execute("INSERT INTO product_labels VALUES(11,1,1,'OP-000001','RESCUE TEST ITEM','Rose',100,'LBB','BLACKHORSE',1)")
    db.execute("INSERT INTO sku_sequences VALUES('OP',2)")
    db.execute('INSERT INTO sales_sequences VALUES(1,2)')
    db.execute("INSERT INTO audit_events VALUES(1,'SALE',100,1,'COMPLETE_CASH_SALE',NULL,'{}',2)")
    if version == 4: db.execute("INSERT INTO receipt_settings VALUES(1,'ROLL_58','Fixture footer',2)")
    db.commit()
    return db


def main():
    password = 'fixture-passphrase'
    with tempfile.TemporaryDirectory(prefix='offer-point-host-tests-') as temporary:
        folder = Path(temporary)
        for version in (3, 4):
            with fixture(folder / f'v{version}.db', version) as db:
                payload = folder / f'v{version}.json'; rescue.write_payload(db,payload,False,1_792_987_200_000)
                parsed = json.loads(payload.read_text())
                assert parsed['databaseVersion'] == 5 and len(parsed['tables']) == 18
                settings = next(t for t in parsed['tables'] if t['name'] == 'app_settings')
                assert settings['rows'] == [[1,1,0,'']]
                encrypted = folder / f'v{version}.opbackup'
                checksum = rescue.encrypt_verified(payload,encrypted,password); assert len(checksum) == 64
                failed = folder / 'wrong-password.json'
                try: rescue.crypto('decrypt',encrypted,failed,'incorrect-password')
                except subprocess.CalledProcessError: pass
                else: raise AssertionError('Incorrect password was accepted')
                assert not failed.exists()
                # Never overwrite or delete a pre-existing destination, even after an error.
                existing = folder / 'existing'; existing.write_text('KEEP THIS FILE')
                try: rescue.crypto('decrypt',encrypted,existing,password)
                except subprocess.CalledProcessError: pass
                else: raise AssertionError('Existing file was overwritten')
                assert existing.read_text() == 'KEEP THIS FILE'; existing.unlink()
                if version == 4:
                    assets = rescue.ROOT / 'app/src/androidTest/assets'; assets.mkdir(parents=True,exist_ok=True)
                    (assets / 'backup-rescue-fixture.b64').write_text(base64.b64encode(encrypted.read_bytes()).decode() + '\n')
            print(f'PASS: legacy Room {version} exact-value payload conversion, Java encryption, decrypt verification, wrong-password rejection and existing-file protection')
    print('Generated a clearly synthetic rescue fixture for native Android interoperability tests.')


if __name__ == '__main__': main()
