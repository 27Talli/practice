# Offer Point POS — Milestone 5 (0.5.0)

An offline Android retail foundation for **Offer Point** and **Fancy Footwears**, Yahya Khan Market, Neher Chowk, Mardan, Pakistan. One local device and cashier; no accounts, subscriptions, server, or checkout network dependency.

Milestone 5 adds **encrypted local backup, staged validation, controlled restore with a mandatory verified recovery copy, and retained-key release-signing configuration**. Both shops and all business records are included; Live and Demo remain isolated. Existing camera checkout, private cost-code labels and receipts remain available.

[Safe backup and upgrade instructions](docs/SAFE_UPGRADES.md) cover the app flow, passphrases, recovery copies, older debug-build data rescue and operator-owned signing keys. No owner key is generated or committed automatically. Retained-key releases require repository secrets; debug APKs alone do not establish stable signing.

## Current verified build

Version **0.5.0** passed [GitHub Actions run 40494409192](https://github.com/27Talli/practice/actions/runs/40494409192): **101 JVM tests per variant**, **94 Android emulator tests**, lint, both debug APK builds/signatures and an isolated unpublished release-signing build. Backup/restore tests cover failure rollback, recovery reuse, exact maximum values, identifier retention, old-cart isolation and post-commit cancellation cleanup. See [VERIFICATION.md](VERIFICATION.md) for actual results and limits.

[Download Live APK ZIP](https://github.com/27Talli/practice/actions/runs/40494409192/artifacts/12730582455) · [Download Demo APK ZIP](https://github.com/27Talli/practice/actions/runs/40494409192/artifacts/12729982795)

Extract the ZIP and install its `.apk` on Android 8.0+; GitHub may require sign-in. Artifacts are retained until 25 January 2027. The owner signing secrets are not configured: these downloads are debug/evaluation builds, not retained-key production releases.

Use Demo for evaluation. **Do not uninstall or clear an installation containing business data to resolve a signing mismatch.** Preserve its data and verify a restore independently before changing signing identities.

## Requirements and opening the project

- Android Studio supporting Android Gradle Plugin 8.7.3, with **JDK 17** selected as the Gradle JDK.
- Android SDK Platform **35** and SDK Build Tools **35.0.0**; Android SDK Platform Tools for installation.
- Android **8.0 / API 26 or newer** on a phone or emulator.
- Internet for the first development dependency download. The installed application and automated tests do not need Internet.

Open this directory in Android Studio and allow Gradle sync. Set the SDK path using Android Studio or an untracked `local.properties` file (`sdk.dir=/absolute/path/to/Android/Sdk`). Select `liveDebug` under Build Variants for real data or `demoDebug` for samples. Dependency versions are pinned in `gradle/libs.versions.toml`; the Gradle wrapper selects Gradle 8.9. See the completed workflow run and [VERIFICATION.md](VERIFICATION.md) for actual dependency-resolution and build results.

## Build and install

### Build and download through GitHub Actions

The [Build Android APKs workflow](https://github.com/27Talli/practice/actions/workflows/android-apk.yml) uses Java 17 and installs the required Android SDK packages. It builds both debug variants, runs the JVM suite for each variant and live-variant Android lint, verifies both APK signatures, and records SHA-256 checksums. It also boots an Android emulator and runs the Room checkout, stock, persistence, and migration tests. Inspect the completed run for actual results; uploading an APK alone does not establish that later device tests passed.

1. Open the workflow link and choose a completed successful run for the desired source commit. A running or failed job does not establish that an APK is ready.
2. Under **Artifacts**, download `offer-point-pos-live-debug` for the normal app or `offer-point-pos-demo-debug` for samples. Sign in to GitHub if prompted.
3. Extract the downloaded ZIP to obtain `app-live-debug.apk` or `app-demo-debug.apk`, then install the APK on an Android 8.0+ phone.
4. Download `verification-reports` for test/lint reports, generated Room schemas, and APK checksums. Reports may also be available from failed runs; check each job's result rather than treating the artifact's presence as a pass.

Artifacts are retained for **90 days**. To create a fresh build, open **Actions → Build Android APKs → Run workflow**, select `main`, and run it. Relevant source changes pushed to `main` also trigger the workflow. Build and test success must be confirmed from the completed run; this README does not claim that a run in progress has passed.

### Build locally

The included `gradlew` / `gradlew.bat` launchers bootstrap the official wrapper JAR when it is missing and verify its published SHA-256 before running it. Linux/macOS need curl or wget and sha256sum or shasum; Windows uses PowerShell. Gradle dependencies download on the first build. Android Studio can use the distribution configured in `gradle/wrapper/gradle-wrapper.properties`. The workspace has Java 21 but no Android SDK or installed Gradle distribution; GitHub Actions provides the JDK 17 and Android build environment.

From the project root, with JDK 17 and the Android SDK configured:

```sh
./gradlew :app:assembleLiveDebug :app:assembleDemoDebug
```

Debug APKs:

- `app/build/outputs/apk/live/debug/app-live-debug.apk`
- `app/build/outputs/apk/demo/debug/app-demo-debug.apk`

Enable Developer Options and USB debugging on the phone, connect it, authorize the computer, and run:

```sh
adb devices
adb install -r app/build/outputs/apk/live/debug/app-live-debug.apk
```

Alternatively copy the APK to the phone, open it, and allow installation from that source. These signed **debug APKs are for evaluation**. Updating with the same application ID and signing key preserves the database. GitHub-hosted debug builds use an ephemeral debug signing key that may differ between runs, so a later CI APK may be rejected as an update. Do not uninstall a business-data installation to work around a signing mismatch: uninstalling or clearing storage deletes its local data. Reliable production upgrades require a dedicated release signing key retained securely for future builds. Never commit, cache, or publish keystores in repository files or build artifacts.

The optional demo installs separately:

```sh
adb install -r app/build/outputs/apk/demo/debug/app-demo-debug.apk
```

Its app name and persistent banner say **Demo**; sample product names begin with `DEMO`. It uses a separate application ID (`com.offerpoint.pos.demo`), sandbox, and database. Samples are seeded once in that variant only. Live installations start with just the two real shops and no products.

## Tests and verification

Local JVM tests validate exact money parsing/formatting, price and quantity boundaries, product validation, and stock arithmetic:

```sh
./gradlew :app:testLiveDebugUnitTest :app:testDemoDebugUnitTest
./gradlew :app:lintLiveDebug
```

Room integration tests require an API 26+ emulator or an attached Android phone. They exercise real SQLite and Room, not a substitute database:

```sh
./gradlew :app:assembleLiveDebugAndroidTest
./gradlew :app:connectedLiveDebugAndroidTest
```

The **101-test JVM suite** covers validation, exact checkout arithmetic, payment recovery, cost-code boundaries, optical decoding and ViewModel behavior. The **94-test Android suite** includes Room/migration tests, an end-to-end Compose checkout test, and native PDF generation/rendering with barcode decoding and FileProvider access. New tests cover label registration, private cost codes, alias persistence, Live/Demo label namespaces, UPC/EAN ambiguity, scan state and duplicate guards, and version 2 → 3 migration. Executed totals are recorded in VERIFICATION.md. GitHub Actions runs the JVM suite for both variants and the device suite on Android; consult its completed logs and reports for actual results. Coverage includes idempotent shop creation, persistent selection, product insertion/editing/retrieval, search, shop-specific prices and quantities, explicit product association, global internal/manufacturer barcode uniqueness, nonnegative stock, integer overflow, required adjustment reasons, ledger/audit consistency, forced transaction rollback, foreign-key isolation of future sales/returns, and closing/reopening a disk database. Test fixtures are created by tests and never inserted into the live app.

A supplemental source-reflected SQLite check can run without Android tooling using Python 3:

```sh
python3 scripts/check_schema.py
```

It validates all 18 entity definitions, the SQL in the current DAO queries, key relationships, barcode uniqueness, and stock isolation against Python’s SQLite. It does not execute Room code, Kotlin, Compose, or the Android instrumentation suite.

Tests need no network at runtime. Once development dependencies have been cached, Gradle can run with `--offline`. Build/test execution results for this delivery are recorded in [VERIFICATION.md](VERIFICATION.md); source code presence alone is not proof that a build or test passed.

Manual acceptance on a phone:

1. Launch the live app in airplane mode. Confirm Offer Point is selected by default and no invented sales figures or products appear.
2. Add a product with a category, optional variant/barcode, cost `100.10`, selling price `150.25`, and opening stock `5`.
3. Search its name, category, SKU, and barcode. Edit its name and prices; confirm stock stays `5`.
4. Increase stock with a reason, then decrease it to zero. Try a decrease below zero and confirm the error and unchanged balance.
5. Switch to Fancy Footwears; confirm the first shop's product is absent. Use **Find existing product** with its barcode/SKU to associate it with independent prices and opening stock.
6. Edit shared details and verify both shops see them while prices and stock stay independent.
7. Force-stop and reopen the app; confirm the selection, products, and stock persist.
8. In Demo, add a product to Checkout, change quantity, apply fixed line/order discounts, enter cash received (or use Exact cash), and complete the sale. Confirm the saved sale reference, price/name snapshots, change, and reduced stock.
9. Open Sales history, restart the app, and confirm the sale remains under the correct shop. Today’s total should match saved completed sales in the Pakistan business day.
10. Try insufficient stock, excessive discounts, and cash below the total. No failed checkout may leave a sale, stock deduction, or receipt-sequence gap.
11. Change a product price while a cart is open; checkout must require an explicit cart refresh and review before saving.
12. Check Settings and Reports: deferred features still say coming soon. Install Demo alongside Live; sample inventory and sales must stay isolated.
13. In Demo, open Checkout → Scan, allow the camera, and scan a registered manufacturer barcode. One item should be added; holding the label still must not add more. Tap **Scan next item** for each additional unit. Test torch, denied permission, Android Settings recovery, and Products fallback.
14. In Products, open a barcode label. Generate/export a PDF, print at actual size, and scan the physical label in airplane mode. Confirm its exact code resolves to the intended product; an unregistered code, another shop's item, or out-of-stock item must not enter the cart.
15. Reveal the private code key in Settings and decode a known purchase cost. Change purchase cost or key, create a new label, then scan both old and new labels: both identify the same item; the old label preserves its recorded cost and checkout uses current catalog pricing. Demo PDF labels must visibly say DEMO and use the DEMO namespace.

## Implementation

One `app` module, Gradle Kotlin DSL, Kotlin, Compose Material 3, Navigation Compose, ViewModel, Coroutines/Flow, and Room:

```text
app/src/main/java/com/offerpoint/pos/
  data/local/entity/       Room entities and key relationships
  data/local/dao/          SQL and reactive shop-scoped projections
  data/local/database/     Database version and entity registration
  data/repository/         Atomic persistence and domain mapping
  domain/model/            UI-independent models and errors
  domain/repository/       Repository contracts
  domain/usecase/          Exact money and inventory validation
  ui/navigation/           Compose navigation
  ui/screens/              Screens, ViewModels and UI state
  ui/scanner/              Camera lifecycle and bundled barcode decoding
  ui/labels/               Private cost settings, label preview and PDF export
  ui/receipts/             Customer-safe previews, PDF layout and Android print adapter
  ui/backup/               File-picker backup, validation preview and explicit restore
  data/backup/             Streaming authenticated encryption and exact SQLite snapshots
  ui/components/           Shared touch-friendly controls
  ui/theme/                Placeholder brand colors and typography
  di/                      Application-scoped constructor injection
```

Manual constructor injection keeps this small single-module app understandable without another compiler plugin. Repositories remain replaceable in tests; Hilt can replace the composition root if the app grows. UI depends on domain interfaces; Room stays in the data layer.

Implemented screens:

- **Shop Selection:** both shop addresses and durable selection; fresh installs default to Offer Point, with other-shop access in Settings.
- **Dashboard:** selected shop, real today’s sales and transaction count in Pakistan time, checkout, products/inventory/settings, sales history, and deferred reports.
- **Products:** shop-filtered reactive list, literal search, add/edit, category/variant, immutable generated SKU, optional existing manufacturer barcode, shop-specific purchase cost/selling price, opening stock, explicit association of an existing registry item.
- **Inventory:** current shop's balances; increases/decreases with a required reason and atomic stock ledger.
- **Checkout:** Offer Point opens Scan with camera permission requested only by the user; separate Cart and Products fallback sections, cart quantities/removal, fixed discounts, exact cash/change, price/stock revalidation, durable sales, and safe retry recovery. Fancy Footwears opens manual Products checkout.
- **Sales history/details:** completed sales for the selected shop, immutable item snapshots, receipt references, totals, discounts, cash, change, and Pakistan timestamps.
- **Barcode labels:** registered Code 128 aliases with a private coded purchase-cost snapshot, preview, staff reveal, 1–100-copy PDF export/share, old-alias compatibility, and visibly marked Demo labels.
- **Receipts:** saved-sale preview, exact snapshot prices/discounts/cash/change, Pakistan timestamps, shop-specific branding, Demo watermark on every page, PDF sharing and Android printing.
- **Settings:** app version, current shop, offline/database status, private cost-code key, per-shop receipt paper/footer, Android print settings, encrypted backup/restore and optional other-shop access.

## Database and data rules

The version 5 schema includes all requested business entities:

| Entity | Responsibility |
| --- | --- |
| Shop | Shop code, name, address, timestamps |
| Product | Shared name, category, variant, unique immutable SKU, active flag |
| ProductBarcode | Global unique text barcode; internal Code 128 identity or manufacturer code |
| ReceiptSettings | Per-shop default paper, optional footer and settings audit timestamp |
| CostCodeSettings / ProductLabel | Private per-shop digit mapping; immutable barcode/cost/key/product-label snapshots |
| ShopProduct | Shop/product composite key; separate cost, selling price, active flag |
| Inventory | Shop/product balance in whole items |
| StockMovement | Append-only opening/adjustment ledger; future sale/return references |
| Sale / SaleItem | Shop identity, receipt reference, totals/discounts/payment, immutable item name/SKU/price/cost snapshots |
| Return / ReturnItem | Original sale/item references, reason, refund amounts, quantities/restocking |
| Expense | Shop-specific amount, category, description, incurred/audit timestamps |

Supporting `AppSettings`, `SkuSequence`, `SaleSequence`, and `AuditEvent` tables persist selection/demo seeding, SKU allocation, per-shop sale references, and audit history. Checkout writes Sale/SaleItem/StockMovement rows in a single transaction; returns and expenses remain schema foundations without posting UI.

- Money is `Long` integer **paise**, never `Float` or `Double`. Text crosses the UI boundary through exact `BigDecimal` conversion. Example: `Rs. 150.25` stores `15025`.
- Quantities are nonnegative `Long` whole items. Each independently stocked color/size is a separate product variant. Zero prices are permitted; selling below purchase cost is not blocked by an invented business policy.
- A registry item has one generated internal SKU/barcode, e.g. `OP-000001` or `FF-000001`, plus an optional manufacturer barcode. Barcode strings preserve leading zeros and case. All barcode types, including generated label aliases, share one global uniqueness constraint. Multiple internal aliases per product preserve previously printed labels. A product's original SKU prefix stays unchanged when it is associated with the other shop.
- Shared names/categories/variants/barcodes update across associated shops; the editor warns about this. Prices and quantities are always scoped to the selected shop. Existing-barcode association is an explicit action rather than silent merging.
- Product creation, initial stock, barcode reservation, SKU allocation, and audit writes commit together. Product edits never set stock; adjustments and ledger inserts commit together with overflow/negative checks.
- Composite foreign keys prevent future sale/return records from referencing another shop or the wrong original sale/item. Business records use restrictive foreign keys; parent rows are never replaced to update them.
- The app requests **camera permission only for scanning**. It does not request Internet, account, location, or storage permissions. Camera hardware/autofocus are optional; manual checkout works without them. CameraX and ZXing decode frames entirely on-device, with no model download. Exported PDFs use a private cache subdirectory and temporary FileProvider read access. Android automatic backup remains disabled; controlled encrypted document backup/restore is available through Settings. Room remains authoritative; only user-requested encrypted backup copies are exported.
- Timestamps are UTC epoch milliseconds. Daily sales use inclusive-start/exclusive-end `Asia/Karachi` day boundaries and refresh at midnight.
- Cash is the only payment method. Line discounts are fixed amounts for the entire line; order discounts apply after line discounts. Discounts may reduce a total to zero but never below zero. No taxes, percentage discounts, card payments, or credit rules are invented.
- Each checkout has one stable request key. Replaying the same request returns the original saved sale; reusing the key with a different payload is rejected. The UI checks saved results after restoration or an uncertain error before enabling changes.
- Receipt references such as `OP-S-000001` and `FF-S-000001` allocate in the same transaction as the sale. They identify saved sales; receipt documents and Android printing reuse these references and never allocate a second sale.
- Cart drafts use Android SavedStateHandle for normal process recreation; they are not long-term parked carts. Completed sales are durable Room records. Force-stopping before Android saves UI state may discard an unpaid draft, while completed sales remain accessible in Sales history.

Room exports versioned schemas to `app/schemas`; the workflow includes generated schemas in the `verification-reports` artifact. Actual generated Room schemas 1–5 are committed after successful verification. Explicit migrations preserve business records through 1 → 2 → 3 → 4 → 5. The additive dataset generation separates saved navigation/cart state after a restore. **There is no destructive migration fallback.** Future schema changes must increment the version, commit an explicit migration, and add migration tests before release. Keep historical stock and audit rows; deletion/archival workflows are outside this milestone.

## Secret cost-code labels

Each shop has a private ten-letter key mapping the digits 0–9. A cryptographically generated random key is saved locally on first use; staff can reveal or customize it in Settings. The code represents integer **paise**. For illustration only, the test key `BLACKHORSE` maps cost `Rs. 125.50` (12550 paise) to `LAHHB`; an own label can contain `OP-000001~LAHHB`. The real key is private and generated per installation/shop, not this example. New labels include a random numeric revision to avoid reusing lost post-backup label aliases; earlier registered labels remain supported. Demo payloads begin `DEMO~` so physical demo labels cannot silently match Live labels.

This is staff-readable obfuscation, not encryption or an access-control mechanism. The app currently has no staff PIN. Keep the key private; neither the key nor a plain purchase-cost amount is printed/exported. Manufacturer barcodes stay unchanged. Labels must be generated/registered in this database: arbitrary external codes are never parsed as trusted prices or costs.

Each generated label preserves the purchase cost, key and product description from creation. Changing costs or keys creates a new alias when necessary while retaining old aliases. The checkout staff reveal distinguishes that old label snapshot from current catalog cost. Sale price/cost snapshots always come from current Room records. Costs are not tracked separately per purchased batch in this milestone.

## Receipts and printing

After a cash sale, choose **View / print receipt**. The same action is available from Sales history → saved sale. Preview customer details, choose A4/58 mm/80 mm, then **Share receipt PDF** or **Print receipt**. The phone's print dialog can save a PDF without a printer. Physical printing requires a compatible printer and Android print service; the app does not automatically pair with arbitrary Bluetooth/USB printers.

In **Settings → Receipts & printing**, save the default paper size and optional footer separately for each shop. No tax registration, customer identity, phone number, official logo or return policy is invented. Receipt lines and payment totals use the immutable sale snapshots, even after catalog price/name changes. Shop name/address use the existing local shop record and the footer uses current preferences. Receipt files can be regenerated; they are private cache files, not business-data backups.

Long receipts paginate with repeated shop identity, sale reference, Pakistan date and a Demo marker on every Demo page. Long names and amounts wrap instead of being truncated. PDF export uses the selected format; Android printing reflows to the print dialog's actual media and margins. Thermal documents use paged 254 mm lengths, not a continuous ESC/POS stream. A print dialog/job is not proof that a physical printer produced paper. Cancelling, changing paper size, sharing or reprinting has no sales/inventory side effects.

Manual acceptance additions:

1. In Demo, complete a discounted cash sale, open its receipt and compare every line, discount, total, cash and change with the saved sale. Confirm purchase costs and secret barcode codes are absent.
2. Export each paper format in airplane mode, open the PDF and inspect all pages. Print through Android's Save as PDF and, if available, the shop printer. Confirm text, margins, page breaks and the Demo marker.
3. Cancel printing, print selected pages and reprint from history. The sale count, receipt reference and stock must stay unchanged.
4. Change receipt defaults/footer, force-stop/reopen and confirm persistence. Switch shops and verify independent settings and branding.
5. Update from Milestone 3 using the same signing key and verify existing products, stock, sales, cost-code keys and old barcode labels before making a new sale.

## Backups and signed upgrades

Open **Settings → Backups & restore**. Export a new encrypted `.opbackup` file using a private passphrase, or inspect an existing file. Restore displays its date and counts, requires typing `RESTORE`, and saves/read-verifies a new recovery file before replacing either shop. It replaces both shops in one transaction; it does not merge datasets. A generation marker discards old cart/navigation state after commit. Passphrases never enter Room, SavedStateHandle or saved Compose state. Full details and local rescue tooling are in [docs/SAFE_UPGRADES.md](docs/SAFE_UPGRADES.md).

The snapshot includes all 18 tables, ID/reference counters, private barcode keys and sales retry identities. Money/quantities stay exact integers. Authenticated streaming encryption rejects wrong passphrases, tampering, truncation, reordering and appended bytes. Unknown schema/columns, conflicting barcodes, broken relationships, inconsistent stock/sales and cross-variant restores are rejected before replacement. Limits and storage/provider requirements are documented in the guide.

Manual acceptance additions:

1. On Demo, create products/stock, complete a cash sale, create cost labels and change receipt preferences. Export to local Downloads in airplane mode and keep another copy off-device.
2. Change a product/stock value, inspect the backup, compare counts/date, confirm `RESTORE`, and save the recovery file. Verify the prior data and saved receipt return, keys/old labels work, and no unpaid cart is reused.
3. Inspect the recovery file and restore it through a second new recovery destination. Confirm the more recent state can be recovered.
4. Try a wrong passphrase, corrupt/truncated file, wrong variant, cancelled picker and unwritable destination. No failed operation may replace current shop data.
5. Reopen after restore and verify persistence. Test SAF behavior on the shop phone and intended USB/storage provider.
6. Before switching signing identities on an older debug installation, use the documented local rescue path and independently validate a restore without removing the original phone's data.

## Next milestone and current limits

The next milestone can add **sales returns/refunds with controlled restocking and receipt links**. Direct thermal-printer pairing remains dependent on the supplied printer model. Financial reports and expense entry remain later milestones.

Owner-controlled release signing is configured but requires the owner's retained key/secrets; CI-generated debug keys are unsuitable for production updates. Old signing keys cannot be recreated from APK certificates. The local rescue tool supports prior debuggable database versions 3/4/5; physical ADB/USB transfer still requires acceptance on the owner's device. There is no staff PIN, automatic backup schedule, multi-device sync, customer registry, fractional stock, tax engine, non-cash payment, automatic corrupt-database repair or official logos. Real storage providers, printer sizing and camera quality need hardware acceptance. Keep encrypted off-device backups, their passphrases and the release key safe.
