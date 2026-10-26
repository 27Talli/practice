# Delivery verification

## Milestone 1 baseline

The original Android build succeeded on GitHub Actions run [40327606654](https://github.com/27Talli/practice/actions/runs/40327606654), source commit `f30ef86ccab5e1ff273d8e4e663e2712db31ccd8`. Both debug APKs, both JVM suites, live lint, instrumentation APK compilation, and v2 APK signature verification passed. The original 19 Room device tests were not run in that baseline workflow.

## Milestone 2 verification

The initial Milestone 2 run [40354132649](https://github.com/27Talli/practice/actions/runs/40354132649), source commit `d9e953259bde5895914289e8ae58aaa9db3030ca`, completed successfully on 26 October 2026:

- Live and Demo signed debug APKs built and passed APK v2 signature verification.
- JVM suites: **37 tests per variant**, no failures, errors, or skipped tests.
- Live Android lint passed.
- Android emulator: **40 Room/migration tests**, no failures, errors, or skipped tests.
- The migration test used the actual historical Milestone 1 exported schema, validated migration to version 2 with Room, verified preservation of every business table/selection/audit history, and completed a checkout afterward.
- The actual KSP-generated schemas 1 and 2 were recovered from checksum-verified log markers and committed for future migration testing.

A final end-to-end Compose test has also been added to exercise the cashier's product selection, exact-cash payment, saved confirmation, and reopening history against isolated real Room repositories. Its build/device run is pending; the initial passing run did not include this additional test.

Local supplemental checks pass for the 15 entity definitions and 28 DAO queries, foreign keys, barcode uniqueness, and stock isolation. They do not replace the executed Android tests.

The cloud workspace lacks a local Android toolchain; GitHub Actions supplies the verified build and emulator environment. Tests do not require Internet at runtime; dependency and SDK downloads are development/CI setup only.

Both APKs are debug/evaluation builds. GitHub-hosted runners generate debug signing keys that may differ across runs, so they are not a reliable production upgrade channel. Do not uninstall an existing business-data installation to resolve a signing mismatch. Use a retained private release signing key for production updates. The emulator test is not a physical-phone acceptance test; the manual acceptance checklist still applies.
