# Stage 1 audit verification

Status: local baseline checks passed; a fresh Android CI run is being prepared on the isolated upgrade branch. This page will record the actual completed run and test counts. No synchronization implementation or multi-device integration test has been run.

Before any changes, all 165 existing project files matched GitHub checkpoint commit `335808a9df7d59feeb05a0d2904a150a58d8988a` by Git blob hash and mode. The original 20-table Room schema 6 hash is unchanged. No Android phone is attached, so no actual phone backup has been exported or verified here.

Executed locally:

- Both existing debug APK builds and Android lint: succeeded.
- Both JVM unit-test tasks explicitly rerun, not just reported up-to-date: succeeded; exact counts will be recorded from XML.
- SQLite structure/query/lineage checks: 20 entities, 55 DAO queries passed.
- Host rescue interoperability: schema 3/4/5/6 synthetic payload conversion, encryption/decryption, wrong-password rejection and existing-file protection passed. The generated test-only ciphertext fixture was restored to its pre-audit checksum afterward.
- Read-only runtime/source/schema inventory check passed: 82 runtime Kotlin files, all six schema exports, dependency configuration and business backup table list match the recorded checkpoint inventory.

Planned synchronization scenarios A–J and actual two-device Supabase authentication/sync/recovery tests remain unexecuted. See `BACKUP_AND_TEST_PLAN.md` for the required assertions and explicit status.
