//! Request authentication and the identity it resolves to.
//!
//! Credentials are owned by an external identity provider (Supabase Auth);
//! authorisation is not. This module verifies the provider's token and maps its
//! subject onto a local `users` row. Platform roles and study membership live in
//! our own database and are never read from the token, so swapping providers —
//! or self-hosting one later — only changes how a token is verified.
//!
//! Supabase projects created since May 2025 sign with asymmetric keys (RS256 by
//! default), verified through the project's JWKS endpoint. Older projects sign
//! HS256 with a shared secret. Both are supported, selected by the token header.

use std::time::{Duration, Instant};

use anyhow::{anyhow, Context, Result};
use jsonwebtoken::{decode, decode_header, jwk::JwkSet, Algorithm, DecodingKey, Validation};
use serde::Deserialize;
use tokio::sync::RwLock;

/// How long a fetched JWKS is trusted before it is refetched.
const JWKS_TTL: Duration = Duration::from_secs(600);
/// Floor between key-rotation refetches, so an unknown `kid` cannot be used to
/// hammer the provider.
const JWKS_MIN_REFETCH: Duration = Duration::from_secs(30);

/// How a request proved who it is.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AuthVia {
    /// A verified identity-provider token belonging to a specific user.
    Jwt,
    /// The legacy deployment-wide bearer token. Carries no user identity.
    SharedToken,
    /// No credential was required, because none is configured.
    Anonymous,
}

/// Who a request acts as, resolved once by the middleware and read by handlers.
#[derive(Clone, Debug)]
pub struct AuthContext {
    /// Local `users.id`. `None` is the unattributed owner, which is what the
    /// shared token and anonymous access resolve to.
    pub user_id: Option<i64>,
    pub subject: Option<String>,
    pub platform_role: Option<String>,
    pub via: AuthVia,
}

impl AuthContext {
    pub fn unattributed(via: AuthVia) -> Self {
        Self {
            user_id: None,
            subject: None,
            platform_role: None,
            via,
        }
    }

    /// The owner key used for experiment ownership.
    pub fn owner(&self) -> Option<i64> {
        self.user_id
    }

    pub fn is_platform_admin(&self) -> bool {
        self.platform_role.as_deref() == Some("platform_admin")
    }
}

#[derive(Clone, Debug)]
pub struct AuthConfig {
    /// JWKS endpoint for asymmetric verification.
    pub jwks_url: Option<String>,
    /// Legacy symmetric secret, for HS256 tokens only.
    pub jwt_secret: Option<String>,
    pub audience: String,
    pub issuer: Option<String>,
    /// Whether the deployment-wide bearer token is still accepted. Kept true
    /// during the transition so existing clients do not break; set false once
    /// every client signs in.
    pub allow_shared_token: bool,
}

impl AuthConfig {
    pub fn from_env(env_or: impl Fn(&str, &str) -> String) -> Self {
        let supabase_url = std::env::var("SUPABASE_URL")
            .ok()
            .filter(|value| !value.is_empty());
        let jwks_url = std::env::var("AUTH_JWKS_URL")
            .ok()
            .filter(|value| !value.is_empty())
            .or_else(|| {
                supabase_url
                    .as_ref()
                    .map(|base| format!("{}/auth/v1/.well-known/jwks.json", base.trim_end_matches('/')))
            });
        Self {
            jwks_url,
            jwt_secret: std::env::var("SUPABASE_JWT_SECRET")
                .ok()
                .filter(|value| !value.is_empty()),
            audience: env_or("AUTH_JWT_AUDIENCE", "authenticated"),
            issuer: std::env::var("AUTH_JWT_ISSUER")
                .ok()
                .filter(|value| !value.is_empty()),
            allow_shared_token: !env_or("AUTH_ALLOW_SHARED_TOKEN", "true")
                .eq_ignore_ascii_case("false"),
        }
    }

    /// True when the backend can verify user tokens at all.
    pub fn user_auth_enabled(&self) -> bool {
        self.jwks_url.is_some() || self.jwt_secret.is_some()
    }
}

/// Claims we rely on. Everything else in the token is deliberately ignored:
/// roles and study membership come from our database, not from the provider.
#[derive(Debug, Deserialize)]
pub struct Claims {
    pub sub: String,
    #[serde(default)]
    pub email: Option<String>,
}

#[derive(Default)]
struct CachedJwks {
    keys: Option<JwkSet>,
    fetched_at: Option<Instant>,
}

pub struct JwksCache {
    cache: RwLock<CachedJwks>,
    http: reqwest::Client,
}

impl JwksCache {
    pub fn new() -> Self {
        Self {
            cache: RwLock::new(CachedJwks::default()),
            http: reqwest::Client::builder()
                .timeout(Duration::from_secs(10))
                .build()
                .unwrap_or_default(),
        }
    }

    async fn fetch(&self, url: &str) -> Result<JwkSet> {
        let set = self
            .http
            .get(url)
            .send()
            .await
            .context("fetch JWKS")?
            .error_for_status()
            .context("JWKS endpoint returned an error status")?
            .json::<JwkSet>()
            .await
            .context("parse JWKS")?;
        Ok(set)
    }

    /// Resolve a key id to a decoding key, refetching once if the id is unknown
    /// so that provider key rotation does not require a restart.
    async fn decoding_key(&self, url: &str, kid: &str) -> Result<DecodingKey> {
        let (cached, fetched_at) = {
            let guard = self.cache.read().await;
            (guard.keys.clone(), guard.fetched_at)
        };

        let fresh = fetched_at.is_some_and(|at| at.elapsed() < JWKS_TTL);
        if fresh {
            if let Some(set) = cached.as_ref() {
                if let Some(jwk) = set.find(kid) {
                    return DecodingKey::from_jwk(jwk).context("build decoding key from JWKS");
                }
            }
        }

        // Either stale, empty, or the key id is unknown. Refetch, but not more
        // often than JWKS_MIN_REFETCH.
        let may_refetch = fetched_at.is_none_or(|at| at.elapsed() >= JWKS_MIN_REFETCH);
        if may_refetch {
            let set = self.fetch(url).await?;
            {
                let mut guard = self.cache.write().await;
                guard.keys = Some(set.clone());
                guard.fetched_at = Some(Instant::now());
            }
            if let Some(jwk) = set.find(kid) {
                return DecodingKey::from_jwk(jwk).context("build decoding key from JWKS");
            }
            return Err(anyhow!("no JWKS key matches key id {kid}"));
        }

        if let Some(set) = cached.as_ref() {
            if let Some(jwk) = set.find(kid) {
                return DecodingKey::from_jwk(jwk).context("build decoding key from JWKS");
            }
        }
        Err(anyhow!("no JWKS key matches key id {kid}"))
    }
}

impl Default for JwksCache {
    fn default() -> Self {
        Self::new()
    }
}

fn validation_for(config: &AuthConfig, algorithm: Algorithm) -> Validation {
    let mut validation = Validation::new(algorithm);
    validation.set_audience(&[config.audience.as_str()]);
    if let Some(issuer) = config.issuer.as_deref() {
        validation.set_issuer(&[issuer]);
    }
    validation.validate_exp = true;
    // jsonwebtoken defaults to 60 seconds of leeway. Set it explicitly and
    // small: enough for clock skew between the provider and this host, not
    // enough to keep accepting a token a minute after it expires.
    validation.leeway = 5;
    validation
}

/// Verify a bearer token and return its claims.
///
/// The algorithm in the token header selects the key: HS256 uses the legacy
/// shared secret, anything else is resolved through JWKS. A token asking for an
/// algorithm we have no key for is rejected rather than downgraded.
pub async fn verify_token(config: &AuthConfig, jwks: &JwksCache, token: &str) -> Result<Claims> {
    let header = decode_header(token).context("decode token header")?;

    let key = if header.alg == Algorithm::HS256 {
        let secret = config
            .jwt_secret
            .as_deref()
            .ok_or_else(|| anyhow!("token is HS256 but no shared JWT secret is configured"))?;
        DecodingKey::from_secret(secret.as_bytes())
    } else {
        let url = config
            .jwks_url
            .as_deref()
            .ok_or_else(|| anyhow!("token is {:?} but no JWKS endpoint is configured", header.alg))?;
        let kid = header
            .kid
            .as_deref()
            .ok_or_else(|| anyhow!("token has no key id"))?;
        jwks.decoding_key(url, kid).await?
    };

    let data = decode::<Claims>(token, &key, &validation_for(config, header.alg))
        .context("verify token")?;
    if data.claims.sub.trim().is_empty() {
        return Err(anyhow!("token subject is empty"));
    }
    Ok(data.claims)
}

/// A bearer token is a JWT if it has three dot-separated parts. Used only to
/// decide which credential path to try, never to trust the token.
pub fn looks_like_jwt(token: &str) -> bool {
    token.split('.').count() == 3
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config() -> AuthConfig {
        AuthConfig {
            jwks_url: None,
            jwt_secret: None,
            audience: "authenticated".to_string(),
            issuer: None,
            allow_shared_token: true,
        }
    }

    #[test]
    fn shared_tokens_are_not_mistaken_for_jwts() {
        assert!(!looks_like_jwt("a-long-random-deployment-token"));
        assert!(!looks_like_jwt(""));
        assert!(looks_like_jwt("header.payload.signature"));
    }

    #[test]
    fn user_auth_is_disabled_until_a_key_source_is_configured() {
        let mut config = config();
        assert!(!config.user_auth_enabled());
        config.jwt_secret = Some("secret".to_string());
        assert!(config.user_auth_enabled());
    }

    #[test]
    fn unattributed_context_owns_nothing_and_is_not_admin() {
        let context = AuthContext::unattributed(AuthVia::SharedToken);
        assert_eq!(context.owner(), None);
        assert!(!context.is_platform_admin());
    }

    #[tokio::test]
    async fn hs256_token_round_trips_and_rejects_a_wrong_secret() {
        use jsonwebtoken::{encode, EncodingKey, Header};

        #[derive(serde::Serialize)]
        struct TestClaims {
            sub: String,
            aud: String,
            exp: usize,
            email: String,
        }

        let mut config = config();
        config.jwt_secret = Some("correct-horse-battery-staple".to_string());
        let jwks = JwksCache::new();

        let exp = (std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs()
            + 3600) as usize;
        let claims = TestClaims {
            sub: "user-uuid-1".to_string(),
            aud: "authenticated".to_string(),
            exp,
            email: "p@example.org".to_string(),
        };
        let token = encode(
            &Header::new(Algorithm::HS256),
            &claims,
            &EncodingKey::from_secret(b"correct-horse-battery-staple"),
        )
        .unwrap();

        let verified = verify_token(&config, &jwks, &token).await.unwrap();
        assert_eq!(verified.sub, "user-uuid-1");
        assert_eq!(verified.email.as_deref(), Some("p@example.org"));

        config.jwt_secret = Some("a-different-secret".to_string());
        assert!(verify_token(&config, &jwks, &token).await.is_err());
    }

    #[tokio::test]
    async fn expired_and_wrong_audience_tokens_are_rejected() {
        use jsonwebtoken::{encode, EncodingKey, Header};

        #[derive(serde::Serialize)]
        struct TestClaims {
            sub: String,
            aud: String,
            exp: usize,
        }

        let mut config = config();
        config.jwt_secret = Some("s3cret".to_string());
        let jwks = JwksCache::new();
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();

        let expired = encode(
            &Header::new(Algorithm::HS256),
            &TestClaims {
                sub: "u".to_string(),
                aud: "authenticated".to_string(),
                exp: (now - 3600) as usize,
            },
            &EncodingKey::from_secret(b"s3cret"),
        )
        .unwrap();
        assert!(verify_token(&config, &jwks, &expired).await.is_err());

        let wrong_audience = encode(
            &Header::new(Algorithm::HS256),
            &TestClaims {
                sub: "u".to_string(),
                aud: "some-other-service".to_string(),
                exp: (now + 3600) as usize,
            },
            &EncodingKey::from_secret(b"s3cret"),
        )
        .unwrap();
        assert!(verify_token(&config, &jwks, &wrong_audience).await.is_err());
    }

    #[tokio::test]
    async fn asymmetric_token_is_rejected_when_no_jwks_is_configured() {
        // A token must not be downgraded to a key source we did not intend.
        let config = AuthConfig {
            jwt_secret: Some("s3cret".to_string()),
            ..config()
        };
        let jwks = JwksCache::new();
        // header alg=RS256, unsigned payload; rejected before signature checks.
        let token = "eyJhbGciOiJSUzI1NiIsImtpZCI6ImsxIn0.eyJzdWIiOiJ1In0.sig";
        let error = verify_token(&config, &jwks, token).await.unwrap_err();
        assert!(error.to_string().contains("JWKS"), "unexpected: {error}");
    }
}
