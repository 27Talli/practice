# Offer Point POS — Milestone 1

An offline Android retail foundation for **Offer Point** and **Fancy Footwears**, Yahya Khan Market, Neher Chowk, Mardan, Pakistan. One local device and cashier; no accounts, subscriptions, server, or checkout network dependency.

This milestone manages shops, products, and stock. **It does not record sales.** Checkout, scanning, receipts, returns, financial reporting, expenses UI, printers, and backups are deferred.

## Requirements and opening the project

- Android Studio supporting Android Gradle Plugin 8.7.3, with **JDK 17** selected as the Gradle JDK.
- Android SDK Platform **35** and SDK Build Tools **35.0.0**; Android SDK Platform Tools for installation.
- Android **8.0 / API 26 or newer** on a phone or emulator.
- Internet for the first development dependency download. The installed application and automated tests do not need Internet.

Open this directory in Android Studio and allow Gradle sync. Set the SDK path using Android Studio or an untracked `local.properties` file (`sdk.dir=/absolute/path/to/Android/Sdk`). Select `liveDebug` under Build Variants for real data or `demoDebug` for samples. Dependency versions are pinned in `gradle/libs.versions.toml`; the Gradle wrapper selects Gradle 8.9. Their full resolution is unverified in this delivery because dependency-download approval was canceled.

## Build and install

This source delivery includes `gradlew` / `gradlew.bat` bootstrap launchers. The official wrapper JAR could not be downloaded in the development sandbox. On the first local invocation, the launcher downloads that JAR from the Gradle project and verifies its published SHA-256 before running it. Linux/macOS need curl or wget and sha256sum or shasum; Windows uses PowerShell. The remaining Gradle dependencies also download on the first build. Android Studio can use the distribution configured in `gradle/wrapper/gradle-wrapper.properties`.

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

Alternatively copy the APK to the phone, open it, and allow installation from that source. Debug builds are for evaluation; release distribution requires a securely retained signing key. Updating with the same application ID and signing key preserves the database. Uninstalling the app or clearing its storage removes its local data.

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

There are **12 JVM tests** and **19 instrumented Room tests**. They are included but have **not run** in this delivery. Coverage includes idempotent shop creation, persistent selection, product insertion/editing/retrieval, search, shop-specific prices and quantities, explicit product association, global internal/manufacturer barcode uniqueness, nonnegative stock, integer overflow, required adjustment reasons, ledger/audit consistency, forced transaction rollback, foreign-key isolation of future sales/returns, and closing/reopening a disk database. Test fixtures are created by tests and never inserted into the live app.

A supplemental source-reflected SQLite check can run without Android tooling using Python 3:

```sh
python3 scripts/check_schema.py
```

It validates all 14 entity definitions, the SQL in 20 DAO queries, key relationships, barcode uniqueness, and stock isolation against Python’s SQLite. It does not execute Room code, Kotlin, Compose, or the Android instrumentation suite.

Tests need no network at runtime. Once development dependencies have been cached, Gradle can run with `--offline`. Build/test execution results for this delivery are recorded in [VERIFICATION.md](VERIFICATION.md); source code presence alone is not proof that a build or test passed.

Manual acceptance on a phone:

1. Launch the live app in airplane mode. Choose Offer Point; confirm no invented sales figures or products appear.
2. Add a product with a category, optional variant/barcode, cost `100.10`, selling price `150.25`, and opening stock `5`.
3. Search its name, category, SKU, and barcode. Edit its name and prices; confirm stock stays `5`.
4. Increase stock with a reason, then decrease it to zero. Try a decrease below zero and confirm the error and unchanged balance.
5. Switch to Fancy Footwears; confirm the first shop's product is absent. Use **Find existing product** with its barcode/SKU to associate it with independent prices and opening stock.
6. Edit shared details and verify both shops see them while prices and stock stay independent.
7. Force-stop and reopen the app; confirm the selection, products, and stock persist.
8. Check Settings, Checkout, and Reports: future features must clearly say coming soon and save no transactions.
9. Install Demo alongside Live; confirm its sample inventory never appears in Live.

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
- **Dashboard:** selected shop, products/inventory/settings shortcuts, honest sales placeholder, clearly deferred checkout/reports.
- **Products:** shop-filtered reactive list, literal search, add/edit, category/variant, immutable generated SKU, optional existing manufacturer barcode, shop-specific purchase cost/selling price, opening stock, explicit association of an existing registry item.
- **Inventory:** current shop's balances; increases/decreases with a required reason and atomic stock ledger.
- **Settings:** application version, current shop, offline operation, checked local database status, printer/backup placeholders.

## Database and data rules

The version 1 schema includes all requested entities:

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

Supporting `AppSettings`, `SkuSequence`, and `AuditEvent` tables persist selection/demo seeding, transactional SKU counters, and before/after catalog history. Sale/return/expense tables are schema foundations; no milestone 1 UI or public repository method posts those transactions.

- Money is `Long` integer **paise**, never `Float` or `Double`. Text crosses the UI boundary through exact `BigDecimal` conversion. Example: `Rs. 150.25` stores `15025`.
- Quantities are nonnegative `Long` whole items. Each independently stocked color/size is a separate product variant. Zero prices are permitted; selling below purchase cost is not blocked by an invented business policy.
- A registry item has one generated internal SKU/barcode, e.g. `OP-000001` or `FF-000001`, plus an optional manufacturer barcode. Barcode strings preserve leading zeros and case. Both types share one uniqueness constraint. A product's original SKU prefix stays unchanged when it is associated with the other shop.
- Shared names/categories/variants/barcodes update across associated shops; the editor warns about this. Prices and quantities are always scoped to the selected shop. Existing-barcode association is an explicit action rather than silent merging.
- Product creation, initial stock, barcode reservation, SKU allocation, and audit writes commit together. Product edits never set stock; adjustments and ledger inserts commit together with overflow/negative checks.
- Composite foreign keys prevent future sale/return records from referencing another shop or the wrong original sale/item. Business records use restrictive foreign keys; parent rows are never replaced to update them.
- The app does not request Internet, camera, account, location, or storage permissions. Android automatic backup is disabled while controlled backup/restore is deferred. All operational data remains in the app's private Room database.
- Timestamps are UTC epoch milliseconds. Future business-day reports must use `Asia/Karachi`; no sales-day calculations are implemented here.

Room is configured to export versioned schemas to `app/schemas`; the generated version 1 JSON must be produced and committed after the first successful build, which was unavailable here. **There is no destructive migration fallback.** Future schema changes must increment the version, commit an explicit migration, and add migration tests before release. Keep historical stock and audit rows; deletion/archival workflows are outside this milestone.

## Next milestone and current limits

Milestone 2 should implement real transactional checkout: cart, integer discount/totals validation, sale and cost/price snapshots, receipt numbering scoped per shop, atomic stock deduction/movements, failure/rollback tests, and an actual persisted sales total. Camera barcode scanning and receipt generation can follow those transaction rules. Returns, reports, expenses entry, printer integration, and backup/restore remain separate later milestones.

This foundation has no production release signing, data export, recovery backup, multi-device synchronization, accounts, customer records, fractional stock, barcode camera permission, or official shop logos. Protect the physical phone and do not treat the app as a complete sales system until checkout and controlled backup/restore are delivered and verified on the shops' phones.
