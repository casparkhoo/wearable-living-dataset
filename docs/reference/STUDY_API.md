# Study and identity API

Covers accounts, studies, membership, consent and withdrawal. Capture and annotation
endpoints are in [CONTROL_API.md](CONTROL_API.md). Verify routes against
[`backend/src/main.rs`](../../backend/src/main.rs) before relying on them.

Requires the identity/tenancy migration
[`20260919_add_identity_and_studies.sql`](../../backend/migrations/20260919_add_identity_and_studies.sql).

## Roles

| Role | Where it lives | Can do |
|---|---|---|
| `platform_admin` | `users.platform_role` | See and administer every study; change platform roles |
| `researcher` | `users.platform_role` | Create studies (accounts start as `participant`, which cannot) |
| `study_admin` / `researcher` | `study_memberships.role` | Administer **one** study and its membership |
| `participant` | `study_memberships.role` | Record their own consent, withdraw themselves, capture into that study |

**Authorisation never comes from the identity token.** Supabase owns credentials only;
`platform_role` and `study_memberships` are read from our database on every request. A
provider that is compromised or swapped cannot grant authority inside the platform.

Study management requires a signed-in account. The deployment-wide `API_TOKEN` has no
identity, so it returns `401` on these endpoints — it can still drive capture during the
transition.

## Endpoints

| Method | Path | Who | Purpose |
|---|---|---|---|
| `GET` | `/api/me` | anyone | Account, platform role and visible studies. Returns `{"authenticated": false}` for the shared token. |
| `GET` | `/api/studies` | signed in | Studies the caller can see; all of them for a platform admin |
| `POST` | `/api/studies` | platform `researcher` or `platform_admin` | Create a study; the creator becomes its `study_admin` |
| `GET` | `/api/studies/:study_id` | member | Study detail with active member and experiment counts |
| `PATCH` | `/api/studies/:study_id` | study admin | Update name, description, `status`, `consent_version`, `ethics_reference`, `data_retention_until`. Omitted fields are left untouched. |
| `GET` | `/api/studies/:study_id/members` | study admin | Membership with consent and withdrawal state |
| `POST` | `/api/studies/:study_id/members` | study admin | Invite by exactly one of `user_id`, `participant_code` or `email` |
| `POST` | `/api/studies/:study_id/consent` | participant | Record **own** consent; moves `invited` to `active` |
| `POST` | `/api/studies/:study_id/withdraw` | participant / study admin | Withdraw self; an admin may withdraw a member |
| `PATCH` | `/api/users/:user_id/platform-role` | platform admin | Set `platform_admin`, `researcher` or `participant` |
| `POST` | `/api/studies/:study_id/members/:user_id/erase` | study admin | Erase a member's data in the study. Body `{"dry_run": true}` previews counts; `{"confirm": "ERASE", "reason": "..."}` erases |
| `GET` | `/api/studies/:study_id/erasures` | study admin | The study's erasure log |

Study `status` is one of `draft`, `recruiting`, `active`, `closed`, `archived`.
Membership `status` is `invited`, `active` or `withdrawn`.

## Consent gates capture

A participant who has been invited but has **not** consented cannot record into the study:
`POST /api/recordings` with that `study_id` returns `403`. After
`POST /api/studies/:id/consent` the same request returns `201`. After withdrawal it returns
`403` again, and the study becomes unreadable to them.

This is enforced by `check_study_membership`, which requires an `active` membership — so the
consent requirement is a property of the system, not of the protocol document.

## Withdrawal and erasure

Withdrawal sets `status = 'withdrawn'`, stamps `withdrawn_at` and stores the reason. It stops
further capture and revokes access. It deliberately **does not delete already-collected
evidence**, and the response says so with `"evidence_retained": true`.

Erasure is the separate, audited operation (`backend/src/erasure.rs`). A study admin erases a
member's data in that study on the participant's request:

- `{"dry_run": true}` returns what would be deleted, per table, and deletes nothing;
- `{"confirm": "ERASE", "reason": "..."}` deletes the member's device recordings, raw packets,
  samples, measurement configurations, live events, derived windows and runs, annotations and
  experiments in that study, in batches, and marks the membership withdrawn;
- it refuses while the member has a recording in progress;
- every erasure writes an `erasure_log` row (subject, study, requested by, reason, per-table
  counts, start and end), which has no foreign keys so it outlives the data.

The account itself, other studies and existing backups are not touched; backups age out
with `BACKUP_KEEP_DAYS`. A platform admin can erase across all studies from the server:
`wearable-backend erase-participant <user id> --all-studies --dry-run`, then `--confirm`.
`scripts/test_erasure.sh` checks all of this against a throwaway database.

## Bootstrapping the first platform admin

Accounts are provisioned on first sign-in as `participant`, and there is deliberately no
self-promotion endpoint. The person who runs the server signs up once, then grants the role
from the server's command line:

```bash
docker compose exec backend wearable-backend set-platform-role you@example.org platform_admin
```

That account can then promote others through `PATCH /api/users/:user_id/platform-role`, or the
same command. Grant `researcher` to anyone who should create studies.

## Supabase configuration, and keeping it free

Set these on the backend; user authentication turns on as soon as a key source is present:

```text
SUPABASE_URL=https://<project-ref>.supabase.co
AUTH_JWT_AUDIENCE=authenticated
AUTH_ALLOW_SHARED_TOKEN=true
```

`SUPABASE_URL` is enough: the JWKS endpoint is derived from it, which is what projects
created since May 2025 use. `SUPABASE_JWT_SECRET` is only for older projects issuing HS256
tokens. Set `AUTH_ALLOW_SHARED_TOKEN=false` once every client signs in, to retire the shared
credential.

To stay on the free plan when creating the project:

- Choose the **Free** plan explicitly at project creation, and pick region
  **Southeast Asia (Sydney) `ap-southeast-2`** so participant identity stays in Australia.
  Region cannot be changed later without recreating the project.
- Free includes 50,000 monthly active users and TOTP multi-factor, which is all this needs.
- **Do not** add paid add-ons, custom domains, point-in-time recovery or extra compute, and
  do not enable spend caps' paid overage.
- The free tier allows **two active projects**, so do not create a throwaway project per
  environment; use one project and separate it by our own study scoping.
- **Free projects pause after about a week of inactivity.** A paused project cannot issue
  tokens, so nobody can sign in. Between collection phases this will bite — either sign in
  once a week to keep it warm, or budget for Pro during the study window and downgrade after.

Store no sensor data in Supabase: the free database is 500 MB, and one hour of ring capture
is roughly 239 MB. It is an identity provider here, nothing more.

## Failure modes worth knowing

- Authentication configured but the `users` table missing: the backend **refuses to start**
  and names the migration.
- Migration not applied and no authentication configured: the backend starts and warns that
  recordings will not be attributed to an owner.
- Applying the migration while a recording is active: the active experiment gains an owner
  the unauthenticated backend cannot see, and can no longer be stopped through the API. Stop
  recordings before migrating.
