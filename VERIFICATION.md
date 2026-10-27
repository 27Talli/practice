# Current candidate: 1.0.0-rc1

Verification is in progress. The changes include schema 6, partial returns and controlled restocking, refund receipts, audited expenses/voids, financial reports/CSV, low-stock alerts, device-local PIN lock and standard direct thermal printing. Builds and native tests have not yet run for this candidate. Earlier results below apply only to the stated historical source.

Local supplemental schema/SQLite checks and host rescue-tool tests pass; Android SDK/Gradle are unavailable in this workspace. GitHub Actions will compile, lint and run both JVM variants plus the Android emulator suite.

# Delivery verification

## Verified Milestone 5 (0.5.0)

[GitHub Actions run 40494409192](https://github.com/27Talli/practice/actions/runs/40494409192) completed successfully on **27 October 2026**, building Android source commit `fb9ff19982eb7caa959217ee13b6ce2cd537a89b`.

| Check | Actual result |
| --- | --- |
| Live debug APK | Built; APK v2 signature verified |
| Demo debug APK | Built; APK v2 signature verified |
| JVM tests, Live | 101 passed; 0 failures/errors/skipped |
| JVM tests, Demo | 101 passed; 0 failures/errors/skipped |
| Live Android lint | Passed |
| Android 35 emulator tests | 94 passed; 0 failures/errors/skipped |
| Release signing path | Demo release built and signature verified with an isolated unpublished TEST key |
| Owner-retained production signing | Not configured; no production release published |
| Host rescue-tool tests | Actual legacy schemas 3/4 converted, encrypted/decrypted and byte-verified successfully |

The JVM suite now includes authenticated multi-chunk encryption, wrong-password/tamper/truncation/reordering rejection, empty/final chunk authentication, passphrase validation/wiping, backup ViewModel recovery and workspace write-gate concurrency. Existing price, quantity, checkout, camera decoding, label and receipt tests remain.

Native Android tests use real Room/SQLite, cryptography, a test-only document provider, and the actual exported schemas. Backup coverage includes encrypted read-verified export, both shops, sales retry identities, stock/history/cost keys/label/receipt settings, reopening after restore, recovery-backup reuse, wrong passwords and corrupted/unsupported inputs, barcode collisions, broken foreign keys, invalid stock, Live/Demo separation, rejected recovery destinations, forced insert rollback, concurrent changes during recovery I/O, discarded/tampered previews, exact Long.MAX_VALUE prices/quantities, and cancellation after commit. A host-generated encrypted legacy fixture is restored on Android and its historical sale remains idempotent.

Restore tests also prove that previously issued SKU/receipt/row counters retain higher values when older records are restored. New cost labels have random numeric revisions; an alias lost by restoring older data is not recreated. A native PDF test rasterizes a revised Code 128 label and decodes the exact value. The Compose suite verifies that a dataset generation change resets navigation/unpaid carts, in addition to completing checkout and opening saved receipts. No business data installation is used by tests.

Migration tests validate actual historical schemas through 1 → 2 → 3 → 4 → 5. Schema 5 adds only the persistent dataset-generation field; there is no destructive fallback. The generated `5.json` was recovered from this successful run and SHA-256 verified. Historical schema files 1–4 remained unchanged.

## APK downloads

- [Live APK ZIP](https://github.com/27Talli/practice/actions/runs/40494409192/artifacts/12730582455)
- [Demo APK ZIP — recommended for testing](https://github.com/27Talli/practice/actions/runs/40494409192/artifacts/12729982795)
- [Test/lint reports, schemas, checksums and emulator logs](https://github.com/27Talli/practice/actions/runs/40494409192/artifacts/12729284663)

Extract the ZIP and install its `.apk` on Android **8.0 or newer**. GitHub may require sign-in. Artifacts are retained until **25 January 2027**. Live and Demo have separate application IDs/data; their backups cannot be mixed.

SHA-256 of the APK files inside the ZIPs:

```text
f0830de1c2cd962adc6000739760210e88137ce8037234be7be5fd82685f63a6  app-live-debug.apk
5911153628f38891c282c7d235adc73fd51b29c11422ce94b55cd930e923838a  app-demo-debug.apk
```

## Reproduction

The workflow uses Java 17, Gradle 8.9, Android SDK/Build Tools 35 and the unchanged pinned dependency catalog. Backups use platform cryptography/SQLite and add no dependency or permission.

```sh
python3 scripts/test_rescue_tool.py
python3 scripts/check_schema.py
./gradlew :app:assembleLiveDebug :app:assembleDemoDebug \
  :app:testLiveDebugUnitTest :app:testDemoDebugUnitTest \
  :app:lintLiveDebug :app:assembleLiveDebugAndroidTest
./gradlew :app:connectedLiveDebugAndroidTest
```

The host test regenerates a synthetic encrypted test asset. An additional host-only follow-up explicitly closes SQLite connections before temporary-file cleanup; its host suite was rerun successfully on local Java 21/Python. It changes neither Android runtime/test source nor the backup format. Documentation and schema follow-ups contain no APK runtime changes. The Android source above is the exact code used for the APKs and native test results.

## Signing and practical limits

The release signing path compiled and verified with a temporary TEST key, and that key/APK were deleted without publication. The workflow reported that the four owner signing secrets are absent. Therefore the downloadable APKs remain **debug/evaluation builds with temporary debug keys**. Do not describe them as stable retained-key production releases. Setup is concrete and documented in [SAFE_UPGRADES.md](docs/SAFE_UPGRADES.md), with local key-generation and CI-staging scripts. No signing key is committed, cached or published.

**Do not uninstall or clear business data to resolve a signing mismatch.** Existing debug keys cannot be recreated from an APK certificate. The local rescue tool supports earlier debuggable database versions 3/4 and current version 5, encrypts its output and never clears or uninstalls the source app. Independently verify a restore on another device/local emulator before removing any old installation. No real phone data was accessed during development.

Physical ADB/USB rescue, Android storage-provider behavior, off-device file retention, printer output and camera/printed-label quality require hardware acceptance. The emulator suite ran on Android 35 x86_64 with KVM. Native crypto/restore and host-tool interoperability passed; a production owner key/deployment and physical storage/printer devices were not available.

Backup format 1 supports schema 5, up to 128 MiB plaintext/two million rows and the documented field/sale limits. Restoration replaces both shops, requires a new verified recovery copy, preserves higher previously used counters, and clears unpaid carts. Passphrases are not recoverable or persisted. Automatic backup scheduling and repair of a physically corrupt SQLite database remain deferred. Staff PINs, returns/refunds, financial reports, direct thermal pairing, expense entry, multi-device sync and official logos are not implemented.

The workspace lacks an Android SDK/installed Gradle distribution; GitHub Actions supplied the actual build and emulator. Local supplemental checks passed for all 18 reflected entities/37 DAO queries, XML/catalog parsing, Python/shell syntax and host rescue conversion/encryption.

## Earlier baseline

[Milestone 4 run 40458420557](https://github.com/27Talli/practice/actions/runs/40458420557), source `8415d712892fd90618091c93a328afd8dc985f40`, passed both APKs, 87 JVM tests per variant, lint/signatures and 75 Android tests. Milestone 3 passed 72 JVM tests per variant and 62 Android tests.
