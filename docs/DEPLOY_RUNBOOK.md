# Deploy runbook: backend and PaPaGei worker on the cloud VM

How to ship a new backend (and, when heads or the worker change, the PaPaGei worker) to a
running instance without touching the database, Caddy or the broker accounts. First set up
the instance with [SELF_HOSTING.md](SELF_HOSTING.md) (`scripts/deploy.sh vm <user@host>`; running it again
also updates a server, but this runbook is the faster backend-only path, with rollback). Instance-specific values (host, key
path) live in `deployment/cloud/deploy.env`, which is gitignored; nothing secret belongs in
this file.

## What a deploy changes

| Part | How it is shipped | Restarted |
|---|---|---|
| backend (`cloud-backend`) | built locally, `docker save`, `scp`, `docker load` | yes |
| PaPaGei worker (`cloud-papagei`) | same, only with `WITH_PAPAGEI=1`; the prediction heads in `ml-inference-server/heads/` are baked into this image, so a new head needs it | yes |
| `docker-compose.yml` | copied only with `SYNC_COMPOSE=1` (old one kept as `docker-compose.yml.prev`) | only services whose configuration changed |
| MySQL, Mosquitto, Caddy, `.env` | never touched | no |
| database schema | never touched; migrations in `backend/migrations/` are applied by hand (see SELF_HOSTING.md) and the backend refuses to start, naming the file, if one it needs is missing | no |

The compose file picks the live head from `ML_LIVE_HEAD_ID` in `.env`, falling back to a
default written in `docker-compose.yml`. Editing `.env` recreates MySQL (it reads the same
file), so the default in the compose file is changed instead and `.env` is left alone. If
`.env` sets `ML_LIVE_HEAD_ID` explicitly it wins over the default.

## 0. Prerequisites

The script's target comes from `deployment/cloud/deploy.env`, which overrides the environment: check
`VM_HOST` there before every run.

- Docker running locally; `ssh`, `scp`, `gzip` on the path; `deployment/cloud/deploy.env`
  with `VM_HOST`, `VM_KEY`.
- The VM is x86-64 (`dpkg --print-architecture` is `amd64`); the script checks this.
- Deploy from a clean export of the commit you mean to ship, never from a working tree that
  is being edited:

  ```bash
  git worktree add --detach /path/to/scratch/deploy-<sha> <sha>
  cp deployment/cloud/deploy.env /path/to/scratch/deploy-<sha>/deployment/cloud/
  ```
- The change was approved for deployment by the person running the study.

## 1. Look before touching

```bash
ssh -i "$VM_KEY" "$VM_HOST" 'cd ~/wearable-app/deployment/cloud && docker compose ps && docker images cloud-backend cloud-papagei'
ssh -i "$VM_KEY" "$VM_HOST" 'cd ~/wearable-app/deployment/cloud && docker compose exec -T backend printenv ML_LIVE_HEAD_ID LIVE_HR_SOURCE'
```

Record the image IDs of `cloud-backend:latest` and `cloud-papagei:latest`; they are the
rollback target (the deploy script also tags them `:previous`; if you use an older copy of the script,
tag them yourself with `docker tag <id> cloud-backend:previous` before loading new images, because
loading replaces `:latest` and leaves the old image untagged). `/health` needs the bearer token, which the VM's own `.env` holds; the
deploy script reads it on the VM. With the shared token disabled `/health` answers 401 by
design, so the check below reads the response body through the backend container instead.

## 2. Back up

On the VM (about 5 minutes on the 1 GB shape, because the dump is compressed on the VM):

```bash
ssh -i "$VM_KEY" "$VM_HOST" 'cd ~/wearable-app && scripts/backup_db.sh'
scripts/pull_backups.sh            # copies new dumps to ./backups and checks checksums
```

Confirm the newest dump is non-empty, its `.sha256` verifies, and
`gzip -t` passes and the last line is `-- Dump completed`. Restore-test on the laptop, not on the VM (a second MySQL does not fit in
1 GB): `BACKUP_DIR=./backups scripts/restore_test.sh backups/<dump>` where Docker is available.
`restore_test.sh` starts its throwaway MySQL and can try to load the dump before the server
inside has finished initialising (it then exits silently after "restoring"); if no table
counts print, wait a minute and run it again, or load the dump into a container by hand.
`backup_db.sh` also prunes dumps older than `BACKUP_KEEP_DAYS`, exactly as the nightly timer does.

## 3. Deploy

```bash
cd /path/to/scratch/deploy-<sha>
WITH_PAPAGEI=1 SYNC_COMPOSE=1 scripts/deploy_cloud_backend.sh
```

Plain backend-only deploys omit both flags. The script tags the running images as
`cloud-backend:previous` / `cloud-papagei:previous` on the VM before loading the new ones,
loads the new images, then runs `docker compose up -d --no-build <services>` for only those
services. If you deploy twice in a row, `:previous` becomes the first deploy; note the image
IDs from step 1 as well.

## 4. Verify

```bash
ssh -i "$VM_KEY" "$VM_HOST" 'cd ~/wearable-app/deployment/cloud && docker compose ps && docker compose logs --since 10m backend papagei | tail -60'
ssh -i "$VM_KEY" "$VM_HOST" 'cd ~/wearable-app/deployment/cloud && docker compose exec -T backend printenv ML_LIVE_HEAD_ID LIVE_HR_SOURCE GIT_SHA'
ssh -i "$VM_KEY" "$VM_HOST" 'cd ~/wearable-app/deployment/cloud && docker compose exec -T papagei ls heads'
```

- the backend logs "backend listening" and no migration warnings;
- `ML_LIVE_HEAD_ID` is the new head and that head's `.json` and `.npz` are in the worker;
- the worker answers `GET /health` (from the backend container: `curl http://papagei:5000/health`);
- `/health` reports `"gzip_ingest": true`. It needs a valid credential: a signed-in call from the
  console, or the shared token if `AUTH_ALLOW_SHARED_TOKEN` is on. With the shared token off
  (the study setting) the VM's own token is refused (401), so confirm the build instead:
  `docker run --rm --entrypoint sh cloud-backend:latest -c 'grep -c gzip_ingest /usr/local/bin/wearable-backend'` locally prints 1,
  and a 401 (not 000 or 502) shows the backend is up;
- the backend log says `live model enabled ... head_id=<new head>` and `live heart rate shown to participants source="papagei"`;
- MySQL, Mosquitto and Caddy `Up` times are unchanged (they were not restarted);
- no errors in the logs for the first few minutes.

End-to-end from the phone (cannot be done by the deploy script): sign in, connect a ring, start a
recording, check the console shows heart rate that varies, stop it, and confirm the upload
appears (the app sends gzip batches when `gzip_ingest` is advertised).

## 5. Roll back

On the VM, using the images tagged before the deploy (or the image IDs from step 1):

```bash
cd ~/wearable-app/deployment/cloud
docker tag cloud-backend:previous cloud-backend:latest
docker tag cloud-papagei:previous cloud-papagei:latest      # only if the worker was shipped
cp docker-compose.yml.prev docker-compose.yml               # only if SYNC_COMPOSE was used
docker compose up -d --no-build papagei backend
```

The database was not changed by the deploy, so nothing there needs undoing. Restoring a
dump is a last resort and replaces newer data; see `scripts/restore_test.sh` for how a dump
loads, and do it into a scratch database first.
