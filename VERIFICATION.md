# Delivery verification — Offer Point POS 1.1.0-rc1

[GitHub Actions run 40526636656](https://github.com/27Talli/practice/actions/runs/40526636656) completed successfully on **27 October 2026**, building source **`818f74c7cd2a2da53c412ad3e814c89afcd41394`**. App version code: **7**. Room database: **6**.

| Check | Executed result |
| --- | --- |
| Live / Demo debug APKs | Built; APK signatures verified |
| JVM tests, Live | **137 passed**, 0 failures/errors/skipped |
| JVM tests, Demo | **137 passed**, 0 failures/errors/skipped |
| Live Android lint | Passed |
| Android 35 emulator | **132 passed**, 0 failures/errors/skipped |
| Release-signing path | Isolated unpublished TEST-key Demo release built and verified; key/APK removed |
| Permanent owner signing | Not configured; no production release published |
| Supplemental database checks | 20 entities / 54 DAO queries and lineage, barcode and stock constraints passed |
| Room schema | Version 6 byte-for-byte unchanged |

## Customization behavior exercised

The new JVM cases cover complete/default layouts, ordered movement, hide/show/nicknames, edge moves, grid pairing around information cards, hidden-item layout, missing/new tools, validation, checkout start sections, draft persistence, preview versus apply, double-tap saves, failed-save retry, import/reset and unreadable-preference recovery.

Native Android cases exercise layout JSON round-trip/validation/size limits, unsupported versions/unknown tools/duplicate items, persisted preferences across repository instances, change notifications, corrupt-file recovery, independent preference files and unchanged Room business fingerprints. A business-backup restore retains the receiving device's latest dashboard customization.

The actual Compose navigation tests edit a heading, choose a grid/color/default checkout section, move and rename Checkout, verify the preview has not persisted yet, apply it, then open the correct checkout tool. They also hide every dashboard item and recover via All tools, and apply theme/layout changes while an unpaid cart and its selected section remain intact. Existing checkout, refund-receipt, PIN-lock/cart and restore-generation flows still pass. Synthetic dashboard and dark checkout screenshots are retained in the reports and visually inspected for readable layout, colors and text; no installed business database is used.

The rest of the suite continues to cover products/shops/barcode uniqueness, integer price/quantity validation, stock isolation/rollback, idempotent sales/refunds/expenses, reports, PIN hashing/lockout, historical migrations, encrypted backups/restore/recovery, PDF/barcode rendering and thermal raster output. No app permission, runtime dependency or business schema was added for customization. The installed app still has no Internet permission.

## APK downloads

- [Live APK ZIP](https://github.com/27Talli/practice/actions/runs/40526636656/artifacts/12744419174)
- [Demo APK ZIP — recommended for testing](https://github.com/27Talli/practice/actions/runs/40526636656/artifacts/12744224315)
- [Test/lint reports, schemas, emulator logs and UI captures](https://github.com/27Talli/practice/actions/runs/40526636656/artifacts/12744434125)

Sign in to GitHub if prompted, extract the ZIP, then install its APK on **Android 8.0/API 26 or newer**. Artifacts expire **25 January 2027**. Live/Demo have separate application IDs and data. APK artifacts are published after the device/report checks pass.

SHA-256 of the APK files inside the ZIPs:

```text
443b61ccaf50f921f076c5a64c7520db6e09c840e6ac49b34a8b205eb3828755  app-live-debug.apk
fe8d728878185b2ab60087b70f9931fbce705dbd217bea811c0f3c98957a2848  app-demo-debug.apk
```

## Reproduce and deployment

```sh
python3 scripts/test_rescue_tool.py
python3 scripts/check_schema.py
./gradlew :app:assembleLiveDebug :app:assembleDemoDebug \
  :app:testLiveDebugUnitTest :app:testDemoDebugUnitTest \
  :app:lintLiveDebug :app:assembleLiveDebugAndroidTest
# With an isolated connected test phone/emulator:
./gradlew :app:connectedLiveDebugAndroidTest
```

This workspace installed Android SDK/Build Tools 35, Gradle 8.9 and a complete, checksum-verified Corretto JDK 17. Both local APK builds, 137 JVM tests per variant, Android test-APK compilation, lint and APK signature checks passed. Local `/dev/kvm` is unavailable; the actual 132-case native suite ran on the linked GitHub-hosted Android 35 x86_64 emulator with KVM and Temurin JDK 17. CI also checked synthetic host-rescue interoperability for exported schemas 3–6. Dependency versions remain pinned and unchanged.

Customization changes existing tool layout/appearance; new business features still need an app update. Presentation preferences live in private SharedPreferences and use checked commits; Room remains the primary business store. Save a separate layout file to transfer customization to another phone. Reset/import cannot modify financial records, receipt branding, printer preferences or device PINs.

This is a tested **debug evaluation candidate**. Owner-retained signing secrets remain absent. Never uninstall or clear a business installation to work around a signing mismatch; follow [safe upgrades](docs/SAFE_UPGRADES.md). Actual phone/camera/printed-label, printer, storage-provider and owner-key acceptance remains as described in [SHOP_ACCEPTANCE.md](docs/SHOP_ACCEPTANCE.md). Official logos were not supplied. Multi-device sync/accounts, non-cash payments, tax automation and proprietary printer drivers remain outside the core release.

Previous core verification: [run 40505558229](https://github.com/27Talli/practice/actions/runs/40505558229), source `701a4feb370663ad7345506a9932705c1b23ed62`, passed 121 JVM tests per variant and 121 Android tests. The first customization run [40520248907](https://github.com/27Talli/practice/actions/runs/40520248907), source `b7b86fdc8b4e26f8ff74a0148a81c9a2a50751a2`, passed all 137/132 cases; subsequent test-report changes retain synthetic UI images before the test app is removed. They do not alter app runtime behavior.
