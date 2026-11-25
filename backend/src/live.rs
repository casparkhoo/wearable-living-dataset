//! Live exploratory analysis of streams while they are being recorded.
//!
//! Every stored sample is also pushed into a short in-memory buffer per device
//! recording and stream. Once a second the engine summarises the last ten
//! seconds of each buffer: transport health for every stream, pulse quality
//! and heart rate for PPG, a movement state for the accelerometer, and a
//! cross-check that separates a poorly fitted sensor from motion artefact.
//!
//! The summaries are derived views. They are computed from samples that have
//! already been written as evidence, they never modify that evidence, and
//! every output carries the recipe version and the window it was computed
//! over. Timing here is backend arrival time, so the health figures describe
//! delivery to the cloud, not the device's own sample clock.
//!
//! Alerts use hysteresis so a single noisy window does not flash a warning on
//! a participant's phone: a condition must hold for several consecutive
//! windows before it is raised and be absent for several before it clears.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet, VecDeque};
use std::sync::Mutex;

use serde::Serialize;
use serde_json::{json, Map, Value};

/// Names the live analysis recipe in every summary, alert and derived row.
/// Rows written before 7 Nov 2026 say `live-eda/1.0.0`: the same algorithm
/// under its earlier name.
pub const RECIPE_VERSION: &str = "live-ppg/1.3.0";
pub const WINDOW_MS: i64 = 10_000;
pub const TICK_MS: u64 = 1_000;

/// Samples older than this are dropped; slightly longer than the window so
/// out-of-order arrivals near the edge are still counted.
const RETAIN_MS: i64 = 12_000;
/// Rate and quality checks wait until a stream has been observed this long,
/// otherwise every new recording would start with a "rate low" alert.
const WARMUP_MS: i64 = 5_000;
/// No sample for this long marks a stream as stalled. 3 s raised "stopped
/// sending" over and over for a ring whose samples were all reaching the phone:
/// the phone now sends half-second batches, and over a relayed mobile link a
/// batch can arrive a few seconds late. 5 s still catches a real stall.
const STALL_MS: i64 = 5_000;
const RAISE_AFTER: u32 = 3;
const CLEAR_AFTER: u32 = 5;
/// Signal quality is a statistic over a whole 10 s window, and consecutive
/// windows overlap by 9 s, so three in a row is barely three seconds of
/// evidence. A borderline signal crossed that line and back several times a
/// minute; quality alerts need longer to raise and to clear.
///
/// In use these were still far too eager: a participant was told "check fit"
/// several times a minute, mostly by the earbuds, whose in-ear signal sits near
/// the quality line. A fit problem worth interrupting someone for lasts; one
/// that comes and goes within 20 s is not acted on anyway.
const QUALITY_RAISE_AFTER: u32 = 20;
const QUALITY_CLEAR_AFTER: u32 = 15;
/// Clipping and movement come and go with posture: they must persist too.
const SIGNAL_RAISE_AFTER: u32 = 10;
const SIGNAL_CLEAR_AFTER: u32 = 10;
/// Ear-worn devices: quality hovers near the line while worn, so even longer.
const EAR_QUALITY_RAISE_AFTER: u32 = 30;
const RATE_LOW_RATIO: f64 = 0.75;
const LOSS_RATIO_LIMIT: f64 = 0.05;
/// An index jump larger than this is a counter restart, not lost samples.
const INDEX_RESET_JUMP: i64 = 1_000;
const MIN_SAMPLES_FOR_SHAPE: usize = 10;
const RECENT_ALERT_LIMIT: usize = 50;
/// Slowly varying signals are legitimately constant over ten seconds, so they
/// are excluded from flatline and saturation checks.
const SLOW_STREAMS: [&str; 2] = ["temperature", "pressure"];

/// Flatline is judged on the most recent samples only, so a dead sensor is
/// reported within seconds instead of once the whole window has gone flat.
const FLAT_RECENT_MS: i64 = 3_000;
/// Share of a window pinned exactly at its extreme that counts as clipping.
/// Clipping recurs on every beat in short runs, so the share matters more
/// than any single run.
const SATURATION_FRACTION: f64 = 0.05;

const PPG_GOOD_SQI: f64 = 0.6;
const PPG_FAIR_SQI: f64 = 0.35;
/// Pulse-band amplitude as a percentage of the PPG baseline below which there
/// is no pulse to find. Recordings of the τ-Ring on a desk or in its case sit
/// at 0.0004-0.001 % (ADC noise); the same ring in use measured 0.01-0.5 %.
const PPG_NO_PULSE_PERFUSION_PCT: f64 = 0.005;
/// The heart-rate search band, and how far inside its edges a rate must sit to
/// be reported. A slow baseline drift repeats cleanly at the band's lower
/// edge, and every "heart rate" the τ-Ring recordings produced while it was
/// being moved was 40-48 bpm.
const HR_BAND_BPM: (f64, f64) = (40.0, 180.0);
const HR_EDGE_MARGIN_BPM: f64 = 4.0;
const MOVEMENT_STILL: f64 = 0.02;
const MOVEMENT_ACTIVE: f64 = 0.15;
/// Minimum periodicity for the accelerometer to have a cadence at all.
const CADENCE_MIN_STRENGTH: f64 = 0.3;
/// How close the pulse rate must sit to the movement cadence (or its half or
/// double) to be treated as the sensor tracking motion instead of the heart.
const CADENCE_TOLERANCE: f64 = 0.1;

#[derive(Clone, Debug)]
struct Sample {
    arrival_ms: i64,
    index: Option<i64>,
    values: BTreeMap<String, f64>,
}

#[derive(Debug)]
struct StreamBuffer {
    samples: VecDeque<Sample>,
    first_arrival_ms: i64,
    last_arrival_ms: i64,
    nominal_rate_hz: Option<f64>,
}

#[derive(Clone, Debug, Serialize)]
pub struct SessionMeta {
    pub session_id: i64,
    pub device_type: String,
    pub device_address: String,
    pub owner_user_id: Option<i64>,
    pub study_id: Option<i64>,
}

/// An alert is identified by its code and the stream it concerns, if any.
type AlertKey = (String, Option<String>);

#[derive(Debug, Default)]
struct Tracker {
    active: bool,
    present_run: u32,
    absent_run: u32,
    quick: bool,
    severity: String,
    message: String,
    raised_at_ms: Option<i64>,
    /// Last `WEAK_SIGNAL_SPAN` ticks, newest last: whether the condition held. Only used by
    /// alerts that count over a span instead of needing an unbroken run.
    recent: VecDeque<bool>,
    /// Same length as `recent`: whether the held tick was one with movement.
    recent_moving: VecDeque<bool>,
}

#[derive(Debug)]
struct SessionState {
    meta: SessionMeta,
    streams: BTreeMap<String, StreamBuffer>,
    trackers: BTreeMap<AlertKey, Tracker>,
    latest: Option<SessionSummary>,
    /// Recent heart-rate readings (time, bpm), for the trend in the app.
    hr_history: VecDeque<(i64, f64)>,
    /// The model's newest estimate, and its trend beside the live algorithm's.
    model: Option<ModelEstimate>,
    model_history: VecDeque<(i64, f64)>,
    last_model_request_ms: Option<i64>,
    /// The activity model's newest estimate (pilot, off unless LIVE_ACTIVITY_MODEL is set).
    activity: Option<ActivityEstimate>,
    last_activity_request_ms: Option<i64>,
}

/// Seconds of PPG the model takes, and how often a window is sent to it.
pub const MODEL_WINDOW_S: f64 = 10.0;
pub const MODEL_EVERY_MS: i64 = 5_000;
/// An estimate older than this is no longer shown as current.
const MODEL_STALE_MS: i64 = 15_000;

/// One window for the model worker: the newest ten seconds of the PPG channel
/// the live algorithm is using, on the device's sample index.
#[derive(Clone, Debug, PartialEq)]
pub struct ModelRequest {
    pub session_id: i64,
    pub channel: String,
    pub values: Vec<f64>,
    pub fs_hz: f64,
    pub window_end_ms: i64,
    /// The live algorithm's quality figures for the same moment, stored with
    /// the estimate so the two can be compared under like conditions.
    pub live_sqi: f64,
    pub live_hr_bpm: Option<f64>,
    pub movement: Option<&'static str>,
}

/// A heart rate from the model worker (PaPaGei-S plus a regression head).
/// Derived data: it names the model, head and runtime that produced it.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct ModelEstimate {
    pub model_id: String,
    pub head_id: String,
    pub head_version: Option<String>,
    pub validation_status: Option<String>,
    pub runtime: Option<String>,
    pub channel: String,
    pub hr_bpm: f64,
    pub window_end_ms: i64,
    pub inference_ms: Option<f64>,
    pub live_sqi: f64,
    pub live_hr_bpm: Option<f64>,
    pub movement: Option<&'static str>,
}

/// Seconds of accelerometer the activity model takes, and how often a window is sent.
pub const ACTIVITY_WINDOW_S: f64 = 10.0;
pub const ACTIVITY_EVERY_MS: i64 = 5_000;
/// An activity estimate older than this is no longer shown as current.
const ACTIVITY_STALE_MS: i64 = 15_000;
/// A window needs at least this share of its nominal samples; a dropout is skipped, not guessed.
const ACTIVITY_MIN_FILL: f64 = 0.8;

/// One accelerometer window for the activity worker: the samples whose arrival falls in the last
/// ten seconds, in the order they were stored, as x, y, z in the device's own units.
#[derive(Clone, Debug, PartialEq)]
pub struct ActivityRequest {
    pub session_id: i64,
    pub device_type: String,
    pub values: Vec<[f64; 3]>,
    pub fs_hz: f64,
    pub window_end_ms: i64,
}

/// Where an activity estimate came from.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct ActivitySource {
    pub model_id: String,
    pub head_id: String,
    pub head_version: Option<String>,
}

/// An activity label from the worker (frozen MOMENT embedding plus a classification head).
/// A pilot: `validation_status` carries the head's own honest one-line verdict.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct ActivityEstimate {
    pub label: String,
    /// The head's largest class probability, 0 to 1. Not a calibrated accuracy.
    pub confidence: f64,
    pub source: ActivitySource,
    pub validation_status: Option<String>,
    pub runtime: Option<String>,
    pub window_end_ms: i64,
    pub inference_ms: Option<f64>,
}

/// Read the worker's `/infer` answer for a window that was sent with `head_id`.
pub fn activity_from_response(
    body: &Value,
    head_id: &str,
    request: &ActivityRequest,
) -> Result<ActivityEstimate, String> {
    let result = body.get("head_result").ok_or("no head result")?;
    let label = result
        .pointer("/prediction/label")
        .and_then(Value::as_str)
        .filter(|label| !label.is_empty())
        .ok_or("head returned no label")?;
    let confidence = result
        .pointer("/prediction/probabilities")
        .and_then(Value::as_array)
        .map(|values| values.iter().filter_map(Value::as_f64).fold(f64::NAN, f64::max))
        .filter(|value| value.is_finite() && (0.0..=1.0 + 1e-6).contains(value))
        .ok_or("head returned no probabilities")?;
    let head = result.get("head");
    let text = |key: &str| head.and_then(|h| h.get(key)).and_then(Value::as_str).map(str::to_string);
    Ok(ActivityEstimate {
        label: label.to_string(),
        confidence: (confidence.min(1.0) * 1000.0).round() / 1000.0,
        source: ActivitySource {
            model_id: text("base_model_id").unwrap_or_else(|| "moment_1_small".to_string()),
            head_id: head_id.to_string(),
            head_version: text("head_version"),
        },
        validation_status: text("validation_status"),
        runtime: body.get("runtime").and_then(Value::as_str).map(str::to_string),
        window_end_ms: request.window_end_ms,
        inference_ms: body.get("inference_ms").and_then(Value::as_f64),
    })
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct StreamHealth {
    pub sample_count: usize,
    pub observed_ms: i64,
    pub warming_up: bool,
    pub effective_rate_hz: Option<f64>,
    pub nominal_rate_hz: Option<f64>,
    pub rate_ratio: Option<f64>,
    pub last_arrival_age_ms: i64,
    pub stalled: bool,
    /// Data has resumed but the window still spans the stall. Rate, loss and
    /// pulse figures over such a window only restate the stall, so they are
    /// not alerted on until it has passed out of the window.
    pub recovering: bool,
    pub max_gap_ms: Option<i64>,
    pub jitter_ms: Option<f64>,
    /// Which counter the continuity figures come from. The phone assigns
    /// `sample_index`, so gaps in it are losses between phone and cloud.
    pub index_source: Option<&'static str>,
    pub missing: Option<i64>,
    pub duplicates: Option<i64>,
    pub resets: Option<i64>,
    pub loss_ratio: Option<f64>,
    /// Channels examined by the flatline and saturation checks.
    pub channel_count: usize,
    pub flatline_channels: Vec<String>,
    pub saturated_channels: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct PpgSignal {
    pub channel: String,
    pub sample_count: usize,
    pub fs_hz: f64,
    /// Pulse-band amplitude relative to the baseline, in percent. Separates a
    /// sensor with no pulse in view (off the finger) from a poor signal.
    pub perfusion_pct: f64,
    /// Peak normalised autocorrelation within the heart-rate band, 0 to 1.
    pub sqi: f64,
    pub quality: &'static str,
    pub hr_bpm: Option<f64>,
    /// The pulse rate matched the accelerometer's cadence, so the periodicity
    /// is movement, not heartbeat, and no heart rate is reported.
    pub motion_locked: bool,
    /// Why there is no heart rate: `no_pulse` (nothing pulsing under the sensor),
    /// `weak` (a pulse too faint or irregular to trust) or `moving` (movement is
    /// swamping it). Absent when a heart rate is reported.
    pub hr_reason: Option<&'static str>,
    pub hr_reason_text: Option<&'static str>,
    /// The heart rate to show the participant, and which method produced it.
    /// `hr_bpm` above stays the rule-based estimate, because that is what is
    /// stored as the `live_ppg` derived result and rebuilt by recompute; this
    /// is display only. PaPaGei's estimate when it is fresh and there is a
    /// pulse at all, otherwise the rule-based one.
    pub display_hr_bpm: Option<f64>,
    pub hr_source: Option<&'static str>,
}

/// Where a wearable sits on the body. Movement means something different at the
/// ear than at the finger, and the participant is told to adjust the right thing.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum WearSite {
    Finger,
    Ear,
    Chest,
    Other,
}

pub fn wear_site(device_type: &str) -> WearSite {
    match device_type {
        "OpenRing" => WearSite::Finger,
        "OpenEarable" => WearSite::Ear,
        "HeartRateMonitor" => WearSite::Chest,
        _ => WearSite::Other,
    }
}

impl WearSite {
    /// Relative variability of acceleration magnitude below which the wearer is
    /// still, and above which they are active.
    ///
    /// A head is never as still as a resting hand: sitting quietly, the earbuds'
    /// accelerometer varied by a median of 1.2-2.0 % per 10 s window (two earbuds,
    /// 200 s each), so the finger's 2 % limit called the wearer "moving" in 20-45 %
    /// of windows and raised motion alerts while they sat still. 4 % sits above
    /// three quarters of those windows; real head movement (p90 about 9 %) still
    /// counts as moving.
    fn movement_limits(self) -> (f64, f64) {
        match self {
            WearSite::Ear => (0.04, MOVEMENT_ACTIVE),
            _ => (MOVEMENT_STILL, MOVEMENT_ACTIVE),
        }
    }

    fn no_pulse_alert(self, device: &str) -> String {
        match self {
            WearSite::Finger => format!("{device} PPG has no pulse: is it on a finger?"),
            WearSite::Ear => format!("{device} PPG has no pulse: is it in the ear?"),
            WearSite::Chest => format!("{device} has no pulse: is the strap against the skin?"),
            WearSite::Other => format!("{device} PPG has no pulse: is it being worn?"),
        }
    }
}

/// Wording for the participant of each reason a heart rate is missing, for
/// where the device is worn.
pub fn hr_reason_text_for(reason: &str, site: WearSite) -> &'static str {
    match (reason, site) {
        ("no_pulse", WearSite::Finger) => "No pulse found - check the ring is snug on the finger",
        ("no_pulse", WearSite::Ear) => "No pulse found - check the earbud sits snugly in the ear",
        ("no_pulse", WearSite::Chest) => "No pulse found - check the strap is against the skin",
        ("no_pulse", WearSite::Other) => "No pulse found - check the sensor is worn snugly",
        // Movement and a weak signal are ordinary in everyday wear: the wording
        // says what helps, without implying something is wrong.
        ("moving", WearSite::Ear) => "Reading pauses while your head moves",
        ("moving", _) => "Reading pauses while you move",
        (_, WearSite::Finger) => "Signal weak - a snug fit helps",
        (_, WearSite::Ear) => "Signal weak - a snug fit in the ear helps",
        (_, _) => "Signal weak - a snug fit helps",
    }
}

/// Wording for the participant of each reason a heart rate is missing.
pub fn hr_reason_text(reason: &str) -> &'static str {
    match reason {
        "no_pulse" => "No pulse found - check the ring is snug on the finger",
        "moving" => "Hand moving - hold still to read the pulse",
        _ => "Pulse too weak to read - fit snugly, keep the hand warm and still",
    }
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Movement {
    pub sample_count: usize,
    /// Standard deviation of acceleration magnitude relative to its median.
    /// Normalising by the median (gravity when worn) makes it unit-free, which
    /// matters because the ring and earable report device-native units.
    pub relative_std: f64,
    pub state: &'static str,
    /// Dominant repetition rate of the movement, when it has one.
    pub cadence_hz: Option<f64>,
}

#[derive(Clone, Debug, Serialize)]
pub struct ActiveAlert {
    pub code: String,
    pub stream_name: Option<String>,
    pub severity: String,
    pub message: String,
    pub raised_at_ms: Option<i64>,
}

#[derive(Clone, Debug, Serialize)]
pub struct SessionSummary {
    #[serde(flatten)]
    pub meta: SessionMeta,
    pub recipe_version: &'static str,
    pub window_start_ms: i64,
    pub window_end_ms: i64,
    pub streams: BTreeMap<String, StreamHealth>,
    pub ppg: Option<PpgSignal>,
    pub movement: Option<Movement>,
    pub active_alerts: Vec<ActiveAlert>,
    /// The model worker's heart rate, when one is running and recent.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<ModelEstimate>,
    /// The activity model's label, when LIVE_ACTIVITY_MODEL is set and a worker answered
    /// recently. Absent otherwise; never replaces `movement`, the rule-based still/active state.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub activity: Option<ActivityEstimate>,
    /// What to draw: the last window of pulse waveform and movement, and the
    /// heart-rate trend. Derived for display only; not stored with summaries.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub trace: Option<Trace>,
}

/// The newest window of a recording as plain numbers to plot.
#[derive(Clone, Debug, Serialize)]
pub struct Trace {
    pub window_ms: i64,
    pub ppg_channel: Option<String>,
    /// Detrended pulse waveform in sensor counts, oldest first.
    pub ppg: Vec<f64>,
    /// Acceleration magnitude minus its mean, device units, oldest first.
    pub accel: Vec<f64>,
    /// Heart rate over roughly the last five minutes: [time_ms, bpm].
    pub hr_history: Vec<[f64; 2]>,
    /// The model's heart rate over the same period, when it is running.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub model_hr_history: Vec<[f64; 2]>,
    /// The history of the displayed heart rate (see PpgSignal::display_hr_bpm).
    pub display_hr_history: Vec<[f64; 2]>,
}

const TRACE_MAX_POINTS: usize = 300;
const HR_HISTORY_MS: i64 = 300_000;

fn thin(values: Vec<f64>) -> Vec<f64> {
    if values.len() <= TRACE_MAX_POINTS {
        return values;
    }
    let stride = values.len().div_ceil(TRACE_MAX_POINTS);
    values.into_iter().step_by(stride).collect()
}

fn build_trace(
    windows: &BTreeMap<String, Vec<Sample>>,
    healths: &BTreeMap<String, StreamHealth>,
    channel: Option<&str>,
    history: &VecDeque<(i64, f64)>,
    model_history: &VecDeque<(i64, f64)>,
) -> Trace {
    let channel_name = channel.unwrap_or("green");
    let ppg = windows
        .get("ppg")
        .map(|window| {
            let values: Vec<f64> = window
                .iter()
                .filter_map(|sample| sample.values.get(channel_name).copied())
                .collect();
            if values.len() < MIN_SAMPLES_FOR_SHAPE {
                return Vec::new();
            }
            let fs = healths
                .get("ppg")
                .and_then(|health| health.nominal_rate_hz)
                .unwrap_or(25.0);
            let shaped = moving_average(
                &detrend(&values, (fs * 1.5).round() as usize),
                ((fs / 12.0).round() as usize).max(1),
            );
            thin(shaped.into_iter().map(|v| v.round()).collect())
        })
        .unwrap_or_default();
    let accel = windows
        .get("accel")
        .map(|window| {
            let magnitudes: Vec<f64> = window
                .iter()
                .filter_map(|sample| {
                    let (x, y, z) = (
                        *sample.values.get("x")?,
                        *sample.values.get("y")?,
                        *sample.values.get("z")?,
                    );
                    Some((x * x + y * y + z * z).sqrt())
                })
                .collect();
            if magnitudes.is_empty() {
                return Vec::new();
            }
            let mean = magnitudes.iter().sum::<f64>() / magnitudes.len() as f64;
            thin(magnitudes.into_iter().map(|m| (m - mean).round()).collect())
        })
        .unwrap_or_default();
    Trace {
        window_ms: WINDOW_MS,
        ppg_channel: if ppg.is_empty() { None } else { Some(channel_name.to_string()) },
        ppg,
        accel,
        hr_history: history.iter().map(|&(t, bpm)| [t as f64, (bpm * 10.0).round() / 10.0]).collect(),
        model_hr_history: model_history
            .iter()
            .map(|&(t, bpm)| [t as f64, (bpm * 10.0).round() / 10.0])
            .collect(),
        display_hr_history: Vec::new(),
    }
}

#[derive(Clone, Debug, Serialize)]
pub struct AlertEvent {
    pub session_id: i64,
    pub owner_user_id: Option<i64>,
    pub study_id: Option<i64>,
    pub device_type: String,
    pub stream_name: Option<String>,
    pub code: String,
    pub severity: String,
    /// `raised` or `cleared`.
    pub state: &'static str,
    pub message: String,
    pub detected_at_ms: i64,
    pub window_start_ms: i64,
    pub window_end_ms: i64,
    pub recipe_version: &'static str,
    pub details: Value,
}

#[derive(Debug, Default)]
pub struct TickOutput {
    pub summaries: Vec<SessionSummary>,
    pub alerts: Vec<AlertEvent>,
}

#[derive(Debug, Default)]
pub struct LiveSnapshot {
    pub sessions: Vec<SessionSummary>,
    pub recent_alerts: Vec<AlertEvent>,
}

struct Condition {
    code: &'static str,
    stream: Option<String>,
    severity: &'static str,
    message: String,
    /// Raise on the first window and clear on the first clean one. Used for
    /// stalls, where waiting would only delay an unambiguous signal.
    quick: bool,
    /// Held while the wearer was moving. Only `ppg_quality_low` reads it (see `WEAK_SIGNAL_SPAN`).
    moving: bool,
    details: Value,
}

#[derive(Default)]
struct Inner {
    sessions: HashMap<i64, SessionState>,
    recent_alerts: VecDeque<AlertEvent>,
}

#[derive(Default)]
pub struct LiveEngine {
    inner: Mutex<Inner>,
    /// Show PaPaGei's heart rate rather than the rule-based one (display only).
    prefer_model_hr: std::sync::atomic::AtomicBool,
    /// Whether accelerometer windows are offered to an activity worker. Off by default.
    activity_enabled: std::sync::atomic::AtomicBool,
}

impl LiveEngine {
    pub fn new() -> Self {
        Self::default()
    }

    /// Which heart rate participants see: PaPaGei's (true) or the rules' (false).
    pub fn set_prefer_model_hr(&self, prefer: bool) {
        self.prefer_model_hr.store(prefer, std::sync::atomic::Ordering::Relaxed);
    }

    /// Turn the activity pilot on (LIVE_ACTIVITY_MODEL set). Until then `activity_windows` is empty.
    pub fn set_activity_enabled(&self, enabled: bool) {
        self.activity_enabled.store(enabled, std::sync::atomic::Ordering::Relaxed);
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Inner> {
        // A panic while holding the lock leaves derived state at worst stale;
        // it must not take ingestion down with it.
        self.inner.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    pub fn knows_session(&self, session_id: i64) -> bool {
        self.lock().sessions.contains_key(&session_id)
    }

    pub fn register_session(&self, meta: SessionMeta) {
        self.lock()
            .sessions
            .entry(meta.session_id)
            .or_insert_with(|| SessionState {
                meta,
                streams: BTreeMap::new(),
                trackers: BTreeMap::new(),
                latest: None,
                hr_history: VecDeque::new(),
                model: None,
                model_history: VecDeque::new(),
                last_model_request_ms: None,
                activity: None,
                last_activity_request_ms: None,
            });
    }

    /// Windows due for the model: one per recording every `MODEL_EVERY_MS`,
    /// only while the live algorithm has a usable pulse signal (not stalled,
    /// warming up, flat, or with nothing pulsing under the sensor). The
    /// samples are the stored evidence on the device's own index, so a lost
    /// sample is a filled gap rather than a silent shift in time.
    pub fn model_windows(&self, now_ms: i64) -> Vec<ModelRequest> {
        let mut inner = self.lock();
        let mut requests = Vec::new();
        for session in inner.sessions.values_mut() {
            if session
                .last_model_request_ms
                .is_some_and(|last| now_ms - last < MODEL_EVERY_MS)
            {
                continue;
            }
            let Some(signal) = session.latest.as_ref().and_then(|summary| summary.ppg.as_ref()) else {
                continue;
            };
            if signal.hr_reason == Some("no_pulse") {
                continue;
            }
            let Some(buffer) = session.streams.get("ppg") else {
                continue;
            };
            let mut samples: Vec<Sample> = buffer.samples.iter().cloned().collect();
            samples.sort_by_key(|sample| (sample.index.unwrap_or(i64::MIN), sample.arrival_ms));
            let Some((series, estimated_fs)) = index_series(&samples, &signal.channel) else {
                continue;
            };
            let fs = buffer.nominal_rate_hz.unwrap_or(estimated_fs);
            let needed = (fs * MODEL_WINDOW_S).round() as usize;
            if fs <= 0.0 || series.len() < needed {
                continue;
            }
            requests.push(ModelRequest {
                session_id: session.meta.session_id,
                channel: signal.channel.clone(),
                values: series[series.len() - needed..].to_vec(),
                fs_hz: fs,
                window_end_ms: now_ms,
                live_sqi: signal.sqi,
                live_hr_bpm: signal.hr_bpm,
                movement: session
                    .latest
                    .as_ref()
                    .and_then(|summary| summary.movement.as_ref())
                    .map(|movement| movement.state),
            });
            session.last_model_request_ms = Some(now_ms);
        }
        requests
    }

    /// Accelerometer windows due for the activity worker: one per recording every
    /// `ACTIVITY_EVERY_MS`, the samples that arrived in the last ten seconds, and only when the
    /// pilot is switched on and the window is at least 80% full. Empty when it is off.
    pub fn activity_windows(&self, now_ms: i64) -> Vec<ActivityRequest> {
        if !self.activity_enabled.load(std::sync::atomic::Ordering::Relaxed) {
            return Vec::new();
        }
        let mut inner = self.lock();
        let mut requests = Vec::new();
        for session in inner.sessions.values_mut() {
            if session
                .last_activity_request_ms
                .is_some_and(|last| now_ms - last < ACTIVITY_EVERY_MS)
            {
                continue;
            }
            let Some(buffer) = session.streams.get("accel") else {
                continue;
            };
            let from = now_ms - (ACTIVITY_WINDOW_S * 1000.0) as i64;
            let window: Vec<[f64; 3]> = buffer
                .samples
                .iter()
                .filter(|sample| sample.arrival_ms > from && sample.arrival_ms <= now_ms)
                .filter_map(|sample| {
                    Some([
                        *sample.values.get("x")?,
                        *sample.values.get("y")?,
                        *sample.values.get("z")?,
                    ])
                })
                .collect();
            let Some(fs) = buffer.nominal_rate_hz.filter(|rate| *rate > 0.0) else {
                continue;
            };
            let needed = (fs * ACTIVITY_WINDOW_S * ACTIVITY_MIN_FILL).ceil() as usize;
            if window.len() < needed {
                continue;
            }
            requests.push(ActivityRequest {
                session_id: session.meta.session_id,
                device_type: session.meta.device_type.clone(),
                values: window,
                fs_hz: fs,
                window_end_ms: now_ms,
            });
            session.last_activity_request_ms = Some(now_ms);
        }
        requests
    }

    /// Attach an activity estimate to its recording. Ignored if it has ended.
    pub fn set_activity_estimate(&self, session_id: i64, estimate: ActivityEstimate) {
        if let Some(session) = self.lock().sessions.get_mut(&session_id) {
            session.activity = Some(estimate);
        }
    }

    /// Attach a model estimate to its recording. Ignored if it has ended.
    pub fn set_model_estimate(&self, session_id: i64, estimate: ModelEstimate) {
        let mut inner = self.lock();
        if let Some(session) = inner.sessions.get_mut(&session_id) {
            session.model_history.push_back((estimate.window_end_ms, estimate.hr_bpm));
            session.model = Some(estimate);
        }
    }

    /// Record one stored sample. Non-numeric channels are ignored; samples for
    /// a recording that has not been registered are dropped.
    pub fn push(
        &self,
        session_id: i64,
        stream_name: &str,
        arrival_ms: i64,
        index: Option<i64>,
        nominal_rate_hz: Option<f64>,
        channel_values: &Map<String, Value>,
    ) {
        let values: BTreeMap<String, f64> = channel_values
            .iter()
            .filter_map(|(key, value)| value.as_f64().map(|number| (key.clone(), number)))
            .collect();
        if values.is_empty() {
            return;
        }
        let mut inner = self.lock();
        let Some(session) = inner.sessions.get_mut(&session_id) else {
            return;
        };
        let buffer = session
            .streams
            .entry(stream_name.to_string())
            .or_insert_with(|| StreamBuffer {
                samples: VecDeque::new(),
                first_arrival_ms: arrival_ms,
                last_arrival_ms: arrival_ms,
                nominal_rate_hz: None,
            });
        buffer.first_arrival_ms = buffer.first_arrival_ms.min(arrival_ms);
        buffer.last_arrival_ms = buffer.last_arrival_ms.max(arrival_ms);
        if nominal_rate_hz.is_some_and(|rate| rate > 0.0) {
            buffer.nominal_rate_hz = nominal_rate_hz;
        }
        buffer.samples.push_back(Sample {
            arrival_ms,
            index,
            values,
        });
        let cutoff = buffer.last_arrival_ms - RETAIN_MS;
        while buffer
            .samples
            .front()
            .is_some_and(|sample| sample.arrival_ms < cutoff)
        {
            buffer.samples.pop_front();
        }
    }

    /// Summarise every recording and update alert state. Recordings that are
    /// no longer active are dropped and any alerts they held are cleared.
    pub fn tick(&self, now_ms: i64, active_sessions: &HashSet<i64>) -> TickOutput {
        let prefer_model_hr = self.prefer_model_hr.load(std::sync::atomic::Ordering::Relaxed);
        let mut inner = self.lock();
        let mut output = TickOutput::default();

        let ended: Vec<i64> = inner
            .sessions
            .keys()
            .filter(|id| !active_sessions.contains(id))
            .copied()
            .collect();
        for session_id in ended {
            if let Some(session) = inner.sessions.remove(&session_id) {
                for ((code, stream), tracker) in session.trackers {
                    if tracker.active {
                        output.alerts.push(alert_event(
                            &session.meta,
                            &code,
                            stream,
                            &tracker.severity,
                            "cleared",
                            tracker.message.clone(),
                            now_ms,
                            json!({ "reason": "recording_stopped" }),
                        ));
                    }
                }
            }
        }

        for session in inner.sessions.values_mut() {
            let (summary, events) = analyse_session(session, now_ms, prefer_model_hr);
            session.latest = Some(summary.clone());
            output.summaries.push(summary);
            output.alerts.extend(events);
        }

        for event in &output.alerts {
            inner.recent_alerts.push_back(event.clone());
        }
        while inner.recent_alerts.len() > RECENT_ALERT_LIMIT {
            inner.recent_alerts.pop_front();
        }
        output
    }

    pub fn snapshot(&self) -> LiveSnapshot {
        let inner = self.lock();
        let mut sessions: Vec<SessionSummary> = inner
            .sessions
            .values()
            .filter_map(|session| session.latest.clone())
            .collect();
        sessions.sort_by_key(|summary| summary.meta.session_id);
        LiveSnapshot {
            sessions,
            recent_alerts: inner.recent_alerts.iter().rev().cloned().collect(),
        }
    }
}

fn analyse_session(
    session: &mut SessionState,
    now_ms: i64,
    prefer_model_hr: bool,
) -> (SessionSummary, Vec<AlertEvent>) {
    let window_start = now_ms - WINDOW_MS;
    let mut conditions = Vec::new();
    let mut healths = BTreeMap::new();
    let mut windows: BTreeMap<String, Vec<Sample>> = BTreeMap::new();
    let device = session.meta.device_type.clone();
    let site = wear_site(&device);

    for (name, buffer) in session.streams.iter_mut() {
        let cutoff = now_ms - RETAIN_MS;
        while buffer
            .samples
            .front()
            .is_some_and(|sample| sample.arrival_ms < cutoff)
        {
            buffer.samples.pop_front();
        }
        let mut window: Vec<Sample> = buffer
            .samples
            .iter()
            .filter(|sample| sample.arrival_ms >= window_start)
            .cloned()
            .collect();
        // Ingestion runs one task per message, so arrival order can differ
        // slightly from the order the phone sent them in.
        window.sort_by_key(|sample| (sample.index.unwrap_or(i64::MIN), sample.arrival_ms));
        let health = stream_health(name, &window, buffer, window_start, now_ms);
        conditions.extend(stream_conditions(&device, name, &health));
        healths.insert(name.clone(), health);
        windows.insert(name.clone(), window);
    }
    let mut conditions = collapse_device_wide(&device, conditions, healths.len());

    let movement = match (windows.get("accel"), healths.get("accel")) {
        (Some(window), Some(health)) if !health.stalled && !health.recovering => {
            let axes: Vec<[f64; 3]> = window
                .iter()
                .filter_map(|sample| {
                    Some([*sample.values.get("x")?, *sample.values.get("y")?, *sample.values.get("z")?])
                })
                .collect();
            let (still, active) = site.movement_limits();
            movement_state_with(&axes, health.effective_rate_hz, still, active)
        }
        _ => None,
    };

    let mut ppg = match (windows.get("ppg"), healths.get("ppg")) {
        (Some(window), Some(health))
            if !health.stalled
                && !health.recovering
                && !health.warming_up
                && health.flatline_channels.len() < health.channel_count =>
        {
            let analyse = |channel: &&str| {
                if health.flatline_channels.iter().any(|flat| flat == channel) {
                    return None;
                }
                let (values, fs) = match index_series(window, channel) {
                    Some(series) => series,
                    None => (
                        window
                            .iter()
                            .filter_map(|sample| sample.values.get(*channel).copied())
                            .collect(),
                        health.effective_rate_hz?,
                    ),
                };
                ppg_analysis(channel, &values, fs)
            };
            let channels = ["green", "ir", "red"];
            if site == WearSite::Ear {
                // In the ear the green light often clips (a stepped waveform and
                // saturation alerts) while red or infrared still carries the
                // pulse, so the channel with the best quality is used. The ring
                // keeps green first, the channel its validation used.
                channels
                    .iter()
                    .filter_map(analyse)
                    .max_by(|a, b| a.sqi.total_cmp(&b.sqi))
            } else {
                channels.iter().find_map(analyse)
            }
        }
        _ => None,
    };

    if let Some(signal) = ppg.as_mut() {
        let moving = movement
            .as_ref()
            .is_some_and(|movement| movement.state != "still");
        // Rhythmic movement such as walking can dominate a wrist or finger PPG
        // so completely that it looks like a clean pulse at the step rate.
        // Agreement with the accelerometer's cadence exposes it.
        let cadence = movement.as_ref().and_then(|movement| movement.cadence_hz);
        if moving && signal.hr_bpm.is_none() && signal.hr_reason == Some("weak") {
            signal.hr_reason = Some("moving");
            signal.hr_reason_text = Some(hr_reason_text("moving"));
        }
        if let (true, Some(cadence_hz), Some(hr_bpm)) = (moving, cadence, signal.hr_bpm) {
            if cadence_locked(hr_bpm / 60.0, cadence_hz) {
                signal.motion_locked = true;
                signal.hr_bpm = None;
                signal.hr_reason = Some("moving");
                signal.hr_reason_text = Some(hr_reason_text("moving"));
                conditions.push(Condition {
                    code: "motion_artifact",
                    stream: Some("ppg".to_string()),
                    severity: "info",
                    message: format!("{device} PPG tracking movement, not pulse"),
                    quick: false,
                    moving: false,
                    details: json!({
                        "pulse_hz": hr_bpm / 60.0,
                        "cadence_hz": cadence_hz,
                        "sqi": signal.sqi,
                        "movement": movement
                    }),
                });
            }
        }
        // A weak signal while moving is ordinary wear, not a fault: it pauses the
        // rule-based estimate (PaPaGei keeps reporting) but raises nothing. It used
        // to raise "PPG unreliable while moving" whenever the wearer moved a hand.
        //
        // 1.3.0: a poor window while moving is still not an alert by itself, but it now
        // counts towards the sustained-weak-signal rule (`ppg_quality_low`, see
        // WEAK_SIGNAL_SPAN), which needs most of a minute. A hand handling a loose ring is
        // moving, and its flickering quality never made the old 10-in-a-row no-pulse run.
        if signal.quality == "poor" && !signal.motion_locked && moving {
            conditions.push(Condition {
                code: "ppg_quality_low",
                stream: Some("ppg".to_string()),
                severity: "warning",
                message: format!("{device} signal weak for a while: adjust the fit"),
                quick: false,
                moving: true,
                details: json!({
                    "sqi": signal.sqi,
                    "perfusion_pct": signal.perfusion_pct,
                    "reason": "poor_while_moving",
                    "channel": signal.channel,
                    "movement": movement
                }),
            });
        }
        if signal.quality == "poor" && !signal.motion_locked && !moving {
            {
                let no_pulse = signal.perfusion_pct < PPG_NO_PULSE_PERFUSION_PCT;
                let details = json!({
                    "sqi": signal.sqi,
                    "perfusion_pct": signal.perfusion_pct,
                    "reason": if no_pulse { "no_pulse" } else { "low_quality" },
                    "channel": signal.channel,
                    "movement": movement
                });
                conditions.push(Condition {
                    // No pulse at all (off the finger, out of the ear) and a weak
                    // pulse are separate alerts: the first needs acting on, the
                    // second is common in ordinary wear and only matters if it lasts.
                    code: if no_pulse { "ppg_no_pulse" } else { "ppg_quality_low" },
                    stream: Some("ppg".to_string()),
                    severity: "warning",
                    message: if no_pulse {
                        site.no_pulse_alert(&device)
                    } else {
                        format!("{device} signal weak for a while: adjust the fit")
                    },
                    quick: false,
                    moving: false,
                    details,
                });
            }
        }
    }

    if let Some(signal) = ppg.as_mut() {
        signal.hr_reason_text = signal.hr_reason.map(|reason| hr_reason_text_for(reason, site));
    }

    let events = update_trackers(session, conditions, now_ms);
    let active_alerts = session
        .trackers
        .iter()
        .filter(|(_, tracker)| tracker.active)
        .map(|((code, stream), tracker)| ActiveAlert {
            code: code.clone(),
            stream_name: stream.clone(),
            severity: tracker.severity.clone(),
            message: tracker.message.clone(),
            raised_at_ms: tracker.raised_at_ms,
        })
        .collect();

    if let Some(bpm) = ppg.as_ref().and_then(|signal| signal.hr_bpm) {
        session.hr_history.push_back((now_ms, bpm));
    }
    while session
        .hr_history
        .front()
        .is_some_and(|(t, _)| *t < now_ms - HR_HISTORY_MS)
    {
        session.hr_history.pop_front();
    }
    while session
        .model_history
        .front()
        .is_some_and(|(t, _)| *t < now_ms - HR_HISTORY_MS)
    {
        session.model_history.pop_front();
    }
    let model = session
        .model
        .clone()
        .filter(|estimate| now_ms - estimate.window_end_ms <= MODEL_STALE_MS);
    let mut model_shown = false;
    if let Some(signal) = ppg.as_mut() {
        signal.display_hr_bpm = signal.hr_bpm;
        signal.hr_source = signal.hr_bpm.map(|_| "rules");
        // No pulse under the sensor means no heart rate from either method.
        if prefer_model_hr && signal.hr_reason != Some("no_pulse") {
            if let Some(estimate) = model.as_ref() {
                // The median of the model's last three estimates (about 15 s): one
                // window's estimate is noisy, the median follows real changes.
                let mut recent: Vec<f64> = session
                    .model_history
                    .iter()
                    .filter(|(t, _)| *t > estimate.window_end_ms - 3 * MODEL_EVERY_MS)
                    .map(|(_, bpm)| *bpm)
                    .collect();
                if recent.is_empty() {
                    recent.push(estimate.hr_bpm);
                }
                recent.sort_by(|a, b| a.total_cmp(b));
                let shown = recent[recent.len() / 2];
                signal.display_hr_bpm = Some((shown * 10.0).round() / 10.0);
                signal.hr_source = Some("papagei");
                model_shown = true;
            }
        }
    }
    let mut trace = build_trace(
        &windows,
        &healths,
        ppg.as_ref().map(|signal| signal.channel.as_str()),
        &session.hr_history,
        &session.model_history,
    );
    trace.display_hr_history = if model_shown || (prefer_model_hr && !trace.model_hr_history.is_empty()) {
        trace.model_hr_history.clone()
    } else {
        trace.hr_history.clone()
    };
    let summary = SessionSummary {
        meta: session.meta.clone(),
        recipe_version: RECIPE_VERSION,
        window_start_ms: window_start,
        window_end_ms: now_ms,
        streams: healths,
        ppg,
        movement,
        active_alerts,
        model,
        activity: session
            .activity
            .clone()
            .filter(|estimate| now_ms - estimate.window_end_ms <= ACTIVITY_STALE_MS),
        trace: Some(trace),
    };
    (summary, events)
}

fn stream_conditions(device: &str, stream: &str, health: &StreamHealth) -> Vec<Condition> {
    let label = stream_label(stream);
    let mut conditions = Vec::new();
    if health.stalled {
        conditions.push(Condition {
            code: "stream_stalled",
            stream: Some(stream.to_string()),
            severity: "critical",
            message: format!("{device} {label} stopped sending"),
            quick: true,
            moving: false,
            details: json!({ "last_arrival_age_ms": health.last_arrival_age_ms }),
        });
        // Every other check would only restate the stall.
        return conditions;
    }
    if health.warming_up || health.recovering {
        return conditions;
    }
    // A shortfall that matches the measured loss means the device produced at
    // its rate and samples went missing on the way, which `sample_loss`
    // already reports. `rate_low` is kept for a device producing too slowly.
    let explained_by_loss = matches!(
        (health.rate_ratio, health.loss_ratio),
        (Some(ratio), Some(loss)) if (ratio - (1.0 - loss)).abs() < 0.1
    );
    if let Some(ratio) = health.rate_ratio.filter(|_| !explained_by_loss) {
        if ratio < RATE_LOW_RATIO {
            conditions.push(Condition {
                code: "rate_low",
                stream: Some(stream.to_string()),
                severity: "warning",
                message: format!(
                    "{device} {label} at {:.0}% of expected rate",
                    ratio * 100.0
                ),
                quick: false,
                moving: false,
                details: json!({
                    "effective_rate_hz": health.effective_rate_hz,
                    "nominal_rate_hz": health.nominal_rate_hz,
                    "rate_ratio": ratio
                }),
            });
        }
    }
    if let Some(loss) = health.loss_ratio {
        if loss > LOSS_RATIO_LIMIT {
            conditions.push(Condition {
                code: "sample_loss",
                stream: Some(stream.to_string()),
                severity: "warning",
                message: format!("{device} {label} losing {:.0}% of samples", loss * 100.0),
                quick: false,
                moving: false,
                details: json!({
                    "missing": health.missing,
                    "loss_ratio": loss,
                    "index_source": health.index_source
                }),
            });
        }
    }
    if health.channel_count > 0 && health.flatline_channels.len() == health.channel_count {
        conditions.push(Condition {
            code: "flatline",
            stream: Some(stream.to_string()),
            severity: "warning",
            message: format!("{device} {label} is flat"),
            quick: false,
            moving: false,
            details: json!({ "channels": health.flatline_channels }),
        });
    }
    if !health.saturated_channels.is_empty() {
        conditions.push(Condition {
            code: "saturation",
            stream: Some(stream.to_string()),
            severity: "warning",
            message: format!("{device} {label} saturating"),
            quick: false,
            moving: false,
            details: json!({ "channels": health.saturated_channels }),
        });
    }
    conditions
}

/// Transport faults hit every stream a device sends, because one packet
/// carries all of them. When every stream reports the same transport alert,
/// replace them with one alert for the device so a single dropout does not
/// arrive on the phone three times.
fn collapse_device_wide(device: &str, conditions: Vec<Condition>, stream_count: usize) -> Vec<Condition> {
    const TRANSPORT: [&str; 3] = ["stream_stalled", "rate_low", "sample_loss"];
    if stream_count < 2 {
        return conditions;
    }
    let mut kept = Vec::new();
    let mut grouped: BTreeMap<&'static str, Vec<Condition>> = BTreeMap::new();
    for condition in conditions {
        if TRANSPORT.contains(&condition.code) {
            grouped.entry(condition.code).or_default().push(condition);
        } else {
            kept.push(condition);
        }
    }
    for (code, group) in grouped {
        if group.len() < stream_count {
            kept.extend(group);
            continue;
        }
        let streams: Vec<String> = group.iter().filter_map(|condition| condition.stream.clone()).collect();
        let first = &group[0];
        let message = match code {
            "stream_stalled" => format!("{device} stopped sending"),
            "rate_low" => format!(
                "{device} at {:.0}% of expected rate",
                first.details["rate_ratio"].as_f64().unwrap_or(0.0) * 100.0
            ),
            _ => format!(
                "{device} losing {:.0}% of samples",
                first.details["loss_ratio"].as_f64().unwrap_or(0.0) * 100.0
            ),
        };
        let mut details = first.details.clone();
        details["streams"] = json!(streams);
        kept.push(Condition {
            code,
            stream: None,
            severity: first.severity,
            message,
            quick: first.quick,
            moving: false,
            details,
        });
    }
    kept
}

/// One channel placed on its sample-index grid, with short gaps filled by
/// linear interpolation, and the rate implied by index span over arrival span.
///
/// Counting samples per second of window understates the rate whenever
/// samples are missing or the window opens on an empty stretch, and a heart
/// rate computed at the wrong rate is wrong by the same factor. The index
/// keeps lost samples as gaps instead of silently closing them up. Returns
/// `None` without usable indices, or when more than a fifth would be filled.
fn index_series(window: &[Sample], channel: &str) -> Option<(Vec<f64>, f64)> {
    let points: Vec<(i64, i64, f64)> = window
        .iter()
        .filter_map(|sample| Some((sample.index?, sample.arrival_ms, *sample.values.get(channel)?)))
        .collect();
    let (first, last) = (points.first()?, points.last()?);
    let index_span = last.0 - first.0;
    let arrival_span_ms = points.iter().map(|p| p.1).max()? - points.iter().map(|p| p.1).min()?;
    if index_span < 2 || index_span > INDEX_RESET_JUMP || arrival_span_ms < WARMUP_MS {
        return None;
    }
    if (index_span + 1 - points.len() as i64) as f64 > 0.2 * (index_span + 1) as f64 {
        return None;
    }
    let mut series = Vec::with_capacity(index_span as usize + 1);
    for pair in points.windows(2) {
        let (a, b) = (pair[0], pair[1]);
        let step = b.0 - a.0;
        if step <= 0 {
            continue;
        }
        for k in 0..step {
            series.push(a.2 + (b.2 - a.2) * k as f64 / step as f64);
        }
    }
    series.push(last.2);
    Some((series, index_span as f64 * 1000.0 / arrival_span_ms as f64))
}

fn cadence_locked(pulse_hz: f64, cadence_hz: f64) -> bool {
    [0.5, 1.0, 2.0]
        .iter()
        .any(|ratio| ((pulse_hz / cadence_hz) / ratio - 1.0).abs() <= CADENCE_TOLERANCE)
}

fn is_quality_alert(code: &str) -> bool {
    matches!(code, "ppg_quality_low" | "ppg_no_pulse" | "rate_low")
}

/// A weak but present pulse (`ppg_quality_low`) is judged by counting, not by an unbroken run.
///
/// Intent: a fit that is bad for most of about a minute should be reported even if the quality
/// index flickers across the poor/fair line, and a short bad patch (a shifted ring, a hand
/// movement) should not. Under 1.1.0 the rule was 45 poor windows in a row, so a signal sitting
/// on the line (poor 70 % of the time, never more than a dozen in a row) was never reported.
///
/// Chosen before any test, from that intent and not from the simulator's schedule: look at the
/// last 60 ticks (one tick is one second and one window), raise when at least 40 of them (two
/// thirds) were poor, clear when 20 or fewer (one third) were. Forty poor seconds in a row still
/// raises, as the old rule's 45 did (a little sooner); a 30 s patch does not; the one-third gap
/// between the two limits stops the alert chattering when the fraction hovers near either.
const WEAK_SIGNAL_SPAN: usize = 60;
const WEAK_SIGNAL_RAISE_COUNT: usize = 40;
const WEAK_SIGNAL_CLEAR_COUNT: usize = 20;
/// Recipe 1.3.0, written 24 Nov 2026 after a loosened ring went unreported (ring session 145,
/// 54 s with the hand moving).
///
/// Intent, decided from principle before any replay was run. Movement suppression exists because
/// motion corrupts the pulse for a moment, and a hand moving about is ordinary wear. It cannot
/// explain a pulse that is missing for most of a minute: that is a fit problem whether or not the
/// hand moved, and handling a loose ring is itself movement. So poor windows while moving now
/// count towards this same counting rule, with a stricter bar than for a still wearer, because
/// movement is a real alternative explanation for some of them. Poor windows while still keep
/// their own bar of 40 (unchanged), so wherever 1.2.0 would raise, 1.3.0 raises too. The clear is
/// not the same: moving poor windows also count against the clear limit, so an alert can last
/// longer and two 1.2.0 episodes can merge into one. The first
/// draft of that bar was 45 of 60 (three quarters). No amplitude condition was added: a poor
/// window already means no pulse was found.
///
/// Revised after the replay, so the final number is NOT held out. Replaying eleven ring and four
/// earbud recordings with the 45 bar added 1-6 `ppg_quality_low` alerts to 13 of the 15,
/// including stretches labelled "Sitting" and the exercise recording: this wearer's ring is
/// poor for most of a minute, with the hand moving, many times in ordinary wear. Quality alone
/// cannot separate that from a loose fit (the loosened ring even showed a higher pulse-band
/// amplitude than normal wear, not a lower one, so a low-level condition would have been wrong).
/// What is left to separate them is how complete the loss is: a hand in use lets a pulse through
/// now and then, a ring that has lost the skin does not. The bar was therefore raised to 57 of 60,
/// at most three fair windows in a minute (`WEAK_SIGNAL_RAISE_COUNT_MOVING`, over poor windows
/// of either kind), which still allows for a quality index that flickers across the line but is
/// far less eager than 45.
const WEAK_SIGNAL_RAISE_COUNT_MOVING: usize = 57;

fn is_signal_alert(code: &str) -> bool {
    matches!(code, "saturation" | "motion_artifact")
}

/// Windows (seconds) a condition must hold before it is raised. Transport
/// faults (stall, loss, flatline) stay quick: they need fixing at once.
fn raise_after(code: &str, site: WearSite) -> u32 {
    if is_quality_alert(code) {
        if site == WearSite::Ear { EAR_QUALITY_RAISE_AFTER } else { QUALITY_RAISE_AFTER }
    } else if is_signal_alert(code) {
        SIGNAL_RAISE_AFTER
    } else {
        RAISE_AFTER
    }
}

fn clear_after(code: &str) -> u32 {
    if is_quality_alert(code) {
        QUALITY_CLEAR_AFTER
    } else if is_signal_alert(code) {
        SIGNAL_CLEAR_AFTER
    } else {
        CLEAR_AFTER
    }
}

fn update_trackers(
    session: &mut SessionState,
    conditions: Vec<Condition>,
    now_ms: i64,
) -> Vec<AlertEvent> {
    let SessionState { meta, trackers, .. } = session;
    let mut present: BTreeMap<AlertKey, Condition> = conditions
        .into_iter()
        .map(|condition| ((condition.code.to_string(), condition.stream.clone()), condition))
        .collect();
    let keys: BTreeSet<AlertKey> = trackers.keys().cloned().chain(present.keys().cloned()).collect();
    let mut events = Vec::new();

    for key in keys {
        let tracker = trackers.entry(key.clone()).or_default();
        if key.0 == "ppg_quality_low" {
            // Counting rule over a sliding span (see WEAK_SIGNAL_SPAN).
            let condition = present.remove(&key);
            tracker.recent.push_back(condition.is_some());
            tracker.recent_moving.push_back(condition.as_ref().is_some_and(|c| c.moving));
            while tracker.recent.len() > WEAK_SIGNAL_SPAN {
                tracker.recent.pop_front();
                tracker.recent_moving.pop_front();
            }
            let count = tracker.recent.iter().filter(|held| **held).count();
            let moving_count = tracker.recent_moving.iter().filter(|m| **m).count();
            // Poor windows while still need 40 of 60, exactly as in 1.2.0; every poor window,
            // moving or not, needs 57. So 1.3.0 raises wherever 1.2.0 did.
            let raise = count - moving_count >= WEAK_SIGNAL_RAISE_COUNT
                || count >= WEAK_SIGNAL_RAISE_COUNT_MOVING;
            if let Some(condition) = condition {
                tracker.severity = condition.severity.to_string();
                tracker.message = condition.message.clone();
                tracker.quick = false;
                if !tracker.active && raise {
                    tracker.active = true;
                    tracker.raised_at_ms = Some(now_ms);
                    events.push(alert_event(
                        meta,
                        &key.0,
                        key.1.clone(),
                        &tracker.severity,
                        "raised",
                        condition.message,
                        now_ms,
                        condition.details,
                    ));
                }
            } else if tracker.active && count <= WEAK_SIGNAL_CLEAR_COUNT {
                tracker.active = false;
                events.push(alert_event(
                    meta,
                    &key.0,
                    key.1.clone(),
                    &tracker.severity,
                    "cleared",
                    tracker.message.clone(),
                    now_ms,
                    json!({ "raised_at_ms": tracker.raised_at_ms }),
                ));
                tracker.raised_at_ms = None;
            }
            continue;
        }
        match present.remove(&key) {
            Some(condition) => {
                tracker.present_run += 1;
                tracker.absent_run = 0;
                tracker.quick = condition.quick;
                tracker.severity = condition.severity.to_string();
                tracker.message = condition.message.clone();
                let needed = if condition.quick { 1 } else { raise_after(&key.0, wear_site(&meta.device_type)) };
                if !tracker.active && tracker.present_run >= needed {
                    tracker.active = true;
                    tracker.raised_at_ms = Some(now_ms);
                    events.push(alert_event(
                        meta,
                        &key.0,
                        key.1.clone(),
                        &tracker.severity,
                        "raised",
                        condition.message,
                        now_ms,
                        condition.details,
                    ));
                }
            }
            None => {
                tracker.absent_run += 1;
                tracker.present_run = 0;
                let needed = if tracker.quick { 1 } else { clear_after(&key.0) };
                if tracker.active && tracker.absent_run >= needed {
                    tracker.active = false;
                    events.push(alert_event(
                        meta,
                        &key.0,
                        key.1.clone(),
                        &tracker.severity,
                        "cleared",
                        tracker.message.clone(),
                        now_ms,
                        json!({ "raised_at_ms": tracker.raised_at_ms }),
                    ));
                    tracker.raised_at_ms = None;
                }
            }
        }
    }
    trackers.retain(|_, tracker| {
        tracker.active || tracker.present_run > 0 || tracker.recent.iter().any(|held| *held)
    });
    events
}

#[allow(clippy::too_many_arguments)]
fn alert_event(
    meta: &SessionMeta,
    code: &str,
    stream_name: Option<String>,
    severity: &str,
    state: &'static str,
    message: String,
    now_ms: i64,
    details: Value,
) -> AlertEvent {
    AlertEvent {
        session_id: meta.session_id,
        owner_user_id: meta.owner_user_id,
        study_id: meta.study_id,
        device_type: meta.device_type.clone(),
        stream_name,
        code: code.to_string(),
        severity: severity.to_string(),
        state,
        message,
        detected_at_ms: now_ms,
        window_start_ms: now_ms - WINDOW_MS,
        window_end_ms: now_ms,
        recipe_version: RECIPE_VERSION,
        details,
    }
}

fn stream_label(stream: &str) -> &str {
    match stream {
        "ppg" => "PPG",
        "accel" => "accelerometer",
        "gyro" => "gyroscope",
        "mag" => "magnetometer",
        "temperature" => "temperature",
        "pressure" => "pressure",
        other => other,
    }
}

fn stream_health(
    stream: &str,
    window: &[Sample],
    buffer: &StreamBuffer,
    window_start: i64,
    now_ms: i64,
) -> StreamHealth {
    let sample_count = window.len();
    // After a stall the window opens on an empty stretch the stall alert has
    // already covered; measuring the rate across it would report the stall a
    // second time as `rate_low`, so the rate is taken from where data resumed.
    let first_in_window = window.iter().map(|sample| sample.arrival_ms).min();
    let observed_from = match first_in_window {
        Some(first) if first - window_start > STALL_MS => first,
        _ => buffer.first_arrival_ms.max(window_start),
    };
    let observed_ms = (now_ms - observed_from).max(0);
    let warming_up = now_ms - buffer.first_arrival_ms < WARMUP_MS;
    let last_arrival_age_ms = (now_ms - buffer.last_arrival_ms).max(0);
    let stalled = last_arrival_age_ms > STALL_MS;
    let effective_rate_hz = (!warming_up && observed_ms > 0)
        .then(|| sample_count as f64 * 1000.0 / observed_ms as f64);
    let rate_ratio = match (effective_rate_hz, buffer.nominal_rate_hz) {
        (Some(effective), Some(nominal)) if nominal > 0.0 => Some(effective / nominal),
        _ => None,
    };

    let mut arrivals: Vec<i64> = window.iter().map(|sample| sample.arrival_ms).collect();
    arrivals.sort_unstable();
    let gaps: Vec<f64> = arrivals
        .windows(2)
        .map(|pair| (pair[1] - pair[0]) as f64)
        .collect();
    let max_gap_ms = gaps.iter().copied().fold(None, |max: Option<f64>, gap| {
        Some(max.map_or(gap, |current| current.max(gap)))
    });
    let jitter_ms = (gaps.len() >= 2).then(|| std_dev(&gaps));

    let indices: Vec<i64> = window.iter().filter_map(|sample| sample.index).collect();
    let (index_source, missing, duplicates, resets, loss_ratio) = if indices.len() >= 2 {
        let continuity = index_continuity(&indices);
        let expected = continuity.unique + continuity.missing;
        (
            Some("sample_index"),
            Some(continuity.missing),
            Some(continuity.duplicates),
            Some(continuity.resets),
            (expected > 0).then(|| continuity.missing as f64 / expected as f64),
        )
    } else {
        (None, None, None, None, None)
    };

    let (channel_count, flatline_channels, saturated_channels) =
        if sample_count >= MIN_SAMPLES_FOR_SHAPE && !SLOW_STREAMS.contains(&stream) {
            shape_checks(window, now_ms - FLAT_RECENT_MS, !is_motion_stream(stream))
        } else {
            (0, Vec::new(), Vec::new())
        };
    let recovering = !stalled && max_gap_ms.is_some_and(|gap| gap > STALL_MS as f64);

    StreamHealth {
        sample_count,
        observed_ms,
        warming_up,
        effective_rate_hz,
        nominal_rate_hz: buffer.nominal_rate_hz,
        rate_ratio,
        last_arrival_age_ms,
        stalled,
        recovering,
        max_gap_ms: max_gap_ms.map(|gap| gap as i64),
        jitter_ms,
        index_source,
        missing,
        duplicates,
        resets,
        loss_ratio,
        channel_count,
        flatline_channels,
        saturated_channels,
    }
}

#[derive(Debug, PartialEq)]
pub struct Continuity {
    pub unique: i64,
    pub missing: i64,
    pub duplicates: i64,
    pub resets: i64,
}

/// Count missing, duplicated and restarted indices in one window.
pub fn index_continuity(indices: &[i64]) -> Continuity {
    let mut sorted = indices.to_vec();
    sorted.sort_unstable();
    let total = sorted.len() as i64;
    sorted.dedup();
    let unique = sorted.len() as i64;
    let mut missing = 0;
    let mut resets = 0;
    for pair in sorted.windows(2) {
        let step = pair[1] - pair[0];
        if step > INDEX_RESET_JUMP {
            resets += 1;
        } else if step > 1 {
            missing += step - 1;
        }
    }
    Continuity {
        unique,
        missing,
        duplicates: total - unique,
        resets,
    }
}

/// Per-channel flatline and saturation flags.
///
/// Flatline looks only at samples since `recent_from`. Saturation is a
/// heuristic over the whole window: a channel that sits exactly at its window
/// extreme for a noticeable share of samples, on a signal with enough distinct
/// values that the repeats are not just coarse quantisation of a still sensor.
/// Motion sensors read the same value for as long as they are still, so a
/// constant reading says nothing about the sensor. Optical and electrodermal
/// signals always carry noise, so a constant one is a fault.
fn is_motion_stream(stream: &str) -> bool {
    ["accel", "gyro", "mag", "imu"]
        .iter()
        .any(|prefix| stream.starts_with(prefix))
}

fn shape_checks(
    window: &[Sample],
    recent_from: i64,
    check_flat: bool,
) -> (usize, Vec<String>, Vec<String>) {
    let channels: BTreeSet<&String> = window.iter().flat_map(|sample| sample.values.keys()).collect();
    let channel_count = channels.len();
    let mut flat = Vec::new();
    let mut saturated = Vec::new();
    for channel in channels {
        let values: Vec<f64> = window
            .iter()
            .filter_map(|sample| sample.values.get(channel).copied())
            .collect();
        if values.len() < MIN_SAMPLES_FOR_SHAPE {
            continue;
        }
        let recent: Vec<f64> = window
            .iter()
            .filter(|sample| sample.arrival_ms >= recent_from)
            .filter_map(|sample| sample.values.get(channel).copied())
            .collect();
        if check_flat && recent.len() >= MIN_SAMPLES_FOR_SHAPE && recent.iter().all(|value| *value == recent[0]) {
            flat.push(channel.clone());
            continue;
        }
        let min = values.iter().copied().fold(f64::INFINITY, f64::min);
        let max = values.iter().copied().fold(f64::NEG_INFINITY, f64::max);
        if max == min {
            continue;
        }
        let mut distinct: Vec<u64> = values.iter().map(|value| value.to_bits()).collect();
        distinct.sort_unstable();
        distinct.dedup();
        let needed = ((values.len() as f64 * SATURATION_FRACTION).ceil() as usize).max(5);
        let at_max = values.iter().filter(|value| **value == max).count();
        let at_min = values.iter().filter(|value| **value == min).count();
        // Samples pile up on a rail that clips; at the end of an ordinary
        // distribution the extreme level is the rarest one. A still sensor
        // quantised to a few levels reaches its top level often without
        // clipping, so the extreme must also hold more samples than the level
        // beside it.
        let next_below_max = values.iter().copied().filter(|value| *value < max).fold(f64::NEG_INFINITY, f64::max);
        let next_above_min = values.iter().copied().filter(|value| *value > min).fold(f64::INFINITY, f64::min);
        let beside_max = values.iter().filter(|value| **value == next_below_max).count();
        let beside_min = values.iter().filter(|value| **value == next_above_min).count();
        let piled_max = at_max >= needed && at_max > beside_max;
        let piled_min = at_min >= needed && at_min > beside_min;
        if distinct.len() >= 8 && (piled_max || piled_min) {
            saturated.push(channel.clone());
        }
    }
    (channel_count, flat, saturated)
}

struct Periodicity {
    frequency_hz: f64,
    /// Normalised autocorrelation at the chosen lag, 0 to 1.
    strength: f64,
}

/// The strongest repetition in `signal` between `min_hz` and `max_hz`.
///
/// Searches the normalised autocorrelation for local peaks, takes the
/// shortest lag close to the strongest one so a two-cycle repeat does not
/// halve the frequency, and refines it with a parabola through the peak.
fn dominant_periodicity(signal: &[f64], fs: f64, min_hz: f64, max_hz: f64) -> Option<Periodicity> {
    let min_lag = ((fs / max_hz).floor() as usize).max(1);
    let max_lag = ((fs / min_hz).ceil() as usize).min(signal.len() / 2);
    if max_lag <= min_lag + 1 {
        return None;
    }
    let correlation: Vec<f64> = (0..=max_lag + 1)
        .map(|lag| autocorrelation(signal, lag))
        .collect();
    let peaks: Vec<usize> = (min_lag..=max_lag)
        .filter(|&lag| correlation[lag] > correlation[lag - 1] && correlation[lag] >= correlation[lag + 1])
        .collect();
    let strongest = peaks.iter().map(|&lag| correlation[lag]).fold(f64::NEG_INFINITY, f64::max);
    if !(strongest > 0.0) {
        return None;
    }
    let lag = peaks
        .iter()
        .copied()
        .find(|&lag| correlation[lag] >= 0.8 * strongest)?;
    let (before, at, after) = (correlation[lag - 1], correlation[lag], correlation[lag + 1]);
    let curvature = before - 2.0 * at + after;
    let offset = if curvature.abs() > f64::EPSILON {
        (0.5 * (before - after) / curvature).clamp(-0.5, 0.5)
    } else {
        0.0
    };
    Some(Periodicity {
        frequency_hz: fs / (lag as f64 + offset),
        strength: at.clamp(0.0, 1.0),
    })
}

/// Pulse quality and heart rate from one PPG channel.
///
/// The signal is detrended with a 1.5 s moving average, smoothed, and searched
/// for its strongest periodicity between 40 and 180 beats per minute. The
/// strength of that periodicity doubles as the signal-quality index: a clean
/// pulse repeats almost exactly from beat to beat, while noise and a loose fit
/// do not. Rhythmic motion can also repeat cleanly, which is why the session
/// analysis cross-checks the result against the accelerometer.
pub fn ppg_analysis(channel: &str, values: &[f64], fs: f64) -> Option<PpgSignal> {
    if fs < 8.0 || (values.len() as f64) < fs * (WARMUP_MS as f64 / 1000.0) {
        return None;
    }
    let smooth = moving_average(
        &detrend(values, (fs * 1.5).round() as usize),
        ((fs / 6.0).round() as usize).max(1),
    );
    let mut signal = PpgSignal {
        channel: channel.to_string(),
        sample_count: values.len(),
        fs_hz: fs,
        perfusion_pct: perfusion_pct(&smooth, values),
        sqi: 0.0,
        quality: "poor",
        hr_bpm: None,
        motion_locked: false,
        hr_reason: Some("weak"),
        hr_reason_text: Some(hr_reason_text("weak")),
        display_hr_bpm: None,
        hr_source: None,
    };
    if signal.perfusion_pct < PPG_NO_PULSE_PERFUSION_PCT {
        signal.hr_reason = Some("no_pulse");
        signal.hr_reason_text = Some(hr_reason_text("no_pulse"));
        // Noise repeats no better than chance, but a period found in it would
        // still look like a rate.
        return Some(signal);
    }
    let Some(periodicity) = dominant_periodicity(
        &smooth,
        fs,
        HR_BAND_BPM.0 / 60.0,
        HR_BAND_BPM.1 / 60.0,
    ) else {
        return Some(signal);
    };
    signal.sqi = periodicity.strength;
    signal.quality = if signal.sqi >= PPG_GOOD_SQI {
        "good"
    } else if signal.sqi >= PPG_FAIR_SQI {
        "fair"
    } else {
        "poor"
    };
    let bpm = 60.0 * periodicity.frequency_hz;
    let inside_band = bpm >= HR_BAND_BPM.0 + HR_EDGE_MARGIN_BPM && bpm <= HR_BAND_BPM.1 - HR_EDGE_MARGIN_BPM;
    if signal.sqi >= PPG_FAIR_SQI && inside_band {
        signal.hr_bpm = Some(bpm);
        signal.hr_reason = None;
        signal.hr_reason_text = None;
    } else if signal.sqi >= PPG_FAIR_SQI {
        // A clean repeat at the edge of the band is baseline drift, not a pulse.
        signal.quality = "poor";
    }
    if signal.hr_bpm.is_none() {
        // Beat-to-beat repetition breaks down under movement while the pulse is
        // still the strongest spectral line. Accept that line only if it holds a
        // good share of the band's power.
        if let Some((spectral_bpm, share)) = spectral_peak(values, fs) {
            // Stricter edges than the repetition test: a transform leaks drift into the
            // first bins of its band.
            let inside = spectral_bpm >= 48.0 && spectral_bpm <= 172.0;
            if share >= SPECTRAL_MIN_SHARE && inside {
                signal.hr_bpm = Some(spectral_bpm);
                signal.sqi = signal.sqi.max(share);
                signal.quality = if signal.sqi >= PPG_GOOD_SQI { "good" } else { "fair" };
                signal.hr_reason = None;
                signal.hr_reason_text = None;
            }
        }
    }
    Some(signal)
}

/// Smallest share of the 42--180 bpm band's power that the strongest spectral
/// line (and its neighbourhood) must hold to count as a pulse. Chosen on a recording
/// with movement, where it kept 80% of windows and agreed with a tracked estimate.
const SPECTRAL_MIN_SHARE: f64 = 0.35;

/// Strongest spectral line of the PPG between 42 and 180 beats per minute and the
/// share of band power within 0.12 Hz of it. A Hann-windowed discrete transform
/// on a 0.01 Hz grid from 0.45 Hz, after removing a 2 s moving average.
fn spectral_peak(values: &[f64], fs: f64) -> Option<(f64, f64)> {
    if values.len() < 64 || fs < 8.0 {
        return None;
    }
    let detrended = detrend(values, (fs * 2.0).round() as usize);
    let n = detrended.len();
    let windowed: Vec<f64> = detrended
        .iter()
        .enumerate()
        .map(|(i, v)| v * (0.5 - 0.5 * (2.0 * std::f64::consts::PI * i as f64 / (n as f64 - 1.0)).cos()))
        .collect();
    // The grid starts below the band so that a line there (drift, breathing) is seen
    // and can veto a leaked edge bin inside the band.
    let (lo, hi, step) = (0.45_f64, 3.0_f64, 0.01_f64);
    let band_start = ((0.7 - lo) / step).round() as usize;
    let bins = ((hi - lo) / step).round() as usize + 1;
    let mut power = Vec::with_capacity(bins);
    for k in 0..bins {
        let f = lo + k as f64 * step;
        let w = 2.0 * std::f64::consts::PI * f / fs;
        let (mut re, mut im) = (0.0, 0.0);
        for (i, v) in windowed.iter().enumerate() {
            let phase = w * i as f64;
            re += v * phase.cos();
            im += v * phase.sin();
        }
        power.push(re * re + im * im);
    }
    let total: f64 = power[band_start..].iter().sum();
    if !(total > 0.0) {
        return None;
    }
    let best = (1..bins - 1)
        .filter(|&i| power[i] > power[i - 1] && power[i] >= power[i + 1])
        .max_by(|a, b| power[*a].partial_cmp(&power[*b]).unwrap_or(std::cmp::Ordering::Equal))?;
    if best < band_start {
        return None; // the strongest line is below the band: drift or breathing
    }
    let reach = (0.12 / step).round() as usize;
    let near: f64 = power[best.saturating_sub(reach).max(band_start)..=(best + reach).min(bins - 1)].iter().sum();
    Some((60.0 * (lo + best as f64 * step), near / total))
}

/// Standard deviation of the detrended, smoothed signal over the magnitude of
/// the raw baseline, in percent.
fn perfusion_pct(smooth: &[f64], raw: &[f64]) -> f64 {
    if raw.is_empty() {
        return 0.0;
    }
    let baseline = (raw.iter().sum::<f64>() / raw.len() as f64).abs();
    if baseline <= f64::EPSILON {
        return 0.0;
    }
    100.0 * std_dev(smooth) / baseline
}

/// Movement intensity from acceleration magnitude, and cadence from the
/// individual axes.
///
/// Magnitude is orientation-free, which suits intensity, but a sideways swing
/// barely changes it (and only at twice the swing rate), so the cadence is
/// taken from whichever axis repeats most strongly.
pub fn movement_state(axes: &[[f64; 3]], fs: Option<f64>) -> Option<Movement> {
    movement_state_with(axes, fs, MOVEMENT_STILL, MOVEMENT_ACTIVE)
}

/// As movement_state, with the still and active limits for where the device is worn.
pub fn movement_state_with(axes: &[[f64; 3]], fs: Option<f64>, still: f64, active: f64) -> Option<Movement> {
    if axes.len() < MIN_SAMPLES_FOR_SHAPE {
        return None;
    }
    let magnitudes: Vec<f64> = axes
        .iter()
        .map(|[x, y, z]| (x * x + y * y + z * z).sqrt())
        .collect();
    let mut sorted = magnitudes.to_vec();
    sorted.sort_by(|a, b| a.total_cmp(b));
    let median = sorted[sorted.len() / 2];
    if median.abs() < 1e-9 {
        return None;
    }
    let relative_std = std_dev(&magnitudes) / median.abs();
    let state = if relative_std < still {
        "still"
    } else if relative_std < active {
        "moving"
    } else {
        "active"
    };
    // Cadence only means something once the wearer is actually moving.
    let cadence_hz = match fs {
        Some(fs) if state != "still" && fs >= 8.0 => (0..3)
            .filter_map(|axis| {
                let values: Vec<f64> = axes.iter().map(|sample| sample[axis]).collect();
                dominant_periodicity(&detrend(&values, (fs * 2.0).round() as usize), fs, 0.5, 4.0)
            })
            .filter(|periodicity| periodicity.strength >= CADENCE_MIN_STRENGTH)
            .max_by(|a, b| a.strength.total_cmp(&b.strength))
            .map(|periodicity| periodicity.frequency_hz),
        _ => None,
    };
    Some(Movement {
        sample_count: magnitudes.len(),
        relative_std,
        state,
        cadence_hz,
    })
}

fn detrend(values: &[f64], width: usize) -> Vec<f64> {
    let baseline = moving_average(values, width);
    values
        .iter()
        .zip(&baseline)
        .map(|(value, base)| value - base)
        .collect()
}

/// Centred moving average with the window shrunk at the edges.
fn moving_average(values: &[f64], width: usize) -> Vec<f64> {
    let width = width.max(1);
    let half = width / 2;
    let mut prefix = Vec::with_capacity(values.len() + 1);
    prefix.push(0.0);
    for value in values {
        prefix.push(prefix.last().copied().unwrap_or(0.0) + value);
    }
    (0..values.len())
        .map(|i| {
            let start = i.saturating_sub(half);
            let end = (i + half + 1).min(values.len());
            (prefix[end] - prefix[start]) / (end - start) as f64
        })
        .collect()
}

fn autocorrelation(values: &[f64], lag: usize) -> f64 {
    if lag >= values.len() {
        return 0.0;
    }
    let (head, tail) = (&values[..values.len() - lag], &values[lag..]);
    let numerator: f64 = head.iter().zip(tail).map(|(a, b)| a * b).sum();
    let energy_head: f64 = head.iter().map(|a| a * a).sum();
    let energy_tail: f64 = tail.iter().map(|b| b * b).sum();
    let denominator = (energy_head * energy_tail).sqrt();
    if denominator <= f64::EPSILON {
        0.0
    } else {
        numerator / denominator
    }
}

fn std_dev(values: &[f64]) -> f64 {
    if values.is_empty() {
        return 0.0;
    }
    let mean = values.iter().sum::<f64>() / values.len() as f64;
    let variance = values.iter().map(|value| (value - mean).powi(2)).sum::<f64>() / values.len() as f64;
    variance.sqrt()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Deterministic noise so tests do not need a random-number crate.
    fn noise(seed: &mut u64) -> f64 {
        *seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        ((*seed >> 33) as f64 / (1u64 << 31) as f64) - 0.5
    }

    fn synthetic_ppg(bpm: f64, fs: f64, seconds: f64, noise_level: f64) -> Vec<f64> {
        let mut seed = 7;
        let n = (fs * seconds) as usize;
        (0..n)
            .map(|i| {
                let t = i as f64 / fs;
                let phase = (t * bpm / 60.0).fract();
                // A sharp systolic upstroke and slower decay, on a drifting baseline.
                let pulse = (-((phase - 0.15) / 0.08).powi(2)).exp()
                    + 0.4 * (-((phase - 0.45) / 0.12).powi(2)).exp();
                5000.0 + 200.0 * pulse + 30.0 * (t * 0.2).sin() + noise_level * noise(&mut seed)
            })
            .collect()
    }

    #[test]
    fn the_spectral_line_recovers_a_pulse_hidden_in_noise() {
        let values: Vec<f64> = (0..300)
            .map(|i| {
                let t = i as f64 / 25.0;
                5000.0 + 30.0 * (2.0 * std::f64::consts::PI * 1.2 * t).sin()
                    + 12.0 * (2.0 * std::f64::consts::PI * 0.37 * t).sin()
                    + 6.0 * ((i * 7919 % 97) as f64 / 97.0 - 0.5)
            })
            .collect();
        let (bpm, share) = spectral_peak(&values, 25.0).expect("a spectrum");
        assert!((bpm - 72.0).abs() < 2.0, "{bpm}");
        assert!(share > SPECTRAL_MIN_SHARE, "{share}");
    }

    #[test]
    fn ppg_recovers_heart_rate_from_a_clean_pulse() {
        for bpm in [55.0, 72.0, 110.0] {
            let values = synthetic_ppg(bpm, 25.0, 10.0, 5.0);
            let signal = ppg_analysis("green", &values, 25.0).expect("enough samples");
            let hr = signal.hr_bpm.expect("clean pulse has a heart rate");
            assert!((hr - bpm).abs() < 4.0, "expected {bpm}, got {hr}");
            assert_eq!(signal.quality, "good", "sqi {}", signal.sqi);
        }
    }

    #[test]
    fn ppg_rejects_noise() {
        let mut seed = 11;
        let values: Vec<f64> = (0..250).map(|_| 5000.0 + 200.0 * noise(&mut seed)).collect();
        let signal = ppg_analysis("green", &values, 25.0).expect("enough samples");
        assert_eq!(signal.quality, "poor", "sqi {}", signal.sqi);
        assert!(signal.hr_bpm.is_none());
    }

    #[test]
    fn ppg_needs_a_warm_window() {
        let values = synthetic_ppg(72.0, 25.0, 2.0, 0.0);
        assert!(ppg_analysis("green", &values, 25.0).is_none());
    }

    #[test]
    fn continuity_counts_missing_duplicate_and_restart() {
        let continuity = index_continuity(&[1, 2, 2, 5, 6, 9000, 9001]);
        assert_eq!(
            continuity,
            Continuity { unique: 6, missing: 2, duplicates: 1, resets: 1 }
        );
    }

    #[test]
    fn movement_separates_still_from_active() {
        let still: Vec<[f64; 3]> = (0..50).map(|i| [0.0, 0.0, 9.81 + 0.01 * ((i % 3) as f64)]).collect();
        assert_eq!(movement_state(&still, Some(25.0)).unwrap().state, "still");
        // A sideways arm swing at 1.5 Hz with a noisy vertical axis, sampled
        // at 25 Hz: the swing hardly moves the magnitude but dominates x.
        let mut seed = 3;
        let active: Vec<[f64; 3]> = (0..250)
            .map(|i| {
                let swing = 4.0 * (2.0 * std::f64::consts::PI * 1.5 * i as f64 / 25.0).sin();
                [swing, 0.0, 9.81 + 3.0 * noise(&mut seed)]
            })
            .collect();
        let movement = movement_state(&active, Some(25.0)).unwrap();
        assert_ne!(movement.state, "still");
        let cadence = movement.cadence_hz.expect("rhythmic movement has a cadence");
        assert!((cadence - 1.5).abs() < 0.1, "cadence {cadence}");
    }

    #[test]
    fn pulse_matching_the_movement_cadence_is_flagged_as_motion() {
        let engine = engine_with_session();
        let active: HashSet<i64> = [1].into();
        // 1.8 Hz swing drives both the accelerometer and the PPG, the way a
        // walking wrist does; the true pulse is buried underneath.
        for i in 0..250 {
            let t = i as f64 / 25.0;
            let swing = (2.0 * std::f64::consts::PI * 1.8 * t).sin();
            let mut ppg = Map::new();
            ppg.insert("green".to_string(), json!(5000.0 + 250.0 * swing));
            let mut accel = Map::new();
            accel.insert("x".to_string(), json!(4.0 * swing));
            accel.insert("y".to_string(), json!(0.0));
            accel.insert("z".to_string(), json!(9.81));
            let arrival = (t * 1000.0) as i64;
            engine.push(1, "ppg", arrival, Some(i), Some(25.0), &ppg);
            engine.push(1, "accel", arrival, Some(i), Some(25.0), &accel);
        }
        let mut raised = Vec::new();
        for now in (0..SIGNAL_RAISE_AFTER as i64).map(|k| 10_000 + 10 * k) {
            let output = engine.tick(now, &active);
            let ppg = output.summaries[0].ppg.clone().unwrap();
            assert!(ppg.motion_locked);
            assert!(ppg.hr_bpm.is_none(), "a motion-locked pulse must not report a heart rate");
            raised.extend(output.alerts);
        }
        assert!(raised.iter().any(|alert| alert.code == "motion_artifact"), "{raised:?}");
    }

    #[test]
    fn clipping_on_every_beat_is_saturation() {
        let engine = engine_with_session();
        let clipped: Vec<f64> = synthetic_ppg(72.0, 25.0, 10.0, 4.0)
            .into_iter()
            .map(|value| value.min(5120.0))
            .collect();
        push_ppg(&engine, &clipped, 0, 25.0, 0);
        let active: HashSet<i64> = [1].into();
        let mut codes = Vec::new();
        for now in (0..SIGNAL_RAISE_AFTER as i64).map(|k| 9_900 + 10 * k) {
            codes.extend(engine.tick(now, &active).alerts.into_iter().map(|alert| alert.code));
        }
        assert!(codes.iter().any(|code| code == "saturation"), "{codes:?}");
    }

    #[test]
    fn a_device_wide_dropout_is_one_alert() {
        let engine = engine_with_session();
        // Data ends at 4 s; at 10 s that is longer ago than STALL_MS.
        let values = synthetic_ppg(72.0, 25.0, 4.0, 5.0);
        for (i, value) in values.iter().enumerate() {
            let arrival = i as i64 * 40;
            let mut ppg = Map::new();
            ppg.insert("green".to_string(), json!(value));
            let mut temperature = Map::new();
            temperature.insert("value".to_string(), json!(33.5));
            engine.push(1, "ppg", arrival, Some(i as i64), Some(25.0), &ppg);
            engine.push(1, "temperature", arrival, Some(i as i64), Some(25.0), &temperature);
        }
        let output = engine.tick(10_000, &[1].into());
        let stalls: Vec<_> = output.alerts.iter().filter(|alert| alert.code == "stream_stalled").collect();
        assert_eq!(stalls.len(), 1);
        assert_eq!(stalls[0].stream_name, None);
        assert_eq!(stalls[0].message, "OpenRing stopped sending");
    }

    fn engine_with_session() -> LiveEngine {
        let engine = LiveEngine::new();
        engine.register_session(SessionMeta {
            session_id: 1,
            device_type: "OpenRing".to_string(),
            device_address: "AA".to_string(),
            owner_user_id: Some(3),
            study_id: None,
        });
        engine
    }

    fn push_ppg(engine: &LiveEngine, values: &[f64], start_ms: i64, fs: f64, first_index: i64) {
        for (i, value) in values.iter().enumerate() {
            let mut channels = Map::new();
            channels.insert("green".to_string(), json!(value));
            engine.push(
                1,
                "ppg",
                start_ms + (i as f64 * 1000.0 / fs) as i64,
                Some(first_index + i as i64),
                Some(fs),
                &channels,
            );
        }
    }

    #[test]
    fn a_stall_is_raised_at_once_and_cleared_when_data_returns() {
        let engine = engine_with_session();
        let active: HashSet<i64> = [1].into();
        let values = synthetic_ppg(72.0, 25.0, 10.0, 5.0);
        push_ppg(&engine, &values, 0, 25.0, 0);

        let quiet = engine.tick(10_000, &active);
        assert!(quiet.alerts.is_empty(), "{:?}", quiet.alerts);
        let summary = &quiet.summaries[0];
        assert!(summary.ppg.as_ref().unwrap().hr_bpm.is_some());

        let stalled = engine.tick(10_000 + STALL_MS + 500, &active);
        assert_eq!(stalled.alerts.len(), 1);
        assert_eq!(stalled.alerts[0].code, "stream_stalled");
        assert_eq!(stalled.alerts[0].state, "raised");

        push_ppg(&engine, &values, 10_000 + STALL_MS + 600, 25.0, 250);
        let resumed = engine.tick(10_000 + STALL_MS + 1_000, &active);
        assert!(resumed
            .alerts
            .iter()
            .any(|alert| alert.code == "stream_stalled" && alert.state == "cleared"));
    }

    #[test]
    fn a_flat_stream_needs_consecutive_windows_before_raising() {
        let engine = engine_with_session();
        let active: HashSet<i64> = [1].into();
        let flat = vec![1234.0; 400];
        push_ppg(&engine, &flat, 0, 25.0, 0);
        // Ticks at 10, 11, 12 s; the samples run to 16 s so nothing stalls.
        let first = engine.tick(10_000, &active);
        assert!(first.alerts.iter().all(|alert| alert.code != "flatline"));
        engine.tick(11_000, &active);
        let third = engine.tick(12_000, &active);
        assert!(third
            .alerts
            .iter()
            .any(|alert| alert.code == "flatline" && alert.state == "raised"));
    }

    fn estimate(bpm: f64, window_end_ms: i64) -> ModelEstimate {
        ModelEstimate {
            model_id: "papagei_s".to_string(),
            head_id: "hr".to_string(),
            head_version: None,
            validation_status: None,
            runtime: None,
            channel: "green".to_string(),
            hr_bpm: bpm,
            window_end_ms,
            inference_ms: None,
            live_sqi: 0.9,
            live_hr_bpm: Some(72.0),
            movement: None,
        }
    }

    #[test]
    fn the_model_gets_ten_seconds_of_pulse_every_five() {
        let engine = engine_with_session();
        let active: HashSet<i64> = [1].into();
        let values = synthetic_ppg(72.0, 25.0, 11.0, 5.0);
        push_ppg(&engine, &values, 0, 25.0, 0);
        engine.tick(10_900, &active);

        let requests = engine.model_windows(10_900);
        assert_eq!(requests.len(), 1);
        assert_eq!(requests[0].values.len(), 250);
        assert_eq!(requests[0].fs_hz, 25.0);
        assert_eq!(requests[0].channel, "green");
        // The newest samples, in index order.
        assert_eq!(requests[0].values.last(), values.last());
        assert!(engine.model_windows(12_000).is_empty(), "not again within five seconds");
    }

    #[test]
    fn no_window_goes_to_the_model_without_a_pulse_signal() {
        let engine = engine_with_session();
        let active: HashSet<i64> = [1].into();
        push_ppg(&engine, &vec![1234.0; 300], 0, 25.0, 0);
        engine.tick(11_000, &active);
        assert!(engine.model_windows(11_000).is_empty(), "flat signal");
    }

    #[test]
    fn a_model_estimate_appears_in_the_summary_until_it_is_stale() {
        let engine = engine_with_session();
        let active: HashSet<i64> = [1].into();
        let values = synthetic_ppg(72.0, 25.0, 30.0, 5.0);
        push_ppg(&engine, &values, 0, 25.0, 0);
        engine.set_model_estimate(1, estimate(70.5, 10_000));

        let fresh = engine.tick(11_000, &active);
        assert_eq!(fresh.summaries[0].model.as_ref().map(|m| m.hr_bpm), Some(70.5));
        let trace = fresh.summaries[0].trace.as_ref().unwrap();
        assert_eq!(trace.model_hr_history, vec![[10_000.0, 70.5]]);

        let stale = engine.tick(26_000, &active);
        assert!(stale.summaries[0].model.is_none());
    }

    #[test]
    fn an_estimate_for_an_ended_recording_is_dropped() {
        let engine = engine_with_session();
        engine.tick(1_000, &HashSet::new());
        engine.set_model_estimate(1, estimate(70.0, 1_000));
        assert!(engine.snapshot().sessions.is_empty());
    }

    #[test]
    fn stopping_a_recording_clears_its_alerts() {
        let engine = engine_with_session();
        let values = synthetic_ppg(72.0, 25.0, 4.0, 5.0);
        push_ppg(&engine, &values, 0, 25.0, 0);
        let raised = engine.tick(10_000, &[1].into());
        assert!(raised.alerts.iter().any(|alert| alert.code == "stream_stalled"));
        let stopped = engine.tick(11_000, &HashSet::new());
        assert_eq!(stopped.alerts.len(), 1);
        assert_eq!(stopped.alerts[0].state, "cleared");
        assert!(engine.snapshot().sessions.is_empty());
    }

    #[test]
    fn sample_loss_is_reported_from_index_gaps() {
        let engine = engine_with_session();
        let values = synthetic_ppg(72.0, 25.0, 10.0, 5.0);
        // Keep four of every five samples.
        for (i, value) in values.iter().enumerate() {
            if i % 5 == 4 {
                continue;
            }
            let mut channels = Map::new();
            channels.insert("green".to_string(), json!(value));
            engine.push(1, "ppg", i as i64 * 40, Some(i as i64), Some(25.0), &channels);
        }
        let output = engine.tick(10_000, &[1].into());
        let health = &output.summaries[0].streams["ppg"];
        let loss = health.loss_ratio.unwrap();
        assert!((loss - 0.2).abs() < 0.02, "loss {loss}");
        // Lost samples stay gaps on the index grid, so the heart rate is not
        // scaled down by the missing fifth.
        let hr = output.summaries[0].ppg.as_ref().unwrap().hr_bpm.unwrap();
        assert!((hr - 72.0).abs() < 4.0, "hr {hr}");
    }

    fn push_axes(engine: &LiveEngine, stream: &str, values: &[[f64; 3]], fs: f64) {
        for (i, v) in values.iter().enumerate() {
            let mut channels = Map::new();
            channels.insert("x".to_string(), json!(v[0]));
            channels.insert("y".to_string(), json!(v[1]));
            channels.insert("z".to_string(), json!(v[2]));
            engine.push(
                1,
                stream,
                (i as f64 * 1000.0 / fs) as i64,
                Some(i as i64),
                Some(fs),
                &channels,
            );
        }
    }

    #[test]
    fn a_still_gyroscope_is_not_a_flatline() {
        let engine = engine_with_session();
        let active: HashSet<i64> = [1].into();
        push_axes(&engine, "gyro", &vec![[0.0, 0.0, 0.0]; 400], 25.0);
        let mut codes = Vec::new();
        for now in [10_000, 11_000, 12_000, 13_000, 14_000] {
            codes.extend(engine.tick(now, &active).alerts.into_iter().map(|alert| alert.code));
        }
        assert!(codes.iter().all(|code| code != "flatline"), "{codes:?}");
    }

    #[test]
    fn a_still_quantised_accelerometer_is_not_saturation() {
        // Gravity on one axis with plus or minus a count of noise: few
        // levels, and the top level is the rarest, as at the end of any
        // ordinary distribution.
        let mut seed = 11;
        let samples: Vec<[f64; 3]> = (0..400)
            .map(|_| {
                let jitter = (noise(&mut seed) * 1.6).round();
                [jitter.clamp(-3.0, 3.0), -502.0 + jitter.clamp(-4.0, 4.0), 70.0 + jitter.clamp(-3.0, 3.0)]
            })
            .collect();
        let engine = engine_with_session();
        push_axes(&engine, "accel", &samples, 25.0);
        let active: HashSet<i64> = [1].into();
        let mut codes = Vec::new();
        for now in [10_000, 11_000, 12_000, 13_000] {
            codes.extend(engine.tick(now, &active).alerts.into_iter().map(|alert| alert.code));
        }
        assert!(codes.iter().all(|code| code != "saturation"), "{codes:?}");
    }

    #[test]
    fn a_ppg_sensor_with_no_pulse_says_so() {
        // A ring on a desk: a large constant baseline and only converter noise.
        let mut seed = 5;
        let idle: Vec<f64> = (0..400).map(|_| 9_548_000.0 + 40.0 * noise(&mut seed)).collect();
        let signal = ppg_analysis("green", &idle, 25.0).expect("analysed");
        assert!(signal.perfusion_pct < PPG_NO_PULSE_PERFUSION_PCT, "{}", signal.perfusion_pct);
        assert_eq!(signal.hr_bpm, None);
        let worn = synthetic_ppg(72.0, 25.0, 10.0, 5.0);
        assert!(ppg_analysis("green", &worn, 25.0).unwrap().perfusion_pct > PPG_NO_PULSE_PERFUSION_PCT);
    }

    #[test]
    fn a_slow_baseline_drift_is_not_reported_as_a_heart_rate() {
        // A clean 40 bpm oscillation is the lower edge of the search band.
        let drift: Vec<f64> = (0..400)
            .map(|i| {
                let t = i as f64 / 25.0;
                9_000_000.0 + 20_000.0 * (2.0 * std::f64::consts::PI * (40.0 / 60.0) * t).sin()
            })
            .collect();
        let signal = ppg_analysis("green", &drift, 25.0).expect("analysed");
        assert_eq!(signal.hr_bpm, None, "{signal:?}");
    }

    /// Replays a recorded capture through the engine at full speed. Run with
    /// `LIVE_REPLAY_TSV=path cargo test replay_recorded -- --ignored --nocapture`
    /// where each line is: arrival ms, green, ir, accel x, y, z.
    #[test]
    #[ignore]
    fn replay_recorded_capture() {
        let Ok(path) = std::env::var("LIVE_REPLAY_TSV") else { return };
        let text = std::fs::read_to_string(path).expect("readable capture");
        // Optional: LIVE_REPLAY_DEVICE (default OpenRing), LIVE_REPLAY_FS (default 25) and a
        // seventh column with the red channel, for an earbud capture.
        let device = std::env::var("LIVE_REPLAY_DEVICE").unwrap_or_else(|_| "OpenRing".to_string());
        let fs: f64 = std::env::var("LIVE_REPLAY_FS").ok().and_then(|v| v.parse().ok()).unwrap_or(25.0);
        let engine = LiveEngine::new();
        engine.register_session(SessionMeta {
            session_id: 1,
            device_type: device,
            device_address: "AA".to_string(),
            owner_user_id: Some(3),
            study_id: None,
        });
        let active: HashSet<i64> = [1].into();
        let mut counts: BTreeMap<String, usize> = BTreeMap::new();
        let mut cleared_counts: BTreeMap<String, usize> = BTreeMap::new();
        let show_events = std::env::var("LIVE_REPLAY_EVENTS").is_ok();
        // One line per second for plotting or checking a rule: unix s, sqi, perfusion %, quality,
        // movement state, whether a rate was found.
        let mut tick_csv = std::env::var("LIVE_REPLAY_CSV")
            .ok()
            .map(|path| std::fs::File::create(path).expect("writable csv"));
        let mut hr_windows = 0;
        let mut windows = 0;
        let mut origin = None;
        let mut next_tick = 0;
        // The windows the live model would be sent, as JSON lines, so they can
        // be posted to a running worker.
        let mut model_out = std::env::var("LIVE_REPLAY_MODEL_WINDOWS")
            .ok()
            .map(|path| std::fs::File::create(path).expect("writable model window file"));
        for (i, line) in text.lines().enumerate() {
            let f: Vec<f64> = line.split('\t').filter_map(|v| v.parse().ok()).collect();
            if f.len() < 6 {
                continue;
            }
            let t0 = *origin.get_or_insert(f[0] as i64);
            let arrival = f[0] as i64 - t0;
            if next_tick == 0 {
                next_tick = 10_000;
            }
            while arrival >= next_tick {
                let output = engine.tick(next_tick, &active);
                for alert in &output.alerts {
                    if show_events {
                        println!("event t_ms={} t0_ms={} {} {}", next_tick, t0, alert.code, alert.state);
                    }
                    if alert.state == "raised" {
                        *counts.entry(alert.code.to_string()).or_default() += 1;
                    } else {
                        *cleared_counts.entry(alert.code.to_string()).or_default() += 1;
                    }
                }
                for summary in &output.summaries {
                    windows += 1;
                    if let (Some(file), Some(ppg)) = (tick_csv.as_mut(), &summary.ppg) {
                        use std::io::Write;
                        writeln!(
                            file,
                            "{},{:.3},{:.5},{},{},{}",
                            (t0 + next_tick) / 1000,
                            ppg.sqi,
                            ppg.perfusion_pct,
                            ppg.quality,
                            summary.movement.as_ref().map_or("none", |m| m.state),
                            ppg.hr_bpm.is_some()
                        )
                        .expect("write tick csv");
                    }
                    if std::env::var("LIVE_REPLAY_TRACE").is_ok() && next_tick % 5_000 == 0 {
                        if let Some(ppg) = &summary.ppg {
                            println!(
                                "t={:>4} hr={:?} sqi={:.2} locked={} reason={:?} move={:?} cadence={:?}",
                                next_tick / 1000, ppg.hr_bpm.map(|v| v.round()), ppg.sqi, ppg.motion_locked,
                                ppg.hr_reason, summary.movement.as_ref().map(|m| m.state),
                                summary.movement.as_ref().and_then(|m| m.cadence_hz).map(|c| (c * 60.0).round())
                            );
                        }
                    }
                    if summary.ppg.as_ref().is_some_and(|ppg| ppg.hr_bpm.is_some()) {
                        hr_windows += 1;
                    }
                }
                if let Some(file) = model_out.as_mut() {
                    use std::io::Write;
                    for request in engine.model_windows(next_tick) {
                        let line = json!({
                            "t_s": next_tick / 1000,
                            "fs_hz": request.fs_hz,
                            "ppg_data": request.values,
                            "live_hr_bpm": request.live_hr_bpm,
                            "live_sqi": request.live_sqi,
                            "movement": request.movement,
                        });
                        writeln!(file, "{line}").expect("write model window");
                    }
                }
                next_tick += 1_000;
            }
            let mut ppg = Map::new();
            ppg.insert("green".to_string(), json!(f[1]));
            ppg.insert("ir".to_string(), json!(f[2]));
            if let Some(red) = f.get(6) {
                ppg.insert("red".to_string(), json!(red));
            }
            engine.push(1, "ppg", arrival, Some(i as i64), Some(fs), &ppg);
            let mut accel = Map::new();
            accel.insert("x".to_string(), json!(f[3]));
            accel.insert("y".to_string(), json!(f[4]));
            accel.insert("z".to_string(), json!(f[5]));
            engine.push(1, "accel", arrival, Some(i as i64), Some(fs), &accel);
        }
        println!("windows {windows}, windows with a heart rate {hr_windows}");
        println!("alerts raised: {counts:?}");
        println!("alerts cleared: {cleared_counts:?}");
    }

    #[test]
    fn the_summary_carries_a_trace_to_plot() {
        let engine = LiveEngine::new();
        engine.register_session(SessionMeta {
            session_id: 1,
            device_type: "OpenRing".into(),
            device_address: "aa".into(),
            owner_user_id: None,
            study_id: None,
        });
        let ppg = synthetic_ppg(72.0, 25.0, 12.0, 0.0);
        push_ppg(&engine, &ppg, 1_000, 25.0, 0);
        let output = engine.tick(1_000 + 12_000, &[1].into_iter().collect());
        let trace = output.summaries[0].trace.as_ref().expect("trace");
        assert!(!trace.ppg.is_empty() && trace.ppg.len() <= TRACE_MAX_POINTS);
        assert_eq!(trace.window_ms, WINDOW_MS);
    }

    #[test]
    fn earbud_wording_names_the_ear_not_the_hand() {
        let site = wear_site("OpenEarable");
        assert_eq!(site, WearSite::Ear);
        assert!(hr_reason_text_for("moving", site).contains("head"));
        assert!(hr_reason_text_for("weak", site).contains("ear"));
        assert!(!hr_reason_text_for("weak", site).contains("hand"));
        assert!(site.no_pulse_alert("OpenEarable").contains("in the ear"));
        assert!(!hr_reason_text_for("moving", wear_site("OpenRing")).contains("head"));
    }

    #[test]
    fn small_head_movements_count_as_still_for_an_earbud() {
        // About 3 % variability: a head at rest, a hand that is moving.
        let axes: Vec<[f64; 3]> = (0..250)
            .map(|i| {
                let wobble = 0.3 * (i as f64 * 0.9).sin();
                [0.0, 0.0, 9.81 + wobble]
            })
            .collect();
        let (still, active) = WearSite::Ear.movement_limits();
        assert_eq!(movement_state_with(&axes, Some(25.0), still, active).unwrap().state, "still");
        assert_eq!(movement_state(&axes, Some(25.0)).unwrap().state, "moving");
    }

    fn shown_hr(engine: &LiveEngine, now: i64) -> (Option<f64>, Option<&'static str>) {
        let output = engine.tick(now, &[1].into());
        let ppg = output.summaries[0].ppg.clone().expect("ppg analysed");
        (ppg.display_hr_bpm, ppg.hr_source)
    }

    fn engine_with_clean_ring(prefer_model: bool) -> LiveEngine {
        let engine = engine_with_session();
        engine.set_prefer_model_hr(prefer_model);
        push_ppg(&engine, &synthetic_ppg(72.0, 25.0, 30.0, 5.0), 0, 25.0, 0);
        engine
    }

    #[test]
    fn display_hr_is_the_rules_value_unless_the_model_is_preferred() {
        let engine = engine_with_clean_ring(false);
        engine.set_model_estimate(1, estimate(95.0, 20_000));
        let (shown, source) = shown_hr(&engine, 21_000);
        assert_eq!(source, Some("rules"));
        assert!((shown.unwrap() - 72.0).abs() < 4.0, "{shown:?}");
    }

    #[test]
    fn display_hr_is_the_median_of_the_last_three_model_estimates() {
        let engine = engine_with_clean_ring(true);
        // One outlier between two plausible estimates: the median ignores it.
        engine.set_model_estimate(1, estimate(70.0, 10_000));
        engine.set_model_estimate(1, estimate(130.0, 15_000));
        engine.set_model_estimate(1, estimate(74.0, 20_000));
        let (shown, source) = shown_hr(&engine, 21_000);
        assert_eq!(source, Some("papagei"));
        assert_eq!(shown, Some(74.0));
    }

    #[test]
    fn display_hr_ignores_estimates_older_than_three_windows() {
        let engine = engine_with_clean_ring(true);
        engine.set_model_estimate(1, estimate(40.0, 5_000));
        engine.set_model_estimate(1, estimate(80.0, 20_000));
        // 5 s is exactly 3 windows before 20 s: excluded, so one value remains.
        let (shown, _) = shown_hr(&engine, 21_000);
        assert_eq!(shown, Some(80.0));
    }

    #[test]
    fn display_hr_with_two_estimates_takes_the_upper_middle() {
        let engine = engine_with_clean_ring(true);
        engine.set_model_estimate(1, estimate(70.0, 15_000));
        engine.set_model_estimate(1, estimate(80.0, 20_000));
        let (shown, _) = shown_hr(&engine, 21_000);
        assert_eq!(shown, Some(80.0));
    }

    #[test]
    fn display_hr_is_rounded_to_one_decimal() {
        let engine = engine_with_clean_ring(true);
        engine.set_model_estimate(1, estimate(71.26, 20_000));
        let (shown, _) = shown_hr(&engine, 21_000);
        assert_eq!(shown, Some(71.3));
    }

    #[test]
    fn a_stale_model_estimate_falls_back_to_the_rules_value() {
        let engine = engine_with_clean_ring(true);
        engine.set_model_estimate(1, estimate(95.0, 5_000));
        // 16 s after the estimate: older than MODEL_STALE_MS.
        let (shown, source) = shown_hr(&engine, 21_000);
        assert_eq!(source, Some("rules"));
        assert!((shown.unwrap() - 72.0).abs() < 4.0, "{shown:?}");
    }

    #[test]
    fn no_pulse_shows_no_heart_rate_even_with_a_model_estimate() {
        let engine = engine_with_session();
        engine.set_prefer_model_hr(true);
        let mut seed = 5;
        let idle: Vec<f64> = (0..750).map(|_| 9_548_000.0 + 40.0 * noise(&mut seed)).collect();
        push_ppg(&engine, &idle, 0, 25.0, 0);
        engine.set_model_estimate(1, estimate(88.0, 20_000));
        let output = engine.tick(21_000, &[1].into());
        let ppg = output.summaries[0].ppg.clone().unwrap();
        assert_eq!(ppg.hr_reason, Some("no_pulse"));
        assert_eq!(ppg.display_hr_bpm, None);
        assert_ne!(ppg.hr_source, Some("papagei"));
    }

    #[test]
    fn a_stall_raises_one_alert_and_does_not_repeat_it() {
        let engine = engine_with_session();
        let active: HashSet<i64> = [1].into();
        push_ppg(&engine, &synthetic_ppg(72.0, 25.0, 10.0, 5.0), 0, 25.0, 0);
        let output = engine.tick(10_000 + 3 * STALL_MS, &active);
        let codes: Vec<_> = output.alerts.iter().map(|a| a.code.clone()).collect();
        assert_eq!(codes, vec!["stream_stalled".to_string()], "{codes:?}");
        let again = engine.tick(10_000 + 3 * STALL_MS + 1_000, &active);
        assert!(again.alerts.is_empty(), "{:?}", again.alerts);
    }

    #[test]
    fn pushes_for_an_unregistered_session_are_dropped() {
        let engine = LiveEngine::new();
        let mut ch = Map::new();
        ch.insert("green".to_string(), json!(1.0));
        engine.push(99, "ppg", 0, Some(0), Some(25.0), &ch);
        assert!(engine.snapshot().sessions.is_empty());
        assert!(engine.tick(1_000, &[99].into()).summaries.is_empty());
    }

    #[test]
    fn non_numeric_channels_do_not_break_a_push() {
        let engine = engine_with_session();
        let mut ch = Map::new();
        ch.insert("green".to_string(), json!("not a number"));
        ch.insert("ir".to_string(), json!(null));
        engine.push(1, "ppg", 0, Some(0), Some(25.0), &ch);
        let output = engine.tick(1_000, &[1].into());
        assert!(output.alerts.is_empty());
    }

    #[test]
    fn a_ppg_window_with_non_finite_values_does_not_panic() {
        let mut values = synthetic_ppg(72.0, 25.0, 10.0, 5.0);
        values[10] = f64::NAN;
        values[20] = f64::INFINITY;
        let _ = ppg_analysis("green", &values, 25.0);
    }

    #[test]
    fn index_continuity_of_nothing_and_of_one_sample() {
        assert_eq!(index_continuity(&[]), Continuity { unique: 0, missing: 0, duplicates: 0, resets: 0 });
        assert_eq!(index_continuity(&[5]), Continuity { unique: 1, missing: 0, duplicates: 0, resets: 0 });
    }

    // ---- activity pilot (MOMENT head on accelerometer windows) ----

    fn push_accel(engine: &LiveEngine, seconds: f64, start_ms: i64, fs: f64) {
        let n = (seconds * fs) as usize;
        for i in 0..n {
            let mut channels = Map::new();
            channels.insert("x".to_string(), json!(0.1 * (i % 7) as f64));
            channels.insert("y".to_string(), json!(0.0));
            channels.insert("z".to_string(), json!(9.8));
            engine.push(
                1,
                "accel",
                start_ms + (i as f64 * 1000.0 / fs) as i64,
                Some(i as i64),
                Some(fs),
                &channels,
            );
        }
    }

    fn activity(label: &str, window_end_ms: i64) -> ActivityEstimate {
        ActivityEstimate {
            label: label.to_string(),
            confidence: 0.8,
            source: ActivitySource {
                model_id: "moment_1_small".to_string(),
                head_id: "act".to_string(),
                head_version: Some("t".to_string()),
            },
            validation_status: Some("pilot".to_string()),
            runtime: None,
            window_end_ms,
            inference_ms: None,
        }
    }

    fn worker_answer(label: &str, probabilities: Value) -> Value {
        json!({
            "status": "success",
            "runtime": "torch-cpu",
            "inference_ms": 31.0,
            "head_result": {
                "head": {"base_model_id": "moment_1_small", "head_version": "v1", "validation_status": "pilot"},
                "prediction": {"label": label, "probabilities": probabilities}
            }
        })
    }

    fn request() -> ActivityRequest {
        ActivityRequest {
            session_id: 1,
            device_type: "OpenRing".to_string(),
            values: vec![[0.0, 0.0, 9.8]; 250],
            fs_hz: 25.0,
            window_end_ms: 10_000,
        }
    }

    #[test]
    fn the_activity_pilot_is_off_by_default_and_asks_for_nothing() {
        let engine = engine_with_session();
        push_accel(&engine, 12.0, 0, 25.0);
        assert!(engine.activity_windows(11_900).is_empty(), "off unless LIVE_ACTIVITY_MODEL is set");
        let active: HashSet<i64> = [1].into();
        let tick = engine.tick(11_900, &active);
        assert!(tick.summaries[0].activity.is_none());
        let json = serde_json::to_value(&tick.summaries[0]).unwrap();
        assert!(json.get("activity").is_none(), "no activity key at all when off");
    }

    #[test]
    fn an_enabled_pilot_gets_ten_seconds_of_accelerometer_every_five() {
        let engine = engine_with_session();
        engine.set_activity_enabled(true);
        push_accel(&engine, 12.0, 0, 25.0);
        let requests = engine.activity_windows(11_900);
        assert_eq!(requests.len(), 1);
        let r = &requests[0];
        assert_eq!(r.values.len(), 250, "ten seconds at 25 Hz");
        assert_eq!(r.fs_hz, 25.0);
        assert_eq!(r.device_type, "OpenRing");
        assert_eq!(r.values[0][2], 9.8);
        assert!(engine.activity_windows(13_000).is_empty(), "not again within five seconds");
        assert_eq!(engine.activity_windows(17_000).len(), 0, "the buffer has run dry by then, so nothing is sent");
    }

    #[test]
    fn a_window_with_a_dropout_or_without_accelerometer_is_not_sent() {
        let engine = engine_with_session();
        engine.set_activity_enabled(true);
        assert!(engine.activity_windows(10_000).is_empty(), "no accel stream yet");
        push_accel(&engine, 6.0, 4_000, 25.0); // only six of ten seconds present
        assert!(engine.activity_windows(10_000).is_empty(), "under 80% full is skipped, not guessed");
    }

    #[test]
    fn without_a_worker_there_is_no_activity_and_nothing_else_changes() {
        // The pilot is on and windows are due, but no estimate ever arrives (worker absent).
        let engine = engine_with_session();
        engine.set_activity_enabled(true);
        let values = synthetic_ppg(72.0, 25.0, 30.0, 5.0);
        push_ppg(&engine, &values, 0, 25.0, 0);
        push_accel(&engine, 30.0, 0, 25.0);
        let active: HashSet<i64> = [1].into();
        assert_eq!(engine.activity_windows(29_900).len(), 1);
        let tick = engine.tick(30_000, &active);
        assert!(tick.summaries[0].activity.is_none());
        assert!(tick.summaries[0].ppg.as_ref().unwrap().hr_bpm.is_some(), "heart rate is unaffected");
        assert!(tick.summaries[0].movement.is_some(), "rule-based movement is unaffected");
    }

    #[test]
    fn an_activity_estimate_is_shown_until_it_is_stale() {
        let engine = engine_with_session();
        engine.set_activity_enabled(true);
        push_accel(&engine, 30.0, 0, 25.0);
        engine.set_activity_estimate(1, activity("sitting", 20_000));
        let active: HashSet<i64> = [1].into();

        let fresh = engine.tick(25_000, &active);
        let shown = fresh.summaries[0].activity.as_ref().expect("fresh estimate shown");
        assert_eq!(shown.label, "sitting");
        let json = serde_json::to_value(&fresh.summaries[0]).unwrap();
        assert_eq!(json["activity"]["label"], "sitting");
        assert_eq!(json["activity"]["confidence"], 0.8);
        assert_eq!(json["activity"]["source"]["model_id"], "moment_1_small");
        assert_eq!(json["activity"]["source"]["head_id"], "act");

        let stale = engine.tick(36_000, &active);
        assert!(stale.summaries[0].activity.is_none(), "older than 15 s is not shown as current");
    }

    #[test]
    fn an_activity_estimate_for_an_ended_recording_is_ignored() {
        let engine = engine_with_session();
        engine.set_activity_estimate(99, activity("sitting", 1_000));
        engine.set_activity_estimate(1, activity("lying down", 1_000));
        let none: HashSet<i64> = HashSet::new();
        engine.tick(2_000, &none); // recording 1 has ended
        engine.set_activity_estimate(1, activity("sitting", 2_000));
        assert!(engine.snapshot().sessions.is_empty());
    }

    #[test]
    fn the_worker_answer_becomes_an_estimate_naming_its_source() {
        let body = worker_answer("lying down", json!([0.2, 0.8]));
        let est = activity_from_response(&body, "act", &request()).expect("valid answer");
        assert_eq!(est.label, "lying down");
        assert_eq!(est.confidence, 0.8);
        assert_eq!(est.source.head_id, "act");
        assert_eq!(est.source.model_id, "moment_1_small");
        assert_eq!(est.source.head_version.as_deref(), Some("v1"));
        assert_eq!(est.validation_status.as_deref(), Some("pilot"));
        assert_eq!(est.window_end_ms, 10_000);
        assert_eq!(est.inference_ms, Some(31.0));
    }

    #[test]
    fn a_malformed_worker_answer_is_an_error_not_an_estimate() {
        let r = request();
        assert!(activity_from_response(&json!({"status": "success"}), "act", &r).is_err());
        assert!(activity_from_response(&worker_answer("", json!([0.5, 0.5])), "act", &r).is_err());
        assert!(activity_from_response(&worker_answer("sitting", json!([])), "act", &r).is_err());
        assert!(activity_from_response(&worker_answer("sitting", json!([1.7, 0.1])), "act", &r).is_err());
        assert!(activity_from_response(&worker_answer("sitting", json!("x")), "act", &r).is_err());
        let no_label = json!({"head_result": {"prediction": {"probabilities": [1.0]}}});
        assert!(activity_from_response(&no_label, "act", &r).is_err());
    }

    // ---- ppg_quality_low: counting rule over a sliding span ----

    fn weak_session() -> SessionState {
        SessionState {
            meta: SessionMeta {
                session_id: 1,
                device_type: "OpenRing".to_string(),
                device_address: "AA".to_string(),
                owner_user_id: Some(3),
                study_id: None,
            },
            streams: BTreeMap::new(),
            trackers: BTreeMap::new(),
            latest: None,
            hr_history: VecDeque::new(),
            model: None,
            model_history: VecDeque::new(),
            last_model_request_ms: None,
            activity: None,
            last_activity_request_ms: None,
        }
    }

    /// Feeds one tick per element (true = a poor window) and returns, per tick,
    /// "raised", "cleared" or "" for ppg_quality_low.
    fn weak_ticks(session: &mut SessionState, ticks: &[bool], start_s: i64) -> Vec<&'static str> {
        ticks
            .iter()
            .enumerate()
            .map(|(i, poor)| {
                let conditions = if *poor {
                    vec![Condition {
                        code: "ppg_quality_low",
                        stream: Some("ppg".to_string()),
                        severity: "warning",
                        message: "weak".to_string(),
                        quick: false,
                        moving: false,
                        details: json!({}),
                    }]
                } else {
                    Vec::new()
                };
                let events = update_trackers(session, conditions, (start_s + i as i64) * 1000);
                events.iter().find(|e| e.code == "ppg_quality_low").map_or("", |e| e.state)
            })
            .collect()
    }

    #[test]
    fn a_flickering_weak_signal_is_raised() {
        let mut session = weak_session();
        // Poor 7 ticks in 10, never more than 3 in a row.
        let ticks: Vec<bool> = (0..90).map(|i| ![2, 5, 8].contains(&(i % 10))).collect();
        let longest = ticks.split(|p| !*p).map(|run| run.len()).max().unwrap();
        assert!(longest < 10);
        let out = weak_ticks(&mut session, &ticks, 0);
        assert!(out.contains(&"raised"), "{out:?}");
    }

    #[test]
    fn a_short_bad_patch_is_not_raised() {
        let mut session = weak_session();
        let mut ticks = vec![false; 20];
        ticks.extend(vec![true; 30]);
        ticks.extend(vec![false; 60]);
        assert!(!weak_ticks(&mut session, &ticks, 0).contains(&"raised"));
        // Two separate patches that each fall short are not added together outside the span.
        let mut session = weak_session();
        let mut ticks = vec![true; 30];
        ticks.extend(vec![false; 40]);
        ticks.extend(vec![true; 30]);
        assert!(!weak_ticks(&mut session, &ticks, 0).contains(&"raised"));
    }

    #[test]
    fn a_clean_stream_never_raises_the_weak_signal_alert() {
        let mut session = weak_session();
        let out = weak_ticks(&mut session, &vec![false; 300], 0);
        assert!(out.iter().all(|state| state.is_empty()));
        assert!(session.trackers.is_empty());
    }

    #[test]
    fn an_unbroken_weak_signal_is_still_raised() {
        let mut session = weak_session();
        let out = weak_ticks(&mut session, &vec![true; 60], 0);
        let at = out.iter().position(|state| *state == "raised").expect("raised");
        assert!(at < 45, "raised at tick {at}");
        assert_eq!(out.iter().filter(|state| **state == "raised").count(), 1);
    }

    #[test]
    fn the_weak_signal_alert_clears_with_hysteresis() {
        let mut session = weak_session();
        let mut ticks = vec![true; 60];
        // Back to clean: the count must fall to 20 of the last 60 before it clears,
        // and a poor tick in between does not make it chatter.
        ticks.extend(vec![false; 30]);
        ticks.push(true);
        ticks.extend(vec![false; 30]);
        let out = weak_ticks(&mut session, &ticks, 0);
        let raised: Vec<usize> = (0..out.len()).filter(|i| out[*i] == "raised").collect();
        let cleared: Vec<usize> = (0..out.len()).filter(|i| out[*i] == "cleared").collect();
        assert_eq!(raised.len(), 1, "{out:?}");
        assert_eq!(cleared.len(), 1, "{out:?}");
        // 60 poor then 40 clean leaves 20 poor in the span (the first clear opportunity,
        // tick 99); the lone poor tick keeps the count at 21 until it ages out.
        assert!(cleared[0] > 99, "cleared at tick {}", cleared[0]);
        // Hovering between the limits neither raises nor clears.
        let mut session = weak_session();
        weak_ticks(&mut session, &vec![true; 60], 0);
        let hover: Vec<bool> = (0..120).map(|i| i % 2 == 0).collect();
        let out = weak_ticks(&mut session, &hover, 60);
        assert!(out.iter().all(|state| state.is_empty()), "{out:?}");
    }

    // ---- recipe 1.3.0: a sustained poor signal counts while moving, with a stricter bar ----

    /// One tick per element: (poor window, wearer moving). Returns "raised"/"cleared"/"" per tick.
    fn weak_moving_ticks(session: &mut SessionState, ticks: &[(bool, bool)], start_s: i64) -> Vec<&'static str> {
        ticks
            .iter()
            .enumerate()
            .map(|(i, (poor, moving))| {
                let conditions = if *poor {
                    vec![Condition {
                        code: "ppg_quality_low",
                        stream: Some("ppg".to_string()),
                        severity: "warning",
                        message: "weak".to_string(),
                        quick: false,
                        moving: *moving,
                        details: json!({}),
                    }]
                } else {
                    Vec::new()
                };
                let events = update_trackers(session, conditions, (start_s + i as i64) * 1000);
                events.iter().find(|e| e.code == "ppg_quality_low").map_or("", |e| e.state)
            })
            .collect()
    }

    #[test]
    fn a_loosened_ring_poor_and_flickering_while_the_hand_moves_is_raised() {
        let mut session = weak_session();
        // 20 s of clean wear, then the loosened ring: the quality window lags the fault by
        // about 10 s, so about 64 s of poor windows with three fair ones among them (the quality
        // index flickering across the line), the hand moving throughout. The recorded event
        // had three fair windows in 140 s. No run of poor ticks reaches the no-pulse rule's 10.
        let mut ticks: Vec<(bool, bool)> = (0..20).map(|_| (false, false)).collect();
        ticks.extend((0..64).map(|i| (i != 17 && i != 41 && i != 55, true)));
        ticks.extend((0..60).map(|_| (false, false)));
        let out = weak_moving_ticks(&mut session, &ticks, 0);
        let at = out.iter().position(|s| *s == "raised").expect("raised");
        assert!((20..84).contains(&at), "raised at tick {at}");
        assert_eq!(out.iter().filter(|s| **s == "raised").count(), 1, "{out:?}");
        assert_eq!(out.iter().filter(|s| **s == "cleared").count(), 1, "{out:?}");
    }

    #[test]
    fn ordinary_movement_with_fair_windows_is_not_raised() {
        let mut session = weak_session();
        // Two minutes of hand movement, poor one window in three, otherwise a visible pulse.
        let ticks: Vec<(bool, bool)> = (0..120).map(|i| (i % 3 == 0, true)).collect();
        assert!(!weak_moving_ticks(&mut session, &ticks, 0).contains(&"raised"));
        // Poor two windows in three (40 of 60) while moving meets the still bar but not the
        // stricter moving bar.
        let mut session = weak_session();
        let ticks: Vec<(bool, bool)> = (0..180).map(|i| (i % 3 != 0, true)).collect();
        assert!(!weak_moving_ticks(&mut session, &ticks, 0).contains(&"raised"));
        // Even 9 in 10 poor (54 of 60) with the hand moving is a pulse that keeps getting through.
        let mut session = weak_session();
        let ticks: Vec<(bool, bool)> = (0..180).map(|i| (i % 10 != 0, true)).collect();
        assert!(!weak_moving_ticks(&mut session, &ticks, 0).contains(&"raised"));
    }

    #[test]
    fn exercise_with_an_intermittent_pulse_is_not_raised() {
        let mut session = weak_session();
        // Five minutes of vigorous activity: the signal is lost in bursts of 20 s with 20 s of
        // visible pulse between them (at most 40 poor in any span of 60).
        let ticks: Vec<(bool, bool)> = (0..300).map(|i| (i % 40 < 20, true)).collect();
        let worst = (0..=240)
            .map(|from| ticks[from..from + 60].iter().filter(|(p, _)| *p).count())
            .max()
            .unwrap();
        assert!(worst < WEAK_SIGNAL_RAISE_COUNT_MOVING, "worst span {worst}");
        assert!(!weak_moving_ticks(&mut session, &ticks, 0).contains(&"raised"));
    }

    #[test]
    fn exercise_with_a_lost_pulse_for_most_of_a_minute_is_raised() {
        // The same bar applies the other way: one fair window in 20 (57 of 60 poor) while moving
        // is a lost pulse.
        let mut session = weak_session();
        let ticks: Vec<(bool, bool)> = (0..120).map(|i| (i % 20 != 0, true)).collect();
        assert!(weak_moving_ticks(&mut session, &ticks, 0).contains(&"raised"));
    }

    #[test]
    fn poor_windows_while_still_keep_their_own_bar_of_forty() {
        // 40 of 60 poor while still, plus 15 more while moving: raised at the 1.2.0 bar (still
        // windows alone), although the 57 bar over all windows is not met.
        let mut session = weak_session();
        let ticks: Vec<(bool, bool)> = (0..60).map(|i| (i % 4 != 3 || i < 20, i % 4 == 0 && i >= 20)).collect();
        let poor = ticks.iter().filter(|(p, _)| *p).count();
        let still_poor = ticks.iter().filter(|(p, m)| *p && !*m).count();
        assert!(poor < WEAK_SIGNAL_RAISE_COUNT_MOVING && still_poor >= WEAK_SIGNAL_RAISE_COUNT, "{poor} {still_poor}");
        assert!(weak_moving_ticks(&mut session, &ticks, 0).contains(&"raised"));
    }

    #[test]
    fn the_moving_alert_has_hysteresis_and_does_not_chatter() {
        let mut session = weak_session();
        let mut ticks: Vec<(bool, bool)> = (0..60).map(|_| (true, true)).collect();
        // Hover between the limits (30 of 60 poor): no clear, no second raise.
        ticks.extend((0..120).map(|i| (i % 2 == 0, true)));
        let out = weak_moving_ticks(&mut session, &ticks, 0);
        assert_eq!(out.iter().filter(|s| **s == "raised").count(), 1, "{out:?}");
        assert_eq!(out.iter().filter(|s| **s == "cleared").count(), 0, "{out:?}");
        // Then recovered: one clear, and a hover below the raise bar afterwards (still moving)
        // does not raise it again.
        let mut more: Vec<(bool, bool)> = (0..60).map(|_| (false, true)).collect();
        more.extend((0..180).map(|i| (i % 3 != 0, true)));
        let out = weak_moving_ticks(&mut session, &more, 180);
        assert_eq!(out.iter().filter(|s| **s == "cleared").count(), 1, "{out:?}");
        assert_eq!(out.iter().filter(|s| **s == "raised").count(), 0, "{out:?}");
    }

    #[test]
    fn a_clean_moving_stream_never_raises() {
        let mut session = weak_session();
        let ticks: Vec<(bool, bool)> = (0..300).map(|_| (false, true)).collect();
        let out = weak_moving_ticks(&mut session, &ticks, 0);
        assert!(out.iter().all(|s| s.is_empty()));
        assert!(session.trackers.is_empty());
    }

    /// Engine level: a clean pulse, then a stretch of noise (no pulse) with the hand moving,
    /// then clean again. Returns the alerts raised, per code.
    fn run_noisy_stretch(moving_during_noise: bool, noise_s: i64) -> BTreeMap<String, usize> {
        let engine = engine_with_session();
        let active: HashSet<i64> = [1].into();
        let clean = synthetic_ppg(72.0, 25.0, 400.0, 3.0);
        let mut seed = 99_u64;
        let mut counts: BTreeMap<String, usize> = BTreeMap::new();
        let noisy = 20..(20 + noise_s);
        let mut index = 0;
        for s in 0..(20 + noise_s + 60) {
            let lost = noisy.contains(&s);
            for k in 0..25 {
                let t = s * 1000 + k * 40;
                let mut ppg = Map::new();
                let value = if lost {
                    {
                        // Hand motion with the sensor off the skin: slow swings and a little
                        // noise, no pulse.
                        let t = (s * 1000 + k * 40) as f64 / 1000.0;
                        5000.0
                            + 600.0 * (2.0 * std::f64::consts::PI * 0.31 * t).sin()
                            + 400.0 * (2.0 * std::f64::consts::PI * 0.17 * t).sin()
                            + 40.0 * noise(&mut seed)
                    }
                } else {
                    clean[(s as usize * 25 + k as usize) % clean.len()]
                };
                ppg.insert("green".to_string(), json!(value));
                engine.push(1, "ppg", t, Some(index), Some(25.0), &ppg);
                let jitter = if lost && moving_during_noise { 1.6 * noise(&mut seed) } else { 0.0 };
                let mut accel = Map::new();
                accel.insert("x".to_string(), json!(0.0));
                accel.insert("y".to_string(), json!(0.0));
                accel.insert("z".to_string(), json!(9.8 + jitter));
                engine.push(1, "accel", t, Some(index), Some(25.0), &accel);
                index += 1;
            }
            if s >= 10 {
                let output = engine.tick((s + 1) * 1000, &active);
                for alert in output.alerts.iter().filter(|alert| alert.state == "raised") {
                    *counts.entry(alert.code.to_string()).or_default() += 1;
                }
            }
        }
        counts
    }

    #[test]
    fn a_noisy_ring_while_the_hand_moves_raises_the_weak_signal_alert_end_to_end() {
        let counts = run_noisy_stretch(true, 75);
        assert_eq!(counts.get("ppg_quality_low"), Some(&1), "{counts:?}");
    }

    #[test]
    fn a_short_noisy_stretch_while_moving_raises_nothing_end_to_end() {
        let counts = run_noisy_stretch(true, 20);
        assert!(!counts.contains_key("ppg_quality_low"), "{counts:?}");
        assert!(!counts.contains_key("ppg_no_pulse"), "{counts:?}");
    }
}
