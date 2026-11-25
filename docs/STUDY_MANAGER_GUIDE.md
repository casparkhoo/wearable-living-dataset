# Running a study

For researchers who run a study on an instance that is already set up
([self-hosting guide](SELF_HOSTING.md)). Everything here is done in the web console unless
it says otherwise; the API behind each step is in
[reference/STUDY_API.md](reference/STUDY_API.md).

## Roles

| Role | Held where | Can |
|---|---|---|
| **platform admin** | the instance | see every study, grant platform roles, erase across studies (server command line) |
| **researcher** (platform role) | the instance | create studies |
| **study admin** / **researcher** (study role) | one study | change that study, invite and withdraw members, see its data, erase a member's data |
| **participant** | one study | consent, record into the study, withdraw themselves |

Every account starts as a participant. Roles are stored in your server's database and
checked on every request; the sign-in provider (Supabase) only proves who someone is.

## Before recruiting

1. **Get the researcher role.** Sign up in the console, then ask the platform admin to grant
   you `researcher` (console, or `wearable-backend set-platform-role <email> researcher`
   on the server).
2. **Create the study** (Studies → Create a study). You become its study admin, and it
   starts as `draft`.
3. **Record the governance details** with the study: the ethics approval reference, the
   consent form version, and the date after which data must be deleted
   (`ethics_reference`, `consent_version`, `data_retention_until` via
   `PATCH /api/studies/:id`). The consent form itself lives with your ethics paperwork; the
   system records which version each participant agreed to.
4. **Say in the consent form** what withdrawal and erasure mean here (see below), and how
   long backups are kept (`BACKUP_KEEP_DAYS` on the server, 14 days by default).
5. **Decide the app's distribution** (Play internal testing or Firebase App Distribution)
   and build it for your instance.
6. Set the study to `recruiting`.

## Enrolling a participant

1. The participant installs the app and creates an account (in the app or the console).
   They tell you the email they used, or the participant code shown under "Your account"
   if you prefer not to collect emails.
2. **Invite them**: open the study, *Invite a member*, enter the email or code, role
   `participant`. Their membership is `invited`.
3. **They consent** in the console (Studies → the study → *Record my consent*). Only now
   can they record into the study; before consent, and after withdrawal, the server
   refuses recordings for that study.
4. Within a minute of their first sign-in the server creates their own broker account, so
   the app can stream. No one else's phone can publish to or read their topics.

The member list shows each person's status (`invited`, `active`, `withdrawn`) and whether
consent is recorded.

## During collection

- The console's live view shows each recording as it arrives, with the Live Analysis
  Engine's alerts (ring not worn, stream stalled, packet loss, poor signal). Participants
  see the same alerts on their phone and, if they wear Even G1 glasses, on the lenses.
- Set the study to `active` once recruitment closes, and `closed` when collection ends
  (a closed study accepts no new recordings).
- Data leaves the server only by export (next section); keep exports on approved storage.

## Getting the data

Use *Export* on an experiment in the console, or the API/CLI described in the
[researcher guide](RESEARCHER_DATA_GUIDE.md). An export is a zip with the raw packets (if
requested), samples, labels, derived windows, a manifest and `checksums.sha256`.

## Withdrawal and erasure

These are different commitments, and the consent form should say which the participant is
offered.

| | Withdrawal | Erasure |
|---|---|---|
| Who | the participant (or a study admin for them) | a study admin, on the participant's request |
| Effect | stops further recording and removes their access | permanently deletes their recordings and everything derived from them in this study |
| Already collected data | kept | deleted |
| Logged | membership marked `withdrawn` with reason | `erasure_log` row: who, when, by whom, why, how many rows |

**To erase a participant's data:** open the study, find them in Members, *Erase data*. The
console first shows what would be deleted (a dry run), then asks you to type `ERASE` and a
reason. Erasure refuses to run while they are recording. The erasure log for a study is at
`GET /api/studies/:id/erasures`.

What erasure does not touch: their account (so they can still sign in; ask the platform
admin to remove it in Supabase if requested), other studies, and existing backups, which
age out after `BACKUP_KEEP_DAYS`. Exports you already made are outside the system: delete
those copies yourself.

A platform admin can erase across every study from the server:

```bash
docker compose exec backend wearable-backend erase-participant <user id> --all-studies --dry-run
docker compose exec backend wearable-backend erase-participant <user id> --all-studies --confirm --reason "request 2026-11-09"
```

## Retention and closing a study

When the study's `data_retention_until` date passes, the data must go. On the server,

```bash
docker compose exec backend wearable-backend retention-report
```

lists studies past their date that still hold recordings. Erase each participant (or ask
the platform admin to do it from the command line), set the study to `archived`, and
delete exported copies held elsewhere.

## Checklist before the first participant

- [ ] Ethics approval reference, consent version and retention date recorded on the study
- [ ] Consent form states: withdrawal vs erasure, backup retention period, where data is stored
- [ ] Nightly backups running and one restore tested (`scripts/restore_test.sh`)
- [ ] Off-server copy of backups (`scripts/pull_backups.sh`)
- [ ] `AUTH_ALLOW_SHARED_TOKEN=false` on the server
- [ ] App distributed through a managed channel, built for this instance
- [ ] A test participant has signed up, consented, recorded, been exported and been erased
