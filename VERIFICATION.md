# Delivery verification

## Milestone 1 baseline

The original Android build succeeded on GitHub Actions run [40327606654](https://github.com/27Talli/practice/actions/runs/40327606654), source commit `f30ef86ccab5e1ff273d8e4e663e2712db31ccd8`. Both debug APKs, both JVM suites, live lint, instrumentation APK compilation, and v2 APK signature verification passed. The original 19 Room device tests were not run in that baseline workflow.

## Milestone 2 verification

Milestone 2 implementation is written; the new build and emulator run are pending. Do not treat source presence as successful compilation or passing tests.

Local supplemental checks pass for the 15 entity definitions and current DAO SQL, foreign keys, barcode uniqueness, and stock isolation. These checks use Python’s SQLite and do not replace Room or Android testing.

The updated workflow will build Live and Demo, run JVM tests for each, run live lint, validate APK signatures, and execute all 40 Room/migration tests on an Android emulator. Actual run results, APK checksums, and any remaining limits will be recorded here after execution.

The local cloud workspace still lacks Android SDK/Gradle/JDK compiler; GitHub Actions supplies the build environment. No local dependency-download approval is required for that route.
