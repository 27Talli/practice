# Migration safety, backup verification and test plan

Status: Stage 1 baseline/audit. Tests below marked planned are not implemented or passed multi-device tests. No phone is attached; no real shop database was accessed. The owner reports only test/demo data and no Supabase project.

## Before any schema migration on a device

1. Record app version, variant, signing certificate, Android version and a non-secret device label. Keep the existing app installed and retain the original signing key.
2. In the existing app, use **Settings → Backups & restore** to export a new encrypted backup. Keep the passphrase separately. Save a second copy outside the phone; a local-only copy cannot recover a lost phone.
3. Inspect the backup in a compatible installation using the correct passphrase. Confirm variant, shop names, time and row counts. A successful copy/hash check alone does not prove a restorable database.
4. Restore into an isolated matching-variant test installation/device with no business data. The existing restore requires a separate, readable recovery backup and confirmation. Do not use a working shop database as the restore-test target.
5. Reopen the test installation and compare products/barcodes/cost-label mappings, prices/stock, sales/items and historical snapshots, receipt numbers/QRs, partial returns/refunds, expenses/voids, PKT report totals, high counters and both shops' separation. Verify the recovery file can restore the pre-replacement test state too.
6. Record the backup file hash, app/schema versions, backup ID, counts, selected hand-checked totals, restore-test result and protected storage locations. Do not put actual data/passphrases in GitHub. Mark the device migration-ready only after this evidence passes.

Since only test data currently exists, retain those records in Demo/test and provision an empty Live organization. Never treat a Demo export as the Live starting inventory. If real records appear later, select one authoritative starting dataset explicitly; review other devices as separate import proposals.

## Existing backup implementation verified by audit

- OPPOSBK1 chunked authenticated encryption, PBKDF2-HMAC-SHA256, AES-GCM, bounded input/output, staged validation and exact integer values.
- Logical snapshots of 19 business tables, consistent SQLite read transaction, fixed table/column whitelist, foreign-key/integrity checks and stock/refund validation.
- External write read-back/hash verification; mandatory separate recovery copy before replacement; all-or-nothing restore with a writer epoch and a new data-generation marker.
- Device PIN and current dashboard layout retained; raw SQLite/WAL files are not blindly copied over a running database.
- Current portable format explicitly covers schema 5/6. Host rescue conversion covers exported schemas 3–6. A sync-aware format must preserve global IDs and pending event identities, and must not restore another phone's credentials/active device authorization/checkpoint.

Stage 1 host checks are synthetic fixture verification, not a backup of the owner's phone. Native `BackupDatabaseTest` covers encrypted export, restore/reopen, recovery restoration, wrong passwords/corruption/truncation, FK/barcode/stock errors, Live/Demo separation, recovery I/O failures, rollback, concurrent mutations, rescue interoperability, high-water safety, maximum integers, cleanup, and retention of device PIN/layout. The fresh audit CI report records actual executed tests.

## Authoritative dataset import (future implementation)

1. Owner names the source device/dataset and temporarily freezes business writes during the final cutover snapshot. First export/verify/restore-test every source independently.
2. Stage import into an isolated namespace; produce counts/totals, duplicate SKU/manufacturer barcode and UPC/EAN ambiguity reports, foreign-key/ledger/refund validation and a deterministic manifest fingerprint.
3. Assign a dataset namespace and global mapping for legacy IDs in topological order. Preserve old receipt/barcode strings, sale/return links, discounts and snapshots. Same local ID from another dataset is not the same global record. The same fingerprint is evidence of a copied snapshot, not permission to merge independently edited databases.
4. Import full historical movements once. Do not also upload current stock as another opening balance. Check that imported movement sums match the verified starting balances; import historical facts without fabricating new business operations.
5. Server stages bounded chunks idempotently under one import ID; organization remains unavailable for normal enrollment until the complete manifest validates and is atomically activated. A failure/retry must not partially expose or duplicate history. Receipt collisions and duplicate products require explicit mapping/resolution, never automatic deletion.
6. Download to an empty test device and compare full data fingerprints plus per-shop financial/stock totals. Re-export and restore-test the imported organization independently. Only then authorize production devices.

With the owner's current test-only starting state, this import path is not needed to create Live; it remains a requirement if circumstances change. No import has been executed.

## Restore after synchronization (design requirement)

Never upload a restored historical database as a replacement for the cloud organization. A replacement phone must enroll with fresh credentials/device key/code, download an authorized current snapshot, and recover any unique pending operations from a verified recovery manifest. Keep original operation/sale/item/movement UUIDs so the server can detect earlier uploads; do not regenerate them to work around idempotency.

A synchronized-device restore must stop the worker, stage/verify the backup, preserve a verified recovery copy, compare organization/dataset lineage and pending operation history, then follow owner-authorized recovery/re-provisioning. Detect wrong-organization and cloned-device restores. Revoke/retire old device authorization where appropriate. No private credential, Keystore key, live pull checkpoint or complete-device identity is portable business data.

Independent cloud backups must be scheduled server-side, encrypted separately, validated before being added to the healthy backup set, retained in multiple generations and restored into an isolated project regularly. Preserve server idempotency/import histories and change-feed/counter state required for safe resumption. Never replace all good copies with a single incomplete export. This is not implemented by local sync or the current audit.

## Required multi-device acceptance matrix

Use two independent Android installations and databases against a disposable Supabase test project. Use controllable network loss plus actual application/process restarts. A mocked server/unit test is useful fault injection but does not satisfy device/backend integration acceptance.

| Scenario | Test and objective assertions | Current sync status |
| --- | --- | --- |
| A: online catalog | A creates product/association/stock/leading-zero barcode; B receives same UUID and shop data; scanner and search resolve it | Not run — sync absent |
| B: offline sales | Disconnect A; complete discounted cash sales and receipts; reconnect; server/B see each sale, item, debit and audit exactly once | Not run |
| C: concurrent sales | A/B sell same product; reorder delivery/acks; both original snapshots and unique movements persist; balances/reports converge | Not run |
| D: app/process restart | Commit offline work, kill process, reopen/reboot, reconnect; outbox survives; no checkout waits for upload | Not run |
| E: duplicate retries | Drop response after server commit; retry/restart/replay same batch; one operation/sale/refund/movement only; changed payload with same ID rejected | Not run |
| F: edit conflicts | Both edit same product/price at one base version; one accepted, other retained as explicit conflict; reviewed resolution increments revision | Not run |
| G: cross-device returns | Sell on A, sync, return on B; exact partial discounts/rounding, original IDs and restock survive; both devices converge | Not run |
| H: offline oversell | Both sell last unit offline; preserve both real sales; signed consolidated deficit remains visible; no stock overwrite or silent sale rejection | Not run |
| I: authorization | Anonymous/unauthorized/other organization/wrong shop/forged role/substituted device/revoked session cannot read or mutate business records | Not run |
| J: sync upgrade preservation | Upgrade copies of every historical schema; original rows/IDs/stock/receipts/barcodes/totals unchanged; new metadata valid; legacy backup round-trip works | Not run — new migration absent |

For D, distinguish a normal process kill from Android force-stop. Queue durability must hold for both; WorkManager cannot execute while the app remains force-stopped. Reopen it before expecting a sync, and record the scheduling restriction honestly.

## Additional release-blocking tests

- Failure injecting outbox insertion rolls back all business rows, stock, audit and receipt allocation. Process death after commit/before scheduling leaves one recoverable event. Every mutation path, including implicit key creation, barcode deletion, expense void and opening stock, is covered.
- Crash before/after page commit: canonical rows and cursor advance together. Repeated/out-of-order pages never lose or duplicate records. Equal timestamps, late-committing transactions, page boundaries, invisible shop events, scope changes and expired cursors are exercised.
- Upload echo arrives before acknowledgement and after acknowledgement: no double stock subtraction or financial rows; identical immutable IDs/payloads reconcile.
- Failed/conflicting parent catalog creation does not invent a product or orphan a sale. Unrelated valid events continue. Tombstones do not resurrect old barcodes or delete immutable financial history.
- Bootstrap while other devices write produces one consistent authorized snapshot and then all subsequent events; partial snapshots never appear as ready inventory. Empty/unprovisioned devices do not claim to know existing stock.
- Revoke one device while keeping another authorized device of the same user active. Device-ID substitution fails. Offline revoked-device writes remain locally preserved; reconnection invokes the documented quarantine/recovery policy. Expired network tokens alone do not disable permitted offline checkout.
- Repeat offline returns of the same last unit across devices. Test the chosen rights/provisional policy, cumulative quantities and paisa limits, duplicate retry, quarantine and reports; do not claim this case is solved by per-device validation.
- Recover a replacement device, restore an old snapshot, submit previously acknowledged operations, and recover truly unsent ones; no new IDs for old sales, no stale checkpoint skip and no cloned device keys/codes.
- Exercise receipt/return/SKU numbering collisions, imported old numbers, both legacy and device-qualified QR lookup, maximum barcode length, cost-label snapshots and device counter recovery.
- Room Flow invalidation refreshes inventory/reports for metadata-only and void updates even when table row counts stay equal. Check cost/privacy permissions in exported receipts and financial reports.
- Online/offline changes during backup export/recovery; reject corrupt/incomplete backup generations; restore both local and independent cloud copies into isolated environments and compare scope/data/totals.
- Two organizations with identical SKU/barcode strings remain isolated. A restricted shop user cannot retrieve another shop's prices, stock, transactions, cost keys or change feed.
- Retained-key install/update, Android supported API range, locally paired printer, camera permission denial, app PIN/grant recovery, multi-day offline operation and low storage are tested on actual intended phones.

## Evidence to record per future run

App/backend source commits, schema/migration checksums, app/signing versions, device IDs/Android versions (non-secret test identities), test organization, operation/global IDs, starting/ending quantities, SQL assertions, crash points, pending queue/cursors and expected conflict outcomes. Report pass/fail/skipped separately and link actual logs. No production transactions, tokens, passphrases or private keys belong in test fixtures or repository artifacts.

No claim of production-ready synchronization is permitted until A–J, security and restore requirements pass against the actual integrated implementation.
