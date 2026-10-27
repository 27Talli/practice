# Stage 1 audit verification

**Stage 1 completed, 27 October 2026 (Asia/Karachi).** [Fresh audit CI run 40551666195](https://github.com/27Talli/practice/actions/runs/40551666195) succeeded for audit source `0e737e20156c74e54fcf208007a572b97e8351ab`. [Download its reports](https://github.com/27Talli/practice/actions/runs/40551666195/artifacts/12756197447).

| Check | Actual result |
| --- | --- |
| Original project at checkpoint | 165 files matched GitHub by blob hash/mode; all 165 remain unchanged after audit additions |
| Runtime/schema/dependency inventory | Passed: 82 Kotlin runtime files, 20 tables, all six exported schemas and existing dependency pins |
| Live / Demo debug builds | Both passed locally and in fresh CI |
| Live Android lint | Passed locally and in CI |
| JVM tests, Live | 160 passed; zero failures/errors/skips |
| JVM tests, Demo | 160 passed; zero failures/errors/skips |
| Android 35 native baseline | 139 passed; zero failures/errors/skips |
| Native encrypted business backup/recovery | 19 `BackupDatabaseTest` cases passed within that native suite |
| Historical Room migrations | Five native migration cases passed, covering the existing 1→6 chain |
| SQLite design/query checks | 20 entities / 55 DAO queries; FK lineage, barcode uniqueness and stock checks passed |
| Host rescue interoperability | Schema 3/4/5/6 synthetic conversion, encryption/decryption, wrong-password rejection and existing-file protection passed |
| Phone database backup | Not performed: `adb devices -l` showed no connected phone; owner reports only test data |
| New sync database migration | Not implemented or executed |
| Supabase / two-device tests A–J | Not implemented or executed; no project/enrollment/backend exists |

The reports ZIP was downloaded and its SHA-256 verified as `d0502f37b06c79af125d6744d135a5464b564dfd890d86a4c700e5ea9058232e`. The actual XML files independently confirm all three test counts and zero failures/errors/skips. Local unit tasks were explicitly rerun using task-level `--rerun`, rather than relying on an up-to-date task message.

The fresh native run exercised encrypted export, staging, restore/reopen and mandatory recovery-file restoration, rollback and malformed-backup rejection, PIN/layout retention, legacy rescue interoperability, existing checkout/stock/refunds/reports, barcode/receipt QR rendering, printer raster output and Compose navigation. These are synthetic isolated test databases, not the owner's phone. Android tests ran on the GitHub-hosted Android 35 x86_64 emulator with KVM; local `/dev/kvm` is unavailable. Physical camera/printer output was not tested here.

The generated host-rescue test ciphertext was restored locally to its pre-audit hash after the host checks. No existing source, schema, dependency configuration, branding/resource, production database, sale or user-entered record was changed. The only additions are audit/design/setup/test-plan documentation, a read-only source inventory checker and an audit-only verification workflow. The main branch and current app release were not changed by this stage.

## Checkpoint and recovery evidence

- Checkpoint branch: [`checkpoint/pre-sync-20261027`](https://github.com/27Talli/practice/tree/checkpoint/pre-sync-20261027).
- Exact checkpoint: `335808a9df7d59feeb05a0d2904a150a58d8988a`.
- Isolated branch: [`upgrade/offline-multi-device-sync`](https://github.com/27Talli/practice/tree/upgrade/offline-multi-device-sync).
- [Draft PR #1](https://github.com/27Talli/practice/pull/1) contains the Stage 1 reviewable result.
- Schema 6 SHA-256 remains `027bd3a6530a8697dc00dd34e0793fea7b4ce9b5d1ae9b27d007d77bfa8e72f2`.

The code checkpoint and synthetic restore tests do not substitute for backing up a phone before installing a migration. Export that phone's encrypted backup, inspect it, restore on an isolated matching-variant installation, compare both shops' counts/totals/stock/receipts, and verify the recovery copy. Retain its original signing key and installation. Do not migrate merely because an encrypted file can be copied or hashed.

## Design clarifications for the next stage

- The owner saying “test data” does not prove that records reside in the Demo APK. Check actual Live/Demo variant and dataset lineage; preserve current records and matching-variant backups. Do not delete/relabel Live-flavor test records or bypass backup variant checks to create an empty cloud business.
- Apply revision comparison to editable catalog/price proposals. A genuine completed offline cash sale must preserve its recorded price/cost/discount snapshots even if another device changed the current catalog price before upload. Link observed revisions and flag deviations where needed; do not reject or silently reprice it just because the latest catalog differs.
- Exact integer paise, quantities, revision numbers and cursors must survive the wire protocol. Prefer explicit decimal-string representations where JavaScript/JSON number intermediaries could round values beyond 2^53; validate into bounded integer types rather than Double/Float.
- Row-level security does not by itself redact purchase-cost columns. Define cost/profit visibility and offline snapshot availability explicitly for Cashier/Owner before claiming role-based confidentiality. A hidden button is not access control.
- Two disconnected devices cannot both have unrestricted final refund rights to the same last unit while guaranteeing no excess payout. The reserved-rights versus provisional/reconciliation policy remains an explicit business decision before production enablement; no silent rule was added in this audit.

This evidence verifies the preserved pre-sync application and restorable synthetic backup path. It does **not** verify the planned sync implementation, a schema-7 upgrade, independent cloud backups, cloud RLS, lost-device enforcement or cross-device financial correctness. Production multi-device use remains gated on the implementation and the A–J/security/recovery matrix in `BACKUP_AND_TEST_PLAN.md`.
