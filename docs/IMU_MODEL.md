# IMU activity model (MOMENT, accelerometer)

**Verdict: negative, twice. Drop MOMENT from the thesis claims (one sentence).** MOMENT-1-small
was applied to windows of **accelerometer** data (the ring's, 25 Hz), not to PPG. A frozen
embedding of 10 s of accelerometer plus a linear head did not beat a handful of handcrafted motion
features on either task tried: sitting vs lying down (pilot 1, 19 Nov) and movement intensity
(pilot 2, 24 Nov, with the walking, tennis, washing, piano and stairs data recorded that day). The
pre-declared gate failed on both intensity variants. No head is shipped; the worker and backend
hook stay in the repository, off by default, so the result can be rerun when there is more data.
For still or active, the rule-based `movement` field in the live summary needs no model.

## What it is

- **Encoder:** MOMENT-1-small (AutonLab, MIT licence), a T5 encoder over 8-sample patches of a
  512-sample series; 512-number embedding per channel. Frozen, CPU, about 150 MB of weights and
  roughly 30 to 90 ms for the four channels of one window.
- **Head:** multinomial logistic regression (numpy/scipy, L2, class-balanced) on the embedding,
  saved as the same two files as a PaPaGei head (`<id>.json` + `<id>.npz`), in
  `ml-inference-server/heads_imu/`.
- **Worker:** `ml-inference-server/moment_app.py`, a second Flask service beside the PaPaGei one
  (port 5001, `POST /infer`, `GET /heads`, `GET /health`). Not in any compose file.
- **Backend:** with `LIVE_ACTIVITY_MODEL=<head id>` the backend sends the newest 10 s of
  accelerometer to the worker every 5 s per recording and adds `activity` to the live summary.

## Pilot 2 (24 Nov 2026): movement intensity

### Target, declared before any result was looked at

Free-text labels are normalised (`normalise_label` in `scripts/moment_windows.py`: "Skipping
roppe" is skipping, "Plahing piano" is piano, "Hanging washing" is washing, "Tennis practise" is
tennis) and mapped to three classes:

| Class | Activities |
|---|---|
| still | sitting, lying down, standing, piano |
| light | walking, washing, stairs |
| vigorous | tennis, skipping, jogging on the spot, star jumps, pushups, running |

"Loose ring" / "Lopse ring" is a fault, not an activity, and typing is not in the mapping: both
are excluded, and they end a bout. The 2-class variant is still vs active (light plus vigorous).

Windows: 10 s every 5 s, at least 80% of the nominal samples, one intensity class covering at
least 90% and no other class overlapping (so sitting then standing is kept, walking then sitting
is dropped). Ring only (25 Hz; earbuds are a different body site and unit).

### Data that exists

Ring windows after filtering (28 recordings): still 3,919, light 137, vigorous 47.

| Recording (ring session) | still | light | vigorous | Contains |
|---|---|---|---|---|
| 65 (session 147, 24 Nov) | 380 | 137 | 38 | sitting 282, piano 71, standing 27; walking 53, washing 79, stairs 5; tennis 38 |
| 61 (session 140) | 259 | 0 | 9 | sitting 152, lying 88, standing 19; pushups 4, jogging 2, star jumps 3 |
| 26 other recordings | 3,280 | 0 | 0 | sitting, lying down, standing |

Per-recording counts are in `outputs/moment_v2/results.json`. Three things limit this:

- **Skipping has no ring samples.** The ring was not delivering data during the labelled skipping
  minute (and delivered only 7 to 17 samples per second during parts of the walking and tennis
  bouts), so those windows fail the 80% rule. Vigorous is tennis (one bout, 38 windows) plus nine
  windows of recording 61.
- **Light exists in one recording only.** Recording 65 is the only recording with light windows and
  one of two with vigorous ones, so leave-one-recording-out cannot test the 3-class task except by
  training without the class (see below). Everything was worn by one person.
- Session 147 holds 3,475 s of data against 60.9 min of labels: the first three minutes of labels
  have no samples.

### Evaluation

Same head and folds for every feature set; scores on pooled out-of-fold windows.

- **loro**: leave-one-recording-out, 28 folds.
- **lobo**: leave-one-bout-out inside recording 65. A bout is a run of abutting annotations of one
  class (8 bouts with windows: still 3, light 4, vigorous 1). Test windows are the held-out bout;
  training is every window of every other recording plus recording 65 windows that do not overlap
  the bout plus or minus 10 s (nothing from the held-out bout or its neighbouring 10 s trains).
  This is the more informative test but remains one wearer on one day, so it is not a
  held-out-person estimate.

Feature sets: `majority` (training fold's commonest class); `motion_basic` (log std of the
magnitude, of its first difference, per-axis std); `motion` (that plus sample entropy, std of the
gravity-free dynamic magnitude, mean absolute deviation, three log band energies, dominant
frequency; the handcrafted baseline of the gate); `moment_mag`, `moment_xyz` (x, y, z embeddings
concatenated), `moment_dyn` (embedding of the dynamic magnitude |a - mean a|, i.e. gravity
direction removed before embedding); `+amp` adds log std of the magnitude and of the dynamic
magnitude, the amplitude MOMENT's instance normalisation removes; `+motion` adds the whole
handcrafted block. Head: class-balanced L2 logistic regression, lambda = 10 fixed in advance; the
`|inner` rows choose lambda from 1, 10, 100, 1000 by 3-fold cross-validation grouped by bout
inside the training fold only. Nothing was tuned on a held-out fold.

**Gate (declared in advance):** keep MOMENT only if its best variant beats `motion` by at least
0.05 macro-F1 in lobo **and** beats the majority class on balanced accuracy in loro.

### Results: 3 classes (still / light / vigorous)

| Features | lobo acc | lobo bal. acc | lobo macro-F1 | loro acc | loro bal. acc | loro macro-F1 |
|---|---|---|---|---|---|---|
| majority | 0.685 | 0.333 | 0.271 | 0.955 | 0.333 | 0.326 |
| motion_basic | 0.598 | 0.492 | 0.451 | 0.760 | 0.567 | 0.343 |
| **motion** | 0.703 | 0.491 | **0.481** | 0.868 | 0.590 | 0.392 |
| moment_mag | 0.742 | 0.428 | 0.436 | 0.930 | 0.381 | 0.383 |
| moment_xyz | 0.719 | 0.401 | 0.396 | 0.940 | 0.363 | 0.376 |
| moment_dyn | 0.681 | 0.394 | 0.389 | 0.907 | 0.372 | 0.386 |
| moment_mag + amp | 0.726 | 0.417 | 0.424 | 0.934 | 0.382 | 0.385 |
| moment_dyn + amp | 0.697 | 0.403 | 0.401 | 0.925 | 0.393 | 0.411 |
| moment_mag + motion | 0.732 | 0.420 | 0.427 | 0.937 | 0.404 | 0.407 |
| moment_dyn + motion | 0.710 | 0.413 | 0.413 | 0.929 | 0.415 | 0.420 |
| motion, lambda inner | 0.701 | 0.512 | 0.488 | 0.871 | 0.591 | 0.394 |
| moment_mag, lambda inner | 0.742 | 0.422 | 0.427 | 0.917 | 0.348 | 0.350 |
| moment_mag + amp, lambda inner | 0.748 | 0.431 | 0.440 | 0.921 | 0.363 | 0.364 |

Confusion matrices (rows true still, light, vigorous; columns predicted), lobo: motion
[[331, 41, 8], [19, 50, 68], [1, 28, 9]]; moment_mag [[369, 10, 1], [43, 43, 51], [0, 38, 0]].
Per-bout recall (lobo): the tennis bout is recalled 0.24 by motion and 0.00 by every MOMENT
variant (all 38 windows called light); the long walking bout 0.27 (motion) and 0.21 (moment_mag);
still bouts 0.81 to 0.90 (motion) and 0.90 to 1.00 (moment_mag). All variants and the other
confusion matrices are in `outputs/moment_v2/results.json`.

Gate: best MOMENT variant `moment_mag`, lobo macro-F1 0.436 against 0.481 for `motion`
(difference -0.045; 95% interval over resampled bouts -0.12 to +0.11). **Fails.** In loro the
MOMENT variants beat the majority class on balanced accuracy only by predicting the rare classes
a few times (0.35 to 0.42 against 0.333) while motion reaches 0.59.

### Results: 2 classes (still / active)

| Features | lobo acc | lobo bal. acc | lobo macro-F1 | loro acc | loro bal. acc | loro macro-F1 |
|---|---|---|---|---|---|---|
| majority | 0.685 | 0.500 | 0.406 | 0.955 | 0.500 | 0.489 |
| motion_basic | 0.796 | 0.820 | 0.784 | 0.811 | 0.859 | 0.596 |
| **motion** | 0.888 | 0.888 | 0.875 | 0.909 | 0.875 | 0.701 |
| moment_mag | 0.897 | 0.854 | 0.874 | 0.940 | 0.562 | 0.575 |
| moment_xyz | 0.863 | 0.804 | 0.827 | 0.940 | 0.503 | 0.500 |
| moment_dyn | 0.791 | 0.713 | 0.730 | 0.915 | 0.523 | 0.522 |
| moment_mag + amp | 0.899 | 0.860 | **0.877** | 0.943 | 0.566 | 0.582 |
| moment_dyn + amp | 0.816 | 0.750 | 0.768 | 0.930 | 0.544 | 0.549 |
| moment_mag + motion | 0.897 | 0.859 | 0.875 | 0.943 | 0.574 | 0.590 |
| moment_dyn + motion | 0.856 | 0.805 | 0.822 | 0.937 | 0.571 | 0.580 |
| motion, lambda inner | 0.886 | 0.882 | 0.872 | 0.912 | 0.858 | 0.700 |
| moment_mag + motion, lambda inner | 0.901 | 0.864 | 0.880 | 0.942 | 0.545 | 0.557 |

Inside the one long recording, MOMENT magnitude ties the handcrafted features at still-vs-active
(0.874 to 0.877 against 0.875; the walking bout is recalled 0.61 against 0.84, tennis 0.97 against
1.00). It does not generalise across recordings: across recordings it finds 27 of the 184 active
windows (recall 0.15; loro balanced accuracy 0.56) where motion finds 154 (0.84; 0.875).
Gate: difference +0.002 (95% interval -0.09 to +0.19), below the 0.05 margin. **Fails.**

### Does giving MOMENT the amplitude and gravity-free signal help?

A little in the 2-class lobo case and not otherwise. Adding the amplitude features (`+amp`) moved
moment_mag from 0.874 to 0.877 (2 classes) and from 0.436 to 0.424 (3 classes); embedding the
dynamic magnitude (`moment_dyn`) was worse than the plain magnitude (0.730 and 0.389). Tuning the
ridge penalty inside the training folds changed no conclusion (lobo macro-F1 within 0.02 of the fixed
lambda; in loro the MOMENT variants fell by up to 0.06, for example moment_mag + motion from 0.407 to 0.350).
MOMENT's embedding adds nothing the handcrafted block does not already have: moment_mag + motion
(0.427 / 0.875) does not beat motion alone (0.481 / 0.875).

### Not done

MOMENT-1-base and -large were not downloaded (public AutonLab weights, hundreds of MB to over 1
GB, not fetched without approval). With the small model never ahead of the handcrafted baseline,
a larger encoder is unlikely to change the verdict; the data (one wearer, 47 vigorous windows, no
ring data for skipping) is the binding limit.

## Pilot 1 (19 Nov 2026): sitting vs lying down

Evaluated on ring windows, leave-one-recording-out, 2,044 windows (1,581 sitting, 463 lying
down), 24 recordings. A posture label, and lying down is mostly a different direction of gravity,
which MOMENT's reversible instance normalisation removes from each channel.

| Features | Accuracy | Balanced accuracy | Macro-F1 |
|---|---|---|---|
| majority class | 0.773 | 0.500 | 0.436 |
| motion (handcrafted) | 0.575 | 0.509 | 0.495 |
| motion + gravity direction | 0.494 | 0.334 | 0.346 |
| MOMENT, magnitude (512) | 0.558 | 0.389 | 0.392 |
| MOMENT, x/y/z (1,536) | 0.604 | 0.399 | 0.389 |
| MOMENT magnitude + gravity | 0.553 | 0.373 | 0.375 |

MOMENT did not beat the majority class or the handcrafted features. With only two lying-down
recordings, even the handcrafted features were near chance; this was not a fair test of what
MOMENT can do, which is why pilot 2 moved to a target an accelerometer foundation model can
reasonably work on. It did not change the outcome.

## Method notes

Resampling, so the numbers can be reproduced: the samples whose backend arrival time falls in the
10 s before a window end are taken in stored order (ring 25 Hz, so about 250 samples) and
**linearly interpolated over the sample index onto 512 evenly spaced points**, first and last
samples at the ends (a ring window is stretched by about 2.05x). The window is assumed uniformly
sampled; arrival times are batchy, so edges are accurate to about one packet. Channels x, y, z,
the magnitude and (pilot 2) the dynamic magnitude are embedded separately. The encoder
(`ml-inference-server/moment_encoder.py`) reimplements the `momentfm` package's `embed()` for one
channel and was checked against the package on the same checkpoint (maximum absolute difference
0.0 on a test window). Labels are typed by the person recording, not verified; annotation and
sample clocks are both server arrival times and agree to about a second; units are device-native,
so ring and earbud are never mixed.

## Limits

- One wearer, a few days. Nothing is known about other people.
- Light activity exists in one recording, vigorous in two (47 windows); skipping has no ring data.
- Earbud accelerometer: not evaluated for intensity (no light data; vigorous in one recording).
- Absolute scale and gravity are not in a MOMENT embedding; the head can only add them back
  through separate handcrafted features, which then do the work.

## What would change the verdict

Several recordings of walking, tennis or running, and skipping with the ring actually streaming,
from at least two wearers; leave-one-recording-out (or person) macro-F1 beating `motion` by 0.05 and
the majority class on balanced accuracy. Until then the sentence for the thesis is that a frozen
accelerometer foundation model was tried and did not beat simple motion features.

## How to retrain and run

Weights: `AutonLab/MOMENT-1-small` (public, MIT, 151 MB `model.safetensors` plus `config.json`)
downloaded outside the repository, never committed. On this machine:

```bash
pip install --target <cache>/pylib transformers safetensors huggingface_hub   # torch is already in the venv
PYTHONPATH=<cache>/pylib python -c "from huggingface_hub import snapshot_download; \
    snapshot_download('AutonLab/MOMENT-1-small', cache_dir='<cache>/hf')"
```

`ml-inference-server/requirements-moment.txt` lists the packages. Then, with a local stack running
(`scripts/local.sh up`; the script reads its MySQL read-only) and `MOMENT_DIR=<cache>/hf`:

```bash
python scripts/moment_windows.py --out <scratch>/imu_windows_v2.npz     # windows + embeddings, about 5 min
python scripts/moment_intensity_eval.py --windows <scratch>/imu_windows_v2.npz \
    --json outputs/moment_v2/results.json                                 # about 11 min on CPU
# only if the gate passes (it did not); the window label is the intensity class:
python scripts/train_activity_head.py --windows <scratch>/imu_windows_v2.npz \
    --classes still light vigorous --feature-set moment_mag --save activity_intensity_v1
```

The training script refuses ("NOT EVALUABLE") when a class occurs in fewer than two recordings.
`--save` writes `<head_id>.json` and `.npz` to `ml-inference-server/heads_imu/`.

To run it live (nothing does by default):

```bash
MOMENT_DIR=<cache>/hf python ml-inference-server/moment_app.py        # port 5001
LIVE_ACTIVITY_MODEL=<head_id> LIVE_ACTIVITY_URL=http://127.0.0.1:5001 ...   # backend env
```

The worker is not in any compose file or Dockerfile and nothing deploys it.

## What the backend does

- `LIVE_ACTIVITY_MODEL` unset, empty, `off`, `false`, `0` or `none`: nothing is requested and the
  summary has no `activity` key.
- Set: every 5 s per recording the backend posts the accelerometer samples that arrived in the last
  10 s (skipped when fewer than 80% of the nominal count are present) to
  `LIVE_ACTIVITY_URL/infer` with the head id and the device type.
- A missing, slow or failing worker only means no `activity` field; an estimate older than 15 s
  disappears. Ingestion, the heart-rate model, rule-based `movement` and alerts never wait for it.
  Failures are logged on the first and then once a minute.
- Estimates are live only; they are not written to `derived_windows`.

**Field for the console:** `summary.activity`, per session in the live snapshot (the same object
that carries `ppg`, `movement` and `model`), absent when off or stale:

```json
"activity": {
  "label": "sitting",
  "confidence": 0.83,
  "source": {"model_id": "moment_1_small", "head_id": "<head id>", "head_version": "<version>"},
  "validation_status": "<the head's one-line verdict; show it>",
  "runtime": "torch-cpu",
  "window_end_ms": 1794350000000,
  "inference_ms": 62.0
}
```

`confidence` is the largest class probability, not a measured accuracy. Display it beside
`validation_status`, and keep `movement.state` (rules) as the primary still/active indicator.

## Files

| File | Purpose |
|---|---|
| `ml-inference-server/moment_encoder.py` | MOMENT loader, resampling, handcrafted statistics, feature vector |
| `ml-inference-server/moment_app.py` | the optional worker |
| `ml-inference-server/model_heads.py` | head loader, now takes a head folder and a base model id |
| `ml-inference-server/heads_imu/` | heads for it (none shipped) |
| `ml-inference-server/tests/test_moment_imu.py` | resampling, head loader, worker endpoint (stub encoder) |
| `scripts/moment_windows.py`, `scripts/moment_features.py` | extraction (label normalisation, intensity mapping, bouts) and handcrafted features |
| `scripts/moment_intensity_eval.py`, `scripts/train_activity_head.py` | leave-one-recording / leave-one-bout-out evaluation; head fitting and saving |
| `outputs/moment_v2/` | results.json and the evaluation log of pilot 2 |
| `scripts/tests/test_moment_intensity.py` | label mapping, bouts, guard, features |
| `backend/src/live.rs`, `backend/src/main.rs` | `activity_windows`, `ActivityEstimate`, `run_live_activity` |
