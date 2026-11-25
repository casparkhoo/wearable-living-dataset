//! Audited erasure of one participant's data.
//!
//! Withdrawal (see `studies::withdraw`) stops further capture but keeps the
//! evidence already collected. Erasure is the separate, stronger commitment: it
//! permanently deletes a participant's recordings and everything derived from
//! them, and writes an `erasure_log` row that survives the deletion, so the
//! study can show what was erased, when, by whom and why.
//!
//! Erasure is performed by a study admin (or a platform admin) on the
//! participant's request, never by the participant directly, so that a
//! mistaken tap cannot destroy a study's data. Over HTTP it is scoped to one
//! study; the command line can also erase across every study.
//!
//! Deletion runs in small batches so that a long recording does not hold a
//! large lock on a small server. If it is interrupted, the log row stays
//! `running` and the same request can simply be repeated.
//!
//! Backups are not touched: they age out on their own rotation, which the
//! self-hosting guide says the consent form should state.

use anyhow::{anyhow, Result};
use serde::Deserialize;
use serde_json::{json, Map, Value};
use sqlx::{MySqlPool, Row};

use crate::auth::AuthContext;
use crate::studies;

/// Rows deleted per statement.
const BATCH: i64 = 5_000;

/// The word a caller must send to confirm an erasure that is not a dry run.
pub const CONFIRM_WORD: &str = "ERASE";

#[derive(Debug, Deserialize, Default)]
pub struct EraseRequest {
    /// Must equal `ERASE` unless `dry_run` is true.
    pub confirm: Option<String>,
    pub reason: Option<String>,
    /// Count what would be deleted without deleting anything.
    pub dry_run: Option<bool>,
}

/// Which recordings an erasure covers.
#[derive(Clone, Copy, Debug)]
pub enum Scope {
    Study(i64),
    AllStudies,
}

impl Scope {
    fn study_id(self) -> Option<i64> {
        match self {
            Scope::Study(id) => Some(id),
            Scope::AllStudies => None,
        }
    }
    fn name(self) -> &'static str {
        match self {
            Scope::Study(_) => "study",
            Scope::AllStudies => "all",
        }
    }
}

/// Tables keyed by `session_id`, in the order they must be emptied so that no
/// foreign key is left pointing at a deleted row.
const SESSION_TABLES: [&str; 6] = [
    "derived_windows",
    "derived_runs",
    "live_events",
    "stream_samples",
    "raw_packets",
    "measurement_configs",
];

/// HTTP entry point: a study admin erases one member's data in their study.
pub async fn erase_study_member(
    db: &MySqlPool,
    auth: &AuthContext,
    study_id: i64,
    user_id: i64,
    request: EraseRequest,
) -> Result<Value> {
    let caller = studies::require_user(auth)?;
    if !studies::can_administer(db, auth, study_id).await? {
        return Err(anyhow!("you cannot administer this study"));
    }
    let dry_run = request.dry_run.unwrap_or(false);
    if !dry_run && request.confirm.as_deref() != Some(CONFIRM_WORD) {
        return Err(anyhow!(
            "erasure is permanent; send \"confirm\": \"{CONFIRM_WORD}\" to proceed, or \"dry_run\": true to preview"
        ));
    }
    erase(
        db,
        user_id,
        Scope::Study(study_id),
        Some(caller),
        request.reason.as_deref(),
        dry_run,
    )
    .await
}

/// The erasure log for a study, for its admins.
pub async fn study_erasure_log(db: &MySqlPool, auth: &AuthContext, study_id: i64) -> Result<Value> {
    if !studies::can_administer(db, auth, study_id).await? {
        return Err(anyhow!("you cannot administer this study"));
    }
    if !table_exists(db, "erasure_log").await? {
        return Ok(json!({ "study_id": study_id, "erasures": [] }));
    }
    let rows = sqlx::query(
        "SELECT id, subject_user_id, requested_by_user_id, scope, reason, status, CAST(counts AS CHAR) AS counts, \
                DATE_FORMAT(started_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS started_at, \
                DATE_FORMAT(completed_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS completed_at \
         FROM erasure_log WHERE study_id = ? ORDER BY id DESC",
    )
    .bind(study_id)
    .fetch_all(db)
    .await?;
    let mut erasures = Vec::new();
    for row in rows {
        let counts: Option<String> = row.try_get("counts")?;
        erasures.push(json!({
            "id": row.try_get::<i64, _>("id")?,
            "subject_user_id": row.try_get::<i64, _>("subject_user_id")?,
            "requested_by_user_id": row.try_get::<Option<i64>, _>("requested_by_user_id")?,
            "scope": row.try_get::<String, _>("scope")?,
            "reason": row.try_get::<Option<String>, _>("reason")?,
            "status": row.try_get::<String, _>("status")?,
            "counts": counts.and_then(|c| serde_json::from_str::<Value>(&c).ok()),
            "started_at": row.try_get::<Option<String>, _>("started_at")?,
            "completed_at": row.try_get::<Option<String>, _>("completed_at")?,
        }));
    }
    Ok(json!({ "study_id": study_id, "erasures": erasures }))
}

/// Erase, or with `dry_run` count, one participant's data within `scope`.
pub async fn erase(
    db: &MySqlPool,
    user_id: i64,
    scope: Scope,
    requested_by: Option<i64>,
    reason: Option<&str>,
    dry_run: bool,
) -> Result<Value> {
    if !table_exists(db, "users").await? {
        return Err(anyhow!(
            "apply backend/migrations/20260919_add_identity_and_studies.sql first: recordings have no owner to erase by"
        ));
    }
    let exists = sqlx::query("SELECT id FROM users WHERE id = ?")
        .bind(user_id)
        .fetch_optional(db)
        .await?;
    if exists.is_none() {
        return Err(anyhow!("user {user_id} not found"));
    }

    let sessions = owned_ids(db, "sessions", user_id, scope).await?;
    let experiments = owned_ids(db, "experiments", user_id, scope).await?;

    let active: i64 = if sessions.is_empty() {
        0
    } else {
        sqlx::query(&format!(
            "SELECT COUNT(*) AS n FROM sessions WHERE status = 'active' AND id IN ({})",
            placeholders(sessions.len())
        ))
        .bind_all(&sessions)
        .fetch_one(db)
        .await?
        .try_get("n")?
    };
    if active > 0 {
        return Err(anyhow!(
            "the participant has a recording in progress; stop it before erasing"
        ));
    }

    let mut present = Vec::new();
    for table in SESSION_TABLES {
        if table_exists(db, table).await? {
            present.push(table);
        }
    }

    if dry_run {
        let mut counts = Map::new();
        for table in &present {
            counts.insert((*table).to_string(), json!(count_by_sessions(db, table, &sessions).await?));
        }
        counts.insert("annotations".into(), json!(count_annotations(db, &sessions, &experiments).await?));
        counts.insert("sessions".into(), json!(sessions.len()));
        counts.insert("experiments".into(), json!(experiments.len()));
        return Ok(json!({
            "dry_run": true,
            "user_id": user_id,
            "scope": scope.name(),
            "study_id": scope.study_id(),
            "would_delete": counts,
        }));
    }

    let log_id = if table_exists(db, "erasure_log").await? {
        Some(
            sqlx::query(
                "INSERT INTO erasure_log (subject_user_id, study_id, requested_by_user_id, scope, reason, status) \
                 VALUES (?, ?, ?, ?, ?, 'running')",
            )
            .bind(user_id)
            .bind(scope.study_id())
            .bind(requested_by)
            .bind(scope.name())
            .bind(reason.map(str::trim).filter(|r| !r.is_empty()))
            .execute(db)
            .await?
            .last_insert_id() as i64,
        )
    } else {
        return Err(anyhow!(
            "apply backend/migrations/20261109_add_erasure_log.sql first: erasures must be logged"
        ));
    };

    let mut counts = Map::new();
    for table in &present {
        let mut deleted = 0u64;
        for session_id in &sessions {
            deleted += delete_batched(db, &format!("DELETE FROM {table} WHERE session_id = ? LIMIT {BATCH}"), *session_id).await?;
        }
        counts.insert((*table).to_string(), json!(deleted));
    }

    let mut annotations = 0u64;
    for session_id in &sessions {
        annotations += delete_batched(db, &format!("DELETE FROM annotations WHERE session_id = ? LIMIT {BATCH}"), *session_id).await?;
    }
    for experiment_id in &experiments {
        annotations += delete_batched(db, &format!("DELETE FROM annotations WHERE experiment_id = ? LIMIT {BATCH}"), *experiment_id).await?;
    }
    counts.insert("annotations".into(), json!(annotations));

    let mut deleted_sessions = 0u64;
    for session_id in &sessions {
        deleted_sessions += sqlx::query("DELETE FROM sessions WHERE id = ?")
            .bind(session_id)
            .execute(db)
            .await?
            .rows_affected();
    }
    counts.insert("sessions".into(), json!(deleted_sessions));

    // An experiment is removed only once no recording of anyone else still
    // belongs to it; in practice every recording in a participant's experiment
    // is theirs.
    let mut deleted_experiments = 0u64;
    for experiment_id in &experiments {
        deleted_experiments += sqlx::query(
            "DELETE FROM experiments WHERE id = ? AND NOT EXISTS (SELECT 1 FROM sessions s WHERE s.experiment_id = ?)",
        )
        .bind(experiment_id)
        .bind(experiment_id)
        .execute(db)
        .await?
        .rows_affected();
    }
    counts.insert("experiments".into(), json!(deleted_experiments));

    if let Scope::Study(study_id) = scope {
        sqlx::query(
            "UPDATE study_memberships SET status = 'withdrawn', withdrawn_at = COALESCE(withdrawn_at, NOW(3)), \
                    withdrawal_reason = COALESCE(withdrawal_reason, 'data erased') \
             WHERE study_id = ? AND user_id = ?",
        )
        .bind(study_id)
        .bind(user_id)
        .execute(db)
        .await?;
    }

    let counts = Value::Object(counts);
    if let Some(log_id) = log_id {
        sqlx::query("UPDATE erasure_log SET status = 'completed', counts = ?, completed_at = NOW(3) WHERE id = ?")
            .bind(counts.to_string())
            .bind(log_id)
            .execute(db)
            .await?;
    }

    Ok(json!({
        "erased": true,
        "erasure_id": log_id,
        "user_id": user_id,
        "scope": scope.name(),
        "study_id": scope.study_id(),
        "deleted": counts,
        "note": "backups are not altered; erased data leaves them as they rotate out",
    }))
}

async fn owned_ids(db: &MySqlPool, table: &str, user_id: i64, scope: Scope) -> Result<Vec<i64>> {
    let rows = match scope {
        Scope::Study(study_id) => {
            sqlx::query(&format!("SELECT id FROM {table} WHERE owner_user_id = ? AND study_id = ?"))
                .bind(user_id)
                .bind(study_id)
                .fetch_all(db)
                .await?
        }
        Scope::AllStudies => {
            sqlx::query(&format!("SELECT id FROM {table} WHERE owner_user_id = ?"))
                .bind(user_id)
                .fetch_all(db)
                .await?
        }
    };
    rows.iter().map(|row| Ok(row.try_get::<i64, _>("id")?)).collect()
}

async fn count_by_sessions(db: &MySqlPool, table: &str, sessions: &[i64]) -> Result<i64> {
    if sessions.is_empty() {
        return Ok(0);
    }
    Ok(sqlx::query(&format!(
        "SELECT COUNT(*) AS n FROM {table} WHERE session_id IN ({})",
        placeholders(sessions.len())
    ))
    .bind_all(sessions)
    .fetch_one(db)
    .await?
    .try_get("n")?)
}

async fn count_annotations(db: &MySqlPool, sessions: &[i64], experiments: &[i64]) -> Result<i64> {
    if sessions.is_empty() && experiments.is_empty() {
        return Ok(0);
    }
    let mut clauses = Vec::new();
    if !sessions.is_empty() {
        clauses.push(format!("session_id IN ({})", placeholders(sessions.len())));
    }
    if !experiments.is_empty() {
        clauses.push(format!("experiment_id IN ({})", placeholders(experiments.len())));
    }
    let sql = format!("SELECT COUNT(*) AS n FROM annotations WHERE {}", clauses.join(" OR "));
    let mut query = sqlx::query(&sql);
    for id in sessions.iter().chain(experiments.iter()) {
        query = query.bind(*id);
    }
    Ok(query.fetch_one(db).await?.try_get("n")?)
}

async fn delete_batched(db: &MySqlPool, sql: &str, id: i64) -> Result<u64> {
    let mut total = 0;
    loop {
        let affected = sqlx::query(sql).bind(id).execute(db).await?.rows_affected();
        total += affected;
        if affected < BATCH as u64 {
            return Ok(total);
        }
    }
}

async fn table_exists(db: &MySqlPool, table: &str) -> Result<bool> {
    let row = sqlx::query(
        "SELECT COUNT(*) AS n FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
    )
    .bind(table)
    .fetch_one(db)
    .await?;
    Ok(row.try_get::<i64, _>("n")? > 0)
}

fn placeholders(n: usize) -> String {
    vec!["?"; n].join(", ")
}

/// Bind every id in order.
trait BindAll<'q> {
    fn bind_all(self, ids: &'q [i64]) -> Self;
}

impl<'q> BindAll<'q> for sqlx::query::Query<'q, sqlx::MySql, sqlx::mysql::MySqlArguments> {
    fn bind_all(mut self, ids: &'q [i64]) -> Self {
        for id in ids {
            self = self.bind(*id);
        }
        self
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn placeholders_match_the_number_of_ids() {
        assert_eq!(placeholders(1), "?");
        assert_eq!(placeholders(3), "?, ?, ?");
    }

    #[test]
    fn session_tables_are_emptied_children_first() {
        // derived_windows references derived_runs, and stream_samples references
        // raw_packets, which references measurement_configs.
        let position = |name: &str| SESSION_TABLES.iter().position(|t| *t == name).unwrap();
        assert!(position("derived_windows") < position("derived_runs"));
        assert!(position("stream_samples") < position("raw_packets"));
        assert!(position("raw_packets") < position("measurement_configs"));
    }

    #[test]
    fn scope_names_are_what_the_log_records() {
        assert_eq!(Scope::Study(4).name(), "study");
        assert_eq!(Scope::Study(4).study_id(), Some(4));
        assert_eq!(Scope::AllStudies.name(), "all");
        assert_eq!(Scope::AllStudies.study_id(), None);
    }
}
