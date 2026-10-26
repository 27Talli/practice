# Delivery verification

## Verified Milestone 2 build (0.2.0)

[GitHub Actions run 40355529395](https://github.com/27Talli/practice/actions/runs/40355529395) completed successfully on 26 October 2026, building source commit `4de91ccdfc52ed5b180f07e50b8444930445d2c4`.

| Check | Actual result |
| --- | --- |
| Live debug APK | Built; APK v2 signature verified |
| Demo debug APK | Built; APK v2 signature verified |
| JVM tests, Live | 37 passed, 0 failed/errors/skipped |
| JVM tests, Demo | 37 passed, 0 failed/errors/skipped |
| Live Android lint | Passed |
| Android emulator tests | 41 passed, 0 failed/errors/skipped |

The Android suite includes 19 original foundation tests, 20 checkout transaction tests, one historical-schema migration test, and one end-to-end Compose checkout test. The screen test selects a real catalog item, completes cash payment, checks the saved confirmation and stock deduction, and reopens the sale through history using isolated real Room repositories.

The transaction tests cover exact amounts/discounts/change, immutable price/cost/name/barcode snapshots, shop isolation, invalid and stale data rejection, concurrent duplicate requests, competing stock deductions, forced ledger failure with full rollback, persistent retry after database reopen, receipt counters, and Karachi day boundaries/total overflow.

The migration test uses the actual exported Milestone 1 schema, validates version 2 with Room, verifies preservation of existing business tables, selection, and audit history, then completes a checkout. Both actual generated schema versions are committed under `app/schemas` for future upgrade tests.

## APK downloads

- [Live APK ZIP](https://github.com/27Talli/practice/actions/runs/40355529395/artifacts/12664709915)
- [Demo APK ZIP](https://github.com/27Talli/practice/actions/runs/40355529395/artifacts/12665871990)
- [Test/lint reports, schemas, checksums, and emulator logs](https://github.com/27Talli/practice/actions/runs/40355529395/artifacts/12666745496)

Extract the ZIP and install its `.apk` on Android 8.0 or newer. GitHub may require sign-in. These artifacts are retained until 24 January 2027; the workflow can create a fresh build later.

APK SHA-256 (the APK inside the ZIP, not the ZIP itself):

```text
9f2d0931d7adb16681d91fe9469bdb67165ec8816c3264e0e70058c1d6f5b78e  app-live-debug.apk
6eeabb8d773783299b6b8b96ba401cc16076ec8de68bda7404c43c861e4ad292  app-demo-debug.apk
```

## Reproduction and limits

The workflow runs these tasks with Java 17, Gradle 8.9, Android SDK/Build Tools 35, and the pinned dependency catalog:

```sh
./gradlew :app:assembleLiveDebug :app:assembleDemoDebug \
  :app:testLiveDebugUnitTest :app:testDemoDebugUnitTest \
  :app:lintLiveDebug :app:assembleLiveDebugAndroidTest
./gradlew :app:connectedLiveDebugAndroidTest
```

The device suite ran on a headless Android 35 x86_64 emulator using KVM and Android's SQLite engine. This is not a physical-phone acceptance test. Follow README's manual acceptance checklist on the shops' phones.

Both APKs are **debug/evaluation builds**. GitHub-hosted runners generate debug signing keys that may differ between runs. An existing Milestone 1 installation may reject a new APK as an update because its key differs. **Do not uninstall or clear business data to work around that error.** Reliable production updates require a retained private release signing key and a verified data-preserving upgrade process.

The local cloud workspace lacks Android SDK/Gradle/JDK compiler; GitHub Actions supplied the actual build and emulator environment. Tests do not require Internet at runtime; dependency and SDK downloads are development setup only.

## Earlier baseline

Milestone 1 build [40327606654](https://github.com/27Talli/practice/actions/runs/40327606654), source `f30ef86ccab5e1ff273d8e4e663e2712db31ccd8`, passed both APK builds, both JVM suites, lint, test APK compilation, and signature checks; it did not execute device tests. The first Milestone 2 run [40354132649](https://github.com/27Talli/practice/actions/runs/40354132649) passed 37 JVM tests per variant and 40 database/migration device tests before the final screen test was added.
