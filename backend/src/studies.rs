//! Study lifecycle and membership.
//!
//! Authorisation is resolved here from our own tables, never from the identity
//! token. Three roles apply:
//!
//! * `platform_admin` — a platform-wide role on `users`, sees every study.
//! * `study_admin` / `researcher` — per-study roles on `study_memberships`;
//!   administer or read one study, and never see another.
//! * `participant` — records their own consent, withdraws themselves, and sees
//!   only their own membership.
//!
//! Every function takes the caller's `AuthContext` and scopes its own queries.
//! Study management always requires a signed-in account: the deployment-wide
//! shared token has no identity, so it cannot act on a study.

use anyhow::{anyhow, Result};
use serde::Deserialize;
use serde_json::{json, Value};
use sqlx::{MySqlPool, Row};

use crate::auth::AuthContext;

/// Roles that may administer a study.
const ADMIN_ROLES: [&str; 2] = ["study_admin", "researcher"];

#[derive(Debug, Deserialize)]
pub struct CreateStudyRequest {
    pub name: String,
    pub description: Option<String>,
    pub consent_version: Option<String>,
    pub ethics_reference: Option<String>,
    pub data_retention_until: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct UpdateStudyRequest {
    pub name: Option<String>,
    pub description: Option<String>,
    pub status: Option<String>,
    pub consent_version: Option<String>,
    pub ethics_reference: Option<String>,
    pub data_retention_until: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct AddMemberRequest {
    /// Identify the member by exactly one of these.
    pub user_id: Option<i64>,
    pub participant_code: Option<String>,
    pub email: Option<String>,
    pub role: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct ConsentRequest {
    pub consent_version: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct SetPlatformRoleRequest {
    pub role: String,
}

#[derive(Debug, Deserialize)]
pub struct WithdrawRequest {
    /// Study admins may withdraw another member; participants may only
    /// withdraw themselves.
    pub user_id: Option<i64>,
    pub reason: Option<String>,
}

/// An authenticated user id, or an error explaining why there is none.
pub fn require_user(auth: &AuthContext) -> Result<i64> {
    auth.user_id
        .ok_or_else(|| anyhow!("study management requires a signed-in account"))
}

pub fn valid_status(status: &str) -> bool {
    matches!(
        status,
        "draft" | "recruiting" | "active" | "closed" | "archived"
    )
}

pub fn valid_member_role(role: &str) -> bool {
    matches!(role, "study_admin" | "researcher" | "participant")
}

/// The caller's active role in a study, if any.
pub async fn study_role(db: &MySqlPool, study_id: i64, user_id: i64) -> Result<Option<String>> {
    let row = sqlx::query(
        "SELECT role FROM study_memberships WHERE study_id = ? AND user_id = ? AND status = 'active'",
    )
    .bind(study_id)
    .bind(user_id)
    .fetch_optional(db)
    .await?;
    Ok(match row {
        Some(row) => Some(row.try_get::<String, _>("role")?),
        None => None,
    })
}

/// Whether the caller may administer a study: change it, or manage membership.
pub async fn can_administer(db: &MySqlPool, auth: &AuthContext, study_id: i64) -> Result<bool> {
    if auth.is_platform_admin() {
        return Ok(true);
    }
    let user_id = require_user(auth)?;
    Ok(study_role(db, study_id, user_id)
        .await?
        .is_some_and(|role| ADMIN_ROLES.contains(&role.as_str())))
}

/// Whether the caller may see a study at all.
pub async fn can_read(db: &MySqlPool, auth: &AuthContext, study_id: i64) -> Result<bool> {
    if auth.is_platform_admin() {
        return Ok(true);
    }
    let user_id = require_user(auth)?;
    Ok(study_role(db, study_id, user_id).await?.is_some())
}

/// Platform roles allowed to create studies. Accounts are provisioned as
/// `participant` on first sign-in, so a participant cannot open a study of
/// their own; a platform admin grants `researcher` to whoever runs studies.
const STUDY_CREATOR_ROLES: [&str; 2] = ["researcher", "platform_admin"];

/// Whether the caller's platform role allows creating a study.
pub fn can_create_study(auth: &AuthContext) -> bool {
    auth.platform_role
        .as_deref()
        .is_some_and(|role| STUDY_CREATOR_ROLES.contains(&role))
}

/// Create a study. The creator becomes its `study_admin`, so a study always has
/// someone able to administer it.
pub async fn create_study(
    db: &MySqlPool,
    auth: &AuthContext,
    request: CreateStudyRequest,
) -> Result<Value> {
    let user_id = require_user(auth)?;
    if !can_create_study(auth) {
        return Err(anyhow!(
            "only a researcher or platform admin can create studies; ask a platform admin to grant you the researcher role"
        ));
    }
    let name = request.name.trim();
    if name.is_empty() {
        return Err(anyhow!("name is required"));
    }

    let mut transaction = db.begin().await?;
    let created = sqlx::query(
        "INSERT INTO studies (name, description, status, created_by_user_id, consent_version, ethics_reference, data_retention_until) VALUES (?, ?, 'draft', ?, ?, ?, ?)",
    )
    .bind(name)
    .bind(trimmed(request.description.as_deref()))
    .bind(user_id)
    .bind(trimmed(request.consent_version.as_deref()))
    .bind(trimmed(request.ethics_reference.as_deref()))
    .bind(trimmed(request.data_retention_until.as_deref()))
    .execute(&mut *transaction)
    .await?;
    let study_id = created.last_insert_id() as i64;

    sqlx::query(
        "INSERT INTO study_memberships (study_id, user_id, role, status, joined_at) VALUES (?, ?, 'study_admin', 'active', NOW(3))",
    )
    .bind(study_id)
    .bind(user_id)
    .execute(&mut *transaction)
    .await?;
    transaction.commit().await?;

    Ok(json!({ "id": study_id, "name": name, "status": "draft" }))
}

/// Studies the caller can see: all of them for a platform admin, otherwise only
/// those they are an active member of.
pub async fn list_studies(db: &MySqlPool, auth: &AuthContext) -> Result<Value> {
    let rows = if auth.is_platform_admin() {
        sqlx::query(
            "SELECT s.id, s.name, s.status, s.consent_version, s.ethics_reference, NULL AS my_role, \
                    (SELECT COUNT(*) FROM study_memberships m WHERE m.study_id = s.id AND m.status = 'active') AS member_count \
             FROM studies s ORDER BY s.id",
        )
        .fetch_all(db)
        .await?
    } else {
        let user_id = require_user(auth)?;
        sqlx::query(
            "SELECT s.id, s.name, s.status, s.consent_version, s.ethics_reference, m.role AS my_role, \
                    (SELECT COUNT(*) FROM study_memberships m2 WHERE m2.study_id = s.id AND m2.status = 'active') AS member_count \
             FROM studies s JOIN study_memberships m ON m.study_id = s.id \
             WHERE m.user_id = ? AND m.status = 'active' ORDER BY s.id",
        )
        .bind(user_id)
        .fetch_all(db)
        .await?
    };

    let studies = rows
        .into_iter()
        .map(|row| {
            Ok(json!({
                "id": row.try_get::<i64, _>("id")?,
                "name": row.try_get::<String, _>("name")?,
                "status": row.try_get::<String, _>("status")?,
                "consent_version": row.try_get::<Option<String>, _>("consent_version")?,
                "ethics_reference": row.try_get::<Option<String>, _>("ethics_reference")?,
                "my_role": row.try_get::<Option<String>, _>("my_role")?,
                "member_count": row.try_get::<i64, _>("member_count")?,
            }))
        })
        .collect::<Result<Vec<_>>>()?;
    Ok(json!({ "studies": studies }))
}

pub async fn study_details(db: &MySqlPool, auth: &AuthContext, study_id: i64) -> Result<Value> {
    if !can_read(db, auth, study_id).await? {
        return Err(anyhow!("you do not have access to this study"));
    }
    let row = sqlx::query(
        "SELECT id, name, description, status, consent_version, ethics_reference, created_by_user_id FROM studies WHERE id = ?",
    )
    .bind(study_id)
    .fetch_optional(db)
    .await?
    .ok_or_else(|| anyhow!("study not found"))?;

    let experiments = sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(*) FROM experiments WHERE study_id = ?",
    )
    .bind(study_id)
    .fetch_one(db)
    .await?;
    let members = sqlx::query_scalar::<_, i64>(
        "SELECT COUNT(*) FROM study_memberships WHERE study_id = ? AND status = 'active'",
    )
    .bind(study_id)
    .fetch_one(db)
    .await?;

    Ok(json!({
        "id": row.try_get::<i64, _>("id")?,
        "name": row.try_get::<String, _>("name")?,
        "description": row.try_get::<Option<String>, _>("description")?,
        "status": row.try_get::<String, _>("status")?,
        "consent_version": row.try_get::<Option<String>, _>("consent_version")?,
        "ethics_reference": row.try_get::<Option<String>, _>("ethics_reference")?,
        "created_by_user_id": row.try_get::<i64, _>("created_by_user_id")?,
        "active_members": members,
        "experiments": experiments,
    }))
}

pub async fn update_study(
    db: &MySqlPool,
    auth: &AuthContext,
    study_id: i64,
    request: UpdateStudyRequest,
) -> Result<Value> {
    if !can_administer(db, auth, study_id).await? {
        return Err(anyhow!("you cannot administer this study"));
    }
    if let Some(status) = request.status.as_deref() {
        if !valid_status(status) {
            return Err(anyhow!(
                "status must be draft, recruiting, active, closed or archived"
            ));
        }
    }
    // COALESCE leaves every omitted field untouched.
    let result = sqlx::query(
        "UPDATE studies SET name = COALESCE(?, name), description = COALESCE(?, description), \
                status = COALESCE(?, status), consent_version = COALESCE(?, consent_version), \
                ethics_reference = COALESCE(?, ethics_reference), \
                data_retention_until = COALESCE(?, data_retention_until) \
         WHERE id = ?",
    )
    .bind(trimmed(request.name.as_deref()))
    .bind(trimmed(request.description.as_deref()))
    .bind(trimmed(request.status.as_deref()))
    .bind(trimmed(request.consent_version.as_deref()))
    .bind(trimmed(request.ethics_reference.as_deref()))
    .bind(trimmed(request.data_retention_until.as_deref()))
    .bind(study_id)
    .execute(db)
    .await?;
    if result.rows_affected() == 0 {
        // Either the study is gone, or nothing actually changed.
        let exists = sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM studies WHERE id = ?")
            .bind(study_id)
            .fetch_one(db)
            .await?;
        if exists == 0 {
            return Err(anyhow!("study not found"));
        }
    }
    study_details(db, auth, study_id).await
}

pub async fn list_members(db: &MySqlPool, auth: &AuthContext, study_id: i64) -> Result<Value> {
    if !can_administer(db, auth, study_id).await? {
        return Err(anyhow!("you cannot administer this study"));
    }
    let rows = sqlx::query(
        // sqlx is built without a date/time feature here, so project the
        // timestamps into strings and a flag MySQL-side rather than decoding
        // DATETIME values in Rust.
        "SELECT m.user_id, m.role, m.status, m.consent_version, \
                (m.consent_recorded_at IS NOT NULL) AS consent_recorded, \
                DATE_FORMAT(m.joined_at, '%Y-%m-%dT%H:%i:%S') AS joined_at, \
                DATE_FORMAT(m.withdrawn_at, '%Y-%m-%dT%H:%i:%S') AS withdrawn_at, \
                u.participant_code, u.display_name \
         FROM study_memberships m JOIN users u ON u.id = m.user_id \
         WHERE m.study_id = ? ORDER BY m.user_id",
    )
    .bind(study_id)
    .fetch_all(db)
    .await?;

    let members = rows
        .into_iter()
        .map(|row| {
            Ok(json!({
                "user_id": row.try_get::<i64, _>("user_id")?,
                "participant_code": row.try_get::<Option<String>, _>("participant_code")?,
                "display_name": row.try_get::<Option<String>, _>("display_name")?,
                "role": row.try_get::<String, _>("role")?,
                "status": row.try_get::<String, _>("status")?,
                "consent_version": row.try_get::<Option<String>, _>("consent_version")?,
                "consent_recorded": row.try_get::<i64, _>("consent_recorded")? != 0,
                "joined_at": row.try_get::<Option<String>, _>("joined_at")?,
                "withdrawn_at": row.try_get::<Option<String>, _>("withdrawn_at")?,
            }))
        })
        .collect::<Result<Vec<_>>>()?;
    Ok(json!({ "study_id": study_id, "members": members }))
}

pub async fn add_member(
    db: &MySqlPool,
    auth: &AuthContext,
    study_id: i64,
    request: AddMemberRequest,
) -> Result<Value> {
    if !can_administer(db, auth, study_id).await? {
        return Err(anyhow!("you cannot administer this study"));
    }
    let role = request.role.as_deref().unwrap_or("participant");
    if !valid_member_role(role) {
        return Err(anyhow!(
            "role must be study_admin, researcher or participant"
        ));
    }

    let user_id = resolve_member(db, &request).await?;

    // Re-adding a previously withdrawn member reopens their membership rather
    // than creating a second row, so their history stays on one record.
    sqlx::query(
        "INSERT INTO study_memberships (study_id, user_id, role, status) VALUES (?, ?, ?, 'invited') \
         ON DUPLICATE KEY UPDATE role = VALUES(role), status = 'invited', withdrawn_at = NULL, withdrawal_reason = NULL",
    )
    .bind(study_id)
    .bind(user_id)
    .bind(role)
    .execute(db)
    .await?;

    Ok(json!({ "study_id": study_id, "user_id": user_id, "role": role, "status": "invited" }))
}

async fn resolve_member(db: &MySqlPool, request: &AddMemberRequest) -> Result<i64> {
    let identifiers = [
        request.user_id.is_some(),
        trimmed(request.participant_code.as_deref()).is_some(),
        trimmed(request.email.as_deref()).is_some(),
    ]
    .into_iter()
    .filter(|present| *present)
    .count();
    if identifiers != 1 {
        return Err(anyhow!(
            "identify the member by exactly one of user_id, participant_code or email"
        ));
    }

    let row = if let Some(user_id) = request.user_id {
        sqlx::query("SELECT id FROM users WHERE id = ? AND status = 'active'")
            .bind(user_id)
            .fetch_optional(db)
            .await?
    } else if let Some(code) = trimmed(request.participant_code.as_deref()) {
        sqlx::query("SELECT id FROM users WHERE participant_code = ? AND status = 'active'")
            .bind(code)
            .fetch_optional(db)
            .await?
    } else {
        sqlx::query("SELECT id FROM users WHERE email = ? AND status = 'active'")
            .bind(trimmed(request.email.as_deref()))
            .fetch_optional(db)
            .await?
    };

    row.ok_or_else(|| anyhow!("no active account matches that identifier"))?
        .try_get::<i64, _>("id")
        .map_err(Into::into)
}

/// A participant records their own consent, which activates their membership.
/// Consent is never recorded on someone else's behalf.
pub async fn record_consent(
    db: &MySqlPool,
    auth: &AuthContext,
    study_id: i64,
    request: ConsentRequest,
) -> Result<Value> {
    let user_id = require_user(auth)?;
    let version = match trimmed(request.consent_version.as_deref()) {
        Some(version) => Some(version.to_string()),
        None => sqlx::query_scalar::<_, Option<String>>(
            "SELECT consent_version FROM studies WHERE id = ?",
        )
        .bind(study_id)
        .fetch_optional(db)
        .await?
        .flatten(),
    };

    let result = sqlx::query(
        "UPDATE study_memberships SET status = 'active', consent_version = COALESCE(?, consent_version), \
                consent_recorded_at = NOW(3), joined_at = COALESCE(joined_at, NOW(3)) \
         WHERE study_id = ? AND user_id = ? AND status IN ('invited', 'active')",
    )
    .bind(version.as_deref())
    .bind(study_id)
    .bind(user_id)
    .execute(db)
    .await?;
    if result.rows_affected() == 0 {
        return Err(anyhow!(
            "you have no open invitation to this study; a withdrawn membership must be reissued"
        ));
    }
    Ok(json!({ "study_id": study_id, "user_id": user_id, "status": "active", "consent_version": version }))
}

/// Withdraw from a study. Participants withdraw themselves; study admins may
/// withdraw another member.
///
/// Withdrawal marks the membership and stops further capture. It deliberately
/// does not delete evidence: erasure is a separate, audited operation.
pub async fn withdraw(
    db: &MySqlPool,
    auth: &AuthContext,
    study_id: i64,
    request: WithdrawRequest,
) -> Result<Value> {
    let caller = require_user(auth)?;
    let target = request.user_id.unwrap_or(caller);
    if target != caller && !can_administer(db, auth, study_id).await? {
        return Err(anyhow!("you can only withdraw yourself from this study"));
    }

    let result = sqlx::query(
        "UPDATE study_memberships SET status = 'withdrawn', withdrawn_at = NOW(3), withdrawal_reason = ? \
         WHERE study_id = ? AND user_id = ? AND status <> 'withdrawn'",
    )
    .bind(trimmed(request.reason.as_deref()))
    .bind(study_id)
    .bind(target)
    .execute(db)
    .await?;
    if result.rows_affected() == 0 {
        return Err(anyhow!("no active membership to withdraw"));
    }
    Ok(json!({
        "study_id": study_id,
        "user_id": target,
        "status": "withdrawn",
        "evidence_retained": true,
    }))
}

/// The caller's own account and studies. Clients use this after sign-in.
pub async fn me(db: &MySqlPool, auth: &AuthContext) -> Result<Value> {
    let Some(user_id) = auth.user_id else {
        return Ok(json!({
            "authenticated": false,
            "via": format!("{:?}", auth.via),
        }));
    };
    let row = sqlx::query(
        "SELECT id, email, participant_code, display_name, platform_role, mfa_enrolled FROM users WHERE id = ?",
    )
    .bind(user_id)
    .fetch_optional(db)
    .await?
    .ok_or_else(|| anyhow!("account not found"))?;

    let studies = list_studies(db, auth).await?;
    Ok(json!({
        "authenticated": true,
        "via": format!("{:?}", auth.via),
        "user_id": row.try_get::<i64, _>("id")?,
        "email": row.try_get::<Option<String>, _>("email")?,
        "participant_code": row.try_get::<Option<String>, _>("participant_code")?,
        "display_name": row.try_get::<Option<String>, _>("display_name")?,
        "platform_role": row.try_get::<String, _>("platform_role")?,
        "mfa_enrolled": row.try_get::<i8, _>("mfa_enrolled")? != 0,
        "studies": studies.get("studies").cloned().unwrap_or(json!([])),
    }))
}

/// Set a platform role by email, from the server's command line.
///
/// This is how the first platform admin is created on a new instance: the
/// person signs in once (which provisions their account), then whoever runs the
/// server grants the role. There is deliberately no HTTP route that does this
/// without an existing platform admin.
pub async fn set_platform_role_by_email(db: &MySqlPool, email: &str, role: &str) -> Result<Value> {
    if !matches!(role, "platform_admin" | "researcher" | "participant") {
        return Err(anyhow!(
            "platform_role must be platform_admin, researcher or participant"
        ));
    }
    let row = sqlx::query("SELECT id FROM users WHERE email = ?")
        .bind(email.trim())
        .fetch_optional(db)
        .await?
        .ok_or_else(|| anyhow!("no account with email {email}; they must sign in once first"))?;
    let user_id: i64 = row.try_get("id")?;
    sqlx::query("UPDATE users SET platform_role = ? WHERE id = ?")
        .bind(role)
        .bind(user_id)
        .execute(db)
        .await?;
    Ok(json!({ "user_id": user_id, "email": email.trim(), "platform_role": role }))
}

/// Promote or demote a platform role. Restricted to platform admins.
///
/// The first platform admin is created from the command line
/// (`wearable-backend set-platform-role <email> platform_admin`).
pub async fn set_platform_role(
    db: &MySqlPool,
    auth: &AuthContext,
    user_id: i64,
    role: &str,
) -> Result<Value> {
    if !auth.is_platform_admin() {
        return Err(anyhow!("only a platform admin can change platform roles"));
    }
    if !matches!(role, "platform_admin" | "researcher" | "participant") {
        return Err(anyhow!(
            "platform_role must be platform_admin, researcher or participant"
        ));
    }
    let result = sqlx::query("UPDATE users SET platform_role = ? WHERE id = ?")
        .bind(role)
        .bind(user_id)
        .execute(db)
        .await?;
    if result.rows_affected() == 0 {
        return Err(anyhow!("account not found"));
    }
    Ok(json!({ "user_id": user_id, "platform_role": role }))
}

fn trimmed(value: Option<&str>) -> Option<&str> {
    value.map(str::trim).filter(|value| !value.is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth::AuthVia;

    fn participant(user_id: i64) -> AuthContext {
        AuthContext {
            user_id: Some(user_id),
            subject: Some(format!("subject-{user_id}")),
            platform_role: Some("participant".to_string()),
            via: AuthVia::Jwt,
        }
    }

    #[test]
    fn study_management_requires_a_signed_in_account() {
        let shared = AuthContext::unattributed(AuthVia::SharedToken);
        assert!(require_user(&shared).is_err());
        assert!(require_user(&participant(7)).is_ok());
    }

    #[test]
    fn only_known_statuses_and_roles_are_accepted() {
        assert!(valid_status("recruiting"));
        assert!(valid_status("closed"));
        assert!(!valid_status("finished"));
        assert!(!valid_status(""));

        assert!(valid_member_role("study_admin"));
        assert!(valid_member_role("participant"));
        assert!(!valid_member_role("platform_admin"));
        assert!(!valid_member_role("owner"));
    }

    #[test]
    fn blank_optional_fields_are_treated_as_absent() {
        assert_eq!(trimmed(Some("  ")), None);
        assert_eq!(trimmed(Some(" v2 ")), Some("v2"));
        assert_eq!(trimmed(None), None);
    }
}

#[cfg(test)]
mod creator_tests {
    use super::*;
    use crate::auth::AuthVia;

    fn with_role(role: Option<&str>) -> AuthContext {
        AuthContext {
            platform_role: role.map(str::to_string),
            user_id: Some(1),
            ..AuthContext::unattributed(AuthVia::Jwt)
        }
    }

    #[test]
    fn only_researchers_and_admins_create_studies() {
        assert!(can_create_study(&with_role(Some("researcher"))));
        assert!(can_create_study(&with_role(Some("platform_admin"))));
        assert!(!can_create_study(&with_role(Some("participant"))));
        assert!(!can_create_study(&with_role(None)));
    }
}
