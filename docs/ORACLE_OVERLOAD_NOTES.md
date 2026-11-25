# Cloud VM overload investigation (19 Nov 2026)

Read-only investigation of the reference VM (1 OCPU / 2 vCPU, 954 MB RAM, 2 GB swap, x86-64).
Nothing on the VM was changed. Times are UTC (the study site is UTC+11).

## Summary

- There is **no evidence of a hard outage**: no container restarts or OOM kills, no MySQL
  errors, no Caddy errors, no gap inside any recording. Raw packets arrived in every minute
  of every session.
- The VM **runs permanently at its memory limit** and slows down under a heavy recording.
  Two recordings coincide with kernel "memory pressure" messages: 10 Nov 09:52 and
  19 Nov 01:48.
- The one place data was **actually lost** is not load. About 12,650 MQTT payloads were
  rejected with "no active REST-created evidence session" (19 Nov, 01:46 to 01:47 and 01:52).
  The broker had already acknowledged them, so the phone considers them delivered and does
  not resend. This is the most likely cause of "it stopped taking in data".
- Honest limit: backend logs from before the 19 Nov redeploy are gone (the container was
  recreated). The database holds no sessions between 10 and 19 Nov, so any failed attempt in
  that period left no trace on the server. A phone that could not connect to the broker
  would not appear in these logs at all.

## Evidence

| Check | Result |
|---|---|
| Memory | 954 MB total, about 350 MB available, swap 1.1 GB of 2 GB used and never released. Commit is 116 to 128 % of RAM plus swap in `sar`. |
| Load | 2 vCPUs. 1-minute load peaked at 1.78 on 19 Nov (run queue 7), mostly under 1.3 on other days. Iowait up to 9.8 % on 19 Nov (4 to 5 % other days). |
| Minimum available memory per day | 256 to 299 MB on 11 to 18 Nov, **171 MB** on 19 Nov during the test. |
| OOM kills | None in the kernel journal. Docker reports `OOMKilled=false` and `RestartCount=0` for backend, MySQL, Caddy and PaPaGei. |
| Mosquitto | RestartCount 6 since 25 Sep (host and Docker restarts, not crashes in the last week). One "outgoing messages are being dropped" for a phone client at 19 Nov 02:03: a subscriber queue overflowed. Persistence is on. |
| MySQL | max_connections 151, peak used 34, no "too many connections". 338 aborted clients and 4,017 aborted connects over 9 days (about one per 3 minutes, probably a probe or the health check; not correlated with the load). 6,461 row-lock waits, longest 10 s. Buffer pool is the default 128 MB against 1.9 GB of data. Binary log is ON with `sync_binlog=1` and `innodb_flush_log_at_trx_commit=1`, so every commit is two fsyncs on the small disk. |
| Slow statements (backend) | 31 in the 3 hours after the redeploy, all 1.0 to 1.6 s: 23 `INSERT stream_samples`, 4 `live_events`, 2 `UPDATE sessions`, 1 `raw_packets`. They cluster at 01:47 to 01:52 (the heavy test) and 02:01 to 02:02. |
| Load per recording | 19 Nov 01:47 to 01:51: about 19,500 raw packets per minute (3 phones' worth of earbuds and ring, about 325 per second). 10 Nov 09:44 to 10:29: peak 7,200 per minute. The 19 Nov test was about 2.7 times heavier than anything recorded before. |
| Data continuity | Every minute in sessions 74 to 79 has packets; no gaps. |
| PaPaGei | One failed estimate at 02:01:25 (one request error, counter reset). 89 MB used of the 300 MB cap. |
| Disk | 19 GB of 45 GB used (44 %). Biggest tables: `stream_samples` 754 MB, `raw_packets` 443 MB, `live_events` 380 MB (143 k rows, growing). Docker json logs are not rotated (no size limit set). |
| Pool and queue in code | MySQL pool of 8 connections; ingest queue of 4,096 messages between the MQTT task and the single batch writer; the sender waits when full (back-pressure toward the broker, no drop). |

## Timeline

| When | What |
|---|---|
| 25 Sep | Broker container last started. |
| 3, 8 Nov | Short recordings, no issues recorded. |
| 10 Nov 09:44 to 10:29 | 45 minute recording, three sessions, up to 7,200 packets per minute. Kernel "under memory pressure" at 09:52. No data gap. |
| 10 Nov 11:44 | Backend image from that date built (the version replaced on 19 Nov). |
| 11 to 18 Nov | No recordings. Idle memory use stays near the limit, swap about 1.1 GB. |
| **19 Nov 00:41** | Redeploy: backend and PaPaGei recreated, new head. |
| 19 Nov 01:46 to 01:47 | Phone starts streaming before the sessions exist: about 9,100 payloads rejected ("start one before streaming"). |
| 19 Nov 01:47 to 01:52 | Sessions 74 to 76 recorded at 19,500 packets per minute. Memory pressure at 01:48, slow inserts of 1 to 1.6 s, load 1.78. |
| 19 Nov 01:52 | About 5,300 more payloads rejected after the stop (phone still draining its outbox). |
| 19 Nov 02:01 to 02:02 | Sessions 77 to 79, light; four slow inserts. |

Before the redeploy: no failures can be shown, and the same memory-pressure pattern was
already visible on 10 Nov. After the redeploy: the heavier load plus the rejected payloads.
The redeploy did not make the VM slower; it happened to be followed by the first heavy test.

## Root cause assessment

1. **Capacity headroom is nil.** MySQL, the backend, Mosquitto, Caddy, PaPaGei and the OS fit
   only by living in swap. Under a burst the page cache is evicted and inserts take
   1 to 1.6 s, but the system recovers within a minute and loses nothing.
2. **Real data loss from session ordering**, not overload: the backend rejects payloads for a
   device with no active evidence session, but the broker has already acknowledged them. The
   phone side believes they arrived. Confirmed counts: 12,650 rejected in two bursts.
3. **MySQL is configured for safety, not throughput:** tiny buffer pool, double fsync per
   commit (binary log is on although nothing replicates from it).

## Proposed fixes, ranked

Value is the benefit for a multi-participant study; risk is the chance of breaking a
running study. "Config" means a compose or settings file edit on the VM with a restart of
the service; "redeploy" means a new backend or app build.

| # | Fix | Value | Risk | Kind |
|---|---|---|---|---|
| 1 | **Stop silent loss of payloads with no active session**: reply or log per device, and on the phone keep batches until the session is confirmed, or have the backend park them for a short time and replay when the session starts. At minimum, count and log rejects once per minute instead of 12,000 warning lines. | High | Medium | Backend and phone redeploy |
| 2 | **MySQL: turn off the binary log** (`--skip-log-bin`) and set `--innodb-flush-log-at-trx-commit=2`. Halves the fsyncs per insert; worst case one second of commits is lost in a host crash, which the nightly dump and the phone outbox tolerate. | High | Low | Config (MySQL restart, a few seconds) |
| 3 | **Larger MySQL buffer pool** to 256 MB (`--innodb-buffer-pool-size`), only together with fix 5. | Medium | Medium (memory) | Config |
| 4 | **Move off the 1 GB shape** to a free Ampere A1 (up to 24 GB) when capacity allows, or a paid 4 GB shape. Needs the image rebuilt for arm64 (`PLATFORM=linux/arm64`) and a new public IP, so hostnames and the built apps change. | Highest long term | High (migration) | Infrastructure |
| 5 | **Memory limits per container** (compose `mem_limit`): MySQL about 450 MB, backend 150 MB, Mosquitto 64 MB, Caddy 64 MB, PaPaGei already 300 MB. A runaway service is killed and restarted instead of taking the host into thrash. | Medium | Medium (a limit set too low restarts it) | Config |
| 6 | **Healthcheck plus restart policy** for the backend (`/health` returning 401 counts as up) and PaPaGei; `restart: unless-stopped` is already set. Unhealthy containers are then restarted by a small systemd watchdog, since Compose alone does not restart on unhealthy. | Medium | Low | Config |
| 7 | **Log rotation** for Docker: `logging: { driver: json-file, options: { max-size: 10m, max-file: 3 } }`. The rejection storm alone wrote 12,000 lines in two minutes. | Medium | Low | Config |
| 8 | **Rate-limit the warning**: aggregate "failed to ingest" to one line per device per 10 s. | Low | Low | Redeploy (backend) |
| 9 | **Mosquitto**: set `max_queued_messages 5000`, `max_inflight_messages 40`, `max_queued_bytes` small, and `persistent_client_expiration 7d` so offline subscribers do not accumulate; log connection and drop events. Check the phone client that dropped messages: it subscribes to a topic it does not need to receive. | Medium | Low | Config (broker restart drops live connections briefly) |
| 10 | **Ingest queue and writer**: raise `INGEST_QUEUE` only if memory allows; increase the writer's batch size so one transaction covers more rows; keep the pool at 8 (the writer is single threaded). Expose queue depth in `/health`. | Medium | Medium | Redeploy |
| 11 | **Retention and growth**: `live_events` is 380 MB for 143 k rows; thin the 'summary' events (keep one per 5 s window) or drop details after export. Raw packets stay as evidence. | Medium | Medium (touches data policy) | Redeploy |
| 12 | **Alerting**: a systemd timer that checks memory available (under 100 MB), swap used (over 1.8 GB), disk (over 85 %), `docker ps` health, and the newest raw packet during an active session, and sends a message (ntfy or email). Also run `sar` as already installed. | Medium | Low | Config |
| 13 | **Zram or a bigger swap** is not recommended: the swap is already busy and the disk is shared with the database; fix 5 and a larger shape are better. | Low | Low | Config |

Suggested order if approved: 2, 7, 6, 12 (all config, low risk, one MySQL restart), then 5
and 9, then 1 with the next backend and app release, then 4 before a larger study.

## Does the local Docker stack have the same weakness?

Partly. Compared in `deployment/local/docker-compose.yml`:

| Setting | Cloud VM | Local stack |
|---|---|---|
| Hardware | 954 MB RAM, 2 GB swap | a laptop with memory to spare |
| MySQL buffer pool | 128 MB default | 1 GB (`MYSQL_BUFFER_POOL`) |
| Binary log and flush settings | defaults (binlog on, double fsync) | defaults, same |
| Container memory limits | PaPaGei 300 MB only | none |
| Healthchecks | MySQL only | MySQL (others not checked) |
| Log rotation | none | none |
| Backend pool 8, ingest queue 4,096 | same code | same code |
| Session ordering rejects | same | same |

The code-level weaknesses (item 1, 8, 10) are identical locally; the capacity weakness is
specific to the 1 GB cloud shape. Fixes 2 and 7 apply to both stacks.
