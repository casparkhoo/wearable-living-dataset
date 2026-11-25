# Adding a model head

A **head** is a small model applied to PaPaGei-S's 512-number embedding of a 10 s PPG window. The
encoder is frozen and shared; a head turns the embedding into one answer (here, a heart rate in
beats per minute). This page covers training a head from recordings you have already collected,
registering it, running it live, and judging whether it is any good. For other kinds of model
(a different encoder, an activity classifier) see [EXTENDING.md](EXTENDING.md) and the
[processor contract](reference/PROCESSOR_CONTRACT.md).

Read the last two sections before you trust a head: the one that ships today does not yet earn it.

## How a head is used

1. Every 5 s the backend sends the newest 10 s of PPG to the worker (`POST /infer` with
   `head_id`). The worker band-passes, resamples to 125 Hz and normalises the window, computes
   the embedding, and applies `ml-inference-server/heads/<head_id>.npz` (`model_heads.apply_head`:
   `embedding @ weights + bias`).
2. The backend stores the result as a derived window with method `papagei_s+<head_id>` and the
   manifest's `head_version` as its version, and the Live Analysis Engine can show it to the
   participant ([ARCHITECTURE.md](ARCHITECTURE.md), "Which heart rate a participant sees").
3. `ML_LIVE_HEAD_ID` names the head used live and by `recompute`. Heads that are installed but not
   named there are not run.

## Files that make a head

Two files in `ml-inference-server/heads/`, with the same stem:

| File | Content |
|---|---|
| `<head_id>.npz` | `weights` (shape `512 x 1` for a regression head) and `bias` (shape `1`), float32, applied to the **raw** embedding. Any input standardisation must be folded into the weights, as `train_head_from_rules.py` does |
| `<head_id>.json` | The manifest |

Manifest fields the worker requires (a manifest missing any of them is skipped by the worker's
`/heads` listing, and `/infer` fails if one it needs is absent): `head_id`, `head_version`, `base_model_id` (must be `papagei_s`),
`input_dimension` (must equal the embedding size, 512), `output_type` (`regression`, or
`classification` with a `labels` list and one weight column per label) and `weights_file`.
Also write, because the backend stores them and people read them:

- `display_name`, `unit` (`bpm`), `task_description`;
- `training`: which recordings, how many labelled windows, label range, regularisation, how the
  labels were made, any augmentation;
- `validation`: the method and numbers from a recording the head did not train on;
- `validation_status`: one honest line, such as "pilot: labels from the rules engine, one held-out
  resting recording". The backend copies it onto every stored estimate.

`ml-inference-server/heads/hr_ring_stretch_v2.json` is a complete example.

Optional, `input_transform`: `{"invert": true}` makes the worker negate the raw window before preprocessing
whenever a request names this head (`/infer` with `head_id`, so live windows and `recompute` alike). Use it only
for a head trained on negated windows: the ring reports detected light, which falls when blood volume rises, so
its pulse is upside down relative to the photoplethysmogram PaPaGei was trained on
(`train_head_from_rules.to_model_input`). It is off unless the value is literally `true`, and every head
installed today leaves it off.

## Train one from the Live Analysis Engine's labels

`scripts/train_head_from_rules.py` trains a heart-rate head for the OpenRing and tests it on a
recording it did not see. It needs a running local stack (`scripts/local.sh up`) holding your
recordings, because it reads windows from the stack's MySQL and computes embeddings in its PaPaGei
container through `docker compose exec`; nothing leaves the machine. It uses plain numpy for the
ridge regression, so it does not need scikit-learn.

```bash
python scripts/train_head_from_rules.py \
    --test-session <recording id to hold out> \
    --reference <EmbracePlus pulse-rate.csv covering that recording> \
    --save my_head_id
```

What it does:

1. Takes every OpenRing recording except the held-out one, and the windows the rules engine
   rated `good` or `fair` (stored in `derived_windows` as method `live_ppg`). The rules' heart
   rate is the label.
2. Time-stretches each window by factors 0.67 to 1.5 and scales the label by the same factor
   (a pulse squeezed into a shorter span has a proportionally higher rate). Without this the
   resting recordings cover too narrow a range and the head learns to answer one number.
3. Embeds everything, chooses the ridge strength by leave-one-recording-out on the rule labels,
   and prints what a constant guess scores on the same check.
4. Fits the head, then sets the offset so the unstretched windows are right on average.
5. Scores the old head (`--old-head`, default `hr_ring_embraceplus`), the new head and the rules
   on the held-out recording against the reference, per minute.
6. With `--save`, writes `<id>.npz` and `<id>.json`. The manifest's `head_version` and
   `display_name` are fixed strings in the script; edit them (and `validation_status` if the
   evidence supports a better one) before you publish a head.

`scripts/papagei_posthoc.py --save-head <id>` is the earlier route: it trains on windows labelled
directly by EmbracePlus per-minute pulse rate (`--labelled ring.tsv:trace.txt ... --embrace
<embrace csv>`) with scikit-learn. It needs the PaPaGei weights locally and scikit-learn
installed (see [TROUBLESHOOTING.md](TROUBLESHOOTING.md) if your machine blocks it).

To train from a different label source (a chest strap, say), copy the structure of
`train_head_from_rules.py`: replace `rule_windows` with your labels, keep everything else.

## Register it and run it live

1. Put both files in `ml-inference-server/heads/` (commit them if they are meant to be shared).
2. Rebuild the worker, because the Docker image copies `heads/` in: `scripts/local.sh up`
   rebuilds what changed. On a server, rebuild the `papagei` service.
3. Check the worker sees it: `GET /heads` on the worker lists installed heads (port 5000 inside
   the stack; the compose files do not publish it, so run your own worker as in
   [DEVELOPER_SETUP.md](DEVELOPER_SETUP.md) to try a head before deploying it).
4. Set `ML_LIVE_HEAD_ID=<head_id>` in `deployment/local/.env` (or `deployment/cloud/.env`) and run
   `scripts/local.sh up` again. The backend logs `live model enabled` with the head id on start.
5. To run it over an old recording as well: `wearable-backend recompute <recording id>` applies
   the head named by `ML_LIVE_HEAD_ID`, writing windows under `papagei_s+<head_id>`.
6. Whether participants *see* the model's number is separate: `LIVE_HR_SOURCE=papagei` (default)
   or `rules`.

Because results are keyed by head id and version, the old and new heads' windows can sit in the
same database and be scored on the same recordings.

## Evaluate it honestly

A head that returns a plausible-looking number is not a head that tracks heart rate. The first
head shipped here answered about 62 bpm whatever the pulse did, and looked fine on its own
numbers. Rules to follow:

1. **Compare with the constant baseline.** On the minutes you score, work out the error of the
   single best constant (the median of the reference). A head that does not beat it has shown
   nothing. `scripts/score_methods.py` prints it as `best constant` for every method, and
   `train_head_from_rules.py` prints it as `best constant MAE`.
2. **Hold out whole recordings.** Windows from one recording are strongly correlated; a random
   split of windows tests memory, not generalisation. Leave one recording out, train on the rest,
   score on the one left out. `score_methods.py` warns when a head is scored on its own training
   recordings (it reads `training.recordings` from the manifest, which is why that list matters).
3. **Look at spread and correlation, not only error.** A head with a small MAE and a standard
   deviation near zero is a constant. Report the standard deviation of the estimates beside the
   reference's, and Pearson r.
4. **Use a range the head can be tested on.** Resting recordings cover about 55 to 70 bpm. A
   head cannot be shown to track changes from them. Include exercise or recovery in the
   held-out recording, or say that it is untested outside that range.
5. **Know what the labels are.** Labels from the rules engine mean the head cannot be better
   than the rules on those windows. The EmbracePlus is a wrist PPG device with its own error,
   not a ground truth. A chest-strap ECG gives beat-level labels and is the better reference.
6. **Write the result into the manifest** (`validation`, `validation_status`) whatever it is.

Score a head over recordings with:

```bash
python scripts/score_methods.py <experiment export.zip> --reference <pulse-rate.csv> --out scores
```

## Current honest state

Two heads are installed, and neither is validated:

| Head | Trained on | Held-out check |
|---|---|---|
| `hr_ring_embraceplus` | 134 windows from three resting recordings, labelled by EmbracePlus; reference spanned about 55 to 67 bpm | Leave-one-out MAE 3.8 bpm over 12 minutes; the manifest says "not yet shown to beat a constant baseline". On a 37-minute recording it answered 61 to 63 bpm throughout |
| `hr_ring_stretch_v2` (default) | 1,274 windows from 11 recordings, rules-engine labels, time-stretched, label range about 30 to 135 bpm | See below |

`hr_ring_stretch_v2` **varies, but does not yet track the EmbracePlus on the resting held-out
recording**. Recording 66, 37 minutes, per-minute medians against EmbracePlus: MAE 5.4 bpm, bias
+2.3, standard deviation of the estimates 5.3 against 2.7 for the reference, Pearson r about
-0.02, while the best constant scores an MAE of 1.9. The head moves, so it no longer repeats one
number, but its movement is not following the pulse in that recording, and it is worse than a
constant there. Its manifest says so: `validation_status` is "pilot: labels from the rules engine,
one held-out resting recording". It is the default because it shows a changing rate and the
display falls back to the rules when there is no fresh model estimate; the rules engine remains
what the stored `live_ppg` windows and the fault-detection evaluation use.

What would change this, in order of value: chest-strap labels covering a wider range of rates
(rest, walking, recovery); more recordings with the same wearer and with others; a held-out
recording with real variation; then retraining and reporting both heads side by side on the same
recordings.

## Extension point: an IMU or activity model (pilot built, off, no head shipped)

A pilot exists: frozen MOMENT-1-small embeddings of 10 s accelerometer windows with a
classification head. It is documented in [IMU_MODEL.md](IMU_MODEL.md), including the label
inventory and a **negative result** (with the activity labels recorded so far, only ring
"sitting vs lying down" can be tested, and nothing beats the majority-class or handcrafted
baselines), which is why no head is installed and the feature is off. The wiring is the pattern
to copy for any other non-PaPaGei model:

- **A worker** per model, with its own port and `POST /infer`: `ml-inference-server/moment_app.py`
  (port 5001; needs torch and transformers, so it is separate from `app.py` and in no compose
  file). It builds the head's input itself, from the manifest's `features` list.
- **Heads** in the same two-file format but in their own folder, `ml-inference-server/heads_imu/`,
  so the PaPaGei worker never lists them. `base_model_id` is `moment_1_small`; `output_type` is
  `classification` with `labels`, one weight column per label; extra manifest fields: `features`
  (`moment:<channel>`, `stat:<name>`, `logstat:<name>`, in input order) and `device_types`
  (the worker refuses another device, since ring and earbud units differ). `model_heads.apply_head`
  and `available_heads` take a `base_model_id` and a folder. Train with
  `scripts/train_activity_head.py` (leave-one-recording-out, with majority and handcrafted
  baselines, refuses classes that occur in fewer than two recordings).
- **Backend wiring** is separate from the heart-rate loop: `LiveEngine::activity_windows` (off
  unless `LIVE_ACTIVITY_MODEL` names a head), `ActivityEstimate`, and `run_live_activity` in
  `backend/src/main.rs`. The result is `activity` in the live summary
  (`label`, `confidence`, `source.model_id`, `source.head_id`, `validation_status`), live only,
  expiring after 15 s, and the backend works unchanged when the worker is absent.
- **Not done:** storing activity estimates as derived windows, `recompute` for them, and a scorer
  for categorical output (`score_methods.py` is built for heart rate against a reference device).

## Train on public datasets with true heart rate (tried 23 Nov, negative result)

`scripts/train_head_public.py` trains the same ridge head on PPG-DaLiA (wrist BVP, 15 subjects, 8 activities, ECG-derived HR) and BIDMC (53 ICU recordings, PPG and monitor HR), then scores it on a held-out ring session against the EmbracePlus with the same per-minute scoring as `train_head_from_rules.py`.

Method:

- Public signals are resampled to 25 Hz with an anti-aliasing polyphase filter, cut into 10 s windows with a 5 s step, labelled with the mean reference HR in the window, and embedded by the worker with `sample_rate=25`, as ring windows are. No time-stretch augmentation (the labels are true).
- Ridge alpha by leave-one-subject-out on DaLiA. Variants: DaLiA, DaLiA+BIDMC, DaLiA+BIDMC+own rule-labelled ring windows.
- DaLiA `.pkl` files are Python pickles: read them only with the restricted unpickler in the script (numpy arrays only). BIDMC is read from its CSV files. Embeddings are cached outside the repo.
- A head is saved (as `hr_public_v1`) only if on the held-out session it beats the best constant and has r above 0.5.

Result (tables in `outputs/headtrain_public/results.md`): leave-one-subject-out on DaLiA gives MAE 13.7 bpm and r 0.59 (a constant gives 18.1), weak at cycling and stairs. On ring session 140 every variant fails: MAE 24.8 to 28.7 bpm (best constant 11.68), bias +18 to +23 bpm, r between -0.13 and 0.22. The heads answer 73 to 98 bpm whatever the ring does, so the public-data prior does not transfer to the ring signal. No head was saved.

Domain note: DaLiA is wrist and BIDMC is ICU finger/ear-style transmittance; neither is the ring's green channel. The rate difference was handled by downsampling the public data to 25 Hz, not by changing the ring data.

## Wide own-data range with EmbracePlus minute labels (tried 24 Nov, negative result)

`scripts/train_head_wide.py` trains the ridge head on the wearer's own recording of 23 Nov (ring session 140 green, earbud sessions 139 and 141 infrared; reference 51-113 bpm from the EmbracePlus per-minute pulse rate) and tests it by leave-one-time-block-out: rest, exercise, recovery. Run `prepare` (database and embeddings, cached under `~/data_public/cache_wide`), then `evaluate --reference <pulse-rate.csv> [--save]`.

- Leakage rules: a held-out block removes the windows of all three devices from training (they share one heart-rate trajectory) plus a 30 s guard; straddling windows are dropped; ridge alpha is chosen only inside the training part. Added sources (older rule-labelled time-stretched windows, DaLiA and BIDMC) each get the same total weight as the own windows.
- Labels are the EmbracePlus value of the minute holding the window centre, or interpolated between minute midpoints (never across a missing minute); either way a window label is uncertain by about 30 s.
- Gate, with the candidate (own + stretch + public, ring + earbud training, interpolated labels) fixed beforehand: on the held-out exercise block MAE below the best constant and r above 0.5, then a head `hr_wide_v1` is written. It was not: exercise MAE 27.9 against a best constant of 10.0 (five minute labels), no variant beat the constant on any block, and the per-minute output never reached above 86 bpm (reference to 113). Own-data-only heads collapse to the training mean. Nothing was saved and the default head is unchanged.
- Results and caveats (single recording, correlated blocks, label noise): `outputs/headtrain_wide/results.md`. Tests: `scripts/tests/test_train_head_wide.py`.
