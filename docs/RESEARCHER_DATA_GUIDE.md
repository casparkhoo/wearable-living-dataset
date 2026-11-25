# Working with the data

How to get data out of an instance, rebuild derived results from evidence, score a method
against a reference device, and reproduce the results reported in the thesis.

## The data model in one paragraph

A **study** contains **experiments** (one recording session, possibly several devices); each
experiment has one **device recording** per wearable. A device recording holds **raw
packets** exactly as received and the **stream samples** parsed from them, each sample
carrying up to three clocks: the device's own time (where the device sends one), the
phone's receive time and the server's receive time. **Annotations** are labelled intervals.
Everything computed from evidence (live heart rate, signal quality, PaPaGei output) is a
**derived window**, stored apart from evidence with its method, method version and the
**derived run** that produced it; each run records a SHA-256 fingerprint of the evidence it
read. Details: [reference/LIVING_DATASET_SCHEMA.md](reference/LIVING_DATASET_SCHEMA.md).

## Exporting

From the console (*Export* on an experiment), or:

```bash
# HTTP, as a signed-in member of the study (add raw=1 to include raw packets)
curl -H "Authorization: Bearer $TOKEN" -o experiment_42.zip \
  "https://<API_DOMAIN>/api/experiments/42/export?raw=1"

# on the server
docker compose exec backend wearable-backend export 42 /tmp/experiment_42.zip --raw
```

The zip holds samples, annotations, derived windows and runs, a `manifest.json` (which
clocks, which methods and versions, backend version and commit) and `checksums.sha256`.
Check it with `sha256sum -c checksums.sha256` after unzipping. The export's own README
explains each file, including why some times are labelled `unix_ms_unverif`.

Derived windows for one device recording as CSV:
`GET /api/evidence-sessions/<recording id>/derived?format=csv`.

## Recomputing derived data

Derived data can always be rebuilt from evidence. This is how you check a stored result,
or apply a new method version to old recordings.

```bash
# HTTP (study admin): POST /api/evidence-sessions/<recording id>/recompute
docker compose exec backend wearable-backend recompute <recording id>            # with PaPaGei
docker compose exec backend wearable-backend recompute <recording id> --no-model # rules only
```

The command prints the new run: its evidence fingerprint, row count and window count.
Two runs over unchanged evidence produce the same fingerprint and the same windows; the
thesis checked this on a laptop and on the server for four recordings.

## Scoring a method against a reference device

The reference used in the thesis is the Empatica EmbracePlus, whose per-minute pulse rate
is downloaded from the organisation's data bucket.

```bash
# 1. Reference data (keys are read from %USERPROFILE%\.thesis\empatica.env, never the repo)
python scripts/empatica_s3.py ls
python scripts/empatica_s3.py get <prefix-for-the-day> data/embrace

# 2. Score every method in an export against it
python scripts/score_methods.py experiment_42.zip --reference data/embrace/*pulse-rate*.csv --out scores_42

# 3. Score over a stretch chosen by signal coverage, never by error
python scripts/window_validation.py experiment_42.zip --reference data/embrace/*pulse-rate*.csv --minutes 30 15

# 4. Agreement figure
python scripts/figure_bland_altman.py scores_42/minutes.csv bland_altman_42.png
```

`score_methods.py` reduces each method to one value per minute (median of its windows,
at least three) and reports coverage, bias with Bland–Altman limits, MAE, RMSE, Pearson r,
share within 5 bpm, and the MAE of the **best constant**. A method that does not beat the
best constant has not shown it tracks heart rate; at rest, with a narrow range, nothing
may. Record an exercise session before drawing conclusions about tracking.

## Training a PaPaGei heart-rate head

```bash
python scripts/papagei_posthoc.py \
  --labelled ring_a.tsv:trace_a.txt ring_b.tsv:trace_b.txt ring_c.tsv:trace_c.txt \
  --embrace data/embrace/pulse-rate.csv \
  --save-head hr_ring_embraceplus_v2
```

It trains a ridge head on 512-d PaPaGei embeddings labelled with the reference, reports a
leave-one-recording-out score, and writes `ml-inference-server/heads/<id>.json|.npz`. Set
`ML_LIVE_HEAD_ID=<id>` on the server to use it live. Train on recordings that cover the
heart-rate range you care about: the head shipped here was trained on three resting
recordings and returns an almost constant rate.

## Reproducing the thesis results

| Result | How |
|---|---|
| Fault detection (6/6 faults, 3.3–9.3 s) | `python scripts/live_fault_injection.py` against a local stack (see its docstring) |
| Live analysis on real recordings | `cargo test replay_recorded -- --ignored` with `LIVE_REPLAY_TSV` (see `scripts/validate_embrace.py`) |
| Heart rate vs EmbracePlus, 37 min | export the recording, `score_methods.py`, `figure_bland_altman.py` |
| Recompute reproducibility | `wearable-backend recompute <id>` on two machines; compare fingerprints and windows |
| Erasure correctness | `scripts/test_erasure.sh` (needs Docker) |

The raw recordings are participant data (the author's own, in the thesis) and are not in
the repository; the scripts reproduce the analysis on any instance's recordings.
