# Local Docker deployment

The whole system on one computer: MySQL, Mosquitto with per-participant accounts, the Rust
backend, the PaPaGei worker and the web console, behind one port.

```bash
scripts/local.sh up
```

Full guide, including connecting the phone and reaching it through a tunnel:
[docs/LOCAL_HOSTING.md](../../docs/LOCAL_HOSTING.md).

| File | Purpose |
|---|---|
| `docker-compose.yml` | the services; `broker-accounts` keeps the broker's accounts in step with the users table |
| `.env.example` | settings; `scripts/local.sh up` creates `.env` (gitignored) with generated passwords |
| `gateway.Dockerfile`, `Caddyfile` | builds the console and serves it with the API and broker routes on one port |
| `mosquitto/mosquitto.conf` | broker listeners; the ACL is shared with the cloud deployment |

Start it with `scripts/deploy.sh local` (it calls `scripts/local.sh up` after checking Docker and
creating `.env`); the guide for both hosting modes is [docs/SELF_HOSTING.md](../../docs/SELF_HOSTING.md).
