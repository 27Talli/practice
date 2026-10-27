# Backups, restore and safe upgrades

## In-app backup

Open **Settings → Backups & restore → Create encrypted backup**. Choose a new `.opbackup` document in Downloads, a USB drive or another trusted location; then set and repeat a passphrase of at least 10 characters. The app encrypts the snapshot before writing it to the selected provider and reads the saved file back to verify its SHA-256. A success message means that verification passed.

The backup includes BOTH shops: products, barcode aliases, prices, balances, stock ledger, sales/item snapshots, idempotency keys, reference counters, returns/expenses foundation tables, audit history, shop selection, private cost-code keys and receipt preferences. It excludes unpaid carts, camera images, regenerable PDF cache files and signing keys. Keep an off-device copy and a separate record of the passphrase. There is no password recovery service.

The Android file picker may show other installed providers. The app does not automatically upload anything or request Internet/storage permissions; choose a location appropriate for your records. A file held only inside the app's private storage is not a safe uninstall backup.

## Controlled restore

1. Select **Inspect backup for restore**, choose the encrypted file and enter its passphrase.
2. Review the date, Live/Demo marker, shop names and record counts. Authentication, schema/column checks, Room foreign keys/uniqueness, stock ledger, sale arithmetic and cost-label consistency are validated in a private staging database first. Live and Demo data cannot cross between variants.
3. Restore replaces **both shops**, rather than merging them. Type `RESTORE` and choose **Save recovery backup & restore**.
4. Choose a NEW recovery document, separate from the source file. It uses the inspected backup's passphrase. Current data is encrypted, saved and read back successfully before replacement starts.
5. The replacement and its audit event commit in one Room transaction. Failure rolls back all business tables. If another write occurs while the external recovery copy is being saved, replacement is rejected so that the recovery file cannot omit a newer transaction.
6. After commit the app opens a fresh workspace. A persistent dataset generation namespaces navigation/saved state, preventing a cart from the replaced database being reused, including after normal process restoration.

Recovery files can be inspected/restored by the same flow. Reinspect after a process restart; passphrases and staged-restore authorization are not saved in Android state or Room. A cancelled picker or failed validation does not replace shop records. Do not operate checkout during maintenance.

Backups use format 1/schema 5. Unknown or newer formats are rejected; future schema changes must add explicit format conversion. Limits are 128 MiB plaintext, two million rows, 100,000 characters per text field and 10,000 lines per sale. The codec streams bounded 64 KiB chunks. AES-256-GCM authenticates each chunk's position/length and an empty final chunk; the complete header is authenticated. PBKDF2-HMAC-SHA256 uses 600,000 iterations, a random 16-byte salt and a private passphrase; every file has fresh nonce material. No APK-wide secret is used.

This flow requires an openable, consistent current Room database to create a valid recovery copy. Physical device loss/corruption can be handled by restoring an existing encrypted backup into a clean installation on another device. Automatic repair of a physically corrupt SQLite file is outside this milestone.

## Why older debug APKs may not update

Android requires the same application ID and signing key for an in-place update. Earlier CI runs generated temporary debug keys. A new key, a matching certificate name, or the public certificate extracted from an APK cannot recreate the previous private key. Room migrations do not bypass Android's signing check.

**Do not uninstall, clear storage or attempt a destructive migration to resolve a signing mismatch.** Preserve the existing installation while recovering its original key or making independently verified backups. Signed releases cannot be enabled until the owner provides a retained signing key through the mechanism below.

## Rescue versions 3/4 of the earlier debuggable app

The rescue utility is for the earlier DEBUG APKs with database version 3 or 4 (and current version 5). It is not an account/backend service. Run it on your own computer with Python 3.10+, JDK 17+ and Android Platform Tools (`adb`). Enable USB debugging and authorize only your own computer. Keep the phone's app closed while copying.

From this source checkout:

```sh
adb devices
python3 scripts/rescue_debug_data.py --output /private/off-device/offer-point-rescue.opbackup --acknowledge-stop
```

Add `--demo` for the Demo app or `--serial DEVICE_SERIAL` when multiple devices are attached. Enter a new passphrase at the hidden prompt. The script force-stops the app, copies its database and WAL using Android's `run-as`, creates a coherent local SQLite snapshot, checks integrity and converts only the known additive version-5 fields. It encrypts locally with JDK cryptography, decrypts the result to verify its bytes, and creates a new output file without overwriting an earlier backup. It never runs uninstall, clear, or database writes on the phone. Temporary unencrypted copies stay in a private temporary directory and are deleted on normal exit. Secure your computer and its disk; interrupted host processes may leave temporary files.

`run-as` fails for a non-debuggable release; such an installation must use its own in-app export or its original update key. The physical-phone ADB/USB flow requires acceptance on your device. Host conversion/encryption and native Android restore interoperability are automated against synthetic historical-schema fixtures; the tool is never run against real shop data in CI.

Before removing any old installation, restore the rescue file on another Android phone or a **local Android Studio emulator**, verify both shops' actual products/quantities, saved sales and private code keys, and keep independent off-device file copies and the passphrase. Do not upload real shop backups to GitHub or this chat. A local emulator permits validation while leaving the original phone untouched. If you cannot verify a restore independently, keep the old app installed and resolve the key/transfer path first.

## Create and retain an owner-controlled release key

On the owner's private computer, choose a new path OUTSIDE the source repository:

```sh
python3 scripts/create_release_key.py /private/offer-point-signing/offerpoint-release.p12
```

The script uses JDK `keytool`, RSA-3072 and PKCS12; it prompts for a password without echoing it. Alias: `offer-point-pos`. Keep encrypted offline copies of the key and password. The store/key passwords are the same for the generated PKCS12 file. Do not regenerate it on each build or commit/publish it.

Configure these repository Actions secrets at [GitHub repository settings](https://github.com/27Talli/practice/settings/secrets/actions):

| Secret | Value |
| --- | --- |
| `POS_KEYSTORE_BASE64` | Base64 of the retained `.p12` file |
| `POS_STORE_PASSWORD` | Its store password |
| `POS_KEY_ALIAS` | `offer-point-pos` |
| `POS_KEY_PASSWORD` | Its key password (same as store password for this script) |

Use the GitHub UI on your own computer or authenticated `gh secret set`. For example, pass the encoded key directly to the CLI without displaying it:

```sh
base64 < /private/offer-point-signing/offerpoint-release.p12 | tr -d '\n' | gh secret set POS_KEYSTORE_BASE64 --repo 27Talli/practice
gh secret set POS_STORE_PASSWORD --repo 27Talli/practice
gh secret set POS_KEY_ALIAS --repo 27Talli/practice
gh secret set POS_KEY_PASSWORD --repo 27Talli/practice
```

Enter values at the CLI prompts; never paste passwords or the encoded key into chat, source files, workflow YAML or issue comments.

When all four secrets are configured, CI stages the validated key only in private runner temporary storage, builds/signs Live and Demo release APKs, verifies their signatures and publishes `offer-point-pos-retained-key-releases`. It removes the staged key afterward. Incomplete configuration fails rather than silently generating a production key. With no secrets, only debug evaluation APKs are published; an isolated unpublished TEST key checks the release build path, then its APK/key are removed. Neither path publishes a keystore.

For local release builds, provide `POS_SIGNING_STORE_FILE`, `POS_STORE_PASSWORD`, `POS_KEY_ALIAS` and `POS_KEY_PASSWORD` securely in the process environment, then run:

```sh
./gradlew :app:assembleLiveRelease :app:assembleDemoRelease
```

Release assembly is refused without a retained key and credentials. Once an installation uses the retained key, future higher-version APKs signed with that same key can update it without removing its database. Keep version codes increasing. The first switch from an older differently signed debug installation still requires the preservation/verification procedure above; a new release key cannot update that installation directly.
