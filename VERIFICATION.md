# Delivery verification — Offer Point POS 1.0.0-rc1

[GitHub Actions run 40505558229](https://github.com/27Talli/practice/actions/runs/40505558229) completed successfully on **27 October 2026**, building source commit **`701a4feb370663ad7345506a9932705c1b23ed62`**. Version code: **6**. Room database version: **6**.

| Check | Actual result |
| --- | --- |
| Live debug APK | Built; APK signature verified |
| Demo debug APK | Built; APK signature verified |
| JVM tests, Live | **121 passed**, 0 failures/errors/skipped |
| JVM tests, Demo | **121 passed**, 0 failures/errors/skipped |
| Live Android lint | Passed; report retained |
| Android 35 emulator suite | **121 passed**, 0 failures/errors/skipped |
| Release signing path | Demo release built and signature verified using an isolated unpublished TEST key; APK/key deleted |
| Owner-retained production signing | **Not configured**; no production release published |
| Host rescue-tool checks | Exported schemas **3/4/5/6** converted, encrypted/decrypted and byte-verified; device credentials excluded |
| Supplemental SQL checks | 20 reflected entities, 54 DAO queries and lineage/barcode/stock constraints passed |

The 121 JVM cases run separately for each flavor. The 121 Android cases use actual Room/SQLite and framework rendering/cryptography; they are not JVM substitutes. Tests have no backend or Internet requirement. The CI build downloads its development tools/dependencies; the installed app has no Internet permission.

## What the checks cover

The new finance tests cover exact order-discount allocation, repeated partial returns conserving every paisa, maximum integer arithmetic, original sale price/cost/category snapshots, partial or damaged returns, shop isolation, retry identity conflicts, concurrent duplicate/excessive returns, rollback of stock/refund/audit/numbering together, refund persistence after reopening, expense retry/void/audit behavior, report invalidation after voids, date boundaries, original cost treatment, current inventory values and low-stock alerts. CSV tests check exact signed decimals, quoting and spreadsheet-formula neutralisation in text cells. Money and quantities remain integer values.

Native security tests cover optional PIN setup, salted device-bound hashing, reopening, persisted wrong-attempt delays, current-PIN verification when changing/disabling a PIN, validation and device-credential reset audit behavior. The Compose lock/unlock test proves an unpaid cart survives an ordinary PIN lock. The existing data-generation test proves business restore instead clears old unpaid carts/navigation. Compose tests also complete checkout, reopen saved sale receipts and post a refund/open its saved receipt.

Backup tests cover encrypted read-verified export, both shops, recovery-copy reuse, wrong passwords/corruption/truncation, unsupported schemas, exact maximum values, invalid foreign keys/barcodes/stock/refund history, forced rollback, Live/Demo isolation, concurrent changes during recovery I/O and post-commit cancellation cleanup. A host-generated schema-5 rescue fixture restores on Android. Schema-6 backups preserve refund/expense/void/printer data, retain higher already-issued return counters and retain the receiving device's current PIN while excluding its credentials from the exported file.

Receipt/barcode tests generate and render actual PDFs, decode printed Code 128 content and exercise FileProvider/Android print behavior. New thermal tests encode paginated receipts for 58/80 mm widths, check raster framing/data bounds, reject A4 direct output and check opt-in cutter commands. These verify the encoder, not physical paper output or a particular printer's protocol compatibility.

Migrations validate exported historical schemas through **1 → 2 → 3 → 4 → 5 → 6** and preserve existing records. Migration 5 → 6 is additive. There is no destructive migration fallback. All six committed schema files were compared byte-for-byte with the generated schemas from the successful run; historical files 1–5 are unchanged. Schema 6 SHA-256:

```text
027bd3a6530a8697dc00dd34e0793fea7b4ce9b5d1ae9b27d007d77bfa8e72f2
```

## Verified APK downloads

- [Live APK ZIP — no demo products](https://github.com/27Talli/practice/actions/runs/40505558229/artifacts/12736220647)
- [Demo APK ZIP — recommended for initial acceptance](https://github.com/27Talli/practice/actions/runs/40505558229/artifacts/12736325250)
- [Verification reports, schemas, checksums and emulator logs](https://github.com/27Talli/practice/actions/runs/40505558229/artifacts/12735308086)

Sign in to GitHub if prompted, extract the ZIP, then install the `.apk` inside on Android **8.0/API 26 or newer**. Artifacts are retained until **25 January 2027**. Live/Demo have different application IDs/databases and cannot import each other's backups. Artifacts were published only after the Android suite passed.

SHA-256 of the APK files inside the ZIPs (different from the ZIP artifact digest):

```text
36124b2085e71dc6ffe23a943cdcc4f598e15655ab204bea29c87a5ac629a05c  app-live-debug.apk
eef42efbb4de284930129e97d5ecee5a1b810310014239891b18fe925d34f1e0  app-demo-debug.apk
```

## Reproduce

The workflow uses JDK 17, Gradle 8.9, SDK/Build Tools 35 and the pinned dependency catalog. Exact refund arithmetic is compatible with API 26; lint caught and the implementation replaced an Android-12-only conversion before this successful run.

```sh
python3 scripts/test_rescue_tool.py
python3 scripts/check_schema.py
./gradlew :app:assembleLiveDebug :app:assembleDemoDebug \
  :app:testLiveDebugUnitTest :app:testDemoDebugUnitTest \
  :app:lintLiveDebug :app:assembleLiveDebugAndroidTest
# With a connected isolated emulator/test device:
./gradlew :app:connectedLiveDebugAndroidTest
```

The local workspace has Java 21/Python but no Android SDK, installed Gradle or emulator; Android builds/tests actually ran in the linked GitHub-hosted environment. Local SQL, script/XML parsing and host crypto/rescue tests also passed. The host fixture uses synthetic records and a test passphrase; no real shop data is committed or used in CI. Subsequent documentation changes do not alter the verified runtime/test source.

## Deployment readiness

The core requested roadmap is implemented: shop/product/inventory management, offline camera checkout, private cost-code labels, saved sales, receipts, returns/refunds, expenses, reports, encrypted backups and optional local PIN. Standard direct ESC/POS Bluetooth Classic SPP/USB printer-class transport is implemented; Android print service/PDF remain available.

This is a **tested release candidate**, not a claim that the owner's hardware or permanent signing setup has been verified. CI explicitly found the retained key unconfigured. Temporary debug keys are unsuitable for dependable production updates; never uninstall/clear a business installation to fix a certificate mismatch. Follow [SAFE_UPGRADES.md](docs/SAFE_UPGRADES.md) for original-key updates, preserved backups, legacy rescue and owner-key setup.

Physical phone/camera/printed-label quality, actual printer compatibility/output, real document-provider recovery and retained-key installation/update require [shop acceptance](docs/SHOP_ACCEPTANCE.md). The printer model and shop phone details have not been supplied. No official logos were provided. Automatic backup scheduling, multi-device sync/accounts, non-cash payments, tax automation and proprietary printer drivers are outside the core release. The next step is **hardware acceptance and owner-signed deployment**.

Earlier verified baseline: [0.5.0 run 40494409192](https://github.com/27Talli/practice/actions/runs/40494409192), source `fb9ff19982eb7caa959217ee13b6ce2cd537a89b`, passed 101 JVM tests per flavor and 94 Android tests.
