# Offer Point POS

An offline Android POS for Offer Point, Shop No. 8, Yahya Khan Market, Neher Chowk, Mardan, Pakistan. Offer Point is the primary workspace. Fancy Footwears remains available as a separate shop with its own stock, prices, sales, receipts, expenses and reports. No account, subscription, server or Internet permission is required.

**Current candidate: 1.2.0-rc1, version code 8, Room schema 6.** Adds private staff price/profit lookup, scanning in the product barcode field, receipt-number QR codes and receipt lookup in the dedicated Returns & refunds section. Dashboard customization and all existing core workflows remain available. Current verification and APK links are recorded in [VERIFICATION.md](VERIFICATION.md). Physical shop acceptance and an owner-retained signing key remain required for production deployment.

## Staff prices, profit and receipt QR codes

- Tap the small **Staff prices and profit** icon in the top-right corner of the dashboard, checkout or saved sale. Search or scan a product to see its current selling price and purchase cost for the selected shop, including out-of-stock items. Lookup is read-only and does not add to the cart.
- During checkout, this private panel shows **estimated gross profit**: the cart total after line and order discounts minus quantities multiplied by recorded purchase costs. A loss is displayed as a negative amount. The saved-sale panel uses the original sale cost snapshots. Shop expenses and subsequent returns are excluded from this per-sale figure; use Reports for period profit.
- Staff details are hidden until tapped. Use **Hide** before showing the phone to a customer. Android's secure-window flag blocks screenshots/casting of the private dialog, and it closes when the app leaves the foreground. Anyone who can use the unlocked app can open it; use the local app PIN to control app access. Camera price lookup pauses checkout scanning until you deliberately tap **Scan next item**, so a barcode still in view cannot accidentally enter the cart when you close the panel.
- In **Products → Add/Edit product**, choose **Scan existing barcode** to fill the manufacturer barcode field without typing. Leading zeros remain intact. Review all fields and Save; global barcode uniqueness and existing product-association rules still apply.
- Every sale and refund receipt preview, PDF, Android print and thermal print includes its saved receipt number and a QR containing **only that number**. Existing saved receipts get a QR when reprinted; no transaction is recreated. Costs, profit and private label codes never enter customer receipts or receipt QR payloads.
- Start a return only in **Returns & refunds**. Choose **Scan receipt QR** or enter the original sale number, then **Find original sale → Select items to return**. Scanning finds a saved transaction and requires review; it never issues a refund automatically. Unknown receipts, other-shop receipts and refund-receipt numbers cannot start a new return. A refund receipt prints its original sale number for later lookup. Checkout and saved-sale screens have no return/refund action.

## Customize the app without programming

Open **Settings → Customize app**, or the **Customize app** link at the bottom of the dashboard. This editor controls the existing app's layout and appearance:

- Choose large cards, a two-column grid, or a compact list.
- Move every dashboard item with **Up / Down**, or choose **Put Checkout first**. The shop card and today's sales can move too.
- Show/hide dashboard items and give shortcuts your own nicknames. **All tools** and Settings remain available even if you hide everything; hiding a shortcut does not disable its feature or delete records.
- Choose teal, blue, purple or warm amber, light/dark/phone appearance, and standard/larger/largest text. Android accessibility text scaling still applies.
- Set whether checkout opens the scanner, product list or cart. All checkout tabs remain available; a current unpaid cart keeps its state when appearance changes.
- Review the preview and choose **Apply changes**. **Reset customization** resets the preview first and needs Apply to take effect. Leaving an unsaved preview asks whether to discard it.
- **Save layout file / Load layout file** exports or imports a small validated JSON layout. Loading changes only the preview until you apply it. The file contains no product, stock, sale, cost-key or PIN records.

Customizations are device-local and shared between the two shop workspaces, with Live/Demo isolated by their application sandboxes. Room remains the primary business database; display preferences use private SharedPreferences with checked disk commits. Business backup/restore keeps the receiving device's current layout. Save a separate layout file to use it on another phone. Receipt branding/prices, stock rules, payment calculations, printer settings and PIN settings have their own controls and are not edited by a layout file. Adding a completely new business feature still requires an app update; this is an in-app editor for the existing tools.

The customization controls, saved profiles and unchanged business records were verified by unit, native Room/preferences and Compose UI tests. Synthetic dashboard and dark-checkout screenshots were retained and visually reviewed; see the linked verification reports.

## Open, build and install

Use Android Studio Ladybug 2024.2.1 or newer compatible with AGP 8.7.3, JDK 17, Android SDK Platform 35, Build Tools 35.0.0, platform-tools, and Gradle 8.9. All dependency versions are pinned in `gradle/libs.versions.toml`; they are the versions used by the earlier successful builds. Open the repository root in Android Studio, select JDK 17 for Gradle, let dependencies sync, and choose `liveDebug` or `demoDebug` in Build Variants. The app supports Android 8.0/API 26 and later. Development dependencies require downloading once; the installed application and all tests run without a service or backend.

The small checked-in launch scripts bootstrap the official Gradle wrapper with published checksum verification. A binary wrapper JAR is deliberately not required. Android Studio can also use an installed Gradle 8.9 distribution. If Gradle's artifact download is unavailable, follow `scripts/bootstrap-gradle.sh` or `scripts/bootstrap-gradle.ps1`; do not change dependency versions at random or enable insecure repositories.

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
2. Open **Products → Add product**. Enter name, category, variant, optional existing manufacturer barcode (scan or type), cost, selling price, opening quantity and low-stock alert level. SKU is assigned once (`OP-000001`, `FF-000001`). Different tracked sizes/colours are separate products. Products, variants and barcode identities form a shared registry; prices, costs and quantities belong to the chosen shop. Use **Find existing product by barcode / SKU** to associate a registered product with the other shop rather than create duplicate barcodes.
3. In **Inventory**, search stock and use **Adjust stock** with a reason for deliveries, damage or corrections. Each adjustment writes a stock movement and audit event. Negative balances are rejected; opening quantity is not re-edited later.
4. In **Checkout**, use the dedicated **Scan** section and point the camera at a saved product's barcode. Camera access is requested only when needed. The offline CameraX/ZXing decoder supports common retail formats and Code 128. A deliberate **Scan next item** action prevents a held barcode from repeatedly adding units. **Cart** and **Products** remain separate sections, with manual lookup available for unbarcoded goods or denied camera access. Unknown codes never invent a product or price.
5. Review quantities/discounts, enter cash or choose **Exact cash**, then **Complete cash sale**. The app validates current prices and available stock, saves sale/item cost and price snapshots, deducts inventory and allocates a receipt in one Room transaction. Repeated or uncertain submissions recover the same saved transaction. Only cash payments are supported.
6. **Sales history → saved sale → View / print receipt** reopens the saved record. Preview/share a PDF or use Android printing (including Save as PDF). The same sale can be reprinted without changing stock or posting another transaction.
7. **Returns & refunds → Scan receipt QR / Find original sale → Select items to return** selects original sold quantities, quantity to restock and a required reason. Review the displayed cash refund, then confirm. Refunds preserve original prices, line discounts and order discounts, including partial-return rounding. Returned quantities cannot exceed the original quantity. Mark damaged/unsaleable units with zero restock. A unique refund reference, original-sale link, refund receipt, stock movements and audit trail are saved together. This section also lists saved returns and their receipts.
8. **Expenses** records actual positive amounts by category, note and date. An incorrect entry can be voided with a reason; the original row and audit history are retained. Repeated submissions do not duplicate expenses.
9. **Reports** shows today, seven days, current month or custom dates in Pakistan time. Sales and refunds use their posting dates; expenses use their entered expense date. Totals include net sales, recorded cost of goods, gross profit, expenses and operating profit, plus product/category breakdowns and CSV export. Only restocked returns reverse cost of goods; unsaleable returned units keep their original cost. Current inventory cost value and low-stock count are explicitly separate from the historical report period. Operating cash movement excludes opening float, owner drawings and other unrecorded cash. This is an operational retail report, not tax filing or a complete general ledger. Legacy sales without category snapshots are labelled honestly.
10. **Settings → Local app PIN & lock** optionally sets a 6–12 digit device-local PIN with 1–30 minute inactivity lock. Persistent wrong-attempt delays discourage guessing. Phone device credentials can reset a forgotten app PIN. Use a secure Android screen lock. The app PIN is not a cashier account, does not encrypt all SQLite files, and is excluded from portable business backups. Business restore retains the receiving phone's PIN and clears old unpaid carts.
11. **Settings → Backups & restore** saves a verified encrypted backup through Android's file picker. Keep an off-device copy and its passphrase separately. A restore first validates a staged snapshot and requires a newly saved, verified recovery backup and typed confirmation before replacing both shops. See the guide before use.

## Private cost-code labels

Set your own ten different uppercase letters for digits 0–9 in **Settings → Private cost-code key**, then open a product's **Barcode label** action. Internal Code 128 labels contain a stable SKU, a secret letter-coded cost snapshot and a unique revision. The app looks up the registered alias; it never trusts arbitrary scanned text as a selling price. Manufacturer barcodes stay unchanged. A code is a staff convention that hides a plain cost from casual reading, not cryptographic secrecy from someone who learns your letter key. Keep the key private and use the local app PIN if other people handle the phone.

Labels do not print a plain purchase cost or the letter key. They show the selected shop, product/SKU, Code 128 and readable coded value, with no invented official logo. Old labels retain their original mappings after cost/key changes; checkout and staff price lookup use the product's current saved cost and selling price. New labels use random revision identities to avoid reusing a lost alias after restoring older data. Demo labels have a `DEMO~` prefix and cannot be mixed with Live labels. Camera/print acceptance with real small labels remains required.

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
