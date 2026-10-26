# Offer Point POS — Milestone 2 (0.2.0)

An offline Android retail foundation for **Offer Point** and **Fancy Footwears**, Yahya Khan Market, Neher Chowk, Mardan, Pakistan. One local device and cashier; no accounts, subscriptions, server, or checkout network dependency.

Milestone 2 adds **real offline cash checkout** to the shop, product, and inventory foundation. It saves sales and stock deductions atomically, shows shop-specific sales history, and calculates today’s sales from saved records using Pakistan time. Camera scanning, receipt generation/printing, returns, financial reports, expenses UI, and backups remain deferred.

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

The included `gradlew` / `gradlew.bat` launchers bootstrap the official wrapper JAR when it is missing and verify its published SHA-256 before running it. Linux/macOS need curl or wget and sha256sum or shasum; Windows uses PowerShell. Gradle dependencies download on the first build. Android Studio can use the distribution configured in `gradle/wrapper/gradle-wrapper.properties`. The initial local workspace lacked the Android toolchain and could not finish downloads; GitHub Actions provides a separate build environment.

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

The JVM suite covers validation, exact checkout arithmetic, payment recovery, and ViewModel behavior. There are **41 instrumented Android tests**: 40 Room/migration tests and one end-to-end Compose checkout test. These include 21 new checkout/migration cases. GitHub Actions runs the JVM suite for both variants and the device suite on Android; consult its completed logs and reports for actual results. Coverage includes idempotent shop creation, persistent selection, product insertion/editing/retrieval, search, shop-specific prices and quantities, explicit product association, global internal/manufacturer barcode uniqueness, nonnegative stock, integer overflow, required adjustment reasons, ledger/audit consistency, forced transaction rollback, foreign-key isolation of future sales/returns, and closing/reopening a disk database. Test fixtures are created by tests and never inserted into the live app.

A supplemental source-reflected SQLite check can run without Android tooling using Python 3:

```sh
python3 scripts/check_schema.py
```

It validates all 15 entity definitions, the SQL in the current DAO queries, key relationships, barcode uniqueness, and stock isolation against Python’s SQLite. It does not execute Room code, Kotlin, Compose, or the Android instrumentation suite.

Tests need no network at runtime. Once development dependencies have been cached, Gradle can run with `--offline`. Build/test execution results for this delivery are recorded in [VERIFICATION.md](VERIFICATION.md); source code presence alone is not proof that a build or test passed.

Manual acceptance on a phone:

1. Launch the live app in airplane mode. Choose Offer Point; confirm no invented sales figures or products appear.
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
  ui/components/           Shared touch-friendly controls
  ui/theme/                Placeholder brand colors and typography
  di/                      Application-scoped constructor injection
```

Manual constructor injection keeps this small single-module app understandable without another compiler plugin. Repositories remain replaceable in tests; Hilt can replace the composition root if the app grows. UI depends on domain interfaces; Room stays in the data layer.

Implemented screens:

- **Shop Selection:** both shop addresses, durable local selection, switching.
- **Dashboard:** selected shop, real today’s sales and transaction count in Pakistan time, checkout, products/inventory/settings, sales history, and deferred reports.
- **Products:** shop-filtered reactive list, literal search, add/edit, category/variant, immutable generated SKU, optional existing manufacturer barcode, shop-specific purchase cost/selling price, opening stock, explicit association of an existing registry item.
- **Inventory:** current shop's balances; increases/decreases with a required reason and atomic stock ledger.
- **Checkout:** selected-shop product search, cart quantities/removal, fixed line and order discounts, exact cash/change, price/stock revalidation, durable posted sales, and safe retry recovery.
- **Sales history/details:** completed sales for the selected shop, immutable item snapshots, receipt references, totals, discounts, cash, change, and Pakistan timestamps.
- **Settings:** application version, current shop, offline operation, checked local database status, printer/backup placeholders.

## Database and data rules

The version 2 schema includes all requested business entities:

| Entity | Responsibility |
| --- | --- |
| Shop | Shop code, name, address, timestamps |
| Product | Shared name, category, variant, unique immutable SKU, active flag |
| ProductBarcode | Global unique text barcode; internal Code 128 identity or manufacturer code |
| ShopProduct | Shop/product composite key; separate cost, selling price, active flag |
| Inventory | Shop/product balance in whole items |
| StockMovement | Append-only opening/adjustment ledger; future sale/return references |
| Sale / SaleItem | Shop identity, receipt reference, totals/discounts/payment, immutable item name/SKU/price/cost snapshots |
| Return / ReturnItem | Original sale/item references, reason, refund amounts, quantities/restocking |
| Expense | Shop-specific amount, category, description, incurred/audit timestamps |

Supporting `AppSettings`, `SkuSequence`, `SaleSequence`, and `AuditEvent` tables persist selection/demo seeding, SKU allocation, per-shop sale references, and audit history. Checkout writes Sale/SaleItem/StockMovement rows in a single transaction; returns and expenses remain schema foundations without posting UI.

- Money is `Long` integer **paise**, never `Float` or `Double`. Text crosses the UI boundary through exact `BigDecimal` conversion. Example: `Rs. 150.25` stores `15025`.
- Quantities are nonnegative `Long` whole items. Each independently stocked color/size is a separate product variant. Zero prices are permitted; selling below purchase cost is not blocked by an invented business policy.
- A registry item has one generated internal SKU/barcode, e.g. `OP-000001` or `FF-000001`, plus an optional manufacturer barcode. Barcode strings preserve leading zeros and case. Both types share one uniqueness constraint. A product's original SKU prefix stays unchanged when it is associated with the other shop.
- Shared names/categories/variants/barcodes update across associated shops; the editor warns about this. Prices and quantities are always scoped to the selected shop. Existing-barcode association is an explicit action rather than silent merging.
- Product creation, initial stock, barcode reservation, SKU allocation, and audit writes commit together. Product edits never set stock; adjustments and ledger inserts commit together with overflow/negative checks.
- Composite foreign keys prevent future sale/return records from referencing another shop or the wrong original sale/item. Business records use restrictive foreign keys; parent rows are never replaced to update them.
- The app does not request Internet, camera, account, location, or storage permissions. Android automatic backup is disabled while controlled backup/restore is deferred. All operational data remains in the app's private Room database.
- Timestamps are UTC epoch milliseconds. Daily sales use inclusive-start/exclusive-end `Asia/Karachi` day boundaries and refresh at midnight.
- Cash is the only payment method. Line discounts are fixed amounts for the entire line; order discounts apply after line discounts. Discounts may reduce a total to zero but never below zero. No taxes, percentage discounts, card payments, or credit rules are invented.
- Each checkout has one stable request key. Replaying the same request returns the original saved sale; reusing the key with a different payload is rejected. The UI checks saved results after restoration or an uncertain error before enabling changes.
- Receipt references such as `OP-S-000001` and `FF-S-000001` allocate in the same transaction as the sale. They identify saved sales; receipt documents and printing are not implemented.
- Cart drafts use Android SavedStateHandle for normal process recreation; they are not long-term parked carts. Completed sales are durable Room records. Force-stopping before Android saves UI state may discard an unpaid draft, while completed sales remain accessible in Sales history.

Room exports versioned schemas to `app/schemas`; the workflow includes generated schemas in the `verification-reports` artifact. Commit the generated version 1 JSON after a successful build, before changing the schema. **There is no destructive migration fallback.** Future schema changes must increment the version, commit an explicit migration, and add migration tests before release. Keep historical stock and audit rows; deletion/archival workflows are outside this milestone.

## Next milestone and current limits

Milestone 3 should add camera barcode scanning and receipt generation on top of the saved transaction model. Returns, financial reports, expenses entry, printers, and controlled backup/restore remain later work.

This build has no production release signing, backup/export, multi-device synchronization, accounts, customer records, fractional stock, camera permission, tax engine, non-cash payment methods, or official shop logos. Test the Demo app and the acceptance steps on the shop phones before operational use. Keep a stable private release signing key for production upgrades; never uninstall a business-data installation to resolve a debug signing-key mismatch.
