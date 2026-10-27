# Delivery verification

## Milestone 4 (0.4.0) — verification pending

Customer-safe receipts, offline PDF sharing, Android printing, per-shop receipt settings and an additive Room 3 → 4 migration are implemented. The new workflow has not yet completed. Results below describe the prior verified Milestone 3 build only.

## Verified Milestone 3 (0.3.0)

[GitHub Actions run 40448504832](https://github.com/27Talli/practice/actions/runs/40448504832) completed successfully on **27 October 2026**, building source commit `18bbdeea0b80574abcda7d1d5ef3a728363b335e`.

| Check | Actual result |
| --- | --- |
| Live debug APK | Built; APK v2 signature verified |
| Demo debug APK | Built; APK v2 signature verified |
| JVM tests, Live | 72 passed; 0 failures/errors/skipped |
| JVM tests, Demo | 72 passed; 0 failures/errors/skipped |
| Live Android lint | Passed |
| Android 35 emulator tests | 62 passed; 0 failures/errors/skipped |

The JVM suite covers exact prices/quantities, checkout arithmetic and recovery, camera-frame strides/rotation, real ZXing optical decoding, duplicate-scan guards, scan-to-cart state, cost-code validation/overflow, and print-safe label content. It runs without network at test runtime.

The Android suite runs real Room and SQLite, including shop/product/stock isolation, transaction rollback and idempotency, database reopen persistence, a complete Compose cash checkout, and historical-schema upgrades. New label tests verify global alias uniqueness, private-key persistence, immutable old labels after key/cost changes, manufacturer barcode compatibility, conflicting UPC/EAN rejection, Demo/Live label namespaces, and atomic rollback. A native Android PDF test exports 13 labels, verifies pagination and FileProvider access, rasterizes the first label, and decodes its exact Code 128 value through the scanner decoder.

Migration tests use the actual exported schemas: version 1 → 2 followed by opening version 3, and a separate validated version 2 → 3 upgrade retaining sales, retry keys, receipt counters, expenses, shop selection, barcodes, audit history and stock. The current generated `3.json` was recovered from the successful run, verified by SHA-256, and committed alongside unchanged `1.json` and `2.json`. No destructive migration fallback is used.

## APK downloads

- [Live APK ZIP](https://github.com/27Talli/practice/actions/runs/40448504832/artifacts/12709715428)
- [Demo APK ZIP — recommended for testing](https://github.com/27Talli/practice/actions/runs/40448504832/artifacts/12709840012)
- [Test/lint reports, schemas, checksums and emulator logs](https://github.com/27Talli/practice/actions/runs/40448504832/artifacts/12709602532)

Extract the ZIP and install its `.apk` on Android **8.0 or newer**. GitHub may require sign-in. Artifacts are retained until **25 January 2027**; the workflow can create a fresh build later. Demo installs separately, has a persistent Demo banner, visibly marked labels and a separate barcode namespace, and does not mix sample stock/sales with Live.

SHA-256 of the APK files inside the ZIPs:

```text
6bfe4037d26d5628ecd448b73a95c6a90a4683c15f6c1aeb6091c71fdaf5d8b4  app-live-debug.apk
95abba16be54949fb5e49e72f377733710303829f9d21e9a91bec8b36f5647ee  app-demo-debug.apk
```

## Reproduction and practical limits

The workflow uses Java 17, Gradle 8.9, Android SDK/Build Tools 35 and the pinned dependency catalog, including CameraX 1.4.1 and ZXing core 3.5.3. These dependencies resolved and compiled in the successful build.

```sh
./gradlew :app:assembleLiveDebug :app:assembleDemoDebug \
  :app:testLiveDebugUnitTest :app:testDemoDebugUnitTest \
  :app:lintLiveDebug :app:assembleLiveDebugAndroidTest
./gradlew :app:connectedLiveDebugAndroidTest
```

The device suite ran on a headless Android 35 x86_64 emulator with KVM. **Physical camera focus, lighting, torch, permission recovery and real printed-label readability still need acceptance on the shop phone/printer.** Barcode decoding itself and rendered PDF readability were automated. Follow README's airplane-mode acceptance checklist.

These are **debug/evaluation builds**. GitHub-hosted runners generate ephemeral debug signing keys; an existing installation may reject a new APK as an update. **Do not uninstall or clear business data to resolve a signing mismatch.** Production updates require a securely retained private release signing key and a data-preserving upgrade process.

The cost code is staff shorthand obfuscation, not encryption; no staff PIN exists yet. Its private per-shop key stays in Room and is never printed. Old labels retain cost snapshots, while posted sales use current catalog cost and selling price. There is no per-batch cost accounting. Receipt documents/printers, returns, financial reports, expense entry and backup/restore remain deferred.

The local workspace has no Android SDK/Gradle/JDK compiler; GitHub Actions supplied the actual build and emulator. Local supplemental checks passed for all 17 reflected entities and all 34 DAO queries, plus XML/catalog parsing and Python/shell syntax. They are supplementary to the successful Android checks above.

## Earlier baseline

[Milestone 2 run 40355529395](https://github.com/27Talli/practice/actions/runs/40355529395), source `4de91ccdfc52ed5b180f07e50b8444930445d2c4`, passed both APK builds, 37 JVM tests per variant, lint, signatures and 41 Android tests on 26 October 2026. Milestone 1 build [40327606654](https://github.com/27Talli/practice/actions/runs/40327606654) compiled and checked APKs but did not execute device tests.
