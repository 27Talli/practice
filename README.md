# Offer Point POS

An offline Android POS for Offer Point, Shop No. 8, Yahya Khan Market, Neher Chowk, Mardan, Pakistan. Offer Point is the primary workspace. Fancy Footwears remains available as a separate shop with its own stock, prices, sales, receipts, expenses and reports. No account, subscription, server or Internet permission is required.

**Current candidate: 1.0.0-rc1, version code 6, Room schema 6.** The remaining core roadmap is implemented in this candidate: returns/refunds, expenses, financial reports, low-stock alerts, optional local app PIN, and standard direct thermal printing, alongside the existing camera checkout, private cost-code labels, receipts and encrypted backups. See [VERIFICATION.md](VERIFICATION.md) for executed checks and the exact APK source. Candidate verification is in progress until that file records a completed successful run. A real phone/printer acceptance check and an owner-retained signing key are required before shop deployment.

## Open, build and install

Use Android Studio Ladybug 2024.2.1 or newer compatible with AGP 8.7.3, JDK 17, Android SDK Platform 35, Build Tools 35.0.0, platform-tools, and Gradle 8.9. All dependency versions are pinned in `gradle/libs.versions.toml`; they are the versions used by the earlier successful builds. Open the repository root in Android Studio, select JDK 17 for Gradle, let dependencies sync, and choose `liveDebug` or `demoDebug` in Build Variants. The app supports Android 8.0/API 26 and later. Development dependencies require downloading once; the installed application and all tests run without a service or backend.

The small checked-in launch scripts bootstrap the pinned Gradle distribution with checksum verification. A binary wrapper JAR is deliberately not required. Android Studio can also use an installed Gradle 8.9 distribution. If Gradle's artifact download is unavailable, follow `scripts/bootstrap-gradle.sh` or `scripts/bootstrap-gradle.ps1`; do not change dependency versions at random or enable insecure repositories.

```sh
chmod +x gradlew
./gradlew :app:assembleLiveDebug :app:assembleDemoDebug
```

On Windows use `gradlew.bat` with the same task names. If the Android SDK is not configured, use Android Studio's SDK Manager, set `ANDROID_HOME`, or create an uncommitted `local.properties` containing `sdk.dir=/your/Android/sdk`. CI uses `scripts/install-android-sdk.py` to install the exact packages.

Build outputs:

- Live: `app/build/outputs/apk/live/debug/app-live-debug.apk` (`com.offerpoint.pos`). Starts with two shops and **no demo products or sales**.
- Demo: `app/build/outputs/apk/demo/debug/app-demo-debug.apk` (`com.offerpoint.pos.demo`). Clearly marked test data, separate database and barcode namespace; can be installed alongside Live.

To build online, open [GitHub Actions](https://github.com/27Talli/practice/actions/workflows/android-apk.yml), run **Build Android APKs**, wait for the **entire job** to succeed, and download its Live or Demo artifact. Sign in to GitHub if necessary. Extract the downloaded ZIP; install the `.apk` inside, not the ZIP. Artifacts are published after tests pass and expire after 90 days; rerun the workflow to replace expired artifacts. Download `verification-reports` for exact test/lint results and schemas. A report artifact from a failed run does not indicate success.

On an Android phone, open the APK and allow installation from the browser/file manager when Android asks. With USB debugging and platform-tools:

```sh
adb install -r app/build/outputs/apk/demo/debug/app-demo-debug.apk
```

**Do not uninstall or clear a business installation to fix a signing mismatch.** GitHub's temporary debug keys can change between runs. Update an existing installation only with its original signing key; otherwise first preserve and validate its data using [SAFE_UPGRADES.md](docs/SAFE_UPGRADES.md). Use Demo for initial acceptance. Production releases use an owner-retained key; the app refuses to assemble a release without its signing credentials. The guide documents the four GitHub secrets and local setup. Never commit keys, passwords or real shop databases.

## Daily workflows

1. Select **Offer Point**. The selection is saved. A fresh Live database creates the two named shops without sample business records. Switch shops in Settings; each shop's transactions and stock remain separate.
2. Open **Products → Add product**. Enter name, category, variant, optional existing manufacturer barcode, cost, selling price, opening quantity and low-stock alert level. SKU is assigned once (`OP-000001`, `FF-000001`). Different tracked sizes/colours are separate products. Products, variants and barcode identities form a shared registry; prices, costs and quantities belong to the chosen shop. Use **Find existing product by barcode / SKU** to associate a registered product with the other shop rather than create duplicate barcodes.
3. In **Inventory**, search stock and use **Adjust stock** with a reason for deliveries, damage or corrections. Each adjustment writes a stock movement and audit event. Negative balances are rejected; opening quantity is not re-edited later.
4. In **Checkout**, use the dedicated **Scan** section and point the camera at a saved product's barcode. Camera access is requested only when needed. The offline CameraX/ZXing decoder supports common retail formats and Code 128. A deliberate **Scan next item** action prevents a held barcode from repeatedly adding units. **Cart** and **Products** remain separate sections, with manual lookup available for unbarcoded goods or denied camera access. Unknown codes never invent a product or price.
5. Review quantities/discounts, enter cash or choose **Exact cash**, then **Complete cash sale**. The app validates current prices and available stock, saves sale/item cost and price snapshots, deducts inventory and allocates a receipt in one Room transaction. Repeated or uncertain submissions recover the same saved transaction. Only cash payments are supported.
6. **Sales history → saved sale → View / print receipt** reopens the saved record. Preview/share a PDF or use Android printing (including Save as PDF). The same sale can be reprinted without changing stock or posting another transaction.
7. **Sales history → saved sale → Return items / refund** selects original sold quantities, quantity to restock and a required reason. Review the displayed cash refund, then confirm. Refunds preserve original prices, line discounts and order discounts, including partial-return rounding. Returned quantities cannot exceed the original quantity. Mark damaged/unsaleable units with zero restock. A unique refund reference, original-sale link, refund receipt, stock movements and audit trail are saved together. **Returns & refunds** lists saved returns; receipts are available there too.
8. **Expenses** records actual positive amounts by category, note and date. An incorrect entry can be voided with a reason; the original row and audit history are retained. Repeated submissions do not duplicate expenses.
9. **Reports** shows today, seven days, current month or custom dates in Pakistan time. Sales and refunds use their posting dates; expenses use their entered expense date. Totals include net sales, recorded cost of goods, gross profit, expenses and operating profit, plus product/category breakdowns and CSV export. Only restocked returns reverse cost of goods; unsaleable returned units keep their original cost. Current inventory cost value and low-stock count are explicitly separate from the historical report period. Operating cash movement excludes opening float, owner drawings and other unrecorded cash. This is an operational retail report, not tax filing or a complete general ledger. Legacy sales without category snapshots are labelled honestly.
10. **Settings → Local app PIN & lock** optionally sets a 6–12 digit device-local PIN with 1–30 minute inactivity lock. Persistent wrong-attempt delays discourage guessing. Phone device credentials can reset a forgotten app PIN. Use a secure Android screen lock. The app PIN is not a cashier account, does not encrypt all SQLite files, and is excluded from portable business backups. Business restore retains the receiving phone's PIN and clears old unpaid carts.
11. **Settings → Encrypted backup & restore** saves a verified encrypted backup through Android's file picker. Keep an off-device copy and its passphrase separately. A restore first validates a staged snapshot and requires a newly saved, verified recovery backup and typed confirmation before replacing both shops. See the guide before use.

## Private cost-code labels

Set your own ten different uppercase letters for digits 0–9 in **Settings → Private cost-code key**, then open a product's **Barcode label** action. Internal Code 128 labels contain a stable SKU, a secret letter-coded cost snapshot and a unique revision. The app looks up the registered alias; it never trusts arbitrary scanned text as a selling price. Manufacturer barcodes stay unchanged. A code is a staff convention that hides a plain cost from casual reading, not cryptographic secrecy from someone who learns your letter key. Keep the key private and use the local app PIN if other people handle the phone.

Labels do not print a plain purchase cost or the letter key. They show the selected shop, product/SKU, Code 128 and readable coded value, with no invented official logo. Old labels retain their original mappings after cost/key changes; checkout uses the product's current saved cost and selling price. A staff-only cost hint can show an old label snapshot. New labels use random revision identities to avoid reusing a lost alias after restoring older data. Demo labels have a `DEMO~` prefix and cannot be mixed with Live labels. Camera/print acceptance with real small labels remains required.

## Printers and receipts

**Settings → Receipts & printing** stores shop-specific footer, paper size (A4/58 mm/80 mm) and printer preference. Android print services and PDF sharing work without direct printer pairing. Direct thermal support is deliberately limited to standard ESC/POS raster printers:

- Bluetooth: first pair a Classic Bluetooth SPP printer in Android settings, then choose it in the app. Android 12+ asks for Nearby devices access. No discovery/location permission is used. BLE-only printers require a model-specific driver.
- USB: connect a USB printer-class device via USB host/OTG, select it and grant Android's USB permission. Standard bulk output is supported. USB serial adapters and proprietary drivers require a model-specific integration. Exactly one matching device must be attached.
- Choose 58 mm or 80 mm for direct printing. Cutter commands are opt-in. Print the clearly labelled **test (no sale)** before using saved receipts. A transfer completion cannot prove paper output. Check for a partial print before retrying; printing never reposts a sale or refund.

The same customer-safe receipt model feeds preview, PDF, Android printing and direct raster printing. Purchase costs, cost keys, coded label payloads and payment request tokens do not enter customer documents. Long receipts paginate with repeated branding/reference and an explicit Demo marker. Actual output, paper width, Bluetooth/USB behavior and cutter support must be verified on your printer. The printer model has not yet been supplied; no universal compatibility claim is made.

## Project and data design

One Android app module keeps the project maintainable for a single phone and cashier. Packages separate `data/local/entity`, `dao`, `database`, `data/repository`, `data/backup`, `domain/model`, `domain/repository`, `domain/usecase`, `ui/navigation`, `ui/screens`, `ui/finance`, `ui/security`, `ui/printing`, `ui/receipts`, `ui/scanner`, `ui/labels`, `ui/backup`, `ui/components`, `ui/theme`, and `di`.

Kotlin, Compose Material 3, Navigation Compose, ViewModels, coroutines/Flow, Room and explicit constructor injection are used. `AppContainer` is the composition root. Manual injection is sufficient for one module and keeps runtime/build complexity small; no global UI access to DAOs is introduced. No unnecessary Hilt or cloud dependency is added.

Schema 6 has 20 entities: Shop, Product, ProductBarcode, ShopProduct, Inventory, StockMovement, Sale, SaleItem, Return, ReturnItem, Expense; AppSettings, SkuSequence, SaleSequence, ReturnSequence, AuditEvent, CostCodeSettings, ProductLabel, ReceiptSettings and device-only OwnerSecurity. Barcode values and SKUs are globally unique, including manufacturer aliases. Composite foreign keys enforce shop/sale/product lineage. Monetary amounts are integer `Long` Pakistani paise (100 paise = Rs. 1); UI parsing and formatting use exact decimal arithmetic. Quantities are whole `Long` units. No `Double`/`Float` money arithmetic is used; display/PDF geometry uses normal graphics coordinates.

Sales, refunds, expenses, stock adjustments and their audit entries are transactional. Request fingerprints make monetary posting retries idempotent. A restore write gate rejects stale queued writes across a data replacement and changes the saved workspace generation. Important history is retained; there is no routine deletion or destructive migration fallback. Schema exports 1–6 and additive migrations are maintained. Portable backup format 1 accepts schema 5 and 6, and excludes this device's PIN/keystore credentials. The host rescue tool handles debuggable schemas 3/4/5/6 with explicit conversion; historical synthetic fixtures prove host-to-Android interoperability.

## Run tests

```sh
python3 scripts/check_schema.py
python3 scripts/test_rescue_tool.py
./gradlew :app:testLiveDebugUnitTest :app:testDemoDebugUnitTest :app:lintLiveDebug
# Start a compatible emulator, or connect an isolated test phone:
./gradlew :app:connectedLiveDebugAndroidTest
```

CI installs an Android 35 x86_64 emulator with KVM and runs the actual Room/SQLite migrations, transaction rollback/isolation/persistence tests, backups, native PDF/barcode rendering, PIN cryptography, raster printing and Compose checkout/refund/lock flows. JVM tests cover exact money and refund allocation, validation, scanner decoding, receipt privacy, backup encryption, request recovery and ViewModels. Tests use isolated databases and synthetic fixtures; they do not touch an installed business database or depend on a backend. Host SQLite checks are supplemental and are never substituted for Android tests. See [VERIFICATION.md](VERIFICATION.md) for actual executed counts and run links.

## Deployment boundaries

The software candidate covers the requested core roadmap for one phone, one cashier and cash sales. Use [SHOP_ACCEPTANCE.md](docs/SHOP_ACCEPTANCE.md) for deployment checks. Owner-retained release signing and physical shop-device/printer/label/backup acceptance remain required. An APK certificate cannot reconstruct a lost private signing key. Official logos will be added when provided; only text/placeholder branding is used now.

Multi-device/cloud sync, user accounts/roles, customer credit, fractional quantities, card payment integration, supplier purchasing/payables, tax automation, automatic backup scheduling and proprietary printer drivers are outside this core release. No fake transaction flow or invented financial data is used in Live. Losing the device without an exported backup or forgetting its backup passphrase cannot be repaired by a server.
