# Supabase preparation for the existing POS

Stage 1 instructions only. The current APK has no cloud enrollment screen or sync engine. No project, cloud tables, migrations, credentials or RLS policies have been created by this audit. This guide prepares the owner-controlled environment for Stage 3; it does not enable synchronization by itself.

## What you can prepare now

1. Open [Supabase Dashboard](https://supabase.com/dashboard) and create/use your own account. Secure the owner account with MFA and retain recovery methods privately.
2. Create a development/test project in your organization. Choose a nearby available region based on the dashboard's current options. Keep its database password in your own password manager; do not send it in chat or commit it.
3. Keep test/demo work in that project. The existing Demo application/database/printed labels must not seed the Live business. When ready for real business use, provision a separate Live project or another explicitly isolated environment; do not point test devices at production by accident.
4. Check current pricing, pausing behavior, resource limits and backup availability before choosing a plan. No specific paid plan, pricing or continuous availability is assumed here.
5. In the project's Connect/API settings, copy the **project URL** and **publishable key**. These are the only client configuration values to provide for app wiring. Do not provide a secret key, legacy service-role key, database password or Supabase management access token.
6. Keep the project private to its administrators while the backend is being implemented. Do not create unprotected public business tables as a shortcut. Do not disable RLS to make a client request succeed.
7. Record your intended first owner login and which users may work at Offer Point, Fancy Footwears or both. Public signup, if enabled, must not automatically grant membership in either shop. Invitations/approval will be required for organization access.

Only demo/test data exists today, so the recommended initial Live state is **an empty business** with the two correctly branded shops and owner-approved devices. The existing test installations remain intact. If real transactions are entered before rollout, stop this empty-start assumption and use the verified authoritative-import procedure in the backup plan.

## Stage 3 developer deployment checklist

These are required future tasks, not completed commands:

- Add reviewed, versioned PostgreSQL migrations under `supabase/migrations/`, based on the audited 20-table model and the protocol tables in `IMPLEMENTATION_PLAN.md`.
- Test migrations locally/on a disposable project before applying to the owner's test project. Record each migration checksum and current remote version. Never use a reset/drop-and-recreate workflow on an existing business database.
- Enable RLS on every exposed business/control table. Deny anonymous reads/writes. Revoke direct client financial/ledger writes and restrict callable RPCs. Test with actual authenticated users, not a privileged SQL Editor session that bypasses normal policies.
- Implement organization membership, shop capability checks, device/session/key binding, owner enrollment and device revocation. Both RPC writes and pull/read paths must check the active device; possession of a public key or a substituted device UUID is not authorization.
- Use narrowly scoped server functions with safe search paths, explicit actor validation, payload bounds, canonical idempotency keys, expected revisions and commit-safe change numbering.
- Prepare separate server-only deployment credentials. Store them only in a trusted local environment or a proper CI secret store; do not include them in Gradle BuildConfig, APK resources, source control, logs or screenshots.
- Configure authentication callbacks/deep links only after the actual app enrollment flow is implemented and its application ID/signing certificate are known. Do not invent a callback URL and assume it works.
- Provision the first Owner membership through a trusted administrator path. Unauthenticated clients cannot appoint themselves Owner. Reserve Manager capabilities until their policy tests are implemented.
- Verify live authentication, token refresh, offline authorization behavior, shop isolation, lost-device revocation, two-device synchronization and replacement-device restore in the test project.
- Configure and restore-test independent encrypted cloud backups. Confirm whether the chosen Supabase plan supplies managed backups/PITR; do not assume free projects have daily recoverable backups.
- Record the chosen offline-refund policy before allowing multi-device production refunds. See the explicit consistency tradeoff in the design.
- Only after all tests pass, apply the same reviewed schema/configuration to Live and enroll owner-approved devices. No demo sales, expenses or stock balances should enter Live.

## App configuration and installation boundary

At implementation time, the Android client needs the project URL and publishable key plus authenticated device/user enrollment. It must not need a database password or privileged key. Use a maintained configuration input outside source control; validate HTTPS and the expected environment before storing enrollment state. Native networking permissions and a compatible WorkManager dependency will be added in a later implementation stage, with pinned versions verified by an actual build.

Retain the original signing key for routine upgrades of an installed app. The currently published debug APK uses a different certificate from some earlier debug builds; the sync upgrade must not solve that by deleting the old installation. Follow [safe upgrades](../SAFE_UPGRADES.md). These instructions do not ask you to install a new synchronization APK now; none has been built.

After provisioning, opening the app, local unlock, catalog/search/scanning, permitted local product edits, stock work, cash checkout/discounts, receipts/printing, permitted returns, reports and expenses must continue using Room without a network round trip. New-device enrollment/initial data download needs connectivity. A never-synchronized phone cannot infer the existing inventory; the UI must say so.

## Reference documentation

- [Supabase client/server API keys](https://supabase.com/docs/guides/getting-started/api-keys)
- [Database RLS](https://supabase.com/docs/guides/database/postgres/row-level-security)
- [Auth sessions](https://supabase.com/docs/guides/auth/sessions)
- [Backup coverage and export guidance](https://supabase.com/docs/guides/platform/backups)

No secrets or customer databases should be attached to this repository or this setup guide.
