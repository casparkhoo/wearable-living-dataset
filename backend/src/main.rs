use std::{
    collections::{HashMap, HashSet},
    env,
    net::SocketAddr,
    sync::Arc,
    time::Duration,
};

use anyhow::{anyhow, Context, Result};
use axum::{
    body::Body,
    extract::{Path, Query, State},
    http::{header, Method, Request, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, patch, post},
    Extension, Json, Router,
};
use rumqttc::{AsyncClient, Event, Incoming, MqttOptions, QoS};
use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};
use sqlx::{mysql::MySqlPoolOptions, MySqlPool, Row};
use tokio::sync::RwLock;
use tower_http::cors::CorsLayer;
use tracing::{error, info, warn};

mod auth;
mod derived;
mod erasure;
mod export;
mod live;
mod pending;
mod preprocessing;
mod studies;

use auth::{AuthConfig, AuthContext, AuthVia, JwksCache};

// Axum 0.7 uses `:parameter` syntax for dynamic path segments.
const SESSION_TIMING_ROUTE: &str = "/api/sessions/:session_id/timing";
const RECORDING_STOP_ROUTE: &str = "/api/recordings/:experiment_id/stop";
const EXPERIMENT_DETAILS_ROUTE: &str = "/api/experiments/:experiment_id/details";
const EXPERIMENT_START_ROUTE: &str = "/api/experiments/:experiment_id/start";
const EXPERIMENT_STOP_ROUTE: &str = "/api/experiments/:experiment_id/stop";
const EVIDENCE_SESSION_CREATE_ROUTE: &str = "/api/experiments/:experiment_id/evidence-sessions";
const EVIDENCE_SESSION_STOP_ROUTE: &str = "/api/evidence-sessions/:session_id/stop";
const ANNOTATION_CREATE_ROUTE: &str = "/api/experiments/:experiment_id/annotations";
const STUDY_DETAILS_ROUTE: &str = "/api/studies/:study_id";
const STUDY_MEMBERS_ROUTE: &str = "/api/studies/:study_id/members";
const STUDY_CONSENT_ROUTE: &str = "/api/studies/:study_id/consent";
const STUDY_WITHDRAW_ROUTE: &str = "/api/studies/:study_id/withdraw";
const PLATFORM_ROLE_ROUTE: &str = "/api/users/:user_id/platform-role";
const STUDY_MEMBER_ERASE_ROUTE: &str = "/api/studies/:study_id/members/:user_id/erase";
const STUDY_ERASURES_ROUTE: &str = "/api/studies/:study_id/erasures";
const ANNOTATION_STOP_ROUTE: &str = "/api/annotations/:annotation_id/stop";

#[derive(Clone, Debug)]
struct Config {
    backend_host: String,
    backend_port: u16,
    mqtt_broker_url: String,
    mqtt_topic_filter: String,
    mqtt_status_topic_prefix: String,
    mqtt_username: Option<String>,
    mqtt_password: Option<String>,
    mysql_url: String,
    mysql_database: String,
    ml_inference_enabled: bool,
    ml_inference_url: String,
    /// Head the model worker applies to live windows (a heart-rate regressor).
    /// Unset, the model is not run live.
    ml_live_head_id: Option<String>,
    /// Head id of the optional accelerometer activity pilot (LIVE_ACTIVITY_MODEL). None = off.
    live_activity_model: Option<String>,
    /// The MOMENT worker (ml-inference-server/moment_app.py), separate from the PaPaGei worker.
    live_activity_url: String,
    api_token: Option<String>,
    /// Secret that participant MQTT passwords are derived from. Set it and
    /// every participant gets their own broker credential; leave it unset and
    /// they keep sharing one account.
    mqtt_user_secret: Option<String>,
    auth: AuthConfig,
}

#[derive(Clone)]
struct AppState {
    db: MySqlPool,
    sessions: Arc<RwLock<HashMap<String, SessionHandle>>>,
    /// Active experiment per owner. Several participants may capture at the
    /// same time; each owner may still have only one experiment in flight.
    active_experiments: Arc<RwLock<HashMap<OwnerKey, i64>>>,
    mqtt_status_client: Arc<RwLock<Option<AsyncClient>>>,
    jwks: Arc<JwksCache>,
    /// Whether the identity/tenancy migration has been applied. When false the
    /// backend still runs, but records cannot be attributed to an owner.
    tenancy_enabled: bool,
    /// Rolling analysis of the streams currently being recorded.
    live: Arc<live::LiveEngine>,
    /// Whether the `live_events` table exists. Live analysis still runs
    /// without it; alerts are then only published, not recorded.
    live_events_enabled: bool,
    /// Whether `derived_windows` and `derived_runs` exist. Without them live
    /// results are only kept as summaries, and recompute is unavailable.
    derived_enabled: bool,
    /// Packets waiting for the ingest writer (run_ingest_writer).
    ingest_tx: tokio::sync::mpsc::Sender<IngestMessage>,
    /// Owner of each device recording, which never changes once created; cached
    /// so ingest does not query it for every packet.
    session_owners: Arc<RwLock<HashMap<i64, Option<i64>>>>,
    /// The recording that just ended on each device, so the packets a phone
    /// flushes right after stopping still reach it (see pending.rs).
    ended_sessions: Arc<std::sync::Mutex<pending::EndedSessions<SessionHandle>>>,
    /// Ingest counters served in /health.
    ingest_counters: Arc<pending::IngestCounters>,
    config: Config,
}

/// Who an experiment belongs to. `None` is the unattributed owner used before
/// authentication exists, and matches `experiments.owner_user_id IS NULL`.
type OwnerKey = Option<i64>;

impl AppState {
    async fn active_experiment_for(&self, owner: OwnerKey) -> Option<i64> {
        active_experiment_for(&*self.active_experiments.read().await, owner)
    }

    async fn owner_has_active_experiment(&self, owner: OwnerKey) -> bool {
        owner_has_active_experiment(&*self.active_experiments.read().await, owner)
    }

    async fn is_active_experiment_for(&self, owner: OwnerKey, experiment_id: i64) -> bool {
        is_active_experiment_for(&*self.active_experiments.read().await, owner, experiment_id)
    }

    async fn set_active_experiment(&self, owner: OwnerKey, experiment_id: i64) {
        self.active_experiments
            .write()
            .await
            .insert(owner, experiment_id);
    }

    async fn clear_active_experiment(&self, experiment_id: i64) {
        clear_active_experiment(&mut *self.active_experiments.write().await, experiment_id);
    }
}

// The ownership rules are kept as free functions over the map so they can be
// unit tested without a database pool. Each owner may hold at most one active
// experiment; different owners are independent, which is what allows several
// participants to capture at the same time.

fn active_experiment_for(active: &HashMap<OwnerKey, i64>, owner: OwnerKey) -> Option<i64> {
    active.get(&owner).copied()
}

fn owner_has_active_experiment(active: &HashMap<OwnerKey, i64>, owner: OwnerKey) -> bool {
    active.contains_key(&owner)
}

fn is_active_experiment_for(
    active: &HashMap<OwnerKey, i64>,
    owner: OwnerKey,
    experiment_id: i64,
) -> bool {
    active.get(&owner) == Some(&experiment_id)
}

fn clear_active_experiment(active: &mut HashMap<OwnerKey, i64>, experiment_id: i64) {
    active.retain(|_, id| *id != experiment_id);
}

#[derive(Clone, Debug)]
struct SessionHandle {
    device_id: i64,
    session_id: i64,
    measurement_config_id: Option<i64>,
}

#[derive(Debug, Deserialize, Serialize)]
struct SensorPacket {
    #[serde(rename = "schemaVersion")]
    schema_version: Option<i32>,
    device: String,
    address: String,
    #[serde(rename = "type")]
    packet_type: String,
    timestamp: Option<i64>,
    #[serde(rename = "serverTimestamp")]
    server_timestamp: Option<i64>,
    #[serde(rename = "sampleRateHz")]
    sample_rate_hz: Option<f64>,
    timing: Option<TimingMetadata>,
    data: Map<String, Value>,
}

#[derive(Debug, Deserialize, Serialize)]
struct TimingMetadata {
    #[serde(rename = "androidReceivedTimestampMs")]
    android_received_timestamp_ms: Option<i64>,
    #[serde(rename = "sourceTimestamp")]
    source_timestamp: Option<i64>,
    #[serde(rename = "sourceTimestampUnit")]
    source_timestamp_unit: Option<String>,
    #[serde(rename = "sequenceNumber")]
    sequence_number: Option<i64>,
    #[serde(rename = "sampleIndex")]
    sample_index: Option<i64>,
    #[serde(rename = "configuredSampleRateHz")]
    configured_sample_rate_hz: Option<f64>,
    #[serde(rename = "effectiveSampleRateHz")]
    effective_sample_rate_hz: Option<f64>,
    #[serde(rename = "clockSource")]
    clock_source: Option<String>,
    #[serde(rename = "clockQuality")]
    clock_quality: Option<String>,
}

#[derive(Debug, Serialize)]
struct HealthResponse {
    status: &'static str,
    database: String,
    mqtt_broker_url: String,
    mqtt_topic_filter: String,
    ml_inference_enabled: bool,
    ml_inference_url: String,
    /// The backend accepts a JSON array of packets in one MQTT message. A phone sends
    /// batches only after seeing this, so an older backend is never sent one.
    batch_ingest: bool,
    /// The backend also accepts a batch compressed with gzip. Over a relayed mobile
    /// link bandwidth, not message count, limited a ring plus two earbuds; their
    /// repetitive JSON compresses several times over.
    gzip_ingest: bool,
    /// Packet counts of the ingest path: accepted, held, replayed, dropped.
    ingest: Value,
}

#[derive(Debug, Deserialize)]
struct RecentSamplesQuery {
    stream_name: Option<String>,
    limit: Option<u32>,
}

#[derive(Debug, Deserialize)]
struct WindowSamplesQuery {
    start_ms: i64,
    end_ms: i64,
    stream_name: Option<String>,
    limit: Option<u32>,
}

#[derive(Debug, Deserialize)]
struct ExperimentDetailQuery {
    limit: Option<u32>,
}

#[derive(Debug, Deserialize)]
struct PostProcessingQuery {
    start_ms: i64,
    end_ms: i64,
    experiment_id: Option<i64>,
    device_recording_id: Option<i64>,
    stream_name: String,
    features: Option<String>,
    window_length_s: f64,
    stride_s: Option<f64>,
    target_sample_rate_hz: Option<f64>,
    limit_windows: Option<u32>,
}

#[derive(Debug, Deserialize)]
struct PostProcessingOptionsQuery {
    start_ms: i64,
    end_ms: i64,
    experiment_id: Option<i64>,
    device_recording_id: Option<i64>,
    stream_name: String,
}

#[derive(Debug, Deserialize)]
struct MlWindowQuery {
    start_ms: i64,
    end_ms: i64,
    channel: Option<String>,
    limit: Option<u32>,
}

#[derive(Debug, Serialize)]
struct MlInferRequest {
    ppg_data: Vec<f64>,
    sample_rate: f64,
}

#[derive(Debug, Deserialize)]
struct CreateExperimentRequest {
    experiment_label: String,
    protocol_name: Option<String>,
    protocol_version: Option<String>,
    operator_label: Option<String>,
    notes: Option<String>,
    start_immediately: Option<bool>,
    client_timestamp_ms: Option<i64>,
    study_id: Option<i64>,
}

#[derive(Debug, Deserialize)]
struct ControlTimestampRequest {
    client_timestamp_ms: Option<i64>,
}

#[derive(Debug, Deserialize)]
struct CreateEvidenceSessionRequest {
    device_type: String,
    device_address: String,
    session_label: Option<String>,
    operator_label: Option<String>,
    notes: Option<String>,
    client_timestamp_ms: Option<i64>,
}

#[derive(Debug, Deserialize)]
struct StartDeviceRecordingRequest {
    device_type: String,
    device_address: String,
    session_label: Option<String>,
}

#[derive(Debug, Deserialize)]
struct StartRecordingRequest {
    experiment_label: String,
    protocol_name: Option<String>,
    client_timestamp_ms: Option<i64>,
    /// Study this recording belongs to. Optional while the shared token is
    /// still accepted; an authenticated caller must be an active member.
    study_id: Option<i64>,
    device_recordings: Vec<StartDeviceRecordingRequest>,
}

#[derive(Debug, Deserialize)]
struct CreateAnnotationRequest {
    session_id: Option<i64>,
    label_name: String,
    label_value: String,
    started_at_ms: Option<i64>,
    ended_at_ms: Option<i64>,
    client_timestamp_ms: Option<i64>,
    client_monotonic_ns: Option<i64>,
    source: Option<String>,
    confidence: Option<f64>,
    timing_uncertainty_ms: Option<f64>,
    notes: Option<String>,
}

#[derive(Debug)]
struct ProcessingPoint {
    session_id: i64,
    device_id: i64,
    device_type: String,
    measurement_config_id: Option<i64>,
    sample_index: Option<i64>,
    timestamp_ms: i64,
    sample_rate_hz: f64,
    clock_source: Option<String>,
    clock_quality: Option<String>,
    channel_values: Map<String, Value>,
    channel_units: Value,
}

#[derive(Debug)]
struct ProcessingLabel {
    id: i64,
    target_session_id: i64,
    session_id: Option<i64>,
    label_name: String,
    label_value: String,
    started_at_ms: i64,
    ended_at_ms: Option<i64>,
}

#[derive(Debug, Serialize)]
struct RecentSample {
    id: i64,
    session_id: i64,
    device_id: i64,
    device_type: String,
    device_address: Option<String>,
    raw_packet_id: Option<i64>,
    stream_name: String,
    received_timestamp_ms: i64,
    sample_rate_hz: Option<f64>,
    channel_values: Value,
}

#[tokio::main]
async fn main() -> Result<()> {
    dotenvy::dotenv().ok();
    dotenvy::from_filename("backend/.env").ok();
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
        .init();

    let config = Config::from_env()?;
    let db = MySqlPoolOptions::new()
        .max_connections(8)
        .connect(&config.mysql_url)
        .await
        .context("connect to MySQL")?;
    // Research commands run against the same database as a one-off and exit.
    // They are dispatched before any startup housekeeping, because that would
    // touch the recordings the running server is handling.
    let args: Vec<String> = env::args().skip(1).collect();
    if !args.is_empty() {
        return run_command(&config, &db, &args).await;
    }
    let abandoned_sessions = abandon_stale_sessions(&db).await?;
    if abandoned_sessions > 0 {
        info!(
            abandoned_sessions,
            "marked stale device recordings as abandoned"
        );
    }
    let tenancy_enabled = has_column(&db, "experiments", "owner_user_id").await?;
    if config.auth.user_auth_enabled() && !has_table(&db, "users").await? {
        return Err(anyhow!(
            "user authentication is configured but the `users` table is missing; \
             apply backend/migrations/20260919_add_identity_and_studies.sql first"
        ));
    }
    if !tenancy_enabled {
        warn!(
            "identity/tenancy migration not applied; recordings will not be attributed to an owner"
        );
    }
    let derived_enabled = has_table(&db, "derived_windows").await? && has_table(&db, "derived_runs").await?;
    if !derived_enabled {
        warn!(
            "derived_windows table missing; per-window results and recompute are off \
             (apply backend/migrations/20261107_add_derived_windows.sql)"
        );
    }
    let live_events_enabled = has_table(&db, "live_events").await?;
    if !live_events_enabled {
        warn!(
            "live_events table missing; live alerts will be published but not recorded \
             (apply backend/migrations/20261005_add_live_events.sql)"
        );
    }
    let active_experiments = load_active_experiments(&db).await?;
    let active_sessions = load_active_sessions(&db).await?;
    let (ingest_tx, ingest_rx) = tokio::sync::mpsc::channel(INGEST_QUEUE);
    let state = AppState {
        db,
        sessions: Arc::new(RwLock::new(active_sessions)),
        active_experiments: Arc::new(RwLock::new(active_experiments)),
        mqtt_status_client: Arc::new(RwLock::new(None)),
        jwks: Arc::new(JwksCache::new()),
        tenancy_enabled,
        live: Arc::new(live::LiveEngine::new()),
        live_events_enabled,
        derived_enabled,
        ingest_tx,
        session_owners: Arc::new(RwLock::new(HashMap::new())),
        ended_sessions: Arc::new(std::sync::Mutex::new(pending::EndedSessions::new())),
        ingest_counters: Arc::new(pending::IngestCounters::default()),
        config: config.clone(),
    };

    let writer_state = state.clone();
    tokio::spawn(async move {
        run_ingest_writer(writer_state, ingest_rx).await;
        error!("ingest writer stopped");
    });

    let mqtt_state = state.clone();
    tokio::spawn(async move {
        // The ingester used to stop for good on its first error, which silently ended
        // all ingestion; it is restarted (and resubscribes) after a short pause.
        loop {
            if let Err(err) = run_mqtt_ingester(mqtt_state.clone()).await {
                error!(error = ?err, "mqtt ingester stopped; restarting in 3 s");
            }
            tokio::time::sleep(Duration::from_secs(3)).await;
        }
    });

    // Which heart rate participants see. Display only: the rule-based estimate is
    // still what is stored as live_ppg and rebuilt by recompute, and PaPaGei's is
    // stored as its own method either way.
    let prefer_model_hr = std::env::var("LIVE_HR_SOURCE")
        .map(|value| !value.trim().eq_ignore_ascii_case("rules"))
        .unwrap_or(true)
        && state.config.ml_inference_enabled;
    state.live.set_prefer_model_hr(prefer_model_hr);
    info!(source = if prefer_model_hr { "papagei" } else { "rules" }, "live heart rate shown to participants");

    let live_state = state.clone();
    tokio::spawn(async move {
        run_live_analysis(live_state).await;
    });

    if state.config.ml_inference_enabled {
        if let Some(head_id) = state.config.ml_live_head_id.clone() {
            let model_state = state.clone();
            tokio::spawn(async move {
                run_live_model(model_state, head_id).await;
            });
        }
    }

    // Optional pilot: an activity label from accelerometer windows. Off unless a head is named.
    if let Some(head_id) = state.config.live_activity_model.clone() {
        state.live.set_activity_enabled(true);
        let activity_state = state.clone();
        tokio::spawn(async move {
            run_live_activity(activity_state, head_id).await;
        });
    }

    let auth_state = state.clone();
    let app = Router::new()
        .route("/health", get(health))
        .route("/api/db/overview", get(db_overview))
        .route(SESSION_TIMING_ROUTE, get(session_timing))
        .route("/api/post-processing/query", get(post_process_query))
        .route("/api/post-processing/options", get(post_process_options))
        .route("/api/recordings", post(start_recording))
        .route(RECORDING_STOP_ROUTE, post(stop_experiment))
        .route(
            "/api/experiments",
            get(list_experiments).post(create_experiment),
        )
        .route(EXPERIMENT_DETAILS_ROUTE, get(experiment_details))
        .route("/api/experiments/active", get(active_experiment))
        .route(EXPERIMENT_START_ROUTE, post(start_experiment))
        .route(EXPERIMENT_STOP_ROUTE, post(stop_experiment))
        .route(EVIDENCE_SESSION_CREATE_ROUTE, post(create_evidence_session))
        .route(EVIDENCE_SESSION_STOP_ROUTE, post(stop_evidence_session))
        .route(ANNOTATION_CREATE_ROUTE, post(create_annotation))
        .route("/api/me", get(who_am_i))
        .route("/api/me/mqtt-credentials", get(my_mqtt_credentials))
        .route("/api/studies", get(get_studies).post(post_study))
        .route(
            STUDY_DETAILS_ROUTE,
            get(get_study_details).patch(patch_study),
        )
        .route(
            STUDY_MEMBERS_ROUTE,
            get(get_study_members).post(post_study_member),
        )
        .route(STUDY_CONSENT_ROUTE, post(post_study_consent))
        .route(STUDY_WITHDRAW_ROUTE, post(post_study_withdraw))
        .route(PLATFORM_ROLE_ROUTE, patch(patch_platform_role))
        .route(STUDY_MEMBER_ERASE_ROUTE, post(post_study_member_erase))
        .route(STUDY_ERASURES_ROUTE, get(get_study_erasures))
        .route(ANNOTATION_STOP_ROUTE, post(stop_annotation))
        .route("/api/ml/health", get(ml_health))
        .route("/api/ml/infer/window", get(ml_infer_window))
        .route("/api/samples/recent", get(recent_samples))
        .route("/api/samples/window", get(window_samples))
        .route("/api/live/summary", get(live_summary))
        .route("/api/evidence-sessions/:session_id/recompute", post(post_recompute))
        .route("/api/evidence-sessions/:session_id/derived", get(get_derived_windows))
        .route("/api/derived-runs/:run_id", get(get_derived_run))
        .route("/api/experiments/:experiment_id/export", get(get_experiment_export))
        // Order matters: the last layer added is the outermost, so CORS must
        // come after authentication. With it inside, a 401 short-circuits
        // before the CORS layer runs and the response carries no
        // Access-Control-Allow-Origin header -- a browser then reports an
        // opaque network error instead of the 401 the backend actually sent.
        .layer(middleware::from_fn_with_state(auth_state, authenticate))
        // Any origin may call the API (every route still needs a credential), but
        // `permissive()` answered preflights with "Allow-Headers: *", which
        // browsers do not extend to Authorization, so a console on another
        // origin (Vercel) could never send a sign-in token. Request headers are
        // mirrored instead. Private-network access is allowed too: a browser on
        // a computer that reaches this server at a private address (a Tailscale
        // or LAN IP) blocks a public page's requests to it without it.
        .layer(
            CorsLayer::new()
                .allow_origin(tower_http::cors::AllowOrigin::mirror_request())
                .allow_methods(tower_http::cors::AllowMethods::mirror_request())
                .allow_headers(tower_http::cors::AllowHeaders::mirror_request())
                .allow_private_network(true)
                .max_age(Duration::from_secs(600)),
        )
        .with_state(state);

    let addr: SocketAddr = format!("{}:{}", config.backend_host, config.backend_port)
        .parse()
        .context("parse backend address")?;

    let listener = tokio::net::TcpListener::bind(addr).await?;
    info!(%addr, "backend listening");
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await?;

    Ok(())
}

impl Config {
    fn from_env() -> Result<Self> {
        let mysql_host = env_or("MYSQL_HOST", "127.0.0.1");
        let mysql_port = env_or("MYSQL_PORT", "3306");
        let mysql_user = env_or("MYSQL_USER", "wearable_app");
        let mysql_password = env_or("MYSQL_PASSWORD", "change_me");
        let mysql_database = env_or("MYSQL_DATABASE", "wearable_living_dataset");
        let mysql_auth = if mysql_password.is_empty() {
            mysql_user
        } else {
            format!("{}:{}", mysql_user, mysql_password)
        };
        let mysql_url = format!(
            "mysql://{}@{}:{}/{}",
            mysql_auth, mysql_host, mysql_port, mysql_database
        );
        Ok(Self {
            backend_host: env_or("BACKEND_HOST", "0.0.0.0"),
            backend_port: env_or("BACKEND_PORT", "8080").parse()?,
            mqtt_broker_url: env_or("MQTT_BROKER_URL", "tcp://127.0.0.1:1883"),
            mqtt_topic_filter: env_or("MQTT_TOPIC_FILTER", "sensors/#"),
            mqtt_status_topic_prefix: env_or("MQTT_STATUS_TOPIC_PREFIX", "status"),
            mqtt_username: env::var("MQTT_USERNAME").ok().filter(|v| !v.is_empty()),
            mqtt_password: env::var("MQTT_PASSWORD").ok().filter(|v| !v.is_empty()),
            mysql_url,
            mysql_database,
            ml_inference_enabled: env_or("ML_INFERENCE_ENABLED", "false")
                .eq_ignore_ascii_case("true"),
            ml_inference_url: env_or("ML_INFERENCE_URL", "http://127.0.0.1:5000"),
            ml_live_head_id: env::var("ML_LIVE_HEAD_ID")
                .ok()
                .map(|value| value.trim().to_string())
                .filter(|value| !value.is_empty()),
            live_activity_model: activity_model_from(env::var("LIVE_ACTIVITY_MODEL").ok().as_deref()),
            live_activity_url: env_or("LIVE_ACTIVITY_URL", "http://127.0.0.1:5001"),
            mqtt_user_secret: env::var("MQTT_USER_SECRET")
                .ok()
                .filter(|value| !value.is_empty()),
            auth: AuthConfig::from_env(env_or),
            api_token: env::var("API_TOKEN")
                .ok()
                .map(|value| value.trim().to_string())
                .filter(|value| !value.is_empty()),
        })
    }
}

/// Map a study-layer error onto a status code.
///
/// The study functions return `anyhow` errors carrying a human-readable reason;
/// this keeps that reason in the response while giving clients a status they
/// can branch on.
fn study_error_response(err: &anyhow::Error) -> Response {
    let message = err.to_string();
    let status = if message.contains("requires a signed-in account") {
        StatusCode::UNAUTHORIZED
    } else if message.contains("not found") {
        StatusCode::NOT_FOUND
    } else if message.contains("cannot administer")
        || message.contains("do not have access")
        || message.contains("only a platform admin")
        || message.contains("can only withdraw")
        || message.contains("can create studies")
    {
        StatusCode::FORBIDDEN
    } else {
        StatusCode::BAD_REQUEST
    };
    (status, Json(json!({ "error": message }))).into_response()
}

fn study_result(result: Result<Value>, ok: StatusCode) -> Response {
    match result {
        Ok(value) => (ok, Json(value)).into_response(),
        Err(err) => study_error_response(&err),
    }
}

async fn who_am_i(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
) -> Response {
    study_result(studies::me(&state.db, &auth).await, StatusCode::OK)
}

/// A participant's own MQTT credential.
///
/// Derived rather than stored, so this can be re-issued at any time and the
/// broker's password file can be rebuilt from the users table without anyone
/// keeping plaintext passwords.
async fn my_mqtt_credentials(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
) -> Response {
    let Some(user_id) = auth.user_id else {
        return (
            StatusCode::UNAUTHORIZED,
            Json(json!({ "error": "sign in to get your own broker credentials" })),
        )
            .into_response();
    };
    let Some(secret) = state.config.mqtt_user_secret.as_deref() else {
        // Without the secret there are no per-participant accounts, so say so
        // rather than handing back a credential the broker will reject.
        return (
            StatusCode::OK,
            Json(json!({
                "per_user_credentials": false,
                "detail": "This deployment still shares one broker account.",
            })),
        )
            .into_response();
    };
    (
        StatusCode::OK,
        Json(json!({
            "per_user_credentials": true,
            "username": mqtt_username_for(user_id),
            "password": mqtt_password_for(secret, user_id),
            "topic_prefix": format!("sensors/{}", mqtt_username_for(user_id)),
            "status_topic": format!("status/{}", mqtt_username_for(user_id)),
        })),
    )
        .into_response()
}

async fn get_studies(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
) -> Response {
    study_result(studies::list_studies(&state.db, &auth).await, StatusCode::OK)
}

async fn post_study(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Json(request): Json<studies::CreateStudyRequest>,
) -> Response {
    study_result(
        studies::create_study(&state.db, &auth, request).await,
        StatusCode::CREATED,
    )
}

async fn get_study_details(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Path(study_id): Path<i64>,
) -> Response {
    study_result(
        studies::study_details(&state.db, &auth, study_id).await,
        StatusCode::OK,
    )
}

async fn patch_study(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Path(study_id): Path<i64>,
    Json(request): Json<studies::UpdateStudyRequest>,
) -> Response {
    study_result(
        studies::update_study(&state.db, &auth, study_id, request).await,
        StatusCode::OK,
    )
}

async fn get_study_members(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Path(study_id): Path<i64>,
) -> Response {
    study_result(
        studies::list_members(&state.db, &auth, study_id).await,
        StatusCode::OK,
    )
}

async fn post_study_member(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Path(study_id): Path<i64>,
    Json(request): Json<studies::AddMemberRequest>,
) -> Response {
    study_result(
        studies::add_member(&state.db, &auth, study_id, request).await,
        StatusCode::CREATED,
    )
}

async fn post_study_consent(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Path(study_id): Path<i64>,
    request: Option<Json<studies::ConsentRequest>>,
) -> Response {
    let request = request
        .map(|Json(value)| value)
        .unwrap_or(studies::ConsentRequest {
            consent_version: None,
        });
    study_result(
        studies::record_consent(&state.db, &auth, study_id, request).await,
        StatusCode::OK,
    )
}

async fn post_study_withdraw(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Path(study_id): Path<i64>,
    request: Option<Json<studies::WithdrawRequest>>,
) -> Response {
    let request = request
        .map(|Json(value)| value)
        .unwrap_or(studies::WithdrawRequest {
            user_id: None,
            reason: None,
        });
    study_result(
        studies::withdraw(&state.db, &auth, study_id, request).await,
        StatusCode::OK,
    )
}

/// Erase one member's data in a study. Study admins only; see `erasure.rs`.
async fn post_study_member_erase(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Path((study_id, user_id)): Path<(i64, i64)>,
    request: Option<Json<erasure::EraseRequest>>,
) -> Response {
    let request = request.map(|Json(value)| value).unwrap_or_default();
    study_result(
        erasure::erase_study_member(&state.db, &auth, study_id, user_id, request).await,
        StatusCode::OK,
    )
}

async fn get_study_erasures(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Path(study_id): Path<i64>,
) -> Response {
    study_result(
        erasure::study_erasure_log(&state.db, &auth, study_id).await,
        StatusCode::OK,
    )
}

async fn patch_platform_role(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Path(user_id): Path<i64>,
    Json(request): Json<studies::SetPlatformRoleRequest>,
) -> Response {
    study_result(
        studies::set_platform_role(&state.db, &auth, user_id, request.role.trim()).await,
        StatusCode::OK,
    )
}

fn unauthorized(detail: &str) -> Response {
    (
        StatusCode::UNAUTHORIZED,
        [(header::WWW_AUTHENTICATE, "Bearer")],
        Json(json!({ "error": "missing or invalid bearer token", "detail": detail })),
    )
        .into_response()
}

/// Resolve who a request acts as and attach it as an `AuthContext` extension.
///
/// Three credentials are accepted, in order of preference:
///
/// 1. An identity-provider JWT, which resolves to a specific user.
/// 2. The deployment-wide shared token, which resolves to the unattributed
///    owner. Kept during the transition, disabled with
///    `AUTH_ALLOW_SHARED_TOKEN=false`.
/// 3. Nothing at all, when neither is configured -- local development.
async fn authenticate(
    State(state): State<AppState>,
    mut request: Request<Body>,
    next: Next,
) -> Response {
    // Browsers send an unauthenticated CORS preflight before any request that
    // carries an Authorization header. The real request is still checked.
    if request.method() == Method::OPTIONS {
        request
            .extensions_mut()
            .insert(AuthContext::unattributed(AuthVia::Anonymous));
        return next.run(request).await;
    }

    let presented = request
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "))
        .map(str::to_owned);

    let user_auth_enabled = state.config.auth.user_auth_enabled();
    let shared_token = state.config.api_token.clone();

    let context = match presented {
        Some(token) if user_auth_enabled && auth::looks_like_jwt(&token) => {
            match auth::verify_token(&state.config.auth, &state.jwks, &token).await {
                Ok(claims) => match resolve_user(&state, &claims).await {
                    Ok(Some(context)) => context,
                    Ok(None) => {
                        return (
                            StatusCode::FORBIDDEN,
                            Json(json!({ "error": "this account is not active" })),
                        )
                            .into_response()
                    }
                    Err(err) => {
                        error!(error = ?err, "failed to resolve authenticated user");
                        return (
                            StatusCode::INTERNAL_SERVER_ERROR,
                            Json(json!({ "error": "failed to resolve account" })),
                        )
                            .into_response();
                    }
                },
                Err(err) => {
                    warn!(error = ?err, "rejected identity-provider token");
                    return unauthorized("token failed verification");
                }
            }
        }
        Some(token) => match shared_token.as_deref() {
            Some(expected) if state.config.auth.allow_shared_token && token == expected => {
                AuthContext::unattributed(AuthVia::SharedToken)
            }
            // A shared token that is configured but disabled, a wrong token,
            // or a JWT presented while user auth is switched off.
            Some(_) => return unauthorized("credential not accepted"),
            None if !user_auth_enabled => AuthContext::unattributed(AuthVia::Anonymous),
            None => return unauthorized("credential not accepted"),
        },
        None => {
            if shared_token.is_none() && !user_auth_enabled {
                AuthContext::unattributed(AuthVia::Anonymous)
            } else {
                return unauthorized("no bearer token presented");
            }
        }
    };

    request.extensions_mut().insert(context);
    next.run(request).await
}

/// Map a verified token subject onto a local `users` row, creating it on first
/// sign-in. Roles and membership come from our database, never from the token.
///
/// Returns `Ok(None)` when the account exists but is not active.
async fn resolve_user(state: &AppState, claims: &auth::Claims) -> Result<Option<AuthContext>> {
    const PROVIDER: &str = "supabase";

    for attempt in 0..2 {
        let existing = sqlx::query(
            "SELECT id, platform_role, status FROM users WHERE auth_provider = ? AND auth_subject = ?",
        )
        .bind(PROVIDER)
        .bind(&claims.sub)
        .fetch_optional(&state.db)
        .await?;

        if let Some(row) = existing {
            let status: String = row.try_get("status")?;
            if status != "active" {
                return Ok(None);
            }
            let user_id: i64 = row.try_get("id")?;
            let platform_role: String = row.try_get("platform_role")?;
            sqlx::query(
                "UPDATE users SET last_login_at = NOW(3), email = COALESCE(?, email) WHERE id = ?",
            )
            .bind(claims.email.as_deref())
            .bind(user_id)
            .execute(&state.db)
            .await?;
            return Ok(Some(AuthContext {
                user_id: Some(user_id),
                subject: Some(claims.sub.clone()),
                platform_role: Some(platform_role),
                via: AuthVia::Jwt,
            }));
        }

        // First sign-in. A concurrent request may win the race; the unique key
        // on (auth_provider, auth_subject) makes that safe, so retry the read.
        let inserted = sqlx::query(
            "INSERT INTO users (auth_provider, auth_subject, email, platform_role, status, last_login_at) VALUES (?, ?, ?, 'participant', 'active', NOW(3))",
        )
        .bind(PROVIDER)
        .bind(&claims.sub)
        .bind(claims.email.as_deref())
        .execute(&state.db)
        .await;

        match inserted {
            Ok(result) => {
                info!(subject = %claims.sub, "provisioned account on first sign-in");
                return Ok(Some(AuthContext {
                    user_id: Some(result.last_insert_id() as i64),
                    subject: Some(claims.sub.clone()),
                    platform_role: Some("participant".to_string()),
                    via: AuthVia::Jwt,
                }));
            }
            Err(err) if attempt == 0 => {
                warn!(error = ?err, "account insert raced; re-reading");
                continue;
            }
            Err(err) => return Err(err.into()),
        }
    }
    Err(anyhow!("could not resolve account for token subject"))
}

fn env_or(key: &str, default: &str) -> String {
    env::var(key).unwrap_or_else(|_| default.to_string())
}

async fn health(State(state): State<AppState>) -> Json<HealthResponse> {
    Json(HealthResponse {
        status: "ok",
        database: state.config.mysql_database.clone(),
        mqtt_broker_url: state.config.mqtt_broker_url.clone(),
        mqtt_topic_filter: state.config.mqtt_topic_filter.clone(),
        ml_inference_enabled: state.config.ml_inference_enabled,
        ml_inference_url: state.config.ml_inference_url.clone(),
        batch_ingest: true,
        gzip_ingest: true,
        ingest: state.ingest_counters.snapshot(),
    })
}

async fn recent_samples(
    State(state): State<AppState>,
    Query(query): Query<RecentSamplesQuery>,
) -> impl IntoResponse {
    let (samples_result, summary_result) = tokio::join!(
        load_recent_samples(&state.db, query),
        load_monitor_summary(&state.db)
    );
    match (samples_result, summary_result) {
        (Ok(samples), Ok(summary)) => (
            StatusCode::OK,
            Json(json!({ "samples": samples, "summary": summary })),
        )
            .into_response(),
        (Err(err), _) | (_, Err(err)) => {
            error!(error = ?err, "failed to load recent samples");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "error": "failed to load recent samples" })),
            )
                .into_response()
        }
    }
}

/// Live analysis of the recordings the caller may see: their own, and those
/// in studies they run. Platform admins see everything.
async fn live_summary(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
) -> Response {
    let snapshot = state.live.snapshot();
    let (sessions, recent_alerts) = match visible_scope(&state, &auth) {
        None => (snapshot.sessions, snapshot.recent_alerts),
        Some(user_id) => {
            let studies: HashSet<i64> = match sqlx::query_scalar::<_, i64>(
                "SELECT study_id FROM study_memberships \
                 WHERE user_id = ? AND status = 'active' AND role IN ('study_admin', 'researcher')",
            )
            .bind(user_id)
            .fetch_all(&state.db)
            .await
            {
                Ok(ids) => ids.into_iter().collect(),
                Err(err) => {
                    error!(error = ?err, "failed to load study roles for live summary");
                    return (
                        StatusCode::INTERNAL_SERVER_ERROR,
                        Json(json!({ "error": "failed to load live summary" })),
                    )
                        .into_response();
                }
            };
            let visible = |owner: Option<i64>, study: Option<i64>| {
                owner == Some(user_id) || study.is_some_and(|id| studies.contains(&id))
            };
            (
                snapshot
                    .sessions
                    .into_iter()
                    .filter(|summary| visible(summary.meta.owner_user_id, summary.meta.study_id))
                    .collect(),
                snapshot
                    .recent_alerts
                    .into_iter()
                    .filter(|alert| visible(alert.owner_user_id, alert.study_id))
                    .collect(),
            )
        }
    };
    (
        StatusCode::OK,
        Json(json!({
            "recipe_version": live::RECIPE_VERSION,
            "window_ms": live::WINDOW_MS,
            "generated_at_ms": now_ms(),
            "sessions": sessions,
            "recent_alerts": recent_alerts,
        })),
    )
        .into_response()
}

async fn window_samples(
    State(state): State<AppState>,
    Query(query): Query<WindowSamplesQuery>,
) -> impl IntoResponse {
    if query.end_ms < query.start_ms {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "error": "end_ms must be greater than or equal to start_ms" })),
        )
            .into_response();
    }

    match load_window_samples(&state.db, query).await {
        Ok(samples) => (StatusCode::OK, Json(json!({ "samples": samples }))).into_response(),
        Err(err) => {
            error!(error = ?err, "failed to load window samples");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "error": "failed to load window samples" })),
            )
                .into_response()
        }
    }
}

async fn post_process_query(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Query(query): Query<PostProcessingQuery>,
) -> impl IntoResponse {
    // Post-processing reads raw evidence, so it needs the same check as
    // viewing it. Without an experiment it would span everything visible, and
    // there is no safe default for that, so require one when scoped.
    if let Some(_) = visible_scope(&state, &auth) {
        let Some(experiment_id) = query.experiment_id else {
            return (
                StatusCode::BAD_REQUEST,
                Json(json!({ "error": "select an experiment to post-process" })),
            )
                .into_response();
        };
        match may_read_experiment(&state, &auth, experiment_id).await {
            Ok(false) => {
                return (
                    StatusCode::NOT_FOUND,
                    Json(json!({ "error": "experiment not found" })),
                )
                    .into_response()
            }
            Err(err) => {
                error!(error = ?err, "failed to check experiment access");
                return (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    Json(json!({ "error": "failed to check experiment access" })),
                )
                    .into_response();
            }
            Ok(true) => {}
        }
    }
    if query.end_ms <= query.start_ms {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "error": "end_ms must be after start_ms" })),
        )
            .into_response();
    }
    if query.stream_name.trim().is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "error": "stream_name is required" })),
        )
            .into_response();
    }
    if query.window_length_s <= 0.0
        || query.stride_s.unwrap_or(query.window_length_s) <= 0.0
        || query.target_sample_rate_hz.is_some_and(|rate| rate <= 0.0)
    {
        return (
            StatusCode::BAD_REQUEST,
            Json(
                json!({ "error": "window length, stride and target sample rate must be positive" }),
            ),
        )
            .into_response();
    }

    match build_post_processed_windows(&state.db, query).await {
        Ok(result) => (StatusCode::OK, Json(result)).into_response(),
        Err(err) => {
            error!(error = ?err, "post-processing query failed");
            (
                StatusCode::BAD_REQUEST,
                Json(json!({ "error": "post-processing query failed", "detail": err.to_string() })),
            )
                .into_response()
        }
    }
}

/// Lists numeric channel names actually present in the selected evidence scope.
/// This is intentionally query-time metadata: it never mutates canonical samples.
async fn post_process_options(
    State(state): State<AppState>,
    Query(query): Query<PostProcessingOptionsQuery>,
) -> impl IntoResponse {
    if query.end_ms <= query.start_ms || query.stream_name.trim().is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "error": "a valid time range and stream_name are required" })),
        )
            .into_response();
    }

    let result = async {
        let rows = sqlx::query(
            r#"
            SELECT ss.channel_values
            FROM stream_samples ss
            JOIN sessions s ON s.id = ss.session_id
            WHERE COALESCE(ss.android_received_timestamp_ms, ss.received_timestamp_ms) BETWEEN ? AND ?
              AND ss.stream_name = ?
              AND (? IS NULL OR s.experiment_id = ?)
              AND (? IS NULL OR ss.session_id = ?)
            LIMIT 200000
            "#,
        )
        .bind(query.start_ms)
        .bind(query.end_ms)
        .bind(query.stream_name.trim())
        .bind(query.experiment_id)
        .bind(query.experiment_id)
        .bind(query.device_recording_id)
        .bind(query.device_recording_id)
        .fetch_all(&state.db)
        .await?;

        let mut features = HashSet::new();
        for row in rows {
            let values: Value = row.try_get("channel_values")?;
            if let Some(object) = values.as_object() {
                for (name, value) in object {
                    if value.as_f64().is_some() {
                        features.insert(name.clone());
                    }
                }
            }
        }
        let mut features = features.into_iter().collect::<Vec<_>>();
        features.sort();
        Ok::<_, anyhow::Error>(json!({ "features": features }))
    }
    .await;

    match result {
        Ok(options) => (StatusCode::OK, Json(options)).into_response(),
        Err(err) => {
            error!(error = ?err, "failed to load post-processing options");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "error": "failed to load post-processing options" })),
            )
                .into_response()
        }
    }
}

async fn db_overview(State(state): State<AppState>) -> impl IntoResponse {
    match load_db_overview(&state.db).await {
        Ok(overview) => (StatusCode::OK, Json(overview)).into_response(),
        Err(err) => {
            error!(error = ?err, "failed to load db overview");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "error": "failed to load db overview" })),
            )
                .into_response()
        }
    }
}

async fn session_timing(
    State(state): State<AppState>,
    Path(session_id): Path<i64>,
) -> impl IntoResponse {
    match load_session_timing(&state.db, session_id).await {
        Ok(Some(report)) => (StatusCode::OK, Json(report)).into_response(),
        Ok(None) => (
            StatusCode::NOT_FOUND,
            Json(json!({ "error": "session not found or has no indexed PPG samples" })),
        )
            .into_response(),
        Err(err) => {
            error!(error = ?err, session_id, "failed to load timing diagnostics");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "error": "failed to load timing diagnostics" })),
            )
                .into_response()
        }
    }
}

async fn list_experiments(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
) -> impl IntoResponse {
    match load_experiments(&state, visible_scope(&state, &auth)).await {
        Ok(experiments) => {
            (StatusCode::OK, Json(json!({ "experiments": experiments }))).into_response()
        }
        Err(err) => {
            error!(error = ?err, "failed to list experiment sessions");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "error": "failed to list experiment sessions" })),
            )
                .into_response()
        }
    }
}

async fn experiment_details(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Path(experiment_id): Path<i64>,
    Query(query): Query<ExperimentDetailQuery>,
) -> impl IntoResponse {
    match may_read_experiment(&state, &auth, experiment_id).await {
        Ok(false) => {
            // Not found rather than forbidden: whether an experiment exists is
            // itself information about another participant.
            return (
                StatusCode::NOT_FOUND,
                Json(json!({ "error": "experiment not found" })),
            )
                .into_response();
        }
        Err(err) => {
            error!(error = ?err, experiment_id, "failed to check experiment access");
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "error": "failed to check experiment access" })),
            )
                .into_response();
        }
        Ok(true) => {}
    }
    let limit = query.limit.unwrap_or(100).clamp(1, 500);
    match load_experiment_details(&state.db, experiment_id, limit).await {
        Ok(Some(details)) => (StatusCode::OK, Json(details)).into_response(),
        Ok(None) => (
            StatusCode::NOT_FOUND,
            Json(json!({ "error": "experiment not found" })),
        )
            .into_response(),
        Err(err) => {
            error!(error = ?err, experiment_id, "failed to load experiment details");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "error": "failed to load experiment details" })),
            )
                .into_response()
        }
    }
}

async fn start_recording(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Json(request): Json<StartRecordingRequest>,
) -> impl IntoResponse {
    if request.experiment_label.trim().is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "error": "experiment_label is required" })),
        )
            .into_response();
    }
    if request.device_recordings.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "error": "at least one device recording is required" })),
        )
            .into_response();
    }
    if request.device_recordings.iter().any(|device| {
        device.device_type.trim().is_empty() || device.device_address.trim().is_empty()
    }) {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "error": "every device recording requires device_type and device_address" })),
        )
            .into_response();
    }
    let owner = auth.owner();
    if let Err(err) = check_study_membership(&state, owner, request.study_id).await {
        return (
            StatusCode::FORBIDDEN,
            Json(json!({ "error": "study access denied", "detail": err.to_string() })),
        )
            .into_response();
    }
    if state.owner_has_active_experiment(owner).await {
        return (
            StatusCode::CONFLICT,
            Json(json!({ "error": "you already have an active recording; stop it before starting another" })),
        )
            .into_response();
    }

    match start_recording_transaction(&state, owner, request).await {
        Ok(report) => {
            publish_control_status(
                &state,
                &format!(
                    "recordings/{}",
                    report.get("id").and_then(Value::as_i64).unwrap_or_default()
                ),
                "recording.started",
                report.clone(),
                true,
            )
            .await;
            (StatusCode::CREATED, Json(report)).into_response()
        }
        Err(err) => {
            error!(error = ?err, "failed to start recording");
            (
                StatusCode::BAD_REQUEST,
                Json(json!({ "error": "failed to start recording", "detail": err.to_string() })),
            )
                .into_response()
        }
    }
}

async fn create_experiment(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Json(request): Json<CreateExperimentRequest>,
) -> impl IntoResponse {
    if request.experiment_label.trim().is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "error": "experiment_label is required" })),
        )
            .into_response();
    }
    let start_immediately = request.start_immediately.unwrap_or(false);
    let owner = auth.owner();
    if let Err(err) = check_study_membership(&state, owner, request.study_id).await {
        return (
            StatusCode::FORBIDDEN,
            Json(json!({ "error": "study access denied", "detail": err.to_string() })),
        )
            .into_response();
    }
    if start_immediately && state.owner_has_active_experiment(owner).await {
        return (StatusCode::CONFLICT, Json(json!({ "error": "you already have an active experiment; stop it before starting another" }))).into_response();
    }
    match create_experiment_record(&state, owner, &request).await {
        Ok(experiment_id) => {
            if start_immediately {
                match start_experiment_capture(
                    &state,
                    owner,
                    experiment_id,
                    request.client_timestamp_ms,
                )
                .await
                {
                    Ok(experiment) => (StatusCode::CREATED, Json(experiment)).into_response(),
                    Err(err) => {
                        error!(error = ?err, experiment_id, "created experiment but failed to start it");
                        (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": "experiment was created but could not be started", "experiment_id": experiment_id, "detail": err.to_string() }))).into_response()
                    }
                }
            } else {
                let report = json!({ "id": experiment_id, "status": "planned" });
                publish_control_status(
                    &state,
                    &format!("experiments/{experiment_id}"),
                    "experiment.created",
                    report.clone(),
                    true,
                )
                .await;
                (StatusCode::CREATED, Json(report)).into_response()
            }
        }
        Err(err) => {
            error!(error = ?err, "failed to create experiment");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "error": "failed to create experiment", "detail": err.to_string() })),
            )
                .into_response()
        }
    }
}

async fn active_experiment(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
) -> impl IntoResponse {
    match state.active_experiment_for(auth.owner()).await {
        Some(id) => {
            let experiment =
                sqlx::query("SELECT experiment_label, started_at_ms FROM experiments WHERE id = ?")
                    .bind(id)
                    .fetch_optional(&state.db)
                    .await;
            let sessions = sqlx::query("SELECT id FROM sessions WHERE experiment_id = ? AND status = 'active' AND ended_at_ms IS NULL ORDER BY id")
                .bind(id).fetch_all(&state.db).await;
            let annotations = sqlx::query("SELECT id FROM annotations WHERE experiment_id = ? AND ended_at_ms IS NULL ORDER BY id")
                .bind(id).fetch_all(&state.db).await;
            match (experiment, sessions, annotations) {
                (Ok(Some(experiment)), Ok(sessions), Ok(annotations)) => (
                    StatusCode::OK,
                    Json(json!({
                        "active": true,
                        "experiment_id": id,
                        "experiment_label": experiment.try_get::<String, _>("experiment_label").unwrap_or_default(),
                        "started_at_ms": experiment.try_get::<Option<i64>, _>("started_at_ms").unwrap_or_default(),
                        "device_recording_ids": sessions.iter().filter_map(|row| row.try_get::<i64, _>("id").ok()).collect::<Vec<_>>(),
                        "evidence_session_ids": sessions.into_iter().filter_map(|row| row.try_get::<i64, _>("id").ok()).collect::<Vec<_>>(),
                        "active_annotation_ids": annotations.into_iter().filter_map(|row| row.try_get::<i64, _>("id").ok()).collect::<Vec<_>>()
                    })),
                ).into_response(),
                _ => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": "failed to load active control state" }))).into_response(),
            }
        }
        None => (StatusCode::OK, Json(json!({ "active": false }))).into_response(),
    }
}

async fn start_experiment(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Path(experiment_id): Path<i64>,
    request: Option<Json<ControlTimestampRequest>>,
) -> impl IntoResponse {
    let owner = auth.owner();
    if state.owner_has_active_experiment(owner).await {
        return (StatusCode::CONFLICT, Json(json!({ "error": "you already have an active experiment; stop it before starting another" }))).into_response();
    }
    let client_timestamp_ms = request.and_then(|Json(value)| value.client_timestamp_ms);
    match start_experiment_capture(&state, owner, experiment_id, client_timestamp_ms).await {
        Ok(report) => (StatusCode::OK, Json(report)).into_response(),
        Err(err) => {
            error!(error = ?err, experiment_id, "failed to start experiment");
            (
                StatusCode::BAD_REQUEST,
                Json(json!({ "error": "failed to start experiment", "detail": err.to_string() })),
            )
                .into_response()
        }
    }
}

async fn stop_experiment(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Path(experiment_id): Path<i64>,
    request: Option<Json<ControlTimestampRequest>>,
) -> impl IntoResponse {
    if !state
        .is_active_experiment_for(auth.owner(), experiment_id)
        .await
    {
        return (
            StatusCode::CONFLICT,
            Json(json!({ "error": "this experiment is not your active capture" })),
        )
            .into_response();
    }
    let client_timestamp_ms = request.and_then(|Json(value)| value.client_timestamp_ms);
    match stop_experiment_capture(&state, experiment_id, client_timestamp_ms).await {
        Ok(report) => (StatusCode::OK, Json(report)).into_response(),
        Err(err) => {
            error!(error = ?err, experiment_id, "failed to stop experiment session");
            (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": "failed to stop experiment session", "detail": err.to_string() }))).into_response()
        }
    }
}

async fn create_evidence_session(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Path(experiment_id): Path<i64>,
    Json(request): Json<CreateEvidenceSessionRequest>,
) -> impl IntoResponse {
    if request.device_type.trim().is_empty() || request.device_address.trim().is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "error": "device_type and device_address are required" })),
        )
            .into_response();
    }
    if !state
        .is_active_experiment_for(auth.owner(), experiment_id)
        .await
    {
        return (StatusCode::CONFLICT, Json(json!({ "error": "evidence sessions can only be started for your active experiment" }))).into_response();
    }
    match start_evidence_session(&state, experiment_id, request).await {
        Ok(report) => (StatusCode::CREATED, Json(report)).into_response(),
        Err(err) => {
            error!(error = ?err, experiment_id, "failed to start evidence session");
            (StatusCode::BAD_REQUEST, Json(json!({ "error": "failed to start evidence session", "detail": err.to_string() }))).into_response()
        }
    }
}

async fn stop_evidence_session(
    State(state): State<AppState>,
    Path(session_id): Path<i64>,
    request: Option<Json<ControlTimestampRequest>>,
) -> impl IntoResponse {
    let client_timestamp_ms = request.and_then(|Json(value)| value.client_timestamp_ms);
    match stop_evidence_session_capture(&state, session_id, client_timestamp_ms).await {
        Ok(report) => (StatusCode::OK, Json(report)).into_response(),
        Err(err) => {
            error!(error = ?err, session_id, "failed to stop evidence session");
            (StatusCode::BAD_REQUEST, Json(json!({ "error": "failed to stop evidence session", "detail": err.to_string() }))).into_response()
        }
    }
}

async fn create_annotation(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Path(experiment_id): Path<i64>,
    Json(request): Json<CreateAnnotationRequest>,
) -> impl IntoResponse {
    if request.label_name.trim().is_empty() || request.label_value.trim().is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "error": "label_name and label_value are required" })),
        )
            .into_response();
    }
    if !state
        .is_active_experiment_for(auth.owner(), experiment_id)
        .await
    {
        return (
            StatusCode::CONFLICT,
            Json(json!({ "error": "annotations can only be created for your active experiment" })),
        )
            .into_response();
    }
    match insert_annotation(&state, experiment_id, request).await {
        Ok(report) => (StatusCode::CREATED, Json(report)).into_response(),
        Err(err) => {
            error!(error = ?err, experiment_id, "failed to create annotation");
            (
                StatusCode::BAD_REQUEST,
                Json(json!({ "error": "failed to create annotation", "detail": err.to_string() })),
            )
                .into_response()
        }
    }
}

async fn stop_annotation(
    State(state): State<AppState>,
    Path(annotation_id): Path<i64>,
    request: Option<Json<ControlTimestampRequest>>,
) -> impl IntoResponse {
    let client_timestamp_ms = request.and_then(|Json(value)| value.client_timestamp_ms);
    match end_annotation(&state, annotation_id, client_timestamp_ms).await {
        Ok(report) => (StatusCode::OK, Json(report)).into_response(),
        Err(err) => {
            error!(error = ?err, annotation_id, "failed to stop annotation");
            (
                StatusCode::BAD_REQUEST,
                Json(json!({ "error": "failed to stop annotation", "detail": err.to_string() })),
            )
                .into_response()
        }
    }
}

async fn ml_health(State(state): State<AppState>) -> impl IntoResponse {
    if !state.config.ml_inference_enabled {
        return (
            StatusCode::OK,
            Json(json!({
                "enabled": false,
                "status": "disabled",
                "ml_inference_url": state.config.ml_inference_url
            })),
        )
            .into_response();
    }

    let url = format!(
        "{}/health",
        state.config.ml_inference_url.trim_end_matches('/')
    );
    match reqwest::get(&url).await {
        Ok(resp) => {
            let status = resp.status().as_u16();
            let body = resp.json::<Value>().await.unwrap_or_else(|_| json!({}));
            (
                StatusCode::OK,
                Json(json!({
                    "enabled": true,
                    "status": status,
                    "ml_inference_url": state.config.ml_inference_url,
                    "server": body
                })),
            )
                .into_response()
        }
        Err(err) => (
            StatusCode::BAD_GATEWAY,
            Json(json!({
                "enabled": true,
                "status": "unreachable",
                "ml_inference_url": state.config.ml_inference_url,
                "error": err.to_string()
            })),
        )
            .into_response(),
    }
}

async fn ml_infer_window(
    State(state): State<AppState>,
    Query(query): Query<MlWindowQuery>,
) -> impl IntoResponse {
    if !state.config.ml_inference_enabled {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "error": "ML inference is disabled in backend config" })),
        )
            .into_response();
    }
    if query.end_ms < query.start_ms {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "error": "end_ms must be greater than or equal to start_ms" })),
        )
            .into_response();
    }

    match load_ppg_window(&state.db, &query).await {
        Ok((ppg_data, sample_rate)) => {
            if ppg_data.is_empty() {
                return (
                    StatusCode::BAD_REQUEST,
                    Json(json!({ "error": "No PPG samples found for this window" })),
                )
                    .into_response();
            }

            let url = format!(
                "{}/infer",
                state.config.ml_inference_url.trim_end_matches('/')
            );
            let request = MlInferRequest {
                ppg_data,
                sample_rate,
            };

            match reqwest::Client::new().post(url).json(&request).send().await {
                Ok(resp) => {
                    let status = resp.status();
                    let body = resp.json::<Value>().await.unwrap_or_else(|_| json!({}));
                    (
                        StatusCode::from_u16(status.as_u16()).unwrap_or(StatusCode::BAD_GATEWAY),
                        Json(json!({
                            "window": {
                                "start_ms": query.start_ms,
                                "end_ms": query.end_ms,
                                "channel": query.channel.unwrap_or_else(|| "green".to_string()),
                                "sample_count": request.ppg_data.len(),
                                "sample_rate": request.sample_rate
                            },
                            "result": body
                        })),
                    )
                        .into_response()
                }
                Err(err) => (
                    StatusCode::BAD_GATEWAY,
                    Json(json!({ "error": err.to_string() })),
                )
                    .into_response(),
            }
        }
        Err(err) => {
            error!(error = ?err, "failed to load ML PPG window");
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({
                    "error": "failed to load PPG window",
                    "detail": err.to_string()
                })),
            )
                .into_response()
        }
    }
}

/// The newest samples are the highest ids. No index starts with the timestamp,
/// so ordering by it sorted the whole table (JSON included) on every request;
/// the console asks once a second, which saturated the small VM's database.
async fn load_recent_samples(
    db: &MySqlPool,
    query: RecentSamplesQuery,
) -> Result<Vec<RecentSample>> {
    let limit = query.limit.unwrap_or(100).clamp(1, 1000);

    let rows = if let Some(stream_name) = query.stream_name {
        sqlx::query(
            r#"
            SELECT ss.id, ss.session_id, ss.device_id, d.device_type, d.device_address,
                   ss.raw_packet_id, ss.stream_name, ss.received_timestamp_ms,
                   ss.sample_rate_hz, ss.channel_values
            FROM stream_samples ss
            JOIN devices d ON d.id = ss.device_id
            WHERE ss.stream_name = ?
            ORDER BY ss.id DESC
            LIMIT ?
            "#,
        )
        .bind(stream_name)
        .bind(limit)
        .fetch_all(db)
        .await?
    } else {
        sqlx::query(
            r#"
            SELECT ss.id, ss.session_id, ss.device_id, d.device_type, d.device_address,
                   ss.raw_packet_id, ss.stream_name, ss.received_timestamp_ms,
                   ss.sample_rate_hz, ss.channel_values
            FROM stream_samples ss
            JOIN devices d ON d.id = ss.device_id
            ORDER BY ss.id DESC
            LIMIT ?
            "#,
        )
        .bind(limit)
        .fetch_all(db)
        .await?
    };

    rows.into_iter()
        .map(|row| {
            let channel_values: Value = row.try_get("channel_values")?;
            Ok(RecentSample {
                id: row.try_get("id")?,
                session_id: row.try_get("session_id")?,
                device_id: row.try_get("device_id")?,
                device_type: row.try_get("device_type")?,
                device_address: row.try_get("device_address")?,
                raw_packet_id: row.try_get("raw_packet_id")?,
                stream_name: row.try_get("stream_name")?,
                received_timestamp_ms: row.try_get("received_timestamp_ms")?,
                sample_rate_hz: row.try_get("sample_rate_hz")?,
                channel_values,
            })
        })
        .collect()
}

/// Totals are intentionally separate from the bounded recent-sample preview.
/// A high-rate stream must not make the monitor's headline counters appear capped.
async fn load_monitor_summary(db: &MySqlPool) -> Result<Value> {
    // Counting every stored sample scans the table, and the console asks every
    // second. A figure up to half a minute old serves the headline equally well.
    static CACHE: std::sync::OnceLock<std::sync::Mutex<Option<(std::time::Instant, Value)>>> =
        std::sync::OnceLock::new();
    let cache = CACHE.get_or_init(|| std::sync::Mutex::new(None));
    if let Some((at, value)) = cache.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {
        if at.elapsed() < Duration::from_secs(30) {
            return Ok(value.clone());
        }
    }
    let row = sqlx::query(
        r#"
        SELECT COUNT(*) AS stored_samples,
               COUNT(DISTINCT device_id) AS known_devices
        FROM stream_samples
        "#,
    )
    .fetch_one(db)
    .await?;
    let value = json!({
        "stored_samples": row.try_get::<i64, _>("stored_samples")?,
        "known_devices": row.try_get::<i64, _>("known_devices")?
    });
    *cache.lock().unwrap_or_else(|e| e.into_inner()) = Some((std::time::Instant::now(), value.clone()));
    Ok(value)
}

async fn load_window_samples(
    db: &MySqlPool,
    query: WindowSamplesQuery,
) -> Result<Vec<RecentSample>> {
    let limit = query.limit.unwrap_or(1000).clamp(1, 10000);

    let rows = if let Some(stream_name) = query.stream_name {
        sqlx::query(
            r#"
            SELECT ss.id, ss.session_id, ss.device_id, d.device_type, d.device_address,
                   ss.raw_packet_id, ss.stream_name, ss.received_timestamp_ms,
                   ss.sample_rate_hz, ss.channel_values
            FROM stream_samples ss
            JOIN devices d ON d.id = ss.device_id
            WHERE ss.received_timestamp_ms BETWEEN ? AND ?
              AND ss.stream_name = ?
            ORDER BY ss.received_timestamp_ms ASC, ss.id ASC
            LIMIT ?
            "#,
        )
        .bind(query.start_ms)
        .bind(query.end_ms)
        .bind(stream_name)
        .bind(limit)
        .fetch_all(db)
        .await?
    } else {
        sqlx::query(
            r#"
            SELECT ss.id, ss.session_id, ss.device_id, d.device_type, d.device_address,
                   ss.raw_packet_id, ss.stream_name, ss.received_timestamp_ms,
                   ss.sample_rate_hz, ss.channel_values
            FROM stream_samples ss
            JOIN devices d ON d.id = ss.device_id
            WHERE ss.received_timestamp_ms BETWEEN ? AND ?
            ORDER BY ss.received_timestamp_ms ASC, ss.id ASC
            LIMIT ?
            "#,
        )
        .bind(query.start_ms)
        .bind(query.end_ms)
        .bind(limit)
        .fetch_all(db)
        .await?
    };

    rows.into_iter().map(row_to_recent_sample).collect()
}

async fn build_post_processed_windows(db: &MySqlPool, query: PostProcessingQuery) -> Result<Value> {
    let rows = sqlx::query(
        r#"
        SELECT ss.session_id, ss.device_id, d.device_type, ss.measurement_config_id,
               ss.sample_index,
               COALESCE(ss.android_received_timestamp_ms, ss.received_timestamp_ms) AS timeline_timestamp_ms,
               COALESCE(ss.effective_sample_rate_hz, ss.configured_sample_rate_hz, ss.sample_rate_hz) AS sample_rate_hz,
               ss.clock_source, ss.clock_quality, ss.channel_values, ss.channel_units
        FROM stream_samples ss
        JOIN sessions s ON s.id = ss.session_id
        JOIN devices d ON d.id = ss.device_id
        WHERE COALESCE(ss.android_received_timestamp_ms, ss.received_timestamp_ms) BETWEEN ? AND ?
          AND ss.stream_name = ?
          AND (? IS NULL OR s.experiment_id = ?)
          AND (? IS NULL OR ss.session_id = ?)
        ORDER BY ss.session_id,
                 CASE WHEN ss.sample_index IS NULL THEN 1 ELSE 0 END,
                 ss.sample_index,
                 timeline_timestamp_ms,
                 ss.id
        LIMIT 200000
        "#,
    )
    .bind(query.start_ms)
    .bind(query.end_ms)
    .bind(query.stream_name.trim())
    .bind(query.experiment_id)
    .bind(query.experiment_id)
    .bind(query.device_recording_id)
    .bind(query.device_recording_id)
    .fetch_all(db)
    .await?;

    let mut points = Vec::with_capacity(rows.len());
    for row in rows {
        let rate = row
            .try_get::<Option<f64>, _>("sample_rate_hz")?
            .unwrap_or(0.0);
        if rate <= 0.0 {
            continue;
        }
        let values: Value = row.try_get("channel_values")?;
        let Some(channel_values) = values.as_object().cloned() else {
            continue;
        };
        points.push(ProcessingPoint {
            session_id: row.try_get("session_id")?,
            device_id: row.try_get("device_id")?,
            device_type: row.try_get("device_type")?,
            measurement_config_id: row.try_get("measurement_config_id")?,
            sample_index: row.try_get("sample_index")?,
            timestamp_ms: row.try_get("timeline_timestamp_ms")?,
            sample_rate_hz: rate,
            clock_source: row.try_get("clock_source")?,
            clock_quality: row.try_get("clock_quality")?,
            channel_values,
            channel_units: row
                .try_get::<Option<Value>, _>("channel_units")?
                .unwrap_or(Value::Null),
        });
    }
    if points.is_empty() {
        return Err(anyhow!(
            "no native samples matched the requested evidence range"
        ));
    }

    let label_rows = sqlx::query(
        r#"
        SELECT a.id, s.id AS target_session_id, a.session_id, a.label_name,
               a.label_value, a.started_at_ms, a.ended_at_ms
        FROM sessions s
        JOIN annotations a
          ON a.experiment_id = s.experiment_id
         AND (a.session_id IS NULL OR a.session_id = s.id)
        WHERE a.started_at_ms <= ?
          AND COALESCE(a.ended_at_ms, ?) >= ?
          AND (? IS NULL OR s.experiment_id = ?)
          AND (? IS NULL OR s.id = ?)
        ORDER BY a.started_at_ms, a.id
        "#,
    )
    .bind(query.end_ms)
    .bind(query.end_ms)
    .bind(query.start_ms)
    .bind(query.experiment_id)
    .bind(query.experiment_id)
    .bind(query.device_recording_id)
    .bind(query.device_recording_id)
    .fetch_all(db)
    .await?;
    let processing_labels = label_rows
        .into_iter()
        .map(|row| {
            Ok(ProcessingLabel {
                id: row.try_get("id")?,
                target_session_id: row.try_get("target_session_id")?,
                session_id: row.try_get("session_id")?,
                label_name: row.try_get("label_name")?,
                label_value: row.try_get("label_value")?,
                started_at_ms: row.try_get("started_at_ms")?,
                ended_at_ms: row.try_get("ended_at_ms")?,
            })
        })
        .collect::<Result<Vec<_>>>()?;

    let mut features = query
        .features
        .as_deref()
        .unwrap_or_default()
        .split(',')
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_string)
        .collect::<Vec<_>>();
    if features.is_empty() {
        features = points[0]
            .channel_values
            .iter()
            .filter_map(|(name, value)| value.as_f64().map(|_| name.clone()))
            .collect();
        features.sort();
    }
    if features.is_empty() {
        return Err(anyhow!(
            "no numeric features were available for post-processing"
        ));
    }

    let mut segments: Vec<Vec<ProcessingPoint>> = Vec::new();
    let mut current = Vec::new();
    for point in points {
        let boundary = current
            .last()
            .map(|previous| processing_boundary(previous, &point))
            .unwrap_or(false);
        if boundary {
            segments.push(std::mem::take(&mut current));
        }
        current.push(point);
    }
    if !current.is_empty() {
        segments.push(current);
    }

    let stride_s = query.stride_s.unwrap_or(query.window_length_s);
    let limit_windows = query.limit_windows.unwrap_or(100).clamp(1, 1000) as usize;
    let mut windows = Vec::new();
    let mut window_lineage = Vec::new();
    let mut rejected_missing_features = 0usize;
    let mut rejected_label_coverage = 0usize;
    let mut rejected_ambiguous_labels = 0usize;

    'segments: for (segment_index, segment) in segments.iter().enumerate() {
        let source_rate = segment[0].sample_rate_hz;
        let source_count = (source_rate * query.window_length_s).round() as usize;
        let stride_count = (source_rate * stride_s).round() as usize;
        if source_count == 0 || stride_count == 0 || segment.len() < source_count {
            continue;
        }

        for start_offset in (0..=segment.len() - source_count).step_by(stride_count) {
            let source = &segment[start_offset..start_offset + source_count];
            if source.iter().any(|point| {
                features.iter().any(|feature| {
                    point
                        .channel_values
                        .get(feature)
                        .and_then(Value::as_f64)
                        .is_none()
                })
            }) {
                rejected_missing_features += 1;
                continue;
            }

            let source_start_ms = source[0].timestamp_ms;
            let source_end_ms = source[source.len() - 1].timestamp_ms;
            let fully_covering_labels = processing_labels
                .iter()
                .filter(|label| {
                    label_fully_covers_window(
                        label,
                        source[0].session_id,
                        source_start_ms,
                        source_end_ms,
                    )
                })
                .collect::<Vec<_>>();
            if fully_covering_labels.is_empty() {
                rejected_label_coverage += 1;
                continue;
            }
            let distinct_labels = fully_covering_labels
                .iter()
                .map(|label| (label.label_name.as_str(), label.label_value.as_str()))
                .collect::<HashSet<_>>();
            if distinct_labels.len() != 1 {
                rejected_ambiguous_labels += 1;
                continue;
            }

            let activity_labels = fully_covering_labels
                .iter()
                .map(|label| {
                    json!({
                        "id": label.id,
                        "scope": if label.session_id.is_some() { "device_recording" } else { "experiment" },
                        "label_name": label.label_name,
                        "label_value": label.label_value,
                        "started_at_ms": label.started_at_ms,
                        "ended_at_ms": label.ended_at_ms,
                        "coverage_fraction": 1.0
                    })
                })
                .collect::<Vec<_>>();
            let feature_units = features
                .iter()
                .filter_map(|feature| {
                    source[0]
                        .channel_units
                        .get(feature)
                        .map(|unit| (feature.clone(), unit.clone()))
                })
                .collect::<Map<String, Value>>();
            let target_rate = query.target_sample_rate_hz.unwrap_or(source_rate);
            let target_count = (target_rate * query.window_length_s).round() as usize;
            let mut output_rows = Vec::with_capacity(target_count);
            for target_index in 0..target_count {
                let source_position = target_index as f64 * source_rate / target_rate;
                let lower = source_position.floor() as usize;
                let upper = source_position.ceil() as usize;
                let lower = lower.min(source.len() - 1);
                let upper = upper.min(source.len() - 1);
                let fraction = source_position - source_position.floor();
                let mut values = Map::new();
                for feature in &features {
                    let lower_value = source[lower].channel_values[feature]
                        .as_f64()
                        .unwrap_or_default();
                    let upper_value = source[upper].channel_values[feature]
                        .as_f64()
                        .unwrap_or_default();
                    values.insert(
                        feature.clone(),
                        json!(lower_value + (upper_value - lower_value) * fraction),
                    );
                }
                output_rows.push(json!({
                    "sample_offset": target_index,
                    "relative_time_s": target_index as f64 / target_rate,
                    "timestamp_ms": source[0].timestamp_ms + ((target_index as f64 / target_rate) * 1000.0).round() as i64,
                    "values": values
                }));
            }

            let window_index = windows.len();
            window_lineage.push(json!({
                "window_index": window_index,
                "device_recording_id": source[0].session_id,
                "measurement_config_id": source[0].measurement_config_id,
                "source_start_ms": source_start_ms,
                "source_end_ms": source_end_ms,
                "source_start_sample_index": source[0].sample_index,
                "source_end_sample_index": source[source.len() - 1].sample_index,
                "source_sample_rate_hz": source_rate,
                "source_sample_count": source_count,
                "target_sample_rate_hz": target_rate,
                "target_sample_count": target_count,
                "clock_source": source[0].clock_source,
                "clock_quality": source[0].clock_quality,
                "feature_units": feature_units.clone(),
                "activity_labels": activity_labels.clone()
            }));
            windows.push(json!({
                "window_index": window_index,
                "segment_index": segment_index,
                "device_recording_id": source[0].session_id,
                "device_id": source[0].device_id,
                "device_type": &source[0].device_type,
                "measurement_config_id": source[0].measurement_config_id,
                "source_start_ms": source_start_ms,
                "source_end_ms": source_end_ms,
                "source_start_sample_index": source[0].sample_index,
                "source_end_sample_index": source[source.len() - 1].sample_index,
                "source_sample_rate_hz": source_rate,
                "source_sample_count": source_count,
                "target_sample_rate_hz": target_rate,
                "target_sample_count": target_count,
                "clock_source": source[0].clock_source,
                "clock_quality": source[0].clock_quality,
                "feature_units": feature_units,
                "activity_labels": activity_labels,
                "rows": output_rows
            }));
            if windows.len() >= limit_windows {
                break 'segments;
            }
        }
    }

    Ok(json!({
        "manifest": {
            "manifest_version": "1.1",
            "created_at_ms": now_ms(),
            "persisted": false,
            "source": "stream_samples_native_rate",
            "pipeline_id": "living_dataset_window_export",
            "pipeline_version": env!("CARGO_PKG_VERSION"),
            "experiment_id": query.experiment_id,
            "device_recording_id": query.device_recording_id,
            "start_ms": query.start_ms,
            "end_ms": query.end_ms,
            "stream_name": query.stream_name,
            "features": features,
            "window_length_s": query.window_length_s,
            "stride_s": stride_s,
            "limit_windows": limit_windows,
            "target_sample_rate_hz": query.target_sample_rate_hz,
            "selection_and_label_time_basis": "android_received_timestamp_ms_with_backend_received_fallback",
            "signal_position_basis": "consecutive_sample_index_and_declared_source_rate_when_available",
            "continuity_policy": "split_on_device_recording_change_sample_index_gap_reset_rate_change_or_large_time_gap",
            "label_policy": {
                "required_coverage_fraction": 1.0,
                "requires_one_unambiguous_label_value": true,
                "open_or_partial_labels_are_rejected": true
            },
            "resampling_method": "linear_interpolation",
            "window_lineage": window_lineage
        },
        "summary": {
            "segments_detected": segments.len(),
            "windows_returned": windows.len(),
            "windows_rejected_missing_features": rejected_missing_features,
            "windows_rejected_label_coverage": rejected_label_coverage,
            "windows_rejected_ambiguous_labels": rejected_ambiguous_labels
        },
        "windows": windows
    }))
}

fn label_fully_covers_window(
    label: &ProcessingLabel,
    session_id: i64,
    window_start_ms: i64,
    window_end_ms: i64,
) -> bool {
    label.target_session_id == session_id
        && label.started_at_ms <= window_start_ms
        && label
            .ended_at_ms
            .is_some_and(|ended_at_ms| ended_at_ms >= window_end_ms)
}

fn processing_boundary(previous: &ProcessingPoint, current: &ProcessingPoint) -> bool {
    if previous.session_id != current.session_id
        || previous.measurement_config_id != current.measurement_config_id
        || (previous.sample_rate_hz - current.sample_rate_hz).abs() > f64::EPSILON
    {
        return true;
    }
    if let (Some(previous_index), Some(current_index)) =
        (previous.sample_index, current.sample_index)
    {
        // A validated consecutive index is stronger continuity evidence than bursty
        // BLE/MQTT callback spacing. Arrival time remains an audit/label axis, not
        // the waveform cadence used to split indexed evidence.
        return current_index != previous_index + 1;
    }
    if previous.sample_index.is_some() != current.sample_index.is_some() {
        return true;
    }
    let expected_interval_ms = 1000.0 / current.sample_rate_hz;
    current.timestamp_ms <= previous.timestamp_ms
        || (current.timestamp_ms - previous.timestamp_ms) as f64 > expected_interval_ms * 2.5
}

async fn load_ppg_window(db: &MySqlPool, query: &MlWindowQuery) -> Result<(Vec<f64>, f64)> {
    let limit = query.limit.unwrap_or(1250).clamp(1, 10000);
    let channel = query.channel.as_deref().unwrap_or("green");

    let rows = sqlx::query(
        r#"
        SELECT channel_values, sample_rate_hz
        FROM stream_samples
        WHERE stream_name = 'ppg'
          AND received_timestamp_ms BETWEEN ? AND ?
        ORDER BY received_timestamp_ms ASC, id ASC
        LIMIT ?
        "#,
    )
    .bind(query.start_ms)
    .bind(query.end_ms)
    .bind(limit)
    .fetch_all(db)
    .await?;

    let mut samples = Vec::with_capacity(rows.len());
    let mut sample_rate = 25.0;
    for row in rows {
        let values: Value = row.try_get("channel_values")?;
        if let Some(value) = values.get(channel).and_then(Value::as_f64) {
            samples.push(value);
        }
        if let Some(rate) = row.try_get::<Option<f64>, _>("sample_rate_hz")? {
            sample_rate = rate;
        }
    }

    Ok((samples, sample_rate))
}

fn row_to_recent_sample(row: sqlx::mysql::MySqlRow) -> Result<RecentSample> {
    let channel_values: Value = row.try_get("channel_values")?;
    Ok(RecentSample {
        id: row.try_get("id")?,
        session_id: row.try_get("session_id")?,
        device_id: row.try_get("device_id")?,
        device_type: row.try_get("device_type")?,
        device_address: row.try_get("device_address")?,
        raw_packet_id: row.try_get("raw_packet_id")?,
        stream_name: row.try_get("stream_name")?,
        received_timestamp_ms: row.try_get("received_timestamp_ms")?,
        sample_rate_hz: row.try_get("sample_rate_hz")?,
        channel_values,
    })
}

async fn load_db_overview(db: &MySqlPool) -> Result<Value> {
    let table_counts = sqlx::query(
        r#"
        SELECT 'devices' AS table_name, COUNT(*) AS rows_count FROM devices
        UNION ALL SELECT 'experiments', COUNT(*) FROM experiments
        UNION ALL SELECT 'sessions', COUNT(*) FROM sessions
        UNION ALL SELECT 'annotations', COUNT(*) FROM annotations
        UNION ALL SELECT 'measurement_configs', COUNT(*) FROM measurement_configs
        UNION ALL SELECT 'raw_packets', COUNT(*) FROM raw_packets
        UNION ALL SELECT 'stream_samples', COUNT(*) FROM stream_samples
        "#,
    )
    .fetch_all(db)
    .await?;

    let stream_summary = sqlx::query(
        r#"
        SELECT stream_name, COUNT(*) AS rows_count,
               MIN(received_timestamp_ms) AS first_ms,
               MAX(received_timestamp_ms) AS last_ms,
               MIN(sample_rate_hz) AS min_hz,
               MAX(sample_rate_hz) AS max_hz
        FROM stream_samples
        GROUP BY stream_name
        ORDER BY stream_name
        "#,
    )
    .fetch_all(db)
    .await?;

    let devices = sqlx::query(
        r#"
        SELECT id, device_type, device_address, CAST(created_at AS CHAR) AS created_at
        FROM devices
        ORDER BY id
        LIMIT 50
        "#,
    )
    .fetch_all(db)
    .await?;

    Ok(json!({
        "table_counts": table_counts.into_iter().map(|row| json!({
            "table": row.try_get::<String, _>("table_name").unwrap_or_default(),
            "rows": row.try_get::<i64, _>("rows_count").unwrap_or_default()
        })).collect::<Vec<_>>(),
        "stream_summary": stream_summary.into_iter().map(|row| json!({
            "stream_name": row.try_get::<String, _>("stream_name").unwrap_or_default(),
            "rows": row.try_get::<i64, _>("rows_count").unwrap_or_default(),
            "first_ms": row.try_get::<Option<i64>, _>("first_ms").unwrap_or_default(),
            "last_ms": row.try_get::<Option<i64>, _>("last_ms").unwrap_or_default(),
            "min_hz": row.try_get::<Option<f64>, _>("min_hz").unwrap_or_default(),
            "max_hz": row.try_get::<Option<f64>, _>("max_hz").unwrap_or_default()
        })).collect::<Vec<_>>(),
        "devices": devices.into_iter().map(|row| json!({
            "id": row.try_get::<i64, _>("id").unwrap_or_default(),
            "device_type": row.try_get::<String, _>("device_type").unwrap_or_default(),
            "device_address": row.try_get::<Option<String>, _>("device_address").unwrap_or_default(),
            "created_at": row.try_get::<String, _>("created_at").unwrap_or_default()
        })).collect::<Vec<_>>()
    }))
}

async fn load_session_timing(db: &MySqlPool, session_id: i64) -> Result<Option<Value>> {
    let summary = sqlx::query(
        r#"
        SELECT COUNT(*) AS ppg_rows,
               COUNT(DISTINCT sample_index) AS distinct_indices,
               MIN(sample_index) AS first_index,
               MAX(sample_index) AS last_index,
               MIN(backend_received_timestamp_ms) AS first_backend_ms,
               MAX(backend_received_timestamp_ms) AS last_backend_ms,
               MIN(configured_sample_rate_hz) AS configured_sample_rate_hz,
               MIN(clock_source) AS clock_source,
               MIN(clock_quality) AS clock_quality
        FROM stream_samples
        WHERE session_id = ? AND stream_name = 'ppg' AND sample_index IS NOT NULL
        "#,
    )
    .bind(session_id)
    .fetch_one(db)
    .await?;

    let ppg_rows: i64 = summary.try_get("ppg_rows")?;
    if ppg_rows == 0 {
        return Ok(None);
    }

    let continuity = sqlx::query(
        r#"
        WITH indexed AS (
          SELECT sample_index,
                 LAG(sample_index) OVER (ORDER BY backend_received_timestamp_ms, id) AS previous_index
          FROM stream_samples
          WHERE session_id = ? AND stream_name = 'ppg' AND sample_index IS NOT NULL
        )
        SELECT COALESCE(SUM(sample_index <= previous_index), 0) AS non_increasing_steps,
               COALESCE(SUM(sample_index - previous_index > 1), 0) AS gap_count,
               COALESCE(SUM(GREATEST(sample_index - previous_index - 1, 0)), 0) AS missing_indices,
               MAX(sample_index - previous_index) AS largest_step
        FROM indexed
        WHERE previous_index IS NOT NULL
        "#,
    )
    .bind(session_id)
    .fetch_one(db)
    .await?;

    let first_backend_ms: Option<i64> = summary.try_get("first_backend_ms")?;
    let last_backend_ms: Option<i64> = summary.try_get("last_backend_ms")?;
    let distinct_indices: i64 = summary.try_get("distinct_indices")?;
    let observed_rate_hz = match (first_backend_ms, last_backend_ms) {
        (Some(first), Some(last)) if last > first => {
            Some((distinct_indices as f64) * 1000.0 / (last - first) as f64)
        }
        _ => None,
    };

    Ok(Some(json!({
        "session_id": session_id,
        "stream_name": "ppg",
        "ppg_rows": ppg_rows,
        "distinct_sample_indices": distinct_indices,
        "first_sample_index": summary.try_get::<Option<i64>, _>("first_index")?,
        "last_sample_index": summary.try_get::<Option<i64>, _>("last_index")?,
        "configured_sample_rate_hz": summary.try_get::<Option<f64>, _>("configured_sample_rate_hz")?,
        "observed_arrival_rate_hz": observed_rate_hz,
        "clock_source": summary.try_get::<Option<String>, _>("clock_source")?,
        "clock_quality": summary.try_get::<Option<String>, _>("clock_quality")?,
        "first_backend_received_ms": first_backend_ms,
        "last_backend_received_ms": last_backend_ms,
        "continuity": {
            "non_increasing_steps": continuity.try_get::<i64, _>("non_increasing_steps")?,
            "index_gap_count": continuity.try_get::<i64, _>("gap_count")?,
            "missing_indices": continuity.try_get::<i64, _>("missing_indices")?,
            "largest_index_step": continuity.try_get::<Option<i64>, _>("largest_step")?
        }
    })))
}

// Historical persisted-analysis prototype retained as reference only. It is
// compile-disabled; the active system post-processes evidence at query time.
#[cfg(any())]
async fn create_analysis_windows(
    evidence_db: &MySqlPool,
    analysis_db: &MySqlPool,
    session_id: i64,
    channel: &str,
    window_duration_s: f64,
    stride_s: f64,
) -> Result<Value> {
    let rows = sqlx::query(
        r#"
        SELECT device_id, sample_index, backend_received_timestamp_ms,
               COALESCE(configured_sample_rate_hz, sample_rate_hz) AS effective_rate,
               COALESCE(clock_source, 'arrival_only') AS clock_source,
               COALESCE(clock_quality, 'unvalidated') AS clock_quality,
               channel_values
        FROM stream_samples
        WHERE session_id = ? AND stream_name = 'ppg' AND sample_index IS NOT NULL
        ORDER BY backend_received_timestamp_ms ASC, id ASC
        "#,
    )
    .bind(session_id)
    .fetch_all(evidence_db)
    .await?;

    let mut samples = Vec::new();
    for row in rows {
        let values: Value = row.try_get("channel_values")?;
        if values.get(channel).and_then(Value::as_f64).is_none() {
            continue;
        }
        let sample_rate_hz = row
            .try_get::<Option<f64>, _>("effective_rate")?
            .unwrap_or(0.0);
        if sample_rate_hz <= 0.0 {
            continue;
        }
        samples.push(IndexedPpgSample {
            device_id: row.try_get("device_id")?,
            sample_index: row.try_get("sample_index")?,
            backend_received_ms: row.try_get("backend_received_timestamp_ms")?,
            sample_rate_hz,
            clock_source: row.try_get("clock_source")?,
            clock_quality: row.try_get("clock_quality")?,
        });
    }

    if samples.is_empty() {
        return Err(anyhow!("no indexed PPG samples for session/channel"));
    }

    let mut segments: Vec<Vec<IndexedPpgSample>> = Vec::new();
    let mut current: Vec<IndexedPpgSample> = Vec::new();
    for sample in samples {
        let starts_new_segment = current
            .last()
            .map(|previous| {
                sample.sample_index <= previous.sample_index
                    || sample.sample_index != previous.sample_index + 1
                    || (sample.sample_rate_hz - previous.sample_rate_hz).abs() > f64::EPSILON
            })
            .unwrap_or(false);
        if starts_new_segment {
            segments.push(std::mem::take(&mut current));
        }
        current.push(sample);
    }
    if !current.is_empty() {
        segments.push(current);
    }

    let mut created_windows = 0usize;
    let mut candidate_windows = 0usize;
    for (epoch, segment) in segments.iter().enumerate() {
        let rate = segment[0].sample_rate_hz;
        let window_sample_count = (rate * window_duration_s).round() as usize;
        let stride_sample_count = (rate * stride_s).round() as usize;
        if window_sample_count == 0
            || stride_sample_count == 0
            || segment.len() < window_sample_count
        {
            continue;
        }
        for start_offset in (0..=segment.len() - window_sample_count).step_by(stride_sample_count) {
            let end_offset = start_offset + window_sample_count - 1;
            let first = &segment[start_offset];
            let last = &segment[end_offset];
            candidate_windows += 1;
            let result = sqlx::query(
                r#"
                INSERT INTO analysis_windows
                  (evidence_session_id, evidence_device_id, stream_name, channel_name,
                   sample_index_epoch, start_sample_index, end_sample_index,
                   source_sample_rate_hz, window_duration_s, source_sample_count,
                   source_first_backend_ms, source_last_backend_ms, clock_source, clock_quality, status)
                VALUES (?, ?, 'ppg', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'candidate')
                ON DUPLICATE KEY UPDATE id = id
                "#,
            )
            .bind(session_id)
            .bind(first.device_id)
            .bind(channel)
            .bind(epoch as i32)
            .bind(first.sample_index)
            .bind(last.sample_index)
            .bind(rate)
            .bind(window_duration_s)
            .bind(window_sample_count as i32)
            .bind(first.backend_received_ms)
            .bind(last.backend_received_ms)
            .bind(&first.clock_source)
            .bind(&first.clock_quality)
            .execute(analysis_db)
            .await?;
            if result.rows_affected() == 1 {
                created_windows += 1;
            }
        }
    }

    Ok(json!({
        "session_id": session_id,
        "stream_name": "ppg",
        "channel": channel,
        "window_duration_s": window_duration_s,
        "stride_s": stride_s,
        "segments_detected": segments.len(),
        "candidate_windows": candidate_windows,
        "created_windows": created_windows
    }))
}

#[cfg(any())]
async fn run_candidate_windows(state: &AppState, session_id: i64, channel: &str) -> Result<Value> {
    let manifest: Value = serde_json::from_str(include_str!("../models/papagei_s.json"))?;
    let model_definition = sqlx::query(
        r#"
        INSERT INTO model_definitions (model_id, model_version, display_name, manifest_json)
        VALUES ('papagei_s', '2024-10', 'PaPaGei-S PPG foundation model', ?)
        ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id), manifest_json = VALUES(manifest_json), active = TRUE
        "#,
    )
    .bind(serde_json::to_string(&manifest)?)
    .execute(&state.analysis_db)
    .await?;
    let model_definition_id = model_definition.last_insert_id() as i64;

    let windows = sqlx::query(
        r#"
        SELECT id, source_sample_rate_hz, start_sample_index, end_sample_index
        FROM analysis_windows
        WHERE evidence_session_id = ? AND channel_name = ? AND status = 'candidate'
        ORDER BY sample_index_epoch, start_sample_index
        "#,
    )
    .bind(session_id)
    .bind(channel)
    .fetch_all(&state.analysis_db)
    .await?;

    let mut succeeded = 0usize;
    let mut failed = Vec::new();
    for window in windows {
        let window_id: i64 = window.try_get("id")?;
        let source_rate: f64 = window.try_get("source_sample_rate_hz")?;
        let start_index: i64 = window.try_get("start_sample_index")?;
        let end_index: i64 = window.try_get("end_sample_index")?;

        let samples = sqlx::query(
            r#"
            SELECT channel_values
            FROM stream_samples
            WHERE session_id = ? AND stream_name = 'ppg' AND sample_index BETWEEN ? AND ?
            ORDER BY backend_received_timestamp_ms, id
            "#,
        )
        .bind(session_id)
        .bind(start_index)
        .bind(end_index)
        .fetch_all(&state.db)
        .await?;
        let ppg_data: Vec<f64> = samples
            .into_iter()
            .filter_map(|row| {
                row.try_get::<Value, _>("channel_values")
                    .ok()
                    .and_then(|values| values.get(channel).and_then(Value::as_f64))
            })
            .collect();

        let worker_url = format!(
            "{}/infer",
            state.config.ml_inference_url.trim_end_matches('/')
        );
        let worker_response = reqwest::Client::new()
            .post(&worker_url)
            .json(&MlInferRequest {
                ppg_data,
                sample_rate: source_rate,
            })
            .send()
            .await;

        match worker_response {
            Ok(response) => {
                let body: Value = response.json().await.unwrap_or_else(|_| json!({}));
                if body.get("status").and_then(Value::as_str) != Some("success") {
                    failed.push(json!({ "window_id": window_id, "error": body.get("error").cloned().unwrap_or_else(|| json!("worker rejected window")) }));
                    continue;
                }
                let preprocessing = body
                    .get("preprocessing")
                    .cloned()
                    .unwrap_or_else(|| json!({}));
                let target_rate = preprocessing
                    .get("target_sample_rate_hz")
                    .and_then(Value::as_f64)
                    .unwrap_or(125.0);
                let target_count = preprocessing
                    .get("target_sample_count")
                    .and_then(Value::as_i64)
                    .unwrap_or(1250) as i32;
                let quality = preprocessing
                    .get("quality")
                    .cloned()
                    .unwrap_or_else(|| json!({}));
                let preprocess_id = sqlx::query(
                    r#"
                    INSERT INTO preprocessing_runs (analysis_window_id, pipeline_id, pipeline_version, parameters_json, target_sample_rate_hz, target_sample_count, quality_metrics_json, accepted)
                    VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)
                    "#,
                )
                .bind(window_id)
                .bind(preprocessing.get("pipeline_id").and_then(Value::as_str).unwrap_or("unknown"))
                .bind(preprocessing.get("pipeline_version").and_then(Value::as_str).unwrap_or("unknown"))
                .bind(serde_json::to_string(&preprocessing)?)
                .bind(target_rate)
                .bind(target_count)
                .bind(serde_json::to_string(&quality)?)
                .execute(&state.analysis_db)
                .await?
                .last_insert_id() as i64;
                let model_run_id = sqlx::query(
                    r#"
                    INSERT INTO model_runs (preprocessing_run_id, model_definition_id, worker_url, status, started_at, completed_at)
                    VALUES (?, ?, ?, 'succeeded', NOW(3), NOW(3))
                    "#,
                )
                .bind(preprocess_id)
                .bind(model_definition_id)
                .bind(&worker_url)
                .execute(&state.analysis_db)
                .await?
                .last_insert_id() as i64;
                sqlx::query(
                    "INSERT INTO model_outputs (model_run_id, output_type, output_dimension, output_json) VALUES (?, 'embedding', ?, ?)",
                )
                .bind(model_run_id)
                .bind(body.get("embedding_dim").and_then(Value::as_i64).unwrap_or(0) as i32)
                .bind(serde_json::to_string(&body.get("embeddings").cloned().unwrap_or_else(|| json!([])))?)
                .execute(&state.analysis_db)
                .await?;
                sqlx::query("UPDATE analysis_windows SET status = 'processed' WHERE id = ?")
                    .bind(window_id)
                    .execute(&state.analysis_db)
                    .await?;
                succeeded += 1;
            }
            Err(err) => failed.push(json!({ "window_id": window_id, "error": err.to_string() })),
        }
    }
    Ok(
        json!({ "session_id": session_id, "channel": channel, "succeeded": succeeded, "failed": failed }),
    )
}

#[cfg(any())]
async fn load_analysis_overview(db: &MySqlPool) -> Result<Value> {
    let counts = sqlx::query(
        "SELECT (SELECT COUNT(*) FROM analysis_windows) AS windows, (SELECT COUNT(*) FROM preprocessing_runs) AS preprocessing_runs, (SELECT COUNT(*) FROM model_runs) AS model_runs, (SELECT COUNT(*) FROM model_outputs) AS outputs, (SELECT COUNT(*) FROM validation_labels) AS labels",
    ).fetch_one(db).await?;
    let runs = sqlx::query(
        r#"
        SELECT aw.id AS window_id, aw.evidence_session_id, aw.sample_index_epoch, aw.start_sample_index, aw.end_sample_index,
               aw.status AS window_status, pr.pipeline_id, pr.target_sample_count, mr.status AS run_status,
               mo.output_dimension, mo.output_json
        FROM analysis_windows aw
        LEFT JOIN preprocessing_runs pr ON pr.analysis_window_id = aw.id
        LEFT JOIN model_runs mr ON mr.preprocessing_run_id = pr.id
        LEFT JOIN model_outputs mo ON mo.model_run_id = mr.id
        ORDER BY aw.id DESC LIMIT 100
        "#,
    ).fetch_all(db).await?;
    Ok(json!({
        "counts": {
            "windows": counts.try_get::<i64,_>("windows")?, "preprocessing_runs": counts.try_get::<i64,_>("preprocessing_runs")?,
            "model_runs": counts.try_get::<i64,_>("model_runs")?, "outputs": counts.try_get::<i64,_>("outputs")?, "labels": counts.try_get::<i64,_>("labels")?
        },
        "runs": runs.into_iter().map(|row| json!({
            "window_id": row.try_get::<i64,_>("window_id").unwrap_or_default(), "session_id": row.try_get::<i64,_>("evidence_session_id").unwrap_or_default(),
            "epoch": row.try_get::<i32,_>("sample_index_epoch").unwrap_or_default(), "start_index": row.try_get::<i64,_>("start_sample_index").unwrap_or_default(), "end_index": row.try_get::<i64,_>("end_sample_index").unwrap_or_default(),
            "window_status": row.try_get::<String,_>("window_status").unwrap_or_default(), "pipeline": row.try_get::<Option<String>,_>("pipeline_id").unwrap_or_default(),
            "target_samples": row.try_get::<Option<i32>,_>("target_sample_count").unwrap_or_default(), "run_status": row.try_get::<Option<String>,_>("run_status").unwrap_or_default(),
            "output_dimension": row.try_get::<Option<i32>,_>("output_dimension").unwrap_or_default(), "embedding": row.try_get::<Option<Value>,_>("output_json").unwrap_or_default()
        })).collect::<Vec<_>>()
    }))
}

/// List experiments the caller may see.
///
/// With tenancy applied, each row also carries who recorded it and which study
/// it belongs to, so a list drawn from several participants' phones is
/// readable rather than an undifferentiated pile.
async fn load_experiments(state: &AppState, scope: Option<i64>) -> Result<Vec<Value>> {
    let db = &state.db;
    if !state.tenancy_enabled {
        return load_experiments_legacy(db).await;
    }

    let base = r#"
        SELECT e.id, e.experiment_label, e.protocol_name, e.protocol_version,
               e.status, e.started_at_ms, e.ended_at_ms,
               DATE_FORMAT(e.created_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS created_at,
               e.owner_user_id, e.study_id,
               COALESCE(u.participant_code, u.email, u.display_name) AS owner_label,
               st.name AS study_name,
               COUNT(s.id) AS linked_sessions
        FROM experiments e
        LEFT JOIN sessions s ON s.experiment_id = e.id
        LEFT JOIN users u ON u.id = e.owner_user_id
        LEFT JOIN studies st ON st.id = e.study_id
    "#;
    let tail = r#"
        GROUP BY e.id, e.experiment_label, e.protocol_name, e.protocol_version,
                 e.status, e.started_at_ms, e.ended_at_ms, e.created_at,
                 e.owner_user_id, e.study_id, owner_label, st.name
        ORDER BY e.id DESC
        LIMIT 100
    "#;

    let rows = match scope {
        None => sqlx::query(&format!("{base}{tail}")).fetch_all(db).await?,
        Some(user_id) => {
            sqlx::query(&format!("{base} WHERE {VISIBLE_EXPERIMENTS_SQL} {tail}"))
                .bind(user_id)
                .bind(user_id)
                .fetch_all(db)
                .await?
        }
    };

    let mut experiments = Vec::with_capacity(rows.len());
    for row in rows {
        experiments.push(json!({
            "id": row.try_get::<i64, _>("id")?,
            "experiment_label": row.try_get::<String, _>("experiment_label")?,
            "protocol_name": row.try_get::<Option<String>, _>("protocol_name")?,
            "protocol_version": row.try_get::<Option<String>, _>("protocol_version")?,
            "status": row.try_get::<Option<String>, _>("status")?,
            "started_at_ms": row.try_get::<Option<i64>, _>("started_at_ms")?,
            "ended_at_ms": row.try_get::<Option<i64>, _>("ended_at_ms")?,
            "created_at": row.try_get::<Option<String>, _>("created_at")?,
            "owner_user_id": row.try_get::<Option<i64>, _>("owner_user_id")?,
            "owner_label": row.try_get::<Option<String>, _>("owner_label")?,
            "study_id": row.try_get::<Option<i64>, _>("study_id")?,
            "study_name": row.try_get::<Option<String>, _>("study_name")?,
            "linked_sessions": row.try_get::<i64, _>("linked_sessions")?,
        }));
    }
    Ok(experiments)
}

async fn load_experiments_legacy(db: &MySqlPool) -> Result<Vec<Value>> {
    let rows = sqlx::query(
        r#"
        SELECT e.id, e.experiment_label, e.protocol_name, e.protocol_version,
               e.status, e.started_at_ms, e.ended_at_ms,
               DATE_FORMAT(e.created_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS created_at,
               COUNT(s.id) AS linked_sessions
        FROM experiments e
        LEFT JOIN sessions s ON s.experiment_id = e.id
        GROUP BY e.id, e.experiment_label, e.protocol_name, e.protocol_version,
                 e.status, e.started_at_ms, e.ended_at_ms, e.created_at
        ORDER BY e.id DESC
        LIMIT 100
        "#,
    )
    .fetch_all(db)
    .await?;
    Ok(rows.into_iter().map(|row| json!({
        "id": row.try_get::<i64, _>("id").unwrap_or_default(),
        "experiment_label": row.try_get::<String, _>("experiment_label").unwrap_or_default(),
        "protocol_name": row.try_get::<Option<String>, _>("protocol_name").unwrap_or_default(),
        "protocol_version": row.try_get::<Option<String>, _>("protocol_version").unwrap_or_default(),
        "status": row.try_get::<String, _>("status").unwrap_or_default(),
        "started_at_ms": row.try_get::<Option<i64>, _>("started_at_ms").unwrap_or_default(),
        "ended_at_ms": row.try_get::<Option<i64>, _>("ended_at_ms").unwrap_or_default(),
        "created_at": row.try_get::<String, _>("created_at").unwrap_or_default(),
        "linked_sessions": row.try_get::<i64, _>("linked_sessions").unwrap_or_default()
    })).collect())
}

async fn load_experiment_details(
    db: &MySqlPool,
    experiment_id: i64,
    limit: u32,
) -> Result<Option<Value>> {
    let experiment = sqlx::query(
        r#"
        SELECT id, experiment_label, protocol_name, protocol_version, operator_label,
               notes, status, started_at_ms, ended_at_ms,
               DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS created_at
        FROM experiments WHERE id = ?
        "#,
    )
    .bind(experiment_id)
    .fetch_optional(db)
    .await?;
    let Some(experiment) = experiment else {
        return Ok(None);
    };

    let recordings = sqlx::query(
        r#"
        SELECT s.id, s.session_label, s.status, s.started_at_ms, s.ended_at_ms,
               d.id AS device_id, d.device_type, d.device_name, d.device_address,
               (SELECT COUNT(*) FROM raw_packets rp WHERE rp.session_id = s.id) AS raw_packet_count,
               (SELECT COUNT(*) FROM stream_samples ss WHERE ss.session_id = s.id) AS stream_sample_count
        FROM sessions s
        JOIN devices d ON d.id = s.device_id
        WHERE s.experiment_id = ?
        ORDER BY s.id
        "#,
    )
    .bind(experiment_id)
    .fetch_all(db)
    .await?;

    let sensor_configurations = sqlx::query(
        r#"
        SELECT mc.id, mc.session_id, mc.command_name, mc.requested_streams,
               mc.effective_streams, mc.requested_sample_rate_hz,
               mc.effective_sample_rate_hz, mc.device_config,
               mc.started_at_ms, mc.ended_at_ms
        FROM measurement_configs mc
        JOIN sessions s ON s.id = mc.session_id
        WHERE s.experiment_id = ?
        ORDER BY mc.started_at_ms, mc.id
        "#,
    )
    .bind(experiment_id)
    .fetch_all(db)
    .await?;

    let annotations = sqlx::query(
        r#"
        SELECT id, session_id, label_name, label_value, started_at_ms, ended_at_ms,
               source, confidence
        FROM annotations
        WHERE experiment_id = ?
        ORDER BY started_at_ms, id
        "#,
    )
    .bind(experiment_id)
    .fetch_all(db)
    .await?;

    let raw_packets = sqlx::query(
        r#"
        SELECT rp.id, rp.session_id, rp.packet_type, rp.source_topic,
               rp.device_timestamp_ms, rp.received_timestamp_ms,
               rp.packet_sample_rate_hz, rp.sequence_number, rp.clock_source,
               rp.clock_quality, rp.parse_status, rp.payload
        FROM raw_packets rp
        JOIN sessions s ON s.id = rp.session_id
        WHERE s.experiment_id = ?
        ORDER BY rp.id DESC
        LIMIT ?
        "#,
    )
    .bind(experiment_id)
    .bind(limit)
    .fetch_all(db)
    .await?;

    let stream_samples = sqlx::query(
        r#"
        SELECT ss.id, ss.session_id, ss.raw_packet_id, ss.stream_name,
               ss.device_timestamp_ms, ss.received_timestamp_ms, ss.sample_rate_hz,
               ss.sequence_number, ss.sample_index, ss.clock_source,
               ss.clock_quality, ss.channel_values, ss.channel_units,
               ss.quality_flags
        FROM stream_samples ss
        JOIN sessions s ON s.id = ss.session_id
        WHERE s.experiment_id = ?
        ORDER BY ss.id DESC
        LIMIT ?
        "#,
    )
    .bind(experiment_id)
    .bind(limit)
    .fetch_all(db)
    .await?;

    Ok(Some(json!({
        "experiment": {
            "id": experiment.try_get::<i64, _>("id")?,
            "experiment_label": experiment.try_get::<String, _>("experiment_label")?,
            "protocol_name": experiment.try_get::<Option<String>, _>("protocol_name")?,
            "protocol_version": experiment.try_get::<Option<String>, _>("protocol_version")?,
            "operator_label": experiment.try_get::<Option<String>, _>("operator_label")?,
            "notes": experiment.try_get::<Option<String>, _>("notes")?,
            "status": experiment.try_get::<String, _>("status")?,
            "started_at_ms": experiment.try_get::<Option<i64>, _>("started_at_ms")?,
            "ended_at_ms": experiment.try_get::<Option<i64>, _>("ended_at_ms")?,
            "created_at": experiment.try_get::<String, _>("created_at")?
        },
        "device_recordings": recordings.into_iter().map(|row| json!({
            "id": row.try_get::<i64, _>("id").unwrap_or_default(),
            "session_label": row.try_get::<Option<String>, _>("session_label").unwrap_or_default(),
            "status": row.try_get::<String, _>("status").unwrap_or_default(),
            "started_at_ms": row.try_get::<i64, _>("started_at_ms").unwrap_or_default(),
            "ended_at_ms": row.try_get::<Option<i64>, _>("ended_at_ms").unwrap_or_default(),
            "device_id": row.try_get::<i64, _>("device_id").unwrap_or_default(),
            "device_type": row.try_get::<String, _>("device_type").unwrap_or_default(),
            "device_name": row.try_get::<Option<String>, _>("device_name").unwrap_or_default(),
            "device_address": row.try_get::<Option<String>, _>("device_address").unwrap_or_default(),
            "raw_packet_count": row.try_get::<i64, _>("raw_packet_count").unwrap_or_default(),
            "stream_sample_count": row.try_get::<i64, _>("stream_sample_count").unwrap_or_default()
        })).collect::<Vec<_>>(),
        "sensor_configurations": sensor_configurations.into_iter().map(|row| json!({
            "id": row.try_get::<i64, _>("id").unwrap_or_default(),
            "device_recording_id": row.try_get::<i64, _>("session_id").unwrap_or_default(),
            "command_name": row.try_get::<String, _>("command_name").unwrap_or_default(),
            "requested_streams": row.try_get::<Option<Value>, _>("requested_streams").unwrap_or_default(),
            "effective_streams": row.try_get::<Option<Value>, _>("effective_streams").unwrap_or_default(),
            "requested_sample_rate_hz": row.try_get::<Option<f64>, _>("requested_sample_rate_hz").unwrap_or_default(),
            "effective_sample_rate_hz": row.try_get::<Option<f64>, _>("effective_sample_rate_hz").unwrap_or_default(),
            "device_config": row.try_get::<Option<Value>, _>("device_config").unwrap_or_default(),
            "started_at_ms": row.try_get::<i64, _>("started_at_ms").unwrap_or_default(),
            "ended_at_ms": row.try_get::<Option<i64>, _>("ended_at_ms").unwrap_or_default()
        })).collect::<Vec<_>>(),
        "activity_labels": annotations.into_iter().map(|row| json!({
            "id": row.try_get::<i64, _>("id").unwrap_or_default(),
            "device_recording_id": row.try_get::<Option<i64>, _>("session_id").unwrap_or_default(),
            "scope": if row.try_get::<Option<i64>, _>("session_id").unwrap_or_default().is_some() { "device_recording" } else { "experiment" },
            "applies_to_all_device_recordings": row.try_get::<Option<i64>, _>("session_id").unwrap_or_default().is_none(),
            "label_name": row.try_get::<String, _>("label_name").unwrap_or_default(),
            "label_value": row.try_get::<String, _>("label_value").unwrap_or_default(),
            "started_at_ms": row.try_get::<i64, _>("started_at_ms").unwrap_or_default(),
            "ended_at_ms": row.try_get::<Option<i64>, _>("ended_at_ms").unwrap_or_default(),
            "source": row.try_get::<String, _>("source").unwrap_or_default(),
            "confidence": row.try_get::<Option<f64>, _>("confidence").unwrap_or_default()
        })).collect::<Vec<_>>(),
        "raw_packets": raw_packets.into_iter().map(|row| json!({
            "id": row.try_get::<i64, _>("id").unwrap_or_default(),
            "device_recording_id": row.try_get::<i64, _>("session_id").unwrap_or_default(),
            "packet_type": row.try_get::<Option<String>, _>("packet_type").unwrap_or_default(),
            "source_topic": row.try_get::<Option<String>, _>("source_topic").unwrap_or_default(),
            "device_timestamp_ms": row.try_get::<Option<i64>, _>("device_timestamp_ms").unwrap_or_default(),
            "received_timestamp_ms": row.try_get::<i64, _>("received_timestamp_ms").unwrap_or_default(),
            "sample_rate_hz": row.try_get::<Option<f64>, _>("packet_sample_rate_hz").unwrap_or_default(),
            "sequence_number": row.try_get::<Option<i64>, _>("sequence_number").unwrap_or_default(),
            "clock_source": row.try_get::<Option<String>, _>("clock_source").unwrap_or_default(),
            "clock_quality": row.try_get::<Option<String>, _>("clock_quality").unwrap_or_default(),
            "parse_status": row.try_get::<String, _>("parse_status").unwrap_or_default(),
            "payload": row.try_get::<Value, _>("payload").unwrap_or(Value::Null)
        })).collect::<Vec<_>>(),
        "stream_samples": stream_samples.into_iter().map(|row| json!({
            "id": row.try_get::<i64, _>("id").unwrap_or_default(),
            "device_recording_id": row.try_get::<i64, _>("session_id").unwrap_or_default(),
            "raw_packet_id": row.try_get::<Option<i64>, _>("raw_packet_id").unwrap_or_default(),
            "stream_name": row.try_get::<String, _>("stream_name").unwrap_or_default(),
            "device_timestamp_ms": row.try_get::<Option<i64>, _>("device_timestamp_ms").unwrap_or_default(),
            "received_timestamp_ms": row.try_get::<i64, _>("received_timestamp_ms").unwrap_or_default(),
            "sample_rate_hz": row.try_get::<Option<f64>, _>("sample_rate_hz").unwrap_or_default(),
            "sequence_number": row.try_get::<Option<i64>, _>("sequence_number").unwrap_or_default(),
            "sample_index": row.try_get::<Option<i64>, _>("sample_index").unwrap_or_default(),
            "clock_source": row.try_get::<Option<String>, _>("clock_source").unwrap_or_default(),
            "clock_quality": row.try_get::<Option<String>, _>("clock_quality").unwrap_or_default(),
            "channel_values": row.try_get::<Value, _>("channel_values").unwrap_or(Value::Null),
            "channel_units": row.try_get::<Option<Value>, _>("channel_units").unwrap_or_default(),
            "quality_flags": row.try_get::<Option<Value>, _>("quality_flags").unwrap_or_default()
        })).collect::<Vec<_>>(),
        "evidence_limit": limit
    })))
}

async fn has_column(db: &MySqlPool, table: &str, column: &str) -> Result<bool> {
    let count = sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
    )
    .bind(table)
    .bind(column)
    .fetch_one(db)
    .await?;
    Ok(count > 0)
}

async fn has_table(db: &MySqlPool, table: &str) -> Result<bool> {
    let count = sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
    )
    .bind(table)
    .fetch_one(db)
    .await?;
    Ok(count > 0)
}

/// Reject a study the caller is not an active member of.
///
/// Only enforced for an authenticated caller. The shared token has no identity,
/// so it acts as the operator and is trusted until it is retired.
async fn check_study_membership(
    state: &AppState,
    owner: OwnerKey,
    study_id: Option<i64>,
) -> Result<()> {
    let (Some(study_id), Some(user_id)) = (study_id, owner) else {
        return Ok(());
    };
    if !state.tenancy_enabled {
        return Err(anyhow!(
            "study_id was supplied but the identity/tenancy migration has not been applied"
        ));
    }
    let member = sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(*) FROM study_memberships WHERE study_id = ? AND user_id = ? AND status = 'active'",
    )
    .bind(study_id)
    .bind(user_id)
    .fetch_one(&state.db)
    .await?;
    if member == 0 {
        return Err(anyhow!("you are not an active member of that study"));
    }
    Ok(())
}

/// The MQTT username for a participant. The broker's ACL keys off this, and
/// topics are namespaced to match, so the two cannot drift apart.
fn mqtt_username_for(user_id: i64) -> String {
    format!("u{user_id}")
}

/// A participant's MQTT password, derived rather than stored.
///
/// Deriving it means the broker's password file can be regenerated from the
/// users table at any time without anyone holding a list of plaintext
/// passwords, and a participant can be handed their own credential after
/// signing in. Rotating `MQTT_USER_SECRET` invalidates every participant
/// credential at once.
fn mqtt_password_for(secret: &str, user_id: i64) -> String {
    use sha2::{Digest, Sha256};
    let mut hasher = Sha256::new();
    hasher.update(secret.as_bytes());
    hasher.update(b":mqtt:");
    hasher.update(user_id.to_string().as_bytes());
    let digest = hasher.finalize();
    digest.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// The owner a `sensors/...` topic claims to belong to.
///
/// `sensors/u12/OpenRing` claims user 12; the legacy `sensors/OpenRing` claims
/// nobody. Returning None for the legacy shape keeps existing clients working
/// while they are migrated.
fn topic_claimed_owner(topic: &str) -> Option<i64> {
    let mut parts = topic.split('/');
    parts.next()?; // "sensors"
    let segment = parts.next()?;
    let rest = segment.strip_prefix('u')?;
    rest.parse::<i64>().ok()
}

/// Which experiments a caller may see.
///
/// `None` means everything, which applies to a platform admin, to the legacy
/// shared token (it has no identity, so there is nothing to scope it to), and
/// to a database where the tenancy migration has not been applied and no row
/// has an owner yet.
///
/// `Some(user_id)` means their own experiments plus any study they administer.
/// This is the single rule; every endpoint that reads evidence applies it
/// rather than each deciding for itself.
fn visible_scope(state: &AppState, auth: &AuthContext) -> Option<i64> {
    if !state.tenancy_enabled || auth.is_platform_admin() {
        return None;
    }
    auth.user_id
}

/// Predicate restricting `experiments e` to what `Some(user_id)` may see.
/// Both placeholders take the same user id.
const VISIBLE_EXPERIMENTS_SQL: &str = "(e.owner_user_id = ? OR e.study_id IN (\
     SELECT m.study_id FROM study_memberships m \
     WHERE m.user_id = ? AND m.status = 'active' \
       AND m.role IN ('study_admin', 'researcher')))";

/// Whether this caller may read a specific experiment.
async fn may_read_experiment(
    state: &AppState,
    auth: &AuthContext,
    experiment_id: i64,
) -> Result<bool> {
    let Some(user_id) = visible_scope(state, auth) else {
        return Ok(true);
    };
    let sql = format!(
        "SELECT COUNT(*) FROM experiments e WHERE e.id = ? AND {VISIBLE_EXPERIMENTS_SQL}"
    );
    let count = sqlx::query_scalar::<_, i64>(&sql)
        .bind(experiment_id)
        .bind(user_id)
        .bind(user_id)
        .fetch_one(&state.db)
        .await?;
    Ok(count > 0)
}

/// Rebuild the per-owner active-experiment map on startup.
///
/// `owner_user_id` only exists once the identity/tenancy migration has been
/// applied. Against an unmigrated database every active experiment is treated
/// as belonging to the unattributed owner, so the backend still starts.
async fn load_active_experiments(db: &MySqlPool) -> Result<HashMap<OwnerKey, i64>> {
    let has_owner_column = sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(*) FROM information_schema.columns          WHERE table_schema = DATABASE() AND table_name = 'experiments'            AND column_name = 'owner_user_id'",
    )
    .fetch_one(db)
    .await?
        > 0;

    let mut active: HashMap<OwnerKey, i64> = HashMap::new();
    if has_owner_column {
        let rows = sqlx::query(
            "SELECT id, owner_user_id FROM experiments              WHERE status = 'active' AND ended_at_ms IS NULL ORDER BY id",
        )
        .fetch_all(db)
        .await?;
        for row in rows {
            let id: i64 = row.try_get("id")?;
            let owner: Option<i64> = row.try_get("owner_user_id")?;
            active.insert(owner, id);
        }
    } else {
        let rows = sqlx::query(
            "SELECT id FROM experiments WHERE status = 'active' AND ended_at_ms IS NULL ORDER BY id",
        )
        .fetch_all(db)
        .await?;
        for row in rows {
            let id: i64 = row.try_get("id")?;
            active.insert(None, id);
        }
    }
    Ok(active)
}

async fn abandon_stale_sessions(db: &MySqlPool) -> Result<u64> {
    let ended_at_ms = now_ms();
    sqlx::query(
        r#"
        UPDATE measurement_configs mc
        JOIN sessions s ON s.id = mc.session_id
        LEFT JOIN experiments e ON e.id = s.experiment_id
        SET mc.ended_at_ms = COALESCE(mc.ended_at_ms, ?)
        WHERE s.status = 'active' AND s.ended_at_ms IS NULL
          AND (s.experiment_id IS NULL OR e.id IS NULL
               OR e.status <> 'active' OR e.ended_at_ms IS NOT NULL)
          AND mc.ended_at_ms IS NULL
        "#,
    )
    .bind(ended_at_ms)
    .execute(db)
    .await?;
    let result = sqlx::query(
        r#"
        UPDATE sessions s
        LEFT JOIN experiments e ON e.id = s.experiment_id
        SET s.status = 'abandoned', s.ended_at_ms = COALESCE(
          s.ended_at_ms,
          (SELECT MAX(rp.backend_received_timestamp_ms)
           FROM raw_packets rp WHERE rp.session_id = s.id),
          s.started_at_ms
        )
        WHERE s.status = 'active' AND s.ended_at_ms IS NULL
          AND (s.experiment_id IS NULL OR e.id IS NULL
               OR e.status <> 'active' OR e.ended_at_ms IS NOT NULL)
        "#,
    )
    .execute(db)
    .await?;
    Ok(result.rows_affected())
}

async fn load_active_sessions(db: &MySqlPool) -> Result<HashMap<String, SessionHandle>> {
    let rows = sqlx::query(
        r#"
        SELECT s.id AS session_id, s.device_id, d.device_type, d.device_address,
               (SELECT mc.id
                FROM measurement_configs mc
                WHERE mc.session_id = s.id AND mc.ended_at_ms IS NULL
                ORDER BY mc.started_at_ms DESC, mc.id DESC LIMIT 1) AS measurement_config_id
        FROM sessions s
        JOIN devices d ON d.id = s.device_id
        JOIN experiments e ON e.id = s.experiment_id
        WHERE s.status = 'active' AND s.ended_at_ms IS NULL
          AND e.status = 'active' AND e.ended_at_ms IS NULL
        "#,
    )
    .fetch_all(db)
    .await?;
    let mut sessions = HashMap::new();
    for row in rows {
        let device_type: String = row.try_get("device_type")?;
        let device_address: Option<String> = row.try_get("device_address")?;
        if let Some(device_address) = device_address {
            sessions.insert(
                session_key(&device_type, &device_address),
                SessionHandle {
                    device_id: row.try_get("device_id")?,
                    session_id: row.try_get("session_id")?,
                    measurement_config_id: row.try_get("measurement_config_id")?,
                },
            );
        }
    }
    Ok(sessions)
}

async fn create_experiment_record(
    state: &AppState,
    owner: OwnerKey,
    request: &CreateExperimentRequest,
) -> Result<i64> {
    let created = sqlx::query(
        r#"
        INSERT INTO experiments
          (experiment_label, protocol_name, protocol_version, operator_label, notes, status)
        VALUES (?, ?, ?, ?, ?, 'planned')
        "#,
    )
    .bind(request.experiment_label.trim())
    .bind(
        request
            .protocol_name
            .as_deref()
            .map(str::trim)
            .filter(|v| !v.is_empty()),
    )
    .bind(
        request
            .protocol_version
            .as_deref()
            .map(str::trim)
            .filter(|v| !v.is_empty()),
    )
    .bind(
        request
            .operator_label
            .as_deref()
            .map(str::trim)
            .filter(|v| !v.is_empty()),
    )
    .bind(
        request
            .notes
            .as_deref()
            .map(str::trim)
            .filter(|v| !v.is_empty()),
    )
    .execute(&state.db)
    .await?;
    let experiment_id = created.last_insert_id() as i64;
    if state.tenancy_enabled {
        sqlx::query("UPDATE experiments SET owner_user_id = ?, study_id = ? WHERE id = ?")
            .bind(owner)
            .bind(request.study_id)
            .bind(experiment_id)
            .execute(&state.db)
            .await?;
    }
    Ok(experiment_id)
}

async fn start_recording_transaction(
    state: &AppState,
    owner: OwnerKey,
    request: StartRecordingRequest,
) -> Result<Value> {
    let mut transaction = state.db.begin().await?;
    let started_at_ms = now_ms();
    let experiment = sqlx::query(
        r#"
        INSERT INTO experiments
          (experiment_label, protocol_name, status, started_at_ms, client_started_at_ms)
        VALUES (?, ?, 'active', ?, ?)
        "#,
    )
    .bind(request.experiment_label.trim())
    .bind(
        request
            .protocol_name
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty()),
    )
    .bind(started_at_ms)
    .bind(request.client_timestamp_ms)
    .execute(&mut *transaction)
    .await?;
    let experiment_id = experiment.last_insert_id() as i64;

    let mut handles = Vec::with_capacity(request.device_recordings.len());
    let mut device_recordings = Vec::with_capacity(request.device_recordings.len());
    let mut unique_devices = std::collections::HashSet::new();

    for device in request.device_recordings {
        let device_type = device.device_type.trim();
        let device_address = device.device_address.trim();
        let key = session_key(device_type, device_address);
        if !unique_devices.insert(key.clone()) {
            return Err(anyhow!(
                "the recording request contains the same device twice"
            ));
        }
        if state.sessions.read().await.contains_key(&key) {
            return Err(anyhow!(
                "device {device_type} {device_address} already has an active device recording"
            ));
        }

        let device_result = sqlx::query(
            "INSERT INTO devices (device_type, device_address) VALUES (?, ?) ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)",
        )
        .bind(device_type)
        .bind(device_address)
        .execute(&mut *transaction)
        .await?;
        let device_id = device_result.last_insert_id() as i64;

        let device_recording = sqlx::query(
            r#"
            INSERT INTO sessions
              (experiment_id, device_id, session_label, status, started_at_ms,
               client_started_at_ms, mqtt_broker_url)
            VALUES (?, ?, ?, 'active', ?, ?, ?)
            "#,
        )
        .bind(experiment_id)
        .bind(device_id)
        .bind(
            device
                .session_label
                .as_deref()
                .map(str::trim)
                .filter(|value| !value.is_empty()),
        )
        .bind(started_at_ms)
        .bind(request.client_timestamp_ms)
        .bind(&state.config.mqtt_broker_url)
        .execute(&mut *transaction)
        .await?;
        let device_recording_id = device_recording.last_insert_id() as i64;
        let measurement_config_id = insert_default_sensor_configuration(
            &mut transaction,
            device_recording_id,
            device_id,
            device_type,
            started_at_ms,
        )
        .await?;

        handles.push((
            key,
            SessionHandle {
                device_id,
                session_id: device_recording_id,
                measurement_config_id,
            },
        ));
        device_recordings.push(json!({
            "id": device_recording_id,
            "device_id": device_id,
            "device_type": device_type,
            "device_address": device_address,
            "sensor_configuration_id": measurement_config_id,
            "status": "active"
        }));
    }

    // Attribute the experiment and its device recordings. Done as a follow-up
    // update inside the same transaction so the inserts above stay valid
    // against a database where the tenancy migration has not been applied.
    if state.tenancy_enabled {
        sqlx::query("UPDATE experiments SET owner_user_id = ?, study_id = ? WHERE id = ?")
            .bind(owner)
            .bind(request.study_id)
            .bind(experiment_id)
            .execute(&mut *transaction)
            .await?;
        sqlx::query("UPDATE sessions SET owner_user_id = ?, study_id = ? WHERE experiment_id = ?")
            .bind(owner)
            .bind(request.study_id)
            .bind(experiment_id)
            .execute(&mut *transaction)
            .await?;
    }

    transaction.commit().await?;
    {
        let mut active_sessions = state.sessions.write().await;
        for (key, handle) in handles {
            active_sessions.insert(key, handle);
        }
    }
    state.set_active_experiment(owner, experiment_id).await;

    Ok(json!({
        "id": experiment_id,
        "experiment_label": request.experiment_label.trim(),
        "protocol_name": request.protocol_name,
        "started_at_ms": started_at_ms,
        "client_started_at_ms": request.client_timestamp_ms,
        "status": "active",
        "device_recordings": device_recordings
    }))
}

async fn insert_default_sensor_configuration(
    transaction: &mut sqlx::Transaction<'_, sqlx::MySql>,
    session_id: i64,
    device_id: i64,
    device_type: &str,
    started_at_ms: i64,
) -> Result<Option<i64>> {
    let configuration = match device_type {
        "OpenRing" => Some((
            "START_MEASUREMENT",
            json!(["ppg", "accel", "gyro", "temperature"]),
            Some(25.0),
            json!({
                "source": "android_adapter_default",
                "collection_time_s": 30,
                "green_led_current": 20,
                "infrared_led_current": 20,
                "red_led_current": 20,
                "progress_response_enabled": true,
                "waveform_response_enabled": true
            }),
        )),
        "OpenEarable" => Some((
            "START_ALL_SENSORS",
            json!(["accel", "gyro", "mag", "ppg", "temperature", "pressure"]),
            None,
            json!({
                "source": "android_adapter_default",
                "per_stream_rates": true
            }),
        )),
        "HeartRateMonitor" => Some((
            "START_MEASUREMENT",
            json!(["heart_rate"]),
            None,
            json!({
                "source": "android_adapter_default",
                "gatt_service": "0x180D",
                "event_driven": true
            }),
        )),
        _ => None,
    };
    let Some((command_name, requested_streams, requested_rate, device_config)) = configuration
    else {
        return Ok(None);
    };
    let result = sqlx::query(
        r#"
        INSERT INTO measurement_configs
          (session_id, device_id, command_name, requested_streams,
           requested_sample_rate_hz, device_config, started_at_ms)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        "#,
    )
    .bind(session_id)
    .bind(device_id)
    .bind(command_name)
    .bind(requested_streams)
    .bind(requested_rate)
    .bind(device_config)
    .bind(started_at_ms)
    .execute(&mut **transaction)
    .await?;
    Ok(Some(result.last_insert_id() as i64))
}

async fn start_experiment_capture(
    state: &AppState,
    owner: OwnerKey,
    experiment_id: i64,
    client_timestamp_ms: Option<i64>,
) -> Result<Value> {
    let started_at_ms = now_ms();
    let result = sqlx::query(
        r#"
        UPDATE experiments
        SET status = 'active', started_at_ms = ?, client_started_at_ms = ?, ended_at_ms = NULL
        WHERE id = ? AND status = 'planned'
        "#,
    )
    .bind(started_at_ms)
    .bind(client_timestamp_ms)
    .bind(experiment_id)
    .execute(&state.db)
    .await?;
    if result.rows_affected() != 1 {
        return Err(anyhow!(
            "experiment does not exist or is not in planned state"
        ));
    }
    state.set_active_experiment(owner, experiment_id).await;
    let report = json!({ "id": experiment_id, "started_at_ms": started_at_ms, "client_started_at_ms": client_timestamp_ms, "status": "active" });
    publish_control_status(
        state,
        &format!("experiments/{experiment_id}"),
        "experiment.started",
        report.clone(),
        true,
    )
    .await;
    Ok(report)
}

async fn stop_experiment_capture(
    state: &AppState,
    experiment_id: i64,
    client_timestamp_ms: Option<i64>,
) -> Result<Value> {
    let ended_at_ms = now_ms();
    let linked =
        sqlx::query("SELECT id FROM sessions WHERE experiment_id = ? AND ended_at_ms IS NULL")
            .bind(experiment_id)
            .fetch_all(&state.db)
            .await?;
    let linked_ids = linked
        .into_iter()
        .map(|row| row.try_get::<i64, _>("id"))
        .collect::<Result<Vec<_>, _>>()?;
    for session_id in &linked_ids {
        sqlx::query("UPDATE sessions SET status = 'stopped', ended_at_ms = COALESCE(ended_at_ms, ?), client_ended_at_ms = COALESCE(client_ended_at_ms, ?) WHERE id = ?")
            .bind(ended_at_ms).bind(client_timestamp_ms).bind(session_id).execute(&state.db).await?;
        sqlx::query("UPDATE measurement_configs SET ended_at_ms = COALESCE(ended_at_ms, ?) WHERE session_id = ? AND ended_at_ms IS NULL")
            .bind(ended_at_ms).bind(session_id).execute(&state.db).await?;
    }
    sqlx::query("UPDATE annotations SET ended_at_ms = COALESCE(ended_at_ms, ?) WHERE experiment_id = ? AND ended_at_ms IS NULL")
        .bind(ended_at_ms).bind(experiment_id).execute(&state.db).await?;
    sqlx::query("UPDATE experiments SET status = 'stopped', ended_at_ms = COALESCE(ended_at_ms, ?), client_ended_at_ms = COALESCE(client_ended_at_ms, ?) WHERE id = ? AND status = 'active'")
        .bind(ended_at_ms).bind(client_timestamp_ms).bind(experiment_id).execute(&state.db).await?;
    {
        let removed = remove_active_sessions(&state.sessions, |handle| {
            linked_ids.contains(&handle.session_id)
        })
        .await;
        remember_ended_sessions(state, removed);
    }
    state.clear_active_experiment(experiment_id).await;
    let report = json!({ "id": experiment_id, "ended_at_ms": ended_at_ms, "client_ended_at_ms": client_timestamp_ms, "closed_device_recordings": linked_ids, "status": "stopped" });
    publish_control_status(
        state,
        &format!("experiments/{experiment_id}"),
        "recording.stopped",
        report.clone(),
        true,
    )
    .await;
    Ok(report)
}

async fn start_evidence_session(
    state: &AppState,
    experiment_id: i64,
    request: CreateEvidenceSessionRequest,
) -> Result<Value> {
    let key = session_key(request.device_type.trim(), request.device_address.trim());
    if state.sessions.read().await.contains_key(&key) {
        return Err(anyhow!(
            "this device already has an active evidence session"
        ));
    }
    let device_result = sqlx::query(
        "INSERT INTO devices (device_type, device_address) VALUES (?, ?) ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)",
    )
    .bind(request.device_type.trim())
    .bind(request.device_address.trim())
    .execute(&state.db)
    .await?;
    let device_id = device_result.last_insert_id() as i64;
    let started_at_ms = now_ms();
    let session_result = sqlx::query(
        r#"
        INSERT INTO sessions
          (experiment_id, device_id, session_label, status, started_at_ms, client_started_at_ms,
           mqtt_broker_url, operator_label, notes)
        VALUES (?, ?, ?, 'active', ?, ?, ?, ?, ?)
        "#,
    )
    .bind(experiment_id)
    .bind(device_id)
    .bind(
        request
            .session_label
            .as_deref()
            .map(str::trim)
            .filter(|v| !v.is_empty()),
    )
    .bind(started_at_ms)
    .bind(request.client_timestamp_ms)
    .bind(&state.config.mqtt_broker_url)
    .bind(
        request
            .operator_label
            .as_deref()
            .map(str::trim)
            .filter(|v| !v.is_empty()),
    )
    .bind(
        request
            .notes
            .as_deref()
            .map(str::trim)
            .filter(|v| !v.is_empty()),
    )
    .execute(&state.db)
    .await?;
    let session_id = session_result.last_insert_id() as i64;
    let mut transaction = state.db.begin().await?;
    let measurement_config_id = insert_default_sensor_configuration(
        &mut transaction,
        session_id,
        device_id,
        request.device_type.trim(),
        started_at_ms,
    )
    .await?;
    transaction.commit().await?;
    state.sessions.write().await.insert(
        key,
        SessionHandle {
            device_id,
            session_id,
            measurement_config_id,
        },
    );
    let report = json!({
        "id": session_id,
        "experiment_id": experiment_id,
        "device_id": device_id,
        "device_type": request.device_type,
        "device_address": request.device_address,
        "sensor_configuration_id": measurement_config_id,
        "started_at_ms": started_at_ms,
        "client_started_at_ms": request.client_timestamp_ms,
        "status": "active"
    });
    publish_control_status(
        state,
        &format!("evidence-sessions/{session_id}"),
        "evidence_session.started",
        report.clone(),
        true,
    )
    .await;
    Ok(report)
}

async fn stop_evidence_session_capture(
    state: &AppState,
    session_id: i64,
    client_timestamp_ms: Option<i64>,
) -> Result<Value> {
    let ended_at_ms = now_ms();
    let result = sqlx::query("UPDATE sessions SET status = 'stopped', ended_at_ms = ?, client_ended_at_ms = ? WHERE id = ? AND status = 'active'")
        .bind(ended_at_ms).bind(client_timestamp_ms).bind(session_id).execute(&state.db).await?;
    if result.rows_affected() != 1 {
        return Err(anyhow!(
            "evidence session does not exist or is already stopped"
        ));
    }
    sqlx::query("UPDATE measurement_configs SET ended_at_ms = COALESCE(ended_at_ms, ?) WHERE session_id = ? AND ended_at_ms IS NULL")
        .bind(ended_at_ms).bind(session_id).execute(&state.db).await?;
    let removed =
        remove_active_sessions(&state.sessions, |handle| handle.session_id == session_id).await;
    remember_ended_sessions(state, removed);
    let report = json!({ "id": session_id, "ended_at_ms": ended_at_ms, "client_ended_at_ms": client_timestamp_ms, "status": "stopped" });
    publish_control_status(
        state,
        &format!("evidence-sessions/{session_id}"),
        "evidence_session.stopped",
        report.clone(),
        true,
    )
    .await;
    Ok(report)
}

async fn insert_annotation(
    state: &AppState,
    experiment_id: i64,
    request: CreateAnnotationRequest,
) -> Result<Value> {
    if let Some(session_id) = request.session_id {
        let belongs = sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM sessions WHERE id = ? AND experiment_id = ?",
        )
        .bind(session_id)
        .bind(experiment_id)
        .fetch_one(&state.db)
        .await?;
        if belongs != 1 {
            return Err(anyhow!("session_id does not belong to this experiment"));
        }
    }
    let server_received_at_ms = now_ms();
    let started_at_ms = request
        .started_at_ms
        .or(request.client_timestamp_ms)
        .unwrap_or(server_received_at_ms);
    if request
        .ended_at_ms
        .is_some_and(|ended| ended < started_at_ms)
    {
        return Err(anyhow!(
            "ended_at_ms must be greater than or equal to started_at_ms"
        ));
    }
    let result = sqlx::query(
        r#"
        INSERT INTO annotations
          (experiment_id, session_id, label_name, label_value, started_at_ms, ended_at_ms,
           client_timestamp_ms, client_monotonic_ns, server_received_at_ms, source,
           confidence, timing_uncertainty_ms, notes)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        "#,
    )
    .bind(experiment_id)
    .bind(request.session_id)
    .bind(request.label_name.trim())
    .bind(request.label_value.trim())
    .bind(started_at_ms)
    .bind(request.ended_at_ms)
    .bind(request.client_timestamp_ms)
    .bind(request.client_monotonic_ns)
    .bind(server_received_at_ms)
    .bind(
        request
            .source
            .as_deref()
            .map(str::trim)
            .filter(|v| !v.is_empty())
            .unwrap_or("manual_app"),
    )
    .bind(request.confidence)
    .bind(request.timing_uncertainty_ms)
    .bind(
        request
            .notes
            .as_deref()
            .map(str::trim)
            .filter(|v| !v.is_empty()),
    )
    .execute(&state.db)
    .await?;
    let annotation_id = result.last_insert_id() as i64;
    let report = json!({
        "id": annotation_id,
        "experiment_id": experiment_id,
        "session_id": request.session_id,
        "scope": if request.session_id.is_some() { "device_recording" } else { "experiment" },
        "applies_to_all_device_recordings": request.session_id.is_none(),
        "label_name": request.label_name,
        "label_value": request.label_value,
        "started_at_ms": started_at_ms,
        "ended_at_ms": request.ended_at_ms,
        "status": if request.ended_at_ms.is_some() { "complete" } else { "active" }
    });
    publish_control_status(
        state,
        &format!("annotations/{annotation_id}"),
        "annotation.created",
        report.clone(),
        false,
    )
    .await;
    Ok(report)
}

async fn end_annotation(
    state: &AppState,
    annotation_id: i64,
    client_timestamp_ms: Option<i64>,
) -> Result<Value> {
    let ended_at_ms = client_timestamp_ms.unwrap_or_else(now_ms);
    let result =
        sqlx::query("UPDATE annotations SET ended_at_ms = ? WHERE id = ? AND ended_at_ms IS NULL")
            .bind(ended_at_ms)
            .bind(annotation_id)
            .execute(&state.db)
            .await?;
    if result.rows_affected() != 1 {
        return Err(anyhow!("annotation does not exist or is already stopped"));
    }
    let report = json!({ "id": annotation_id, "ended_at_ms": ended_at_ms, "status": "complete" });
    publish_control_status(
        state,
        &format!("annotations/{annotation_id}"),
        "annotation.stopped",
        report.clone(),
        false,
    )
    .await;
    Ok(report)
}

async fn run_mqtt_ingester(state: AppState) -> Result<()> {
    let (host, port) = parse_mqtt_url(&state.config.mqtt_broker_url)?;
    let mut options = MqttOptions::new("wearable-backend", host, port);
    options.set_keep_alive(Duration::from_secs(30));
    // The default outgoing limit is 10 KiB, and the live summary with its traces
    // reached it, which stopped the ingester.
    options.set_max_packet_size(1024 * 1024, 1024 * 1024);
    if let (Some(username), Some(password)) = (
        state.config.mqtt_username.as_deref(),
        state.config.mqtt_password.as_deref(),
    ) {
        options.set_credentials(username, password);
    }

    let (client, mut eventloop) = AsyncClient::new(options, 64);
    *state.mqtt_status_client.write().await = Some(client.clone());
    client
        .subscribe(state.config.mqtt_topic_filter.clone(), QoS::AtLeastOnce)
        .await
        .context("subscribe to MQTT topic")?;
    info!(topic = %state.config.mqtt_topic_filter, "subscribed to MQTT");

    loop {
        match eventloop.poll().await {
            Ok(Event::Incoming(Incoming::Publish(publish))) => {
                let message = IngestMessage {
                    topic: publish.topic,
                    payload: publish.payload.to_vec(),
                    backend_received_ms: now_ms(),
                };
                // Waits when the queue is full, which slows the broker down
                // instead of overloading the database.
                if state.ingest_tx.send(message).await.is_err() {
                    return Err(anyhow!("ingest writer stopped"));
                }
            }
            Ok(_) => {}
            Err(err) => return Err(err.into()),
        }
    }
}

async fn publish_control_status(
    state: &AppState,
    topic_suffix: &str,
    event_type: &str,
    data: Value,
    retain: bool,
) {
    let client = state.mqtt_status_client.read().await.clone();
    let Some(client) = client else {
        warn!(event_type, "MQTT status publisher is not connected yet");
        return;
    };
    let topic = format!(
        "{}/{}",
        state.config.mqtt_status_topic_prefix.trim_end_matches('/'),
        topic_suffix.trim_start_matches('/')
    );
    let payload = json!({
        "schemaVersion": 1,
        "event": event_type,
        "serverTimestampMs": now_ms(),
        "data": data
    });
    if let Err(err) = client
        .publish(topic, QoS::AtLeastOnce, retain, payload.to_string())
        .await
    {
        warn!(event_type, error = ?err, "failed to publish MQTT control status");
    }
}

fn parse_mqtt_url(url: &str) -> Result<(String, u16)> {
    let stripped = url.strip_prefix("tcp://").unwrap_or(url);
    let (host, port) = stripped
        .rsplit_once(':')
        .ok_or_else(|| anyhow!("MQTT_BROKER_URL must include host and port"))?;
    Ok((host.to_string(), port.parse()?))
}

/// Whether a payload is a batch (a JSON array of packets) rather than one packet.
fn is_batch(payload: &[u8]) -> bool {
    payload.iter().find(|byte| !byte.is_ascii_whitespace()) == Some(&b'[')
}

/// Messages waiting to be stored. Bounded, so that when the database falls
/// behind the MQTT loop waits (and the broker holds messages for us) instead of
/// the backend piling up thousands of tasks that each wait for a connection.
const INGEST_QUEUE: usize = 4096;
/// Packets written in one transaction at most.
const INGEST_BATCH_MAX_PACKETS: usize = 500;
/// How long a part-filled batch waits for more messages before it is written.
const INGEST_BATCH_WAIT_MS: u64 = 50;
/// Rows per multi-row INSERT into stream_samples (21 placeholders each, well
/// under MySQL's 65,535-placeholder limit).
const SAMPLE_ROWS_PER_INSERT: usize = 1000;

struct IngestMessage {
    topic: String,
    payload: Vec<u8>,
    backend_received_ms: i64,
}

/// One MQTT message is either a packet or a batch of them; either way it becomes
/// a list of packets, kept in the order they arrived.
/// Largest decompressed batch accepted (a compressed message can expand a lot).
const MAX_DECOMPRESSED_BYTES: u64 = 32 * 1024 * 1024;

fn parse_payload(payload: &[u8]) -> Result<Vec<SensorPacket>> {
    if payload.starts_with(&[0x1f, 0x8b]) {
        use std::io::Read;
        let mut decoded = Vec::new();
        flate2::read::GzDecoder::new(payload)
            .take(MAX_DECOMPRESSED_BYTES)
            .read_to_end(&mut decoded)
            .context("decompress gzip batch")?;
        return parse_payload(&decoded);
    }
    if !is_batch(payload) {
        return Ok(vec![
            serde_json::from_slice(payload).context("parse sensor packet JSON")?,
        ]);
    }
    let values: Vec<Value> =
        serde_json::from_slice(payload).context("parse sensor packet batch JSON")?;
    let mut packets = Vec::with_capacity(values.len());
    for value in values {
        // A bad packet in a batch is skipped; the rest are kept.
        match serde_json::from_value::<SensorPacket>(value) {
            Ok(packet) => packets.push(packet),
            Err(err) => warn!(error = %err, "skipping unparseable packet in batch"),
        }
    }
    Ok(packets)
}

/// The single writer: drains the ingest queue and stores what it finds in as
/// few transactions as possible.
///
/// Previously every message (and every packet in a batch) was its own task,
/// with its own owner lookup and its own transaction and commit. Two earbuds
/// streaming every sensor were enough to exhaust the connection pool on the
/// 1 GB server: requests waited 13 s for a connection, sign-in checks timed out
/// and samples were dropped. One writer with one commit per batch keeps the
/// database's work proportional to the data, not to the number of messages.
async fn run_ingest_writer(state: AppState, mut rx: tokio::sync::mpsc::Receiver<IngestMessage>) {
    let mut pending = pending::PendingBuffer::new();
    let mut tick = tokio::time::interval(Duration::from_millis(PENDING_POLL_MS));
    tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut reject_log = pending::LogLimiter::new(pending::LOG_INTERVAL);
    let mut loss_log = pending::LogLimiter::new(pending::LOG_INTERVAL);
    let counters = state.ingest_counters.clone();

    loop {
        // Wake on a message, or on the tick so that held packets are replayed
        // and expired even when nothing new arrives.
        let first = tokio::select! {
            received = rx.recv() => match received {
                Some(message) => Some(message),
                None => break,
            },
            _ = tick.tick() => None,
        };
        let mut messages = Vec::new();
        if let Some(first) = first {
            let mut packet_estimate = if is_batch(&first.payload) { 25 } else { 1 };
            messages.push(first);
            let deadline =
                tokio::time::Instant::now() + Duration::from_millis(INGEST_BATCH_WAIT_MS);
            while packet_estimate < INGEST_BATCH_MAX_PACKETS {
                match tokio::time::timeout_at(deadline, rx.recv()).await {
                    Ok(Some(message)) => {
                        packet_estimate += if is_batch(&message.payload) { 25 } else { 1 };
                        messages.push(message);
                    }
                    Ok(None) | Err(_) => break,
                }
            }
        }

        let mut prepared = Vec::new();
        let mut full_drops = 0u64;
        // Packets held earlier go first, so they are stored before newer ones.
        if !pending.is_empty() {
            replay_ready(&state, &mut pending, &mut prepared, &mut reject_log).await;
        }
        for message in &messages {
            let packets = match parse_payload(&message.payload) {
                Ok(packets) => packets,
                Err(err) => {
                    warn!(topic = %message.topic, error = ?err, "failed to parse MQTT payload");
                    continue;
                }
            };
            let owner = topic_claimed_owner(&message.topic);
            for packet in packets {
                let key = session_key(&packet.device, &packet.address);
                let (handle, origin) = match lookup_session(&state, &key).await {
                    SessionLookup::Active(handle) => (handle, Origin::Direct),
                    SessionLookup::JustEnded(handle) => (handle, Origin::AfterStop),
                    SessionLookup::None => {
                        full_drops += hold_packet(
                            &mut pending,
                            &counters,
                            owner,
                            key,
                            &message.topic,
                            &packet,
                            message.backend_received_ms,
                        );
                        continue;
                    }
                };
                if pending.has_pair(owner, &key) {
                    // The recording started between two checks: release what
                    // was held for it before this newer packet.
                    replay_ready(&state, &mut pending, &mut prepared, &mut reject_log).await;
                }
                match prepare_packet(
                    &state,
                    &message.topic,
                    packet,
                    message.backend_received_ms,
                    handle,
                    origin,
                )
                .await
                {
                    Ok(item) => prepared.push(item),
                    Err(err) => note_rejected(&counters, &mut reject_log, &message.topic, &err),
                }
            }
        }

        // Held packets that waited the whole grace are lost, but counted and logged.
        let now = std::time::Instant::now();
        let expired = pending.expire(now);
        pending::IngestCounters::add(&counters.dropped_no_session, expired);
        if let Some(total) = loss_log.record(now, expired + full_drops) {
            warn!(
                dropped = total,
                pending = pending.len(),
                "packets with no recording were dropped (no session within {} s, or buffer full); see the ingest counters in /health",
                pending::PENDING_GRACE.as_secs()
            );
        }
        counters
            .pending_packets
            .store(pending.len() as u64, std::sync::atomic::Ordering::Relaxed);
        counters
            .pending_bytes
            .store(pending.bytes() as u64, std::sync::atomic::Ordering::Relaxed);

        if prepared.is_empty() {
            continue;
        }

        match store_packets(&state, &mut prepared).await {
            Ok(()) => {
                count_stored(&counters, &prepared);
                push_live(&state, &prepared).await;
            }
            Err(err) => {
                // Something in the batch was refused. Store the packets one at a
                // time so that one bad row does not lose the rest.
                warn!(packets = prepared.len(), error = ?err, "batch insert failed; storing packets one by one");
                for item in prepared.iter_mut() {
                    let mut single = vec![std::mem::replace(item, PreparedPacket::placeholder())];
                    match store_packets(&state, &mut single).await {
                        Ok(()) => {
                            count_stored(&counters, &single);
                            push_live(&state, &single).await;
                        }
                        Err(err) => {
                            pending::IngestCounters::add(&counters.rejected_other, 1);
                            warn!(topic = %single[0].topic, error = ?err, "failed to ingest MQTT payload");
                        }
                    }
                }
            }
        }
    }
}

/// How often the writer wakes without a message to replay and expire held packets.
const PENDING_POLL_MS: u64 = 500;

/// Where a stored packet came from, for the counters and for live analysis.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Origin {
    /// Arrived while its recording was active.
    Direct,
    /// Held because no recording existed, stored when one started.
    Replayed,
    /// Arrived after its recording ended (within the grace).
    AfterStop,
}

/// Hold a packet that has no recording yet. Returns how many older packets
/// were dropped to make room (already added to the counters).
fn hold_packet(
    pending: &mut pending::PendingBuffer,
    counters: &pending::IngestCounters,
    owner: Option<i64>,
    key: String,
    topic: &str,
    packet: &SensorPacket,
    backend_received_ms: i64,
) -> u64 {
    let json = match serde_json::to_string(packet) {
        Ok(json) => json,
        Err(err) => {
            warn!(error = ?err, "could not hold a packet");
            pending::IngestCounters::add(&counters.rejected_other, 1);
            return 0;
        }
    };
    let dropped = pending.hold(
        std::time::Instant::now(),
        owner,
        key,
        topic.to_string(),
        json,
        backend_received_ms,
    );
    pending::IngestCounters::add(&counters.held, 1);
    pending::IngestCounters::add(&counters.dropped_buffer_full, dropped);
    dropped
}

fn note_rejected(
    counters: &pending::IngestCounters,
    limiter: &mut pending::LogLimiter,
    topic: &str,
    err: &anyhow::Error,
) {
    pending::IngestCounters::add(&counters.rejected_other, 1);
    if let Some(total) = limiter.record(std::time::Instant::now(), 1) {
        warn!(topic = %topic, error = ?err, rejected_since_last_line = total, "failed to ingest MQTT payload");
    }
}

fn count_stored(counters: &pending::IngestCounters, items: &[PreparedPacket]) {
    for item in items {
        let counter = match item.origin {
            Origin::Direct => &counters.accepted,
            Origin::Replayed => &counters.replayed,
            Origin::AfterStop => &counters.accepted_after_stop,
        };
        pending::IngestCounters::add(counter, 1);
    }
}

/// Whether a recording may take packets published under this topic owner: a
/// topic without a `u<id>` segment is unattributed and always may; otherwise the
/// recording must belong to that user (or to nobody, as in an untenanted
/// database). A failed lookup says no, and is retried on the next tick.
async fn session_accepts_owner(
    state: &AppState,
    session: &SessionHandle,
    owner: Option<i64>,
) -> bool {
    let Some(claimed) = owner else { return true };
    match session_owner(state, session.session_id).await {
        Ok(Some(actual)) => actual == claimed,
        Ok(None) => true,
        Err(_) => false,
    }
}

/// Move held packets whose user and device now have a recording into `prepared`.
async fn replay_ready(
    state: &AppState,
    pending: &mut pending::PendingBuffer,
    prepared: &mut Vec<PreparedPacket>,
    reject_log: &mut pending::LogLimiter,
) {
    let mut ready: HashMap<Option<i64>, HashMap<String, SessionHandle>> = HashMap::new();
    for (owner, key) in pending.pairs() {
        let handle = match lookup_session(state, &key).await {
            SessionLookup::Active(handle) | SessionLookup::JustEnded(handle) => handle,
            SessionLookup::None => continue,
        };
        if session_accepts_owner(state, &handle, owner).await {
            ready.entry(owner).or_default().insert(key, handle);
        }
    }
    if ready.is_empty() {
        return;
    }
    let held = pending
        .take_ready(|owner, key| ready.get(&owner).is_some_and(|keys| keys.contains_key(key)));
    for item in held {
        let Some(handle) = ready
            .get(&item.owner)
            .and_then(|keys| keys.get(&item.device_key))
            .cloned()
        else {
            continue;
        };
        let packet = match serde_json::from_str::<SensorPacket>(&item.packet_json) {
            Ok(packet) => packet,
            Err(err) => {
                warn!(error = ?err, "held packet could not be read back");
                pending::IngestCounters::add(&state.ingest_counters.rejected_other, 1);
                continue;
            }
        };
        match prepare_packet(
            state,
            &item.topic,
            packet,
            item.backend_received_ms,
            handle,
            Origin::Replayed,
        )
        .await
        {
            Ok(prepared_item) => prepared.push(prepared_item),
            Err(err) => note_rejected(&state.ingest_counters, reject_log, &item.topic, &err),
        }
    }
}

/// A packet checked against its recording and ready to store.
struct PreparedPacket {
    topic: String,
    packet: SensorPacket,
    session: SessionHandle,
    payload_json: String,
    samples: Vec<CanonicalSample>,
    backend_received_ms: i64,
    received_ms: i64,
    device_timestamp_ms: Option<i64>,
    schema_version: i32,
    source_timestamp_value: Option<i64>,
    source_timestamp_unit: Option<String>,
    configured_sample_rate_hz: Option<f64>,
    effective_sample_rate_hz: Option<f64>,
    sequence_number: Option<i64>,
    sample_index: Option<i64>,
    clock_source: Option<String>,
    clock_quality: Option<String>,
    raw_packet_id: i64,
    origin: Origin,
}

impl PreparedPacket {
    /// An empty value to move a packet out of a list with mem::replace.
    fn placeholder() -> Self {
        PreparedPacket {
            topic: String::new(),
            packet: SensorPacket {
                schema_version: None,
                device: String::new(),
                address: String::new(),
                packet_type: String::new(),
                timestamp: None,
                server_timestamp: None,
                sample_rate_hz: None,
                timing: None,
                data: Map::new(),
            },
            session: SessionHandle { device_id: 0, session_id: 0, measurement_config_id: None },
            payload_json: String::new(),
            samples: Vec::new(),
            backend_received_ms: 0,
            received_ms: 0,
            device_timestamp_ms: None,
            schema_version: 1,
            source_timestamp_value: None,
            source_timestamp_unit: None,
            configured_sample_rate_hz: None,
            effective_sample_rate_hz: None,
            sequence_number: None,
            sample_index: None,
            clock_source: None,
            clock_quality: None,
            raw_packet_id: 0,
            origin: Origin::Direct,
        }
    }
}

async fn prepare_packet(
    state: &AppState,
    topic: &str,
    packet: SensorPacket,
    backend_received_ms: i64,
    session: SessionHandle,
    origin: Origin,
) -> Result<PreparedPacket> {
    let timing = packet.timing.as_ref();
    let received_ms = timing
        .and_then(|value| value.android_received_timestamp_ms)
        .or(packet.server_timestamp)
        .or(packet.timestamp)
        .unwrap_or(backend_received_ms);
    let source_timestamp_unit = timing.and_then(|value| value.source_timestamp_unit.clone());
    let source_timestamp_value = timing.and_then(|value| value.source_timestamp);
    let device_timestamp_ms = if source_timestamp_unit.as_deref() == Some("ms") {
        source_timestamp_value
    } else {
        None
    };
    let schema_version = packet.schema_version.unwrap_or(1);
    let configured_sample_rate_hz = timing
        .and_then(|value| value.configured_sample_rate_hz)
        .or(packet.sample_rate_hz);
    let effective_sample_rate_hz = timing.and_then(|value| value.effective_sample_rate_hz);
    let sequence_number = timing.and_then(|value| value.sequence_number);
    let sample_index = timing.and_then(|value| value.sample_index);
    let clock_source = timing.and_then(|value| value.clock_source.clone());
    let clock_quality = timing.and_then(|value| value.clock_quality.clone());

    // Defence in depth behind the broker ACL: a packet published under
    // sensors/u<id>/... must belong to the session's owner. The broker should
    // already have refused a participant publishing into another's namespace,
    // but a misconfigured or reloaded broker must not silently let evidence be
    // attributed to the wrong person.
    if let Some(claimed) = topic_claimed_owner(topic) {
        if let Some(owner) = session_owner(state, session.session_id).await? {
            if owner != claimed {
                return Err(anyhow!(
                    "topic claims user {claimed} but the active recording belongs to user {owner}"
                ));
            }
        }
    }

    let payload_json = serde_json::to_string(&packet)?;
    let samples = canonical_stream_samples(&packet);
    Ok(PreparedPacket {
        topic: topic.to_string(),
        packet,
        session,
        payload_json,
        samples,
        backend_received_ms,
        received_ms,
        device_timestamp_ms,
        schema_version,
        source_timestamp_value,
        source_timestamp_unit,
        configured_sample_rate_hz,
        effective_sample_rate_hz,
        sequence_number,
        sample_index,
        clock_source,
        clock_quality,
        raw_packet_id: 0,
        origin,
    })
}

/// Store packets and their stream samples in one transaction: one row per raw
/// packet (each needs its own id, which its samples point to), then all their
/// samples in multi-row inserts, then one commit.
async fn store_packets(state: &AppState, items: &mut [PreparedPacket]) -> Result<()> {
    let mut tx = state.db.begin().await?;

    for item in items.iter_mut() {
        let result = sqlx::query(
            r#"
            INSERT INTO raw_packets
              (session_id, device_id, measurement_config_id, packet_type, source_topic, device_timestamp_ms,
               received_timestamp_ms, packet_sample_rate_hz, sequence_number, payload, parser_name, parser_version,
               schema_version, source_timestamp_value, source_timestamp_unit, android_received_timestamp_ms,
               backend_received_timestamp_ms, configured_sample_rate_hz, effective_sample_rate_hz, clock_source, clock_quality)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'backend-mqtt-json', '0.2.0', ?, ?, ?, ?, ?, ?, ?, ?, ?)
            "#,
        )
        .bind(item.session.session_id)
        .bind(item.session.device_id)
        .bind(item.session.measurement_config_id)
        .bind(&item.packet.packet_type)
        .bind(&item.topic)
        .bind(item.device_timestamp_ms)
        .bind(item.backend_received_ms)
        .bind(item.packet.sample_rate_hz)
        .bind(item.sequence_number)
        .bind(&item.payload_json)
        .bind(item.schema_version)
        .bind(item.source_timestamp_value)
        .bind(item.source_timestamp_unit.clone())
        .bind(item.received_ms)
        .bind(item.backend_received_ms)
        .bind(item.configured_sample_rate_hz)
        .bind(item.effective_sample_rate_hz)
        .bind(item.clock_source.clone())
        .bind(item.clock_quality.clone())
        .execute(&mut *tx)
        .await?;
        item.raw_packet_id = result.last_insert_id() as i64;
    }

    let rows: Vec<(&PreparedPacket, &CanonicalSample)> = items
        .iter()
        .flat_map(|item| item.samples.iter().map(move |sample| (item, sample)))
        .collect();
    for chunk in rows.chunks(SAMPLE_ROWS_PER_INSERT) {
        let mut builder = sqlx::QueryBuilder::<sqlx::MySql>::new(
            "INSERT INTO stream_samples \
              (session_id, device_id, raw_packet_id, measurement_config_id, stream_name, device_timestamp_ms, \
               received_timestamp_ms, sample_rate_hz, channel_values, channel_units, \
               schema_version, source_timestamp_value, source_timestamp_unit, android_received_timestamp_ms, \
               backend_received_timestamp_ms, sequence_number, sample_index, configured_sample_rate_hz, \
               effective_sample_rate_hz, clock_source, clock_quality) ",
        );
        let encoded: Vec<(String, String)> = chunk
            .iter()
            .map(|(_, sample)| {
                Ok((
                    serde_json::to_string(&sample.channel_values)?,
                    serde_json::to_string(&sample.channel_units)?,
                ))
            })
            .collect::<Result<_>>()?;
        builder.push_values(chunk.iter().zip(encoded), |mut row, ((item, sample), (values, units))| {
            row.push_bind(item.session.session_id)
                .push_bind(item.session.device_id)
                .push_bind(item.raw_packet_id)
                .push_bind(item.session.measurement_config_id)
                .push_bind(sample.stream_name)
                .push_bind(item.device_timestamp_ms)
                .push_bind(item.backend_received_ms)
                .push_bind(item.packet.sample_rate_hz)
                .push_bind(values)
                .push_bind(units)
                .push_bind(item.schema_version)
                .push_bind(item.source_timestamp_value)
                .push_bind(item.source_timestamp_unit.clone())
                .push_bind(item.received_ms)
                .push_bind(item.backend_received_ms)
                .push_bind(item.sequence_number)
                .push_bind(item.sample_index)
                .push_bind(item.configured_sample_rate_hz)
                .push_bind(item.effective_sample_rate_hz)
                .push_bind(item.clock_source.clone())
                .push_bind(item.clock_quality.clone());
        });
        builder.build().execute(&mut *tx).await?;
    }

    tx.commit().await?;
    Ok(())
}

/// Live analysis only ever sees samples that were stored, so what the console
/// and phone are told always corresponds to recorded evidence.
async fn push_live(state: &AppState, items: &[PreparedPacket]) {
    for item in items {
        // A recording that has ended is not fed any more; its late packets are
        // stored as evidence but do not reopen its live analysis.
        if item.origin == Origin::AfterStop {
            continue;
        }
        let session_id = item.session.session_id;
        if !state.live.knows_session(session_id) {
            let (owner_user_id, study_id) = live_session_attribution(state, session_id).await;
            state.live.register_session(live::SessionMeta {
                session_id,
                device_type: item.packet.device.clone(),
                device_address: item.packet.address.clone(),
                owner_user_id,
                study_id,
            });
        }
        for sample in &item.samples {
            if let Value::Object(channels) = &sample.channel_values {
                state.live.push(
                    session_id,
                    sample.stream_name,
                    item.backend_received_ms,
                    item.sample_index.or(item.sequence_number),
                    item.configured_sample_rate_hz,
                    channels,
                );
            }
        }
    }
}

/// Owner and study of a device recording, for scoping its live summary.
async fn live_session_attribution(state: &AppState, session_id: i64) -> (Option<i64>, Option<i64>) {
    if !state.tenancy_enabled {
        return (None, None);
    }
    match sqlx::query("SELECT owner_user_id, study_id FROM sessions WHERE id = ?")
        .bind(session_id)
        .fetch_optional(&state.db)
        .await
    {
        Ok(Some(row)) => (
            row.try_get::<Option<i64>, _>("owner_user_id").ok().flatten(),
            row.try_get::<Option<i64>, _>("study_id").ok().flatten(),
        ),
        Ok(None) => (None, None),
        Err(err) => {
            warn!(session_id, error = ?err, "could not attribute live session");
            (None, None)
        }
    }
}

/// Summaries are recorded every few ticks rather than every second; alerts
/// are always recorded.
const LIVE_SUMMARY_PERSIST_EVERY: u64 = 5;

/// Once a second, summarise the active recordings, then record and publish
/// what changed. Each participant receives only their own recordings, on the
/// status namespace the broker ACL already lets them read.
async fn run_live_analysis(state: AppState) {
    let mut interval = tokio::time::interval(Duration::from_millis(live::TICK_MS));
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut tick: u64 = 0;
    loop {
        interval.tick().await;
        tick += 1;
        let active: HashSet<i64> = state
            .sessions
            .read()
            .await
            .values()
            .map(|handle| handle.session_id)
            .collect();
        let output = state.live.tick(now_ms(), &active);

        for alert in &output.alerts {
            if state.live_events_enabled {
                if let Err(err) = insert_live_alert(&state.db, alert).await {
                    warn!(error = ?err, "failed to record live alert");
                }
            }
            publish_control_status(
                &state,
                &live_status_suffix(alert.owner_user_id, "alerts"),
                &format!("live.alert.{}", alert.state),
                serde_json::to_value(alert).unwrap_or(Value::Null),
                false,
            )
            .await;
        }

        let mut by_owner: HashMap<Option<i64>, Vec<&live::SessionSummary>> = HashMap::new();
        for summary in &output.summaries {
            by_owner.entry(summary.meta.owner_user_id).or_default().push(summary);
        }
        for (owner, summaries) in by_owner {
            publish_control_status(
                &state,
                &live_status_suffix(owner, "summary"),
                "live.summary",
                json!({ "sessions": summaries }),
                false,
            )
            .await;
        }

        if state.live_events_enabled && tick % LIVE_SUMMARY_PERSIST_EVERY == 0 {
            for summary in &output.summaries {
                if let Err(err) = insert_live_summary(&state.db, summary).await {
                    warn!(error = ?err, "failed to record live summary");
                }
            }
        }
        if state.derived_enabled && tick % derived::LIVE_ROW_EVERY_TICKS == 0 && !output.summaries.is_empty() {
            let rows: Vec<derived::DerivedRow> = output
                .summaries
                .iter()
                .map(|summary| derived::live_row(summary, None))
                .collect();
            if let Err(err) = derived::insert_rows(&state.db, &rows).await {
                warn!(error = ?err, "failed to record derived windows");
            }
        }
    }
}

/// Run the model worker beside the live algorithm. Every second, take the
/// windows that are due (one per recording every five seconds), send each to
/// the worker with the configured head, and attach the heart rate it returns
/// to the recording's live summary, which is published and stored with it.
/// A slow or missing worker only delays model estimates; the live algorithm,
/// ingestion and alerts never wait on it.
async fn run_live_model(state: AppState, head_id: String) {
    let client = match reqwest::Client::builder()
        .timeout(Duration::from_secs(3))
        .build()
    {
        Ok(client) => client,
        Err(err) => {
            error!(error = ?err, "live model disabled: could not build HTTP client");
            return;
        }
    };
    let url = format!("{}/infer", state.config.ml_inference_url.trim_end_matches('/'));
    info!(%url, %head_id, "live model enabled");
    let mut interval = tokio::time::interval(Duration::from_millis(live::TICK_MS));
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut failures: u64 = 0;
    loop {
        interval.tick().await;
        for request in state.live.model_windows(now_ms()) {
            match live_model_estimate(&client, &url, &head_id, &request).await {
                Ok(estimate) => {
                    if failures > 0 {
                        info!(failures, "live model worker reachable again");
                        failures = 0;
                    }
                    if state.derived_enabled {
                        let row = derived::model_row(request.session_id, &estimate, None);
                        if let Err(err) = derived::insert_rows(&state.db, &[row]).await {
                            warn!(error = ?err, "failed to record model window");
                        }
                    }
                    state.live.set_model_estimate(request.session_id, estimate);
                }
                Err(err) => {
                    // Log the first failure and then once a minute, not every window.
                    if failures % 12 == 0 {
                        warn!(error = %err, failures, "live model estimate failed");
                    }
                    failures += 1;
                }
            }
        }
    }
}

/// `LIVE_ACTIVITY_MODEL`: a head id turns the activity pilot on; unset, empty, `off`, `false`,
/// `0` or `none` leave it off.
fn activity_model_from(value: Option<&str>) -> Option<String> {
    let value = value?.trim();
    match value.to_ascii_lowercase().as_str() {
        "" | "off" | "false" | "0" | "none" => None,
        _ => Some(value.to_string()),
    }
}

/// Run the optional activity worker beside the live algorithm: every second take the
/// accelerometer windows that are due (one per recording every five seconds), send each to
/// the MOMENT worker with the configured head, and attach the label to the recording's live
/// summary. A missing, slow or erroring worker only means no `activity` field (a stale
/// estimate expires after 15 s); ingestion, the live algorithm, alerts and the heart-rate
/// model never wait on it. Estimates are shown live only and are not stored as derived rows.
async fn run_live_activity(state: AppState, head_id: String) {
    let client = match reqwest::Client::builder().timeout(Duration::from_secs(3)).build() {
        Ok(client) => client,
        Err(err) => {
            error!(error = ?err, "live activity disabled: could not build HTTP client");
            return;
        }
    };
    let url = format!("{}/infer", state.config.live_activity_url.trim_end_matches('/'));
    info!(%url, %head_id, "live activity pilot enabled");
    let mut interval = tokio::time::interval(Duration::from_millis(live::TICK_MS));
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut failures: u64 = 0;
    loop {
        interval.tick().await;
        for request in state.live.activity_windows(now_ms()) {
            match live_activity_estimate(&client, &url, &head_id, &request).await {
                Ok(estimate) => {
                    if failures > 0 {
                        info!(failures, "live activity worker reachable again");
                        failures = 0;
                    }
                    state.live.set_activity_estimate(request.session_id, estimate);
                }
                Err(err) => {
                    if failures % 12 == 0 {
                        warn!(error = %err, failures, "live activity estimate failed");
                    }
                    failures += 1;
                }
            }
        }
    }
}

async fn live_activity_estimate(
    client: &reqwest::Client,
    url: &str,
    head_id: &str,
    request: &live::ActivityRequest,
) -> Result<live::ActivityEstimate> {
    let response = client
        .post(url)
        .json(&json!({
            "accel": request.values,
            "sample_rate": request.fs_hz,
            "window_duration_s": live::ACTIVITY_WINDOW_S,
            "head_id": head_id,
            "device_type": request.device_type,
            "omit_embedding": true,
        }))
        .send()
        .await?;
    let status = response.status();
    let body: Value = response.json().await?;
    if !status.is_success() {
        return Err(anyhow!(
            "worker returned {status}: {}",
            body.get("error").and_then(Value::as_str).unwrap_or("no detail")
        ));
    }
    live::activity_from_response(&body, head_id, request).map_err(|err| anyhow!(err))
}

const COMMAND_USAGE: &str = "usage:
  wearable-backend                                   run the server
  wearable-backend recompute <recording id> [--no-model]
      recompute derived windows from stored samples; prints the run
  wearable-backend export <experiment id> <out.zip> [--raw]
      write the experiment's dataset export
  wearable-backend set-platform-role <email> <platform_admin|researcher|participant>
      grant a platform role; how the first admin of a new instance is made
  wearable-backend erase-participant <user id> (--study <id> | --all-studies) [--dry-run] [--confirm] [--reason <text>]
      permanently delete a participant's recordings and derived data, logged
  wearable-backend retention-report
      studies past their data_retention_until date that still hold recordings";

/// Command-line access to recompute and export, for someone with access to
/// the server; the HTTP endpoints do the same for a signed-in researcher.
async fn run_command(config: &Config, db: &MySqlPool, args: &[String]) -> Result<()> {
    let id = |position: usize| -> Result<i64> {
        args.get(position)
            .and_then(|value| value.parse().ok())
            .ok_or_else(|| anyhow!("{COMMAND_USAGE}"))
    };
    match args[0].as_str() {
        "recompute" => {
            let session_id = id(1)?;
            if !(has_table(db, "derived_windows").await? && has_table(db, "derived_runs").await?) {
                return Err(anyhow!("apply backend/migrations/20261107_add_derived_windows.sql first"));
            }
            let model = model_client(config, !args.iter().any(|a| a == "--no-model"))?;
            let (run_id, _) = create_derived_run(db, session_id, model.as_ref()).await?;
            let outcome = derived::recompute(db, session_id, run_id, model.as_ref()).await;
            finish_derived_run(db, run_id, &outcome).await?;
            outcome?;
            println!("{}", serde_json::to_string_pretty(&derived_run_json(db, run_id).await?)?);
            Ok(())
        }
        "export" => {
            let experiment_id = id(1)?;
            let out = args.get(2).ok_or_else(|| anyhow!("{COMMAND_USAGE}"))?;
            let options = export::ExportOptions {
                include_raw_packets: args.iter().any(|a| a == "--raw"),
                backend_version: backend_version(),
                exported_at_ms: now_ms(),
            };
            let bytes = export::export_experiment(db, experiment_id, &options).await?;
            std::fs::write(out, &bytes)?;
            println!("wrote {out} ({} bytes)", bytes.len());
            Ok(())
        }
        "set-platform-role" => {
            let email = args.get(1).ok_or_else(|| anyhow!("{COMMAND_USAGE}"))?;
            let role = args.get(2).ok_or_else(|| anyhow!("{COMMAND_USAGE}"))?;
            let result = studies::set_platform_role_by_email(db, email, role).await?;
            println!("{}", serde_json::to_string_pretty(&result)?);
            Ok(())
        }
        "erase-participant" => {
            let user_id = id(1)?;
            let flag_value = |flag: &str| {
                args.iter()
                    .position(|a| a == flag)
                    .and_then(|i| args.get(i + 1))
                    .cloned()
            };
            let scope = match (flag_value("--study"), args.iter().any(|a| a == "--all-studies")) {
                (Some(study), false) => erasure::Scope::Study(
                    study.parse().map_err(|_| anyhow!("{COMMAND_USAGE}"))?,
                ),
                (None, true) => erasure::Scope::AllStudies,
                _ => return Err(anyhow!("give exactly one of --study <id> or --all-studies
{COMMAND_USAGE}")),
            };
            let dry_run = args.iter().any(|a| a == "--dry-run");
            if !dry_run && !args.iter().any(|a| a == "--confirm") {
                return Err(anyhow!("erasure is permanent: run with --dry-run first, then add --confirm"));
            }
            let reason = flag_value("--reason");
            let result = erasure::erase(db, user_id, scope, None, reason.as_deref(), dry_run).await?;
            println!("{}", serde_json::to_string_pretty(&result)?);
            Ok(())
        }
        "retention-report" => {
            let rows = sqlx::query(
                "SELECT s.id, s.name, CAST(s.data_retention_until AS CHAR) AS until_date,                         (SELECT COUNT(*) FROM sessions r WHERE r.study_id = s.id) AS recordings                  FROM studies s                  WHERE s.data_retention_until IS NOT NULL AND s.data_retention_until < CURDATE()                  ORDER BY s.data_retention_until",
            )
            .fetch_all(db)
            .await?;
            let mut overdue = Vec::new();
            for row in rows {
                let recordings: i64 = row.try_get("recordings")?;
                if recordings > 0 {
                    overdue.push(json!({
                        "study_id": row.try_get::<i64, _>("id")?,
                        "name": row.try_get::<String, _>("name")?,
                        "data_retention_until": row.try_get::<Option<String>, _>("until_date")?,
                        "recordings": recordings,
                    }));
                }
            }
            println!("{}", serde_json::to_string_pretty(&json!({ "past_retention": overdue }))?);
            Ok(())
        }
        _ => Err(anyhow!("{COMMAND_USAGE}")),
    }
}

fn backend_version() -> String {
    format!("{} ({})", env!("CARGO_PKG_VERSION"), option_env!("GIT_SHA").unwrap_or("unknown commit"))
}

/// The model worker client for a recompute, when wanted and configured.
fn model_client(config: &Config, wanted: bool) -> Result<Option<derived::ModelClient>> {
    match (wanted && config.ml_inference_enabled, &config.ml_live_head_id) {
        (true, Some(head_id)) => Ok(Some(derived::ModelClient {
            client: reqwest::Client::builder().timeout(Duration::from_secs(5)).build()?,
            url: format!("{}/infer", config.ml_inference_url.trim_end_matches('/')),
            head_id: head_id.clone(),
        })),
        _ => Ok(None),
    }
}

/// Record a recompute as running, with the methods it will apply.
async fn create_derived_run(
    db: &MySqlPool,
    session_id: i64,
    model: Option<&derived::ModelClient>,
) -> Result<(i64, Vec<Value>)> {
    let mut methods = vec![json!({ "method_id": derived::LIVE_METHOD_ID, "method_version": live::RECIPE_VERSION })];
    if let Some(model) = model {
        methods.push(json!({ "method_id": format!("papagei_s+{}", model.head_id), "worker": model.url }));
    }
    let run_id = sqlx::query(
        "INSERT INTO derived_runs (session_id, run_kind, methods, status, started_at_ms) \
         VALUES (?, 'recompute', ?, 'running', ?)",
    )
    .bind(session_id)
    .bind(Value::Array(methods.clone()).to_string())
    .bind(now_ms())
    .execute(db)
    .await?
    .last_insert_id() as i64;
    Ok((run_id, methods))
}

async fn finish_derived_run(
    db: &MySqlPool,
    run_id: i64,
    outcome: &Result<derived::RecomputeOutcome>,
) -> Result<()> {
    match outcome {
        Ok(outcome) => {
            sqlx::query(
                "UPDATE derived_runs SET status = 'completed', evidence_rows = ?, evidence_sha256 = ?, \
                 window_count = ?, completed_at_ms = ? WHERE id = ?",
            )
            .bind(outcome.evidence_rows)
            .bind(&outcome.evidence_sha256)
            .bind(outcome.window_count)
            .bind(now_ms())
            .bind(run_id)
            .execute(db)
            .await?;
        }
        Err(err) => {
            sqlx::query("UPDATE derived_runs SET status = 'failed', error = ?, completed_at_ms = ? WHERE id = ?")
                .bind(err.to_string())
                .bind(now_ms())
                .bind(run_id)
                .execute(db)
                .await?;
        }
    }
    Ok(())
}

/// Whether this caller may read a device recording: through its experiment,
/// or, for a recording made before experiments existed, only with full scope.
async fn may_read_session(state: &AppState, auth: &AuthContext, session_id: i64) -> Result<bool> {
    let experiment_id: Option<Option<i64>> =
        sqlx::query_scalar("SELECT experiment_id FROM sessions WHERE id = ?")
            .bind(session_id)
            .fetch_optional(&state.db)
            .await?;
    match experiment_id {
        None => Ok(false),
        Some(Some(experiment_id)) => may_read_experiment(state, auth, experiment_id).await,
        Some(None) => Ok(visible_scope(state, auth).is_none()),
    }
}

fn not_found(what: &str) -> Response {
    (StatusCode::NOT_FOUND, Json(json!({ "error": format!("{what} not found") }))).into_response()
}

fn internal_error(context: &str, err: anyhow::Error) -> Response {
    error!(error = ?err, "{context}");
    (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": context }))).into_response()
}

fn derived_unavailable() -> Response {
    (
        StatusCode::SERVICE_UNAVAILABLE,
        Json(json!({
            "error": "derived results are not enabled on this deployment",
            "detail": "apply backend/migrations/20261107_add_derived_windows.sql and restart"
        })),
    )
        .into_response()
}

#[derive(Debug, Default, Deserialize)]
struct RecomputeRequest {
    /// Also run the model worker over the recording (default: when it is enabled).
    model: Option<bool>,
}

/// Recompute a recording's derived windows from its stored samples, with the
/// algorithm versions running now. Runs in the background; poll the run.
async fn post_recompute(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Path(session_id): Path<i64>,
    body: Option<Json<RecomputeRequest>>,
) -> Response {
    if !state.derived_enabled {
        return derived_unavailable();
    }
    match may_read_session(&state, &auth, session_id).await {
        Ok(true) => {}
        Ok(false) => return not_found("recording"),
        Err(err) => return internal_error("failed to check recording access", err),
    }
    let request = body.map(|Json(request)| request).unwrap_or_default();
    let model = match model_client(&state.config, request.model.unwrap_or(true)) {
        Ok(model) => model,
        Err(err) => return internal_error("failed to build model client", err),
    };
    let (run_id, methods) = match create_derived_run(&state.db, session_id, model.as_ref()).await {
        Ok(created) => created,
        Err(err) => return internal_error("failed to create derived run", err),
    };

    let db = state.db.clone();
    tokio::spawn(async move {
        let outcome = derived::recompute(&db, session_id, run_id, model.as_ref()).await;
        if let Err(err) = finish_derived_run(&db, run_id, &outcome).await {
            error!(error = ?err, run_id, "failed to record recompute outcome");
        }
        match outcome {
            Ok(outcome) => info!(run_id, session_id, windows = outcome.window_count, "recompute completed"),
            Err(err) => warn!(run_id, session_id, error = %err, "recompute failed"),
        }
    });

    (
        StatusCode::ACCEPTED,
        Json(json!({
            "run_id": run_id,
            "session_id": session_id,
            "status": "running",
            "methods": methods,
            "poll": format!("/api/derived-runs/{run_id}"),
        })),
    )
        .into_response()
}

async fn derived_run_json(db: &MySqlPool, run_id: i64) -> Result<Option<Value>> {
    let row = sqlx::query(
        "SELECT id, session_id, run_kind, CAST(methods AS CHAR) AS methods, status, evidence_rows, \
                evidence_sha256, window_count, error, started_at_ms, completed_at_ms \
         FROM derived_runs WHERE id = ?",
    )
    .bind(run_id)
    .fetch_optional(db)
    .await?;
    Ok(row.map(|row| {
        json!({
            "run_id": row.try_get::<i64, _>("id").unwrap_or_default(),
            "session_id": row.try_get::<i64, _>("session_id").unwrap_or_default(),
            "run_kind": row.try_get::<String, _>("run_kind").unwrap_or_default(),
            "methods": row.try_get::<String, _>("methods").ok().and_then(|text| serde_json::from_str::<Value>(&text).ok()),
            "status": row.try_get::<String, _>("status").unwrap_or_default(),
            "evidence_rows": row.try_get::<Option<i64>, _>("evidence_rows").ok().flatten(),
            "evidence_sha256": row.try_get::<Option<String>, _>("evidence_sha256").ok().flatten(),
            "window_count": row.try_get::<Option<i32>, _>("window_count").ok().flatten(),
            "error": row.try_get::<Option<String>, _>("error").ok().flatten(),
            "started_at_ms": row.try_get::<i64, _>("started_at_ms").unwrap_or_default(),
            "completed_at_ms": row.try_get::<Option<i64>, _>("completed_at_ms").ok().flatten(),
        })
    }))
}

async fn get_derived_run(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Path(run_id): Path<i64>,
) -> Response {
    if !state.derived_enabled {
        return derived_unavailable();
    }
    let run = match derived_run_json(&state.db, run_id).await {
        Ok(Some(run)) => run,
        Ok(None) => return not_found("derived run"),
        Err(err) => return internal_error("failed to load derived run", err),
    };
    let session_id = run["session_id"].as_i64().unwrap_or_default();
    match may_read_session(&state, &auth, session_id).await {
        Ok(true) => (StatusCode::OK, Json(run)).into_response(),
        Ok(false) => not_found("derived run"),
        Err(err) => internal_error("failed to check recording access", err),
    }
}

#[derive(Debug, Deserialize)]
struct DerivedWindowsQuery {
    /// A run id, or `live` for the rows written while recording. Default: all.
    run_id: Option<String>,
    method_id: Option<String>,
    /// `json` (default) or `csv`.
    format: Option<String>,
}

const DERIVED_COLUMNS: [&str; 14] = [
    "id", "session_id", "run_id", "method_id", "method_version", "window_start_ms", "window_end_ms",
    "hr_bpm", "sqi", "quality", "hr_reason", "movement", "channel", "details",
];

async fn get_derived_windows(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Path(session_id): Path<i64>,
    Query(query): Query<DerivedWindowsQuery>,
) -> Response {
    if !state.derived_enabled {
        return derived_unavailable();
    }
    match may_read_session(&state, &auth, session_id).await {
        Ok(true) => {}
        Ok(false) => return not_found("recording"),
        Err(err) => return internal_error("failed to check recording access", err),
    }
    let mut sql = String::from(
        "SELECT id, session_id, run_id, method_id, method_version, window_start_ms, window_end_ms, \
                hr_bpm, sqi, quality, hr_reason, movement, channel, CAST(details AS CHAR) AS details \
         FROM derived_windows WHERE session_id = ?",
    );
    let run_filter = query.run_id.as_deref().map(str::trim).filter(|value| !value.is_empty());
    match run_filter {
        Some("live") => sql.push_str(" AND run_id IS NULL"),
        Some(value) if value.parse::<i64>().is_ok() => sql.push_str(" AND run_id = ?"),
        Some(_) => {
            return (StatusCode::BAD_REQUEST, Json(json!({ "error": "run_id must be a number or 'live'" })))
                .into_response()
        }
        None => {}
    }
    if query.method_id.is_some() {
        sql.push_str(" AND method_id = ?");
    }
    sql.push_str(" ORDER BY method_id, run_id, window_end_ms LIMIT 200000");
    let mut statement = sqlx::query(&sql).bind(session_id);
    if let Some(run_id) = run_filter.and_then(|value| value.parse::<i64>().ok()) {
        statement = statement.bind(run_id);
    }
    if let Some(method_id) = &query.method_id {
        statement = statement.bind(method_id);
    }
    let rows = match statement.fetch_all(&state.db).await {
        Ok(rows) => rows,
        Err(err) => return internal_error("failed to load derived windows", err.into()),
    };
    let values: Vec<Value> = rows
        .iter()
        .map(|row| {
            json!({
                "id": row.try_get::<i64, _>("id").unwrap_or_default(),
                "session_id": row.try_get::<i64, _>("session_id").unwrap_or_default(),
                "run_id": row.try_get::<Option<i64>, _>("run_id").ok().flatten(),
                "method_id": row.try_get::<String, _>("method_id").unwrap_or_default(),
                "method_version": row.try_get::<String, _>("method_version").unwrap_or_default(),
                "window_start_ms": row.try_get::<i64, _>("window_start_ms").unwrap_or_default(),
                "window_end_ms": row.try_get::<i64, _>("window_end_ms").unwrap_or_default(),
                "hr_bpm": row.try_get::<Option<f64>, _>("hr_bpm").ok().flatten(),
                "sqi": row.try_get::<Option<f64>, _>("sqi").ok().flatten(),
                "quality": row.try_get::<Option<String>, _>("quality").ok().flatten(),
                "hr_reason": row.try_get::<Option<String>, _>("hr_reason").ok().flatten(),
                "movement": row.try_get::<Option<String>, _>("movement").ok().flatten(),
                "channel": row.try_get::<Option<String>, _>("channel").ok().flatten(),
                "details": row.try_get::<Option<String>, _>("details").ok().flatten()
                    .and_then(|text| serde_json::from_str::<Value>(&text).ok()),
            })
        })
        .collect();
    if query.format.as_deref() == Some("csv") {
        let mut body = export::csv_line(&DERIVED_COLUMNS.iter().map(|c| c.to_string()).collect::<Vec<_>>());
        for value in &values {
            let cells: Vec<String> = DERIVED_COLUMNS
                .iter()
                .map(|column| match &value[*column] {
                    Value::Null => String::new(),
                    Value::String(text) => text.clone(),
                    other => other.to_string(),
                })
                .collect();
            body.push_str(&export::csv_line(&cells));
        }
        return (
            StatusCode::OK,
            [(axum::http::header::CONTENT_TYPE, "text/csv; charset=utf-8")],
            body,
        )
            .into_response();
    }
    (StatusCode::OK, Json(json!({ "session_id": session_id, "count": values.len(), "windows": values }))).into_response()
}

#[derive(Debug, Deserialize)]
struct ExportQuery {
    /// `1` also includes the raw packets as received.
    raw: Option<u8>,
}

/// The whole experiment as a checksummed dataset zip.
async fn get_experiment_export(
    State(state): State<AppState>,
    Extension(auth): Extension<AuthContext>,
    Path(experiment_id): Path<i64>,
    Query(query): Query<ExportQuery>,
) -> Response {
    match may_read_experiment(&state, &auth, experiment_id).await {
        Ok(true) => {}
        Ok(false) => return not_found("experiment"),
        Err(err) => return internal_error("failed to check experiment access", err),
    }
    let options = export::ExportOptions {
        include_raw_packets: query.raw == Some(1),
        backend_version: backend_version(),
        exported_at_ms: now_ms(),
    };
    match export::export_experiment(&state.db, experiment_id, &options).await {
        Ok(bytes) => (
            StatusCode::OK,
            [
                (axum::http::header::CONTENT_TYPE, "application/zip".to_string()),
                (
                    axum::http::header::CONTENT_DISPOSITION,
                    format!("attachment; filename=\"experiment_{experiment_id}.zip\""),
                ),
            ],
            bytes,
        )
            .into_response(),
        Err(err) if err.to_string() == "experiment not found" => not_found("experiment"),
        Err(err) => internal_error("failed to export experiment", err),
    }
}

async fn live_model_estimate(
    client: &reqwest::Client,
    url: &str,
    head_id: &str,
    request: &live::ModelRequest,
) -> Result<live::ModelEstimate> {
    let response = client
        .post(url)
        .json(&json!({
            "ppg_data": request.values,
            "sample_rate": request.fs_hz,
            "window_duration_s": live::MODEL_WINDOW_S,
            "head_id": head_id,
            "omit_embedding": true,
        }))
        .send()
        .await?;
    let status = response.status();
    let body: Value = response.json().await?;
    if !status.is_success() {
        return Err(anyhow!(
            "worker returned {status}: {}",
            body.get("error").and_then(Value::as_str).unwrap_or("no detail")
        ));
    }
    let head_result = body.get("head_result").ok_or_else(|| anyhow!("no head result"))?;
    let hr_bpm = head_result
        .pointer("/prediction/value")
        .and_then(Value::as_f64)
        .filter(|value| value.is_finite())
        .ok_or_else(|| anyhow!("head returned no numeric value"))?;
    let head = head_result.get("head");
    let text = |key: &str| head.and_then(|h| h.get(key)).and_then(Value::as_str).map(str::to_string);
    Ok(live::ModelEstimate {
        model_id: text("base_model_id").unwrap_or_else(|| "papagei_s".to_string()),
        head_id: head_id.to_string(),
        head_version: text("head_version"),
        validation_status: text("validation_status"),
        runtime: body.get("runtime").and_then(Value::as_str).map(str::to_string),
        channel: request.channel.clone(),
        hr_bpm: (hr_bpm * 10.0).round() / 10.0,
        window_end_ms: request.window_end_ms,
        inference_ms: body.get("inference_ms").and_then(Value::as_f64),
        live_sqi: request.live_sqi,
        live_hr_bpm: request.live_hr_bpm,
        movement: request.movement,
    })
}

/// `u<id>/live/<kind>` for an attributed recording, so it lands under the
/// participant's own `status/u<id>/#` namespace; `live/<kind>` otherwise.
fn live_status_suffix(owner: Option<i64>, kind: &str) -> String {
    match owner {
        Some(user_id) => format!("{}/live/{kind}", mqtt_username_for(user_id)),
        None => format!("live/{kind}"),
    }
}

async fn insert_live_alert(db: &MySqlPool, alert: &live::AlertEvent) -> Result<()> {
    sqlx::query(
        r#"
        INSERT INTO live_events
          (session_id, owner_user_id, stream_name, event_type, alert_code, severity, message,
           window_start_ms, window_end_ms, detected_at_ms, recipe_version, details)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        "#,
    )
    .bind(alert.session_id)
    .bind(alert.owner_user_id)
    .bind(alert.stream_name.as_deref())
    .bind(format!("alert_{}", alert.state))
    .bind(&alert.code)
    .bind(&alert.severity)
    .bind(&alert.message)
    .bind(alert.window_start_ms)
    .bind(alert.window_end_ms)
    .bind(alert.detected_at_ms)
    .bind(alert.recipe_version)
    .bind(serde_json::to_string(&alert.details)?)
    .execute(db)
    .await?;
    Ok(())
}

async fn insert_live_summary(db: &MySqlPool, summary: &live::SessionSummary) -> Result<()> {
    sqlx::query(
        r#"
        INSERT INTO live_events
          (session_id, owner_user_id, event_type, window_start_ms, window_end_ms,
           detected_at_ms, recipe_version, details)
        VALUES (?, ?, 'summary', ?, ?, ?, ?, ?)
        "#,
    )
    .bind(summary.meta.session_id)
    .bind(summary.meta.owner_user_id)
    .bind(summary.window_start_ms)
    .bind(summary.window_end_ms)
    .bind(summary.window_end_ms)
    .bind(summary.recipe_version)
    .bind(serde_json::to_string(&live::SessionSummary { trace: None, ..summary.clone() })?)
    .execute(db)
    .await?;
    Ok(())
}

/// Take the matching recordings out of the active map and return them.
async fn remove_active_sessions(
    sessions: &RwLock<HashMap<String, SessionHandle>>,
    matches: impl Fn(&SessionHandle) -> bool,
) -> Vec<(String, SessionHandle)> {
    let mut map = sessions.write().await;
    let keys: Vec<String> = map
        .iter()
        .filter(|(_, handle)| matches(handle))
        .map(|(key, _)| key.clone())
        .collect();
    keys.into_iter()
        .filter_map(|key| map.remove(&key).map(|handle| (key, handle)))
        .collect()
}

/// Remember recordings that just ended, so late packets can still reach them.
fn remember_ended_sessions(state: &AppState, removed: Vec<(String, SessionHandle)>) {
    if removed.is_empty() {
        return;
    }
    let now = std::time::Instant::now();
    let mut ended = state
        .ended_sessions
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    for (key, handle) in removed {
        ended.remember(now, key, handle);
    }
}

/// Where a packet goes: the active recording of its device, else the one that
/// ended within the last 30 s, else nowhere yet (it is held).
enum SessionLookup {
    Active(SessionHandle),
    JustEnded(SessionHandle),
    None,
}

async fn lookup_session(state: &AppState, key: &str) -> SessionLookup {
    if let Some(handle) = state.sessions.read().await.get(key).cloned() {
        return SessionLookup::Active(handle);
    }
    let recent = state
        .ended_sessions
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .recent(std::time::Instant::now(), key);
    match recent {
        Some(handle) => SessionLookup::JustEnded(handle),
        None => SessionLookup::None,
    }
}

/// Who owns a device recording, when the tenancy migration has been applied.
async fn session_owner(state: &AppState, session_id: i64) -> Result<Option<i64>> {
    if !state.tenancy_enabled {
        return Ok(None);
    }
    if let Some(owner) = state.session_owners.read().await.get(&session_id) {
        return Ok(*owner);
    }
    let owner = sqlx::query_scalar::<_, Option<i64>>(
        "SELECT owner_user_id FROM sessions WHERE id = ?",
    )
    .bind(session_id)
    .fetch_optional(&state.db)
    .await?
    .flatten();
    state.session_owners.write().await.insert(session_id, owner);
    Ok(owner)
}

fn session_key(device_type: &str, device_address: &str) -> String {
    format!(
        "{}:{}",
        device_type.trim().to_ascii_lowercase(),
        device_address.trim().to_ascii_uppercase()
    )
}

#[derive(Debug)]
struct CanonicalSample {
    stream_name: &'static str,
    channel_values: Value,
    channel_units: Value,
}

fn canonical_stream_samples(packet: &SensorPacket) -> Vec<CanonicalSample> {
    let mut samples = Vec::new();

    push_stream(
        &mut samples,
        "ppg",
        &packet.data,
        &[
            ("green", "ppg_green"),
            ("red", "ppg_red"),
            ("ir", "ppg_ir"),
            ("ambient", "ppg_ambient"),
        ],
        json!({ "green": "ct", "red": "ct", "ir": "ct", "ambient": "ct" }),
    );
    push_stream(
        &mut samples,
        "accel",
        &packet.data,
        &[("x", "accel_x"), ("y", "accel_y"), ("z", "accel_z")],
        json!({ "x": "device_native", "y": "device_native", "z": "device_native" }),
    );
    push_stream(
        &mut samples,
        "gyro",
        &packet.data,
        &[("x", "gyro_x"), ("y", "gyro_y"), ("z", "gyro_z")],
        json!({ "x": "device_native", "y": "device_native", "z": "device_native" }),
    );
    push_stream(
        &mut samples,
        "mag",
        &packet.data,
        &[("x", "mag_x"), ("y", "mag_y"), ("z", "mag_z")],
        json!({ "x": "uT", "y": "uT", "z": "uT" }),
    );
    push_stream(
        &mut samples,
        "temperature",
        &packet.data,
        &[("value", "temperature")],
        json!({ "value": "device_native" }),
    );
    push_stream(
        &mut samples,
        "pressure",
        &packet.data,
        &[("value", "pressure")],
        json!({ "value": "hPa" }),
    );
    // A rate the device computed itself (Bluetooth Heart Rate Service), kept as
    // its own stream: it is evidence of what the device reported, not a
    // waveform, and never mixed with rates this system derives.
    push_stream(
        &mut samples,
        "heart_rate",
        &packet.data,
        &[("bpm", "heart_rate"), ("rr_interval_ms", "rr_interval_ms")],
        json!({ "bpm": "bpm", "rr_interval_ms": "ms" }),
    );

    let known_keys = [
        "deviceTimestamp",
        "ppg_sample_count",
        "ppg_data_size",
        "ppg_sample_interval_us",
        "raw",
        "ppg_green",
        "ppg_red",
        "ppg_ir",
        "ppg_ambient",
        "accel_x",
        "accel_y",
        "accel_z",
        "gyro_x",
        "gyro_y",
        "gyro_z",
        "mag_x",
        "mag_y",
        "mag_z",
        "temperature",
        "pressure",
        "heart_rate",
        "rr_interval_ms",
    ];
    let other: Map<String, Value> = packet
        .data
        .iter()
        .filter(|(key, value)| !known_keys.contains(&key.as_str()) && value.is_number())
        .map(|(key, value)| (key.clone(), value.clone()))
        .collect();
    if !other.is_empty() {
        samples.push(CanonicalSample {
            stream_name: "other",
            channel_values: Value::Object(other),
            channel_units: json!({}),
        });
    }

    samples
}

fn push_stream(
    samples: &mut Vec<CanonicalSample>,
    stream_name: &'static str,
    data: &Map<String, Value>,
    mappings: &[(&str, &str)],
    channel_units: Value,
) {
    let values: Map<String, Value> = mappings
        .iter()
        .filter_map(|(canonical, source)| {
            data.get(*source)
                .map(|value| ((*canonical).to_string(), value.clone()))
        })
        .collect();

    if !values.is_empty() {
        samples.push(CanonicalSample {
            stream_name,
            channel_values: Value::Object(values),
            channel_units,
        });
    }
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_millis() as i64)
        .unwrap_or_default()
}

async fn shutdown_signal() {
    let _ = tokio::signal::ctrl_c().await;
}

#[cfg(test)]
mod tests {
    #[test]
    fn the_activity_pilot_is_off_unless_a_head_is_named() {
        assert_eq!(activity_model_from(None), None);
        for off in ["", "  ", "off", "OFF", "false", "0", "none"] {
            assert_eq!(activity_model_from(Some(off)), None, "{off:?}");
        }
        assert_eq!(activity_model_from(Some(" act_v1 ")), Some("act_v1".to_string()));
    }

    #[test]
    fn a_json_array_is_a_batch_and_an_object_is_one_packet() {
        assert!(is_batch(br#"[{"device":"OpenEarable"},{"device":"OpenEarable"}]"#));
        assert!(is_batch(b"  
 [ ]"));
        assert!(!is_batch(br#"{"device":"OpenRing"}"#));
        assert!(!is_batch(b""));
    }

    use super::*;

    fn processing_point(sample_index: Option<i64>, timestamp_ms: i64) -> ProcessingPoint {
        ProcessingPoint {
            session_id: 1,
            device_id: 1,
            device_type: "OpenRing".to_string(),
            measurement_config_id: Some(1),
            sample_index,
            timestamp_ms,
            sample_rate_hz: 25.0,
            clock_source: Some("arrival_only".to_string()),
            clock_quality: Some("unvalidated".to_string()),
            channel_values: Map::new(),
            channel_units: Value::Null,
        }
    }

    #[test]
    fn dynamic_routes_use_axum_07_parameter_syntax() {
        let routes = [
            SESSION_TIMING_ROUTE,
            RECORDING_STOP_ROUTE,
            EXPERIMENT_DETAILS_ROUTE,
            EXPERIMENT_START_ROUTE,
            EXPERIMENT_STOP_ROUTE,
            EVIDENCE_SESSION_CREATE_ROUTE,
            EVIDENCE_SESSION_STOP_ROUTE,
            ANNOTATION_CREATE_ROUTE,
            ANNOTATION_STOP_ROUTE,
            STUDY_DETAILS_ROUTE,
            STUDY_MEMBERS_ROUTE,
            STUDY_CONSENT_ROUTE,
            STUDY_WITHDRAW_ROUTE,
            PLATFORM_ROLE_ROUTE,
        ];
        for route in routes {
            assert!(
                route.contains(':'),
                "dynamic route lacks a parameter: {route}"
            );
            assert!(
                !route.contains('{') && !route.contains('}'),
                "Axum 0.7 does not match brace parameters: {route}"
            );
        }
    }

    #[test]
    fn consecutive_indices_are_not_split_by_bursty_arrival_time() {
        let previous = processing_point(Some(100), 1_000);
        let current = processing_point(Some(101), 1_250);
        assert!(!processing_boundary(&previous, &current));
    }

    #[test]
    fn index_gap_creates_processing_boundary() {
        let previous = processing_point(Some(100), 1_000);
        let current = processing_point(Some(102), 1_040);
        assert!(processing_boundary(&previous, &current));
    }

    #[test]
    fn a_gzip_batch_is_decompressed_and_parsed() {
        use std::io::Write;
        let json = br#"[{"device":"OpenRing","address":"AA","type":"PPG","data":{"ppg_green":1.0}},{"device":"OpenRing","address":"AA","type":"PPG","data":{"ppg_green":2.0}}]"#;
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        encoder.write_all(json).unwrap();
        let packets = parse_payload(&encoder.finish().unwrap()).unwrap();
        assert_eq!(packets.len(), 2);
        assert_eq!(packets[1].data["ppg_green"], json!(2.0));
    }

    #[test]
    fn heart_rate_service_packet_becomes_its_own_stream() {
        let packet: SensorPacket = serde_json::from_value(json!({
            "device": "HeartRateMonitor",
            "address": "AA:BB:CC:DD:EE:FF",
            "type": "HEART_RATE",
            "data": { "heart_rate": 64, "rr_interval_ms": 937.5 }
        }))
        .unwrap();
        let samples = canonical_stream_samples(&packet);
        assert_eq!(samples.len(), 1);
        assert_eq!(samples[0].stream_name, "heart_rate");
        assert_eq!(samples[0].channel_values["bpm"], json!(64));
        assert_eq!(samples[0].channel_values["rr_interval_ms"], json!(937.5));
        assert_eq!(samples[0].channel_units["bpm"], json!("bpm"));
    }

    #[test]
    fn arrival_only_stream_still_splits_on_large_gap() {
        let previous = processing_point(None, 1_000);
        let current = processing_point(None, 1_250);
        assert!(processing_boundary(&previous, &current));
    }

    #[test]
    fn two_owners_capture_concurrently() {
        // The property that makes a multi-participant study possible: one
        // owner holding an active experiment must not block another.
        let mut active: HashMap<OwnerKey, i64> = HashMap::new();
        active.insert(Some(1), 100);

        assert!(owner_has_active_experiment(&active, Some(1)));
        assert!(!owner_has_active_experiment(&active, Some(2)));

        active.insert(Some(2), 200);
        assert_eq!(active_experiment_for(&active, Some(1)), Some(100));
        assert_eq!(active_experiment_for(&active, Some(2)), Some(200));
        assert_eq!(active.len(), 2);
    }

    #[test]
    fn one_owner_still_holds_only_one_experiment() {
        let mut active: HashMap<OwnerKey, i64> = HashMap::new();
        active.insert(Some(1), 100);
        assert!(owner_has_active_experiment(&active, Some(1)));
    }

    #[test]
    fn an_owner_cannot_act_on_another_owners_experiment() {
        // Guards stop, evidence-session and annotation requests.
        let mut active: HashMap<OwnerKey, i64> = HashMap::new();
        active.insert(Some(1), 100);
        active.insert(Some(2), 200);

        assert!(is_active_experiment_for(&active, Some(1), 100));
        assert!(!is_active_experiment_for(&active, Some(2), 100));
        assert!(!is_active_experiment_for(&active, Some(1), 200));
        assert!(!is_active_experiment_for(&active, None, 100));
    }

    #[test]
    fn stopping_clears_only_that_experiment() {
        let mut active: HashMap<OwnerKey, i64> = HashMap::new();
        active.insert(Some(1), 100);
        active.insert(Some(2), 200);

        clear_active_experiment(&mut active, 100);

        assert!(!owner_has_active_experiment(&active, Some(1)));
        assert_eq!(active_experiment_for(&active, Some(2)), Some(200));
    }

    #[test]
    fn unattributed_owner_behaves_like_the_previous_single_slot() {
        // Before authentication exists every request resolves to `None`, so
        // the observable behaviour must match the old global slot exactly.
        let mut active: HashMap<OwnerKey, i64> = HashMap::new();
        assert!(!owner_has_active_experiment(&active, None));

        active.insert(None, 42);
        assert!(owner_has_active_experiment(&active, None));
        assert!(is_active_experiment_for(&active, None, 42));
        assert!(!is_active_experiment_for(&active, None, 43));

        clear_active_experiment(&mut active, 42);
        assert!(!owner_has_active_experiment(&active, None));
        assert_eq!(active_experiment_for(&active, None), None);
    }

    #[test]
    fn mqtt_password_matches_the_documented_derivation() {
        // scripts/sync_mqtt_users.sh writes the broker's password file using
        // sha256("<secret>:mqtt:<user_id>"). If the two ever disagree, every
        // participant is silently locked out of the broker, so pin the exact
        // value rather than trusting both sides to stay in step.
        //
        // Reference: printf '%s:mqtt:%s' "s3cret" "12" | sha256sum
        let derived = mqtt_password_for("s3cret", 12);
        assert_eq!(derived.len(), 64, "expected a hex sha256 digest");
        assert_eq!(
            derived,
            {
                use sha2::{Digest, Sha256};
                let mut h = Sha256::new();
                h.update(b"s3cret:mqtt:12");
                h.finalize().iter().map(|b| format!("{b:02x}")).collect::<String>()
            },
            "derivation must stay byte-identical to the shell script's"
        );
        // Different users and different secrets must not collide.
        assert_ne!(mqtt_password_for("s3cret", 12), mqtt_password_for("s3cret", 13));
        assert_ne!(mqtt_password_for("s3cret", 12), mqtt_password_for("other", 12));
    }

    #[test]
    fn mqtt_usernames_and_topic_namespaces_agree() {
        assert_eq!(mqtt_username_for(12), "u12");
        // The namespace a topic claims must round-trip to the same user.
        assert_eq!(topic_claimed_owner("sensors/u12/OpenRing"), Some(12));
        assert_eq!(topic_claimed_owner("sensors/u12/OpenRing/extra"), Some(12));
        // The legacy shape claims nobody, so existing clients keep working.
        assert_eq!(topic_claimed_owner("sensors/OpenRing"), None);
        // Nothing that is not a u<number> segment may claim an owner.
        assert_eq!(topic_claimed_owner("sensors/user12/OpenRing"), None);
        assert_eq!(topic_claimed_owner("sensors/u/OpenRing"), None);
        assert_eq!(topic_claimed_owner("sensors"), None);
    }

    #[test]
    fn label_must_cover_the_entire_window() {
        let complete = ProcessingLabel {
            id: 1,
            target_session_id: 7,
            session_id: None,
            label_name: "activity".to_string(),
            label_value: "typing".to_string(),
            started_at_ms: 900,
            ended_at_ms: Some(2_100),
        };
        let partial = ProcessingLabel {
            ended_at_ms: Some(1_500),
            ..ProcessingLabel {
                id: 2,
                target_session_id: 7,
                session_id: None,
                label_name: "activity".to_string(),
                label_value: "typing".to_string(),
                started_at_ms: 900,
                ended_at_ms: None,
            }
        };
        assert!(label_fully_covers_window(&complete, 7, 1_000, 2_000));
        assert!(!label_fully_covers_window(&partial, 7, 1_000, 2_000));
        assert!(!label_fully_covers_window(&complete, 8, 1_000, 2_000));
    }

    fn packet_json(device: &str) -> String {
        format!(
            r#"{{"device":"{device}","address":"AA","type":"ppg","timestamp":1,"data":{{"green":1.0}}}}"#
        )
    }

    fn gzip(bytes: &[u8]) -> Vec<u8> {
        use std::io::Write;
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
        encoder.write_all(bytes).unwrap();
        encoder.finish().unwrap()
    }

    fn devices(packets: &[SensorPacket]) -> Vec<&str> {
        packets.iter().map(|p| p.device.as_str()).collect()
    }

    #[test]
    fn a_held_packet_reads_back_unchanged() {
        let body = r#"{"schemaVersion":2,"device":"OpenRing","address":"AA:BB","type":"ppg","timestamp":5,
            "timing":{"androidReceivedTimestampMs":1234,"sequenceNumber":9},"data":{"green":[1,2,3]}}"#;
        let packet = parse_payload(body.as_bytes()).unwrap().remove(0);
        let held = serde_json::to_string(&packet).unwrap();
        let back: SensorPacket = serde_json::from_str(&held).unwrap();
        assert_eq!(serde_json::to_string(&back).unwrap(), held);
        assert_eq!(back.timing.unwrap().android_received_timestamp_ms, Some(1234));
    }

    #[tokio::test]
    async fn ended_recordings_are_found_for_a_while_and_only_by_their_device() {
        let sessions = RwLock::new(HashMap::from([
            ("openring:A".to_string(), SessionHandle { device_id: 1, session_id: 10, measurement_config_id: None }),
            ("openearable:B".to_string(), SessionHandle { device_id: 2, session_id: 11, measurement_config_id: None }),
        ]));
        let removed = remove_active_sessions(&sessions, |h| h.session_id == 10).await;
        assert_eq!(removed.len(), 1);
        assert_eq!(removed[0].0, "openring:A");
        assert!(sessions.read().await.contains_key("openearable:B"));
        assert!(!sessions.read().await.contains_key("openring:A"));
        let mut ended = pending::EndedSessions::new();
        let now = std::time::Instant::now();
        for (key, handle) in removed {
            ended.remember(now, key, handle);
        }
        assert_eq!(ended.recent(now, "openring:A").map(|h| h.session_id), Some(10));
        assert!(ended.recent(now, "openearable:B").is_none());
    }

    #[test]
    fn the_topic_owner_decides_whose_held_packets_are_replayed() {
        assert_eq!(topic_claimed_owner("sensors/u7/openring/AA"), Some(7));
        assert_eq!(topic_claimed_owner("sensors/openring/AA"), None);
    }

    #[test]
    fn parse_payload_reads_a_single_object() {
        let packets = parse_payload(packet_json("OpenRing").as_bytes()).unwrap();
        assert_eq!(devices(&packets), ["OpenRing"]);
    }

    #[test]
    fn parse_payload_reads_a_plain_array_in_order() {
        let body = format!("[{},{}]", packet_json("A"), packet_json("B"));
        assert_eq!(devices(&parse_payload(body.as_bytes()).unwrap()), ["A", "B"]);
    }

    #[test]
    fn parse_payload_reads_a_gzip_array_and_a_gzip_object() {
        let body = format!("[{},{}]", packet_json("A"), packet_json("B"));
        assert_eq!(parse_payload(&gzip(body.as_bytes())).unwrap().len(), 2);
        assert_eq!(parse_payload(&gzip(packet_json("A").as_bytes())).unwrap().len(), 1);
    }

    #[test]
    fn parse_payload_reads_an_array_with_leading_whitespace() {
        let body = format!("  \n[{}]", packet_json("A"));
        assert_eq!(parse_payload(body.as_bytes()).unwrap().len(), 1);
    }

    #[test]
    fn parse_payload_rejects_an_empty_body_and_an_empty_gzip() {
        assert!(parse_payload(b"").is_err());
        assert!(parse_payload(&gzip(b"")).is_err());
    }

    #[test]
    fn parse_payload_accepts_an_empty_array_as_no_packets() {
        assert!(parse_payload(b"[]").unwrap().is_empty());
    }

    #[test]
    fn parse_payload_rejects_truncated_gzip() {
        let body = format!("[{}]", packet_json("A"));
        let full = gzip(body.as_bytes());
        for cut in [2, full.len() / 2, full.len() - 1] {
            assert!(parse_payload(&full[..cut]).is_err(), "cut at {cut}");
        }
    }

    #[test]
    fn parse_payload_rejects_a_bare_gzip_magic_and_garbage_after_it() {
        assert!(parse_payload(&[0x1f, 0x8b]).is_err());
        assert!(parse_payload(&[0x1f, 0x8b, 0x08, 0x00, 1, 2, 3]).is_err());
    }

    #[test]
    fn parse_payload_caps_a_gzip_bomb() {
        // 40 MB expands from well under 1 MB. Only 32 MB is read, the cut JSON
        // is invalid, and the call fails instead of allocating without limit.
        let mut body = Vec::with_capacity(40 * 1024 * 1024);
        body.push(b'[');
        body.resize(40 * 1024 * 1024, b' ');
        let bomb = gzip(&body);
        assert!(bomb.len() < 1024 * 1024);
        assert!(parse_payload(&bomb).is_err());
    }

    #[test]
    fn parse_payload_rejects_non_array_non_object_json() {
        for body in ["42", "\"text\"", "null", "true", "not json"] {
            assert!(parse_payload(body.as_bytes()).is_err(), "{body}");
        }
    }

    #[test]
    fn parse_payload_rejects_an_object_missing_required_fields() {
        assert!(parse_payload(br#"{"device":"OpenRing"}"#).is_err());
        assert!(parse_payload(br#"{"device":"A","address":"B","type":"ppg"}"#).is_err());
        assert!(parse_payload(br#"{"device":"A","address":"B","type":"ppg","data":[1]}"#).is_err());
    }

    #[test]
    fn parse_payload_skips_bad_packets_in_a_batch_and_keeps_the_rest() {
        let body = format!(
            r#"[{},{{"device":"X"}},42,null,"s",{}]"#,
            packet_json("A"),
            packet_json("B")
        );
        assert_eq!(devices(&parse_payload(body.as_bytes()).unwrap()), ["A", "B"]);
    }

    #[test]
    fn parse_payload_rejects_a_truncated_batch() {
        let body = format!("[{},", packet_json("A"));
        assert!(parse_payload(body.as_bytes()).is_err());
    }

    #[test]
    fn parse_payload_handles_a_large_batch() {
        let one = packet_json("A");
        let body = format!("[{}]", vec![one.as_str(); 20_000].join(","));
        assert_eq!(parse_payload(body.as_bytes()).unwrap().len(), 20_000);
    }

    #[test]
    fn parse_payload_drops_only_the_packet_with_a_wrongly_typed_field() {
        let bad = r#"{"device":"A","address":"B","type":"ppg","timestamp":"soon","data":{}}"#;
        let body = format!("[{bad},{}]", packet_json("B"));
        assert_eq!(devices(&parse_payload(body.as_bytes()).unwrap()), ["B"]);
    }

    #[test]
    fn label_window_coverage_is_inclusive_at_both_ends() {
        let label = |start, end| ProcessingLabel {
            id: 1,
            target_session_id: 7,
            session_id: None,
            label_name: "a".to_string(),
            label_value: "b".to_string(),
            started_at_ms: start,
            ended_at_ms: end,
        };
        assert!(label_fully_covers_window(&label(1_000, Some(2_000)), 7, 1_000, 2_000));
        assert!(!label_fully_covers_window(&label(1_001, Some(2_000)), 7, 1_000, 2_000));
        assert!(!label_fully_covers_window(&label(1_000, Some(1_999)), 7, 1_000, 2_000));
    }
}
