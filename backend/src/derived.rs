//! Derived results, one row per window per method, and recomputing them from
//! stored evidence.
//!
//! Live analysis writes rows as a recording runs. A recompute replays a stored
//! recording through a fresh live engine, exactly as ingestion fed it, and
//! writes rows tagged with the run, so an algorithm can be changed and rerun
//! over everything already collected. The run records how many evidence rows
//! it read and their SHA-256, which shows two runs read identical input.

use std::collections::HashSet;

use anyhow::{anyhow, Result};
use serde::Serialize;
use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};
use sqlx::{MySqlPool, QueryBuilder, Row};
use tracing::warn;

use crate::live::{self, LiveEngine, ModelEstimate, SessionMeta, SessionSummary};

/// The live algorithm's method id; its version is `live::RECIPE_VERSION`.
pub const LIVE_METHOD_ID: &str = "live_ppg";
/// Live rows are written every this many one-second ticks, as summaries are.
pub const LIVE_ROW_EVERY_TICKS: u64 = 5;
const PAGE_ROWS: i64 = 5_000;
const INSERT_BATCH: usize = 200;

#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct DerivedRow {
    pub session_id: i64,
    pub run_id: Option<i64>,
    pub method_id: String,
    pub method_version: String,
    pub window_start_ms: i64,
    pub window_end_ms: i64,
    pub hr_bpm: Option<f64>,
    pub sqi: Option<f64>,
    pub quality: Option<String>,
    pub hr_reason: Option<String>,
    pub movement: Option<String>,
    pub channel: Option<String>,
    pub details: Value,
}

/// The live algorithm's result for one summary window.
pub fn live_row(summary: &SessionSummary, run_id: Option<i64>) -> DerivedRow {
    let ppg = summary.ppg.as_ref();
    DerivedRow {
        session_id: summary.meta.session_id,
        run_id,
        method_id: LIVE_METHOD_ID.to_string(),
        method_version: summary.recipe_version.to_string(),
        window_start_ms: summary.window_start_ms,
        window_end_ms: summary.window_end_ms,
        hr_bpm: ppg.and_then(|signal| signal.hr_bpm).map(round1),
        sqi: ppg.map(|signal| (signal.sqi * 1000.0).round() / 1000.0),
        quality: ppg.map(|signal| signal.quality.to_string()),
        hr_reason: ppg.and_then(|signal| signal.hr_reason).map(str::to_string),
        movement: summary.movement.as_ref().map(|movement| movement.state.to_string()),
        channel: ppg.map(|signal| signal.channel.clone()),
        details: json!({
            "perfusion_pct": ppg.map(|signal| signal.perfusion_pct),
            "motion_locked": ppg.map(|signal| signal.motion_locked),
            "active_alerts": summary.active_alerts.iter().map(|alert| alert.code.clone()).collect::<Vec<_>>(),
        }),
    }
}

/// The model's result for the window it was given.
pub fn model_row(session_id: i64, estimate: &ModelEstimate, run_id: Option<i64>) -> DerivedRow {
    DerivedRow {
        session_id,
        run_id,
        method_id: model_method_id(estimate),
        method_version: estimate
            .head_version
            .clone()
            .unwrap_or_else(|| "unversioned".to_string()),
        window_start_ms: estimate.window_end_ms - (live::MODEL_WINDOW_S * 1000.0) as i64,
        window_end_ms: estimate.window_end_ms,
        hr_bpm: Some(round1(estimate.hr_bpm)),
        sqi: Some((estimate.live_sqi * 1000.0).round() / 1000.0),
        quality: None,
        hr_reason: None,
        movement: estimate.movement.map(str::to_string),
        channel: Some(estimate.channel.clone()),
        details: json!({
            "model_id": estimate.model_id,
            "head_id": estimate.head_id,
            "runtime": estimate.runtime,
            "inference_ms": estimate.inference_ms,
            "validation_status": estimate.validation_status,
            "live_hr_bpm": estimate.live_hr_bpm,
        }),
    }
}

pub fn model_method_id(estimate: &ModelEstimate) -> String {
    format!("{}+{}", estimate.model_id, estimate.head_id)
}

fn round1(value: f64) -> f64 {
    (value * 10.0).round() / 10.0
}

pub async fn insert_rows(db: &MySqlPool, rows: &[DerivedRow]) -> Result<()> {
    for chunk in rows.chunks(INSERT_BATCH) {
        let mut builder = QueryBuilder::new(
            "INSERT INTO derived_windows (session_id, run_id, method_id, method_version, \
             window_start_ms, window_end_ms, hr_bpm, sqi, quality, hr_reason, movement, channel, details) ",
        );
        builder.push_values(chunk, |mut b, row| {
            b.push_bind(row.session_id)
                .push_bind(row.run_id)
                .push_bind(&row.method_id)
                .push_bind(&row.method_version)
                .push_bind(row.window_start_ms)
                .push_bind(row.window_end_ms)
                .push_bind(row.hr_bpm)
                .push_bind(row.sqi)
                .push_bind(&row.quality)
                .push_bind(&row.hr_reason)
                .push_bind(&row.movement)
                .push_bind(&row.channel)
                .push_bind(row.details.to_string());
        });
        builder.build().execute(db).await?;
    }
    Ok(())
}

/// What the model worker needs for a recompute: the same call the live path makes.
#[derive(Clone)]
pub struct ModelClient {
    pub client: reqwest::Client,
    pub url: String,
    pub head_id: String,
}

/// One stored sample, as ingestion handed it to the live engine.
#[derive(Debug, Clone, PartialEq)]
pub struct EvidenceRow {
    pub id: i64,
    pub stream_name: String,
    pub arrival_ms: i64,
    pub index: Option<i64>,
    pub nominal_rate_hz: Option<f64>,
    /// `channel_values` exactly as MySQL returns it, which is what is hashed.
    pub channel_values: String,
}

/// Running SHA-256 over the evidence a run reads, in id order.
#[derive(Default)]
pub struct EvidenceHash {
    hasher: Sha256,
    pub rows: i64,
}

impl EvidenceHash {
    pub fn update(&mut self, row: &EvidenceRow) {
        self.hasher.update(
            format!(
                "{}|{}|{}|{:?}|{:?}|{}\n",
                row.id, row.stream_name, row.arrival_ms, row.index, row.nominal_rate_hz, row.channel_values
            )
            .as_bytes(),
        );
        self.rows += 1;
    }

    pub fn finish(self) -> String {
        self.hasher.finalize().iter().map(|byte| format!("{byte:02x}")).collect()
    }
}

/// Replays evidence through a private live engine, one-second ticks on the
/// arrival clock, just as `run_live_analysis` ticks on the wall clock.
pub struct Replay {
    engine: LiveEngine,
    session_id: i64,
    active: HashSet<i64>,
    next_tick_ms: Option<i64>,
    ticks: u64,
}

pub struct TickResult {
    pub live_row: Option<DerivedRow>,
    pub model_requests: Vec<live::ModelRequest>,
}

impl Replay {
    pub fn new(meta: SessionMeta) -> Self {
        let session_id = meta.session_id;
        let engine = LiveEngine::new();
        engine.register_session(meta);
        Self { engine, session_id, active: [session_id].into(), next_tick_ms: None, ticks: 0 }
    }

    /// Feed one row; returns the ticks that fell due before it arrived.
    pub fn push(&mut self, row: &EvidenceRow, run_id: Option<i64>, with_model: bool) -> Vec<TickResult> {
        let first_tick = *self.next_tick_ms.get_or_insert(row.arrival_ms + live::TICK_MS as i64);
        let mut results = Vec::new();
        let mut next = first_tick;
        while row.arrival_ms >= next {
            results.push(self.tick(next, run_id, with_model));
            next += live::TICK_MS as i64;
        }
        self.next_tick_ms = Some(next);
        if let Ok(Value::Object(channels)) = serde_json::from_str::<Value>(&row.channel_values) {
            self.engine.push(
                self.session_id,
                &row.stream_name,
                row.arrival_ms,
                row.index,
                row.nominal_rate_hz,
                &channels,
            );
        }
        results
    }

    /// One final tick after the last sample, so its window is analysed too.
    pub fn finish(&mut self, run_id: Option<i64>, with_model: bool) -> Option<TickResult> {
        let next = self.next_tick_ms?;
        Some(self.tick(next, run_id, with_model))
    }

    fn tick(&mut self, now_ms: i64, run_id: Option<i64>, with_model: bool) -> TickResult {
        self.ticks += 1;
        let output = self.engine.tick(now_ms, &self.active);
        let live_row = (self.ticks % LIVE_ROW_EVERY_TICKS == 0)
            .then(|| output.summaries.first().map(|summary| live_row(summary, run_id)))
            .flatten();
        let model_requests = if with_model { self.engine.model_windows(now_ms) } else { Vec::new() };
        TickResult { live_row, model_requests }
    }

    pub fn set_model_estimate(&self, estimate: ModelEstimate) {
        self.engine.set_model_estimate(self.session_id, estimate);
    }
}

pub struct RecomputeOutcome {
    pub evidence_rows: i64,
    pub evidence_sha256: String,
    pub window_count: i64,
}

/// Recompute every derived window of one recording from its stored samples.
pub async fn recompute(
    db: &MySqlPool,
    session_id: i64,
    run_id: i64,
    model: Option<&ModelClient>,
) -> Result<RecomputeOutcome> {
    let meta_row = sqlx::query(
        "SELECT s.owner_user_id, s.study_id, d.device_type, d.device_address \
         FROM sessions s JOIN devices d ON d.id = s.device_id WHERE s.id = ?",
    )
    .bind(session_id)
    .fetch_optional(db)
    .await?
    .ok_or_else(|| anyhow!("recording {session_id} not found"))?;
    let meta = SessionMeta {
        session_id,
        device_type: meta_row.try_get("device_type")?,
        device_address: meta_row.try_get::<Option<String>, _>("device_address")?.unwrap_or_default(),
        owner_user_id: meta_row.try_get("owner_user_id").ok().flatten(),
        study_id: meta_row.try_get("study_id").ok().flatten(),
    };

    let mut replay = Replay::new(meta);
    let mut hash = EvidenceHash::default();
    let mut pending: Vec<DerivedRow> = Vec::new();
    let mut window_count = 0i64;
    let mut model_failures = 0u64;
    let mut after_id = 0i64;
    loop {
        let page = load_evidence_page(db, session_id, after_id).await?;
        let Some(last) = page.last() else { break };
        after_id = last.id;
        for row in &page {
            hash.update(row);
            for result in replay.push(row, Some(run_id), model.is_some()) {
                handle_tick(&replay, result, session_id, run_id, model, &mut pending, &mut model_failures).await;
            }
        }
        window_count += pending.len() as i64;
        insert_rows(db, &pending).await?;
        pending.clear();
    }
    if let Some(result) = replay.finish(Some(run_id), model.is_some()) {
        handle_tick(&replay, result, session_id, run_id, model, &mut pending, &mut model_failures).await;
    }
    window_count += pending.len() as i64;
    insert_rows(db, &pending).await?;
    if model_failures > 0 {
        warn!(session_id, run_id, model_failures, "recompute: some model windows failed");
    }
    Ok(RecomputeOutcome { evidence_rows: hash.rows, evidence_sha256: hash.finish(), window_count })
}

async fn handle_tick(
    replay: &Replay,
    result: TickResult,
    session_id: i64,
    run_id: i64,
    model: Option<&ModelClient>,
    pending: &mut Vec<DerivedRow>,
    model_failures: &mut u64,
) {
    pending.extend(result.live_row);
    let Some(model) = model else { return };
    for request in result.model_requests {
        match crate::live_model_estimate(&model.client, &model.url, &model.head_id, &request).await {
            Ok(estimate) => {
                pending.push(model_row(session_id, &estimate, Some(run_id)));
                replay.set_model_estimate(estimate);
            }
            Err(_) => *model_failures += 1,
        }
    }
}

async fn load_evidence_page(db: &MySqlPool, session_id: i64, after_id: i64) -> Result<Vec<EvidenceRow>> {
    let rows = sqlx::query(
        "SELECT id, stream_name, \
                COALESCE(backend_received_timestamp_ms, received_timestamp_ms) AS arrival_ms, \
                COALESCE(sample_index, sequence_number) AS sample_index, \
                configured_sample_rate_hz, CAST(channel_values AS CHAR) AS channel_values \
         FROM stream_samples \
         WHERE session_id = ? AND stream_name IN ('ppg', 'accel') AND id > ? \
         ORDER BY id LIMIT ?",
    )
    .bind(session_id)
    .bind(after_id)
    .bind(PAGE_ROWS)
    .fetch_all(db)
    .await?;
    rows.into_iter()
        .map(|row| {
            Ok(EvidenceRow {
                id: row.try_get("id")?,
                stream_name: row.try_get("stream_name")?,
                arrival_ms: row.try_get("arrival_ms")?,
                index: row.try_get("sample_index")?,
                nominal_rate_hz: row.try_get("configured_sample_rate_hz")?,
                channel_values: row.try_get("channel_values")?,
            })
        })
        .collect()
}

/// Flatten one sample's channel JSON into the columns of its stream.
pub fn channel_cells(channel_values: &str, columns: &[String]) -> Vec<String> {
    let parsed: Map<String, Value> = serde_json::from_str(channel_values).unwrap_or_default();
    columns
        .iter()
        .map(|column| match parsed.get(column) {
            Some(Value::Null) | None => String::new(),
            Some(Value::String(text)) => text.clone(),
            Some(other) => other.to_string(),
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn meta() -> SessionMeta {
        SessionMeta {
            session_id: 9,
            device_type: "OpenRing".into(),
            device_address: "AA".into(),
            owner_user_id: None,
            study_id: None,
        }
    }

    fn ppg_rows(seconds: i64) -> Vec<EvidenceRow> {
        (0..seconds * 25)
            .map(|i| {
                let t = i as f64 / 25.0;
                let phase = (t * 72.0 / 60.0).fract();
                let value = 5000.0 + 200.0 * (-((phase - 0.15) / 0.08).powi(2)).exp();
                EvidenceRow {
                    id: i + 1,
                    stream_name: "ppg".into(),
                    arrival_ms: 1_000_000 + i * 40,
                    index: Some(i),
                    nominal_rate_hz: Some(25.0),
                    channel_values: format!("{{\"green\": {value:.1}}}"),
                }
            })
            .collect()
    }

    #[test]
    fn a_replay_writes_a_live_row_every_five_seconds() {
        let mut replay = Replay::new(meta());
        let mut rows = Vec::new();
        let mut requests = 0;
        for evidence in ppg_rows(30) {
            for result in replay.push(&evidence, Some(4), true) {
                rows.extend(result.live_row);
                requests += result.model_requests.len();
            }
        }
        assert_eq!(rows.len(), 5, "ticks 5, 10, 15, 20, 25");
        assert!(rows.iter().all(|row| row.run_id == Some(4) && row.method_id == LIVE_METHOD_ID));
        let with_hr: Vec<f64> = rows.iter().filter_map(|row| row.hr_bpm).collect();
        assert!(!with_hr.is_empty());
        assert!(with_hr.iter().all(|bpm| (bpm - 72.0).abs() < 4.0), "{with_hr:?}");
        assert!(requests >= 3, "model windows every five seconds once warm: {requests}");
    }

    #[test]
    fn the_evidence_hash_depends_on_every_row() {
        let rows = ppg_rows(2);
        let digest = |rows: &[EvidenceRow]| {
            let mut hash = EvidenceHash::default();
            rows.iter().for_each(|row| hash.update(row));
            hash.finish()
        };
        let mut changed = rows.clone();
        changed[10].channel_values = "{\"green\": 1.0}".into();
        assert_eq!(digest(&rows), digest(&rows));
        assert_ne!(digest(&rows), digest(&changed));
    }

    #[test]
    fn channel_cells_follow_the_column_order() {
        let columns = vec!["x".to_string(), "y".to_string(), "z".to_string()];
        assert_eq!(channel_cells("{\"z\": 3, \"x\": 1.5}", &columns), vec!["1.5", "", "3"]);
    }

    #[test]
    fn a_model_row_covers_the_window_it_was_given() {
        let estimate = ModelEstimate {
            model_id: "papagei_s".into(),
            head_id: "hr_ring_embraceplus".into(),
            head_version: Some("2026-11-07".into()),
            validation_status: None,
            runtime: Some("onnxruntime-cpu".into()),
            channel: "green".into(),
            hr_bpm: 63.27,
            window_end_ms: 50_000,
            inference_ms: Some(2.0),
            live_sqi: 0.81234,
            live_hr_bpm: Some(64.0),
            movement: Some("still"),
        };
        let row = model_row(9, &estimate, None);
        assert_eq!(row.method_id, "papagei_s+hr_ring_embraceplus");
        assert_eq!(row.method_version, "2026-11-07");
        assert_eq!((row.window_start_ms, row.window_end_ms), (40_000, 50_000));
        assert_eq!(row.hr_bpm, Some(63.3));
    }
}
