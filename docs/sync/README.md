# Existing POS synchronization upgrade — Stage 1

The existing Offer Point POS has been audited and checkpointed. **Synchronization is not implemented or enabled yet.** This branch adds audit evidence, a migration/protocol design, owner setup instructions, backup/test plans and a baseline-verification workflow. Application source, Room schema 6, app version, branding and existing release downloads are preserved.

Start with:

1. [Existing architecture, tables, migrations and findings](AUDIT.md)
2. [Staged migration and synchronization design](IMPLEMENTATION_PLAN.md)
3. [Supabase preparation for the owner](SUPABASE_SETUP.md)
4. [Backup recovery and multi-device test matrix](BACKUP_AND_TEST_PLAN.md)
5. [Actual verification results and limits](VERIFICATION.md)
6. [Machine-readable source/schema inventory](baseline-inventory.json)

Git checkpoint: `checkpoint/pre-sync-20261027`, commit `335808a9df7d59feeb05a0d2904a150a58d8988a`. Work branch: `upgrade/offline-multi-device-sync`. Existing files matched the checkpoint tree before additions.

Owner confirmed only demo/test data and no Supabase project. Prepare a test project using the setup guide; keep privileged secrets private. Future Live initialization should be empty and must not import Demo records automatically. No phone database has been backed up by this workspace; a real device's backup must be exported, inspected and restored on an isolated matching-variant installation before its migration.

The next implementation stage is coordinated, non-destructive identity/outbox/backup preparation, followed by validated server authorization and the sync engine. Offline refund concurrency requires an explicit rights/provisional-payout policy; a local UUID alone cannot prevent two disconnected devices handing back the same cash. No production multi-device rollout is authorized by passing the existing single-device baseline tests.
