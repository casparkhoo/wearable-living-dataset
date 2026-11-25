# Cloud deployment

The Docker Compose stack for an internet-facing instance: MySQL, Mosquitto, the backend, the
PaPaGei worker and Caddy (automatic HTTPS for the API and for MQTT over WebSockets).

Deploy it with `scripts/deploy.sh vm <user@host>` (installs Docker, ships the images, creates
secrets on the server, starts Caddy, installs the backup timers). [docs/SELF_HOSTING.md](../../docs/SELF_HOSTING.md) covers `.env`, hostnames,
Supabase, broker accounts, backups, the console and the Android build.

| File | Purpose |
|---|---|
| `docker-compose.yml` | the stack |
| `.env.example` | every setting, with how to generate secrets (copy to `.env`, never commit) |
| `Caddyfile` | reverse proxy for `API_DOMAIN` and `MQTT_DOMAIN` |
| `mosquitto/` | broker config and per-user topic ACL; `passwordfile` is generated |
| `Dockerfile`, `papagei.Dockerfile` | backend and model worker images |
| `deploy.env.example` | where `scripts/deploy_cloud_backend.sh` deploys to (copy to `deploy.env`) |
