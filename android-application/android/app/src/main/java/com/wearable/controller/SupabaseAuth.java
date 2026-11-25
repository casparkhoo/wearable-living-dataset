package com.wearable.controller;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Participant sign-in against Supabase Auth.
 *
 * Supabase owns credentials only. It never sees research data, and it does not
 * decide what this participant may do: the backend reads the platform role and
 * study membership from its own database. This class therefore does one job —
 * obtain and keep a valid access token.
 *
 * Tokens live in {@link EncryptedSharedPreferences}, not plain preferences, so
 * they are encrypted at rest with a key held in the Android keystore. The
 * backend URL and MQTT settings remain in ordinary preferences; participant
 * credentials do not.
 */
public final class SupabaseAuth {
    private static final String TAG = "SupabaseAuth";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String PREFS = "auth_secure";
    private static final String KEY_ACCESS = "access_token";
    private static final String KEY_REFRESH = "refresh_token";
    private static final String KEY_EXPIRES_AT = "expires_at_ms";
    private static final String KEY_EMAIL = "email";

    /** Refresh this long before expiry so a request never carries a dead token. */
    private static final long REFRESH_MARGIN_MS = 60_000L;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private final SharedPreferences prefs;
    private volatile String projectUrl = "";
    private volatile String publishableKey = "";

    public SupabaseAuth(Context context) {
        this.prefs = openSecurePreferences(context.getApplicationContext());
    }

    private static SharedPreferences openSecurePreferences(Context context) {
        try {
            // security-crypto 1.0.0 API: a keystore alias rather than a MasterKey
            // object. The key itself never leaves the Android keystore.
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            return EncryptedSharedPreferences.create(
                    PREFS,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            // Keystore unavailable (very old or unusual devices). Falling back to
            // plain preferences would silently downgrade credential storage, so
            // refuse instead and let the caller surface it.
            Log.e(TAG, "encrypted preferences unavailable", e);
            throw new IllegalStateException("secure credential storage is unavailable", e);
        }
    }

    /** Supabase project URL and publishable key. Sign-in is unavailable until both are set. */
    public void configure(String url, String key) {
        String normalized = url == null ? "" : url.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        this.projectUrl = normalized;
        this.publishableKey = key == null ? "" : key.trim();
    }

    public boolean isConfigured() {
        return !projectUrl.isEmpty() && !publishableKey.isEmpty();
    }

    public boolean isSignedIn() {
        return !prefs.getString(KEY_REFRESH, "").isEmpty();
    }

    public String getSignedInEmail() {
        return prefs.getString(KEY_EMAIL, "");
    }

    /**
     * Sign in with email and password. Blocking; call from a background thread.
     *
     * @return null on success, or a message describing why sign-in failed.
     */
    public String signIn(String email, String password) {
        if (!isConfigured()) {
            return "Sign-in is not configured. Set the Supabase URL and key first.";
        }
        JsonObject body = new JsonObject();
        body.addProperty("email", email == null ? "" : email.trim());
        body.addProperty("password", password == null ? "" : password);

        Request request = new Request.Builder()
                .url(projectUrl + "/auth/v1/token?grant_type=password")
                .addHeader("apikey", publishableKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        return exchange(request, email);
    }

    /**
     * A valid access token, refreshing it if it is close to expiry.
     * Blocking; call from a background thread. Returns "" when not signed in.
     */
    public String getValidAccessToken() {
        String access = prefs.getString(KEY_ACCESS, "");
        long expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L);
        if (!access.isEmpty() && System.currentTimeMillis() < expiresAt - REFRESH_MARGIN_MS) {
            return access;
        }
        String refresh = prefs.getString(KEY_REFRESH, "");
        if (refresh.isEmpty() || !isConfigured()) {
            return "";
        }

        JsonObject body = new JsonObject();
        body.addProperty("refresh_token", refresh);
        Request request = new Request.Builder()
                .url(projectUrl + "/auth/v1/token?grant_type=refresh_token")
                .addHeader("apikey", publishableKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        String error = exchange(request, null);
        if (error != null) {
            Log.w(TAG, "token refresh failed: " + error);
            // A refresh token is single-use and may have been rotated or revoked.
            // Returning the stale access token would produce a confusing 401, so
            // report signed-out instead and let the participant sign in again.
            return "";
        }
        return prefs.getString(KEY_ACCESS, "");
    }

    /**
     * Create an account. Blocking; call from a background thread.
     *
     * Returns null on success. The provider requires email confirmation, so a
     * successful call usually does not produce a session: the caller should say
     * the address needs confirming rather than assume the account is usable.
     */
    public String signUp(String email, String password) {
        if (!isConfigured()) {
            return "Sign-up is not configured in this build.";
        }
        JsonObject body = new JsonObject();
        body.addProperty("email", email == null ? "" : email.trim());
        body.addProperty("password", password == null ? "" : password);

        Request request = new Request.Builder()
                .url(projectUrl + "/auth/v1/signup")
                .addHeader("apikey", publishableKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        // A session only comes back when confirmation is disabled; exchange()
        // stores it when present and is harmless when it is not.
        try (Response response = client.newCall(request).execute()) {
            String payload = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                return describeFailure(response.code(), payload);
            }
            return null;
        } catch (IOException e) {
            return "Could not reach the identity provider: " + e.getMessage();
        } catch (RuntimeException e) {
            return "Unexpected response from the identity provider.";
        }
    }

    /**
     * Ask the provider to email a password-reset link. Blocking.
     *
     * Returns null on success. The provider answers the same way whether or not
     * the address has an account, and this does not inspect that, so the phone
     * cannot be used to discover who holds one either.
     */
    public String requestPasswordReset(String email) {
        if (!isConfigured()) {
            return "Password reset is not configured in this build.";
        }
        JsonObject body = new JsonObject();
        body.addProperty("email", email == null ? "" : email.trim());

        Request request = new Request.Builder()
                .url(projectUrl + "/auth/v1/recover")
                .addHeader("apikey", publishableKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            String payload = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                return describeFailure(response.code(), payload);
            }
            return null;
        } catch (IOException e) {
            return "Could not reach the identity provider: " + e.getMessage();
        }
    }

    /** Forget the session locally and best-effort revoke it at the provider. */
    public void signOut() {
        String access = prefs.getString(KEY_ACCESS, "");
        prefs.edit().clear().apply();
        if (access.isEmpty() || !isConfigured()) {
            return;
        }
        try {
            Request request = new Request.Builder()
                    .url(projectUrl + "/auth/v1/logout")
                    .addHeader("apikey", publishableKey)
                    .addHeader("Authorization", "Bearer " + access)
                    .post(RequestBody.create("", JSON))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                Log.i(TAG, "sign-out returned " + response.code());
            }
        } catch (IOException e) {
            // The local session is already cleared, which is what matters here.
            Log.w(TAG, "sign-out request failed", e);
        }
    }

    /** Run a token request and persist the result. Returns null on success. */
    private String exchange(Request request, String emailForDisplay) {
        try (Response response = client.newCall(request).execute()) {
            String payload = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                return describeFailure(response.code(), payload);
            }
            JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
            String access = optString(json, "access_token");
            String refresh = optString(json, "refresh_token");
            if (access.isEmpty() || refresh.isEmpty()) {
                return "The identity provider returned an unexpected response.";
            }
            long expiresIn = json.has("expires_in") && !json.get("expires_in").isJsonNull()
                    ? json.get("expires_in").getAsLong()
                    : 3600L;

            String email = emailForDisplay;
            if (email == null || email.trim().isEmpty()) {
                email = prefs.getString(KEY_EMAIL, "");
                if (json.has("user") && json.get("user").isJsonObject()) {
                    String fromUser = optString(json.getAsJsonObject("user"), "email");
                    if (!fromUser.isEmpty()) email = fromUser;
                }
            }

            prefs.edit()
                    .putString(KEY_ACCESS, access)
                    .putString(KEY_REFRESH, refresh)
                    .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000L)
                    .putString(KEY_EMAIL, email == null ? "" : email.trim())
                    .apply();
            return null;
        } catch (IOException e) {
            return "Could not reach the identity provider: " + e.getMessage();
        } catch (RuntimeException e) {
            return "Unexpected response from the identity provider.";
        }
    }

    private static String describeFailure(int code, String payload) {
        String detail = "";
        try {
            JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
            detail = optString(json, "error_description");
            if (detail.isEmpty()) detail = optString(json, "msg");
            if (detail.isEmpty()) detail = optString(json, "message");
        } catch (RuntimeException ignored) {
            // Not JSON; fall through to the status-based message.
        }
        if (code == 400 || code == 401) {
            return detail.isEmpty() ? "Incorrect email or password." : detail;
        }
        if (code == 422) {
            return detail.isEmpty() ? "That email or password is not valid." : detail;
        }
        if (code == 429) {
            return "Too many attempts. Wait a moment and try again.";
        }
        return detail.isEmpty() ? ("Sign-in failed (" + code + ")") : detail;
    }

    private static String optString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }
}
