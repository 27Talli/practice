# Delivery verification — Offer Point POS 1.2.0-rc1

[GitHub Actions run 40541624294](https://github.com/27Talli/practice/actions/runs/40541624294) completed successfully on **27 October 2026** for source **`83b2976f71632cc1461ee9811d6e45d0a1e709c4`**. App version code: **8**. Room database: **6** (unchanged).

| Check | Executed result |
| --- | --- |
| Live / Demo debug APKs | Built; APK signatures verified |
| JVM tests, Live | **160 passed**, 0 failures/errors/skipped |
| JVM tests, Demo | **160 passed**, 0 failures/errors/skipped |
| Live Android lint | Passed |
| Android 35 emulator | **139 passed**, 0 failures/errors/skipped |
| Release-signing path | Isolated unpublished TEST-key Demo release built and verified; key/APK removed |
| Permanent owner signing | Not configured; no production release published |
| Supplemental database checks | 20 entities / 55 DAO queries and lineage, barcode and stock constraints passed |
| Host rescue tool | Schema 3–6 conversion/encryption/decryption fixtures passed |
| Room schema | Version 6 byte-for-byte unchanged |

## New behavior exercised

JVM tests cover exact gross profit with quantities and all discounts, zero-price/loss-making sales, missing/negative costs and overflow. Saved profit uses original cost snapshots; estimates reject stale selling prices. Read-only staff lookup accepts out-of-stock products, preserves old label costs separately, rejects other-shop labels, clears private state when hidden, and never posts a sale or changes stock. Checkout stays paused after a private price lookup until Scan next item. Product barcode input preserves leading zeros and validates bounds/control characters.

Receipt tests encode and actually decode the public number offline. Receipt QR mode rejects product barcodes; checkout ignores receipt QRs. Receipt lookup accepts only original-sale numbers for the selected shop, handles unknown/failing lookups, cancels stale results after editing, and never posts a refund automatically.

Native tests render sale/refund QRs from A4, 58 mm and 80 mm PDFs and decode the rendered pixels. They reconstruct actual ESC/POS raster bytes for both printer widths and decode the QR, and check one complete QR after the last line on long receipts. Room tests reopen a saved database, find exact receipts without crossing shops, reject injection-like references, preserve original profit after product cost/price edits, and verify no extra business writes.

The real Compose navigation tests complete a sale with no refund action on its saved screen, preview its receipt QR, start a return only in Returns & refunds using the original receipt number, and open the saved refund receipt/QR. Additional UI tests reveal/hide private purchase costs and profit while preserving an unpaid cart, open/cancel product barcode capture without saving anything, and open receipt scanning/reject a wrong-shop reference in the dedicated returns screen. Existing customization, PIN/cart, restore-generation, stock, report, backup, migration and receipt tests remain covered. Synthetic dashboard/dark-checkout captures are retained in reports and were visually inspected; the new staff icon is visible and the checkout keeps purchase costs/profit hidden.

No runtime dependency, app permission or business schema was added. The APK still has **no Internet permission**. Customer receipt models and QR payloads contain no purchase costs, profit, private label codes or PINs.

## APK downloads

- [Live APK ZIP](https://github.com/27Talli/practice/actions/runs/40541624294/artifacts/12752855177)
- [Demo APK ZIP — for testing](https://github.com/27Talli/practice/actions/runs/40541624294/artifacts/12752496448)
- [Test/lint reports, schemas and emulator logs](https://github.com/27Talli/practice/actions/runs/40541624294/artifacts/12751971849)

Sign in to GitHub if prompted, extract the ZIP, then install its APK on **Android 8.0/API 26 or newer**. Artifacts expire **25 January 2027**. Live and Demo have separate application IDs/data. APK artifacts publish after the native test step succeeds.

SHA-256 of the APK files inside the ZIPs:

```text
7ab8859b1e3f60fd5237dcae3cb3782b1f1f8e2ff605025417e533e9737d69e4  app-live-debug.apk
bc89a45c4b72a3fcd306b32c9dcfb05a76b8fda75f66f96ab7d60ba6d43c7072  app-demo-debug.apk
```

Both exact CI APKs and the reports ZIP were downloaded and their SHA-256 hashes checked against the workflow/artifact records. APK v2 signatures and version/API metadata were independently verified locally. The downloaded XML reports confirm 160 tests per JVM variant and 139 native tests, with no failures/errors/skips.

**Upgrade limitation:** the current debug signing certificate SHA-256 is `a4cbee2babf96f3427dd4ee83727bd03304118ef1a4dbb18297ea7d1704d1953`. The prior 1.1.0-rc1 APK uses `d92b1a3bd3ebb591909e1040ce9101d03aa9880e97e6b3f4d22c5d0055e1df3f`. These differ, so Android cannot install this APK as an in-place update over that build. Do not uninstall or clear an existing business installation; preserve and verify its data and follow [safe upgrades](docs/SAFE_UPGRADES.md) first. Retaining the original signing key is required for routine in-place updates.

## Reproduce and install

```sh
python3 scripts/test_rescue_tool.py
python3 scripts/check_schema.py
./gradlew :app:assembleLiveDebug :app:assembleDemoDebug \
  :app:testLiveDebugUnitTest :app:testDemoDebugUnitTest \
  :app:lintLiveDebug :app:assembleLiveDebugAndroidTest
# Use an isolated connected test phone/emulator, not an installed business database:
./gradlew :app:connectedLiveDebugAndroidTest
adb install -r app/build/outputs/apk/demo/debug/app-demo-debug.apk
```

Local builds, 160 JVM tests per variant, test-APK compilation, lint, signatures, version/permission inspection and supplemental SQL checks passed with checksum-verified Corretto JDK 17, SDK/Build Tools 35 and Gradle 8.9. Local `/dev/kvm` is unavailable; the linked GitHub-hosted Android 35 x86_64 emulator executed the actual 139 native tests using KVM and Temurin JDK 17. Dependency versions remain pinned and unchanged.

This is a tested **debug evaluation candidate**. Owner-retained signing secrets remain absent. Never uninstall or clear a business installation to work around a signing mismatch; follow [safe upgrades](docs/SAFE_UPGRADES.md). Physical camera/printed QR, printer/width/cutter, storage-provider, owner-key and ordinary-phone acceptance remain in [SHOP_ACCEPTANCE.md](docs/SHOP_ACCEPTANCE.md). Secure-window/private-view behavior still needs checking on that phone; staff must Hide the panel before showing the screen to a customer. Anyone with access to the unlocked app can open staff tools. The current purchase cost is the product's recorded shop cost, not a batch/FIFO valuation. Final sale profit uses recorded sale costs, excludes expenses and later refunds, and does not replace period reports.

Dashboard customization is retained, including ordering, hidden tools, colors, text and default checkout section. Layout files do not change business data. Multi-device synchronization, electronic/tax integrations and printer-specific drivers remain outside this offline single-device cash workflow.
