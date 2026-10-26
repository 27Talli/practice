# Delivery verification

This file records checks actually executed in the development workspace on 26 October 2026.

- Workspace began without Android SDK, Gradle, a Java compiler, or an emulator.
- OpenJDK 21 runtime was present; a build JDK/SDK must be provisioned.
- No Android device or `/dev/kvm` was available at initial inspection.
- Network approval for toolchain/dependency downloads was canceled. The Android SDK, JDK compiler, and Gradle distribution therefore remain unavailable.

Passed locally:

- `sh -n gradlew scripts/bootstrap-gradle.sh` — shell launchers parse.
- All five Android XML manifests/resources parse using Python's XML parser.
- The Gradle version catalog parses and all library/plugin version references resolve within that catalog. This does not establish remote artifact availability.
- `python3 scripts/check_schema.py` — all 14 entities reflected from Kotlin annotations, exact foreign-key parent uniqueness, covering child indices, compilation of 20 actual DAO SQL queries using SQLite 3.53.1, cross-shop/cross-sale/wrong-product rejection, global barcode uniqueness, nonnegative stock update behavior, shop stock isolation, literal search, leading-zero barcode, and clean foreign-key check.

Attempted:

```sh
./gradlew :app:assembleLiveDebug :app:assembleDemoDebug \
  :app:testLiveDebugUnitTest :app:testDemoDebugUnitTest \
  :app:lintLiveDebug :app:assembleLiveDebugAndroidTest
```

Result: **exit 7 during the wrapper download**, before Gradle or Kotlin compilation started:

```text
Downloading the official Gradle 8.9 wrapper (first run only)…
curl: (7) Failed to connect to proxy port 8080 after 0 ms: Could not connect to server
```

Consequently:

- **No successful APK build. No APK is included.**
- **No JVM test or Android lint result.** The 12 JVM tests were written but not executed.
- **No Room annotation-processing or instrumentation result.** The 19 instrumented tests were written but not executed. No device/emulator was present.
- The full pinned dependency set could not be fetched/verified. No claims are made about a successful Gradle sync.
- The official wrapper JAR and generated Room schema JSON could not be downloaded/generated. The included source bootstrap launchers obtain and checksum-verify the official wrapper on a developer's first build; KSP generates the schema during a successful build.
- Supplemental SQLite checks verify schema/query design; they are **not** a substitute for Room, Android, or Kotlin tests.

Exact reproduction commands are in README.md.
