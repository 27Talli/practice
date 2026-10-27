# Delivery verification

## Verified Milestone 4 (0.4.0)

[GitHub Actions run 40458420557](https://github.com/27Talli/practice/actions/runs/40458420557) completed successfully on **27 October 2026**, building source commit `8415d712892fd90618091c93a328afd8dc985f40`.

| Check | Actual result |
| --- | --- |
| Live debug APK | Built; APK v2 signature verified |
| Demo debug APK | Built; APK v2 signature verified |
| JVM tests, Live | 87 passed; 0 failures/errors/skipped |
| JVM tests, Demo | 87 passed; 0 failures/errors/skipped |
| Live Android lint | Passed |
| Android 35 emulator tests | 75 passed; 0 failures/errors/skipped |

The new JVM tests cover exact receipt amounts, discounts/change, zero and maximum totals, private-cost exclusion, immutable sale projections, shop matching, Demo flags, Pakistan timestamps, preferences validation and receipt/settings ViewModel recovery. Earlier checkout, barcode, cost-code and inventory tests remain in the suite.

The Android suite uses real Room/SQLite and native Android PDF APIs. Receipt tests export and rasterize A4/58 mm/80 mm PDFs, check pagination/margins, repeated Demo markers and sale identity, long-name/amount wrapping, FileProvider access and cancellation. Print-engine tests reflow after media changes, write only requested pages into a valid PDF and report cancelled layout/writes. The thin Android PrintDocumentAdapter forwards the OS-owned callbacks to that engine; a physical printer/Android print service was not exercised in CI.

Database tests cover independent persistent shop preferences, validation/auditing, forced rollback and repeated exports without changed sales, stock, ledger, audit history or sale counters. Catalog edits do not change receipt item snapshots. The existing Compose test now completes cash checkout, reopens its saved history, opens the receipt preview, verifies share/print actions and checks unchanged sale count/stock.

Migration tests use actual historical Room schemas through 1 → 2 → 3 → 4. The explicit 3 → 4 test preserves existing sales/retry identities, selection, inventory, counters, cost-code keys and old label aliases, then generates a receipt for the historical sale. Current generated `4.json` was recovered from the successful run and verified by SHA-256; historical `1.json`, `2.json` and `3.json` were unchanged. No destructive fallback is configured.

## APK downloads

- [Live APK ZIP](https://github.com/27Talli/practice/actions/runs/40458420557/artifacts/12713711535)
- [Demo APK ZIP — recommended for testing](https://github.com/27Talli/practice/actions/runs/40458420557/artifacts/12713641865)
- [Test/lint reports, schemas, checksums and emulator logs](https://github.com/27Talli/practice/actions/runs/40458420557/artifacts/12713736704)

Extract the ZIP and install its `.apk` on Android **8.0 or newer**. GitHub may require sign-in. Artifacts are retained until **25 January 2027**; the workflow can create fresh builds. Live and Demo have separate IDs/databases; Demo labels and receipts are visibly marked.

SHA-256 of the APK files inside the ZIPs:

```text
7b8012a4f3e44de054431f23dd06eeb5be4ebf2ca8fd0a4802020c8a50aff444  app-live-debug.apk
216fcf67512ff60fb98edfc1aa89ef66f0cca7da56d71fc41267197e5f4341d1  app-demo-debug.apk
```

## Reproduction and practical limits

The workflow uses Java 17, Gradle 8.9, Android SDK/Build Tools 35 and the existing pinned dependency catalog. Receipt PDF/printing uses Android APIs and adds no dependency or permission.

```sh
./gradlew :app:assembleLiveDebug :app:assembleDemoDebug \
  :app:testLiveDebugUnitTest :app:testDemoDebugUnitTest \
  :app:lintLiveDebug :app:assembleLiveDebugAndroidTest
./gradlew :app:connectedLiveDebugAndroidTest
```

The device suite ran on a headless Android 35 x86_64 emulator with KVM. **Real printer output, Android print-service compatibility, physical paper sizing, camera focus/lighting and printed-label readability need acceptance on the shop hardware.** Receipt PDF content/rendering and the print engine were verified automatically. Direct Bluetooth/USB ESC/POS pairing is deferred until the printer model/connection is known; 58/80 mm PDF pages are not raw thermal-printer commands.

These are **debug/evaluation builds**. GitHub-hosted runners generate ephemeral debug signing keys, so an existing installation may reject a new APK as an update. **Do not uninstall or clear business data to resolve a signing mismatch.** Production updates require a securely retained private release signing key and a data-preserving upgrade process. Room migration success does not override Android's signing-key requirement.

The cost code remains obfuscation, not encryption; there is no staff PIN. Receipts never print purchase costs or private code keys. Receipt item/payment values come from saved sale snapshots; shop branding and the optional footer use current local shop/settings records. PDF cache files are regenerable and are not backups. Returns/refunds, financial reports, expense entry and backup/restore remain deferred. Controlled backups and stable signed upgrades are the recommended next milestone.

The workspace has Java 21 but lacks the Android SDK and an installed Gradle distribution; GitHub Actions supplied the actual JDK 17 build/emulator. Supplemental local checks passed for all 18 reflected entities and 35 DAO queries, XML/catalog parsing and Python/shell syntax. These supplement the actual Android checks above.

## Earlier baseline

[Milestone 3 run 40448504832](https://github.com/27Talli/practice/actions/runs/40448504832), source `18bbdeea0b80574abcda7d1d5ef3a728363b335e`, passed both APK builds, 72 JVM tests per variant, lint, signatures and 62 Android tests. [Milestone 2 run 40355529395](https://github.com/27Talli/practice/actions/runs/40355529395) passed 37 JVM tests per variant and 41 Android tests.
