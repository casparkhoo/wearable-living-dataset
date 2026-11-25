package com.wearable.controller;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The first screen: sign in, create an account, or continue as a guest.
 *
 * A restored session skips straight to the app, so a participant sees this
 * only when signed out. Sign-in talks to the identity provider directly; the
 * main screen then picks up the stored session and sets up the cloud
 * connection from it.
 */
public class LoginActivity extends AppCompatActivity {

    /** A line to show on arrival, e.g. after a server switch. */
    static final String EXTRA_MESSAGE = "message";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SupabaseAuth auth;
    private EditText emailInput;
    private EditText passwordInput;
    private TextView message;
    private Button signInButton;
    private Button createButton;
    private Button forgotButton;
    private TextView serverText;


    /**
     * Draw the whole app at a smaller scale: more fits on the screen and there is less
     * scrolling. A uniform change of density rather than per-view sizes, so dialogs and
     * every tab follow.
     */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        android.content.res.Configuration config = new android.content.res.Configuration(base.getResources().getConfiguration());
        config.densityDpi = Math.round(config.densityDpi * 0.86f);
        super.attachBaseContext(base.createConfigurationContext(config));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (!getSharedPreferences("display", MODE_PRIVATE).contains("dark_mode")) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        }
        if (getSharedPreferences("display", MODE_PRIVATE).contains("dark_mode")) {
            AppCompatDelegate.setDefaultNightMode(
                    getSharedPreferences("display", MODE_PRIVATE).getBoolean("dark_mode", false)
                            ? AppCompatDelegate.MODE_NIGHT_YES
                            : AppCompatDelegate.MODE_NIGHT_NO);
        }
        super.onCreate(savedInstanceState);

        try {
            auth = new SupabaseAuth(this);
            auth.configure(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY);
        } catch (IllegalStateException e) {
            // No secure storage: sign-in is unavailable, so go in as a guest.
            openMain();
            return;
        }
        SetupLink pendingLink = linkFromIntent(getIntent());
        if (pendingLink == null && (auth.isSignedIn() || !auth.isConfigured())) {
            openMain();
            return;
        }

        setContentView(R.layout.activity_login);
        emailInput = findViewById(R.id.login_email);
        passwordInput = findViewById(R.id.login_password);
        message = findViewById(R.id.login_message);
        signInButton = findViewById(R.id.login_sign_in);
        createButton = findViewById(R.id.login_create_account);
        forgotButton = findViewById(R.id.login_forgot);

        signInButton.setOnClickListener(v -> signIn());
        passwordInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                signIn();
                return true;
            }
            return false;
        });
        createButton.setOnClickListener(v -> createAccount());
        forgotButton.setOnClickListener(v -> resetPassword());
        findViewById(R.id.login_guest).setOnClickListener(v -> openMain());
        serverText = findViewById(R.id.login_server);
        findViewById(R.id.login_server_change).setOnClickListener(v -> ServerSwitcher.showPicker(this, serverHost));
        refreshServerText();
        String carried = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_MESSAGE);
        if (carried != null) show(carried, false);
        if (pendingLink != null) {
            if (pendingLink.isValid()) ServerSwitcher.confirmLink(this, pendingLink, serverHost);
            else show(pendingLink.error, true);
        }
    }

    /** A wearablesetup:// link that opened the app; null for a normal launch. */
    private SetupLink linkFromIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return null;
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) return null;
        return SetupLink.parse(intent.getData().toString());
    }

    private void refreshServerText() {
        serverText.setText(ServerSwitcher.summary(this));
    }

    /** Sign out and start the login screen afresh so nothing from the old server stays in the task. */
    private final ServerSwitcher.Host serverHost = new ServerSwitcher.Host() {
        @Override
        public boolean canSwitch() {
            return true;
        }

        @Override
        public void onServerChanged(String text) {
            refreshServerText();
            show(text, false);
            executor.execute(() -> {
                auth.signOut();
                runOnUiThread(() -> startActivity(new Intent(LoginActivity.this, LoginActivity.class)
                        .putExtra(EXTRA_MESSAGE, text)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)));
            });
        }
    };

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        SetupLink link = linkFromIntent(intent);
        if (link == null || serverText == null) return;
        if (link.isValid()) ServerSwitcher.confirmLink(this, link, serverHost);
        else show(link.error, true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void signIn() {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (email.isEmpty() || password.isEmpty()) {
            show("Enter your email and password.", true);
            return;
        }
        run("Signing in…", () -> auth.signIn(email, password), error -> {
            if (error == null) {
                openMain();
            } else {
                show(error, true);
            }
        });
    }

    private void createAccount() {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (email.isEmpty() || password.length() < 8) {
            show("Enter an email and a password of at least 8 characters, then tap Create account.", true);
            return;
        }
        run("Creating account…", () -> auth.signUp(email, password), error -> show(error == null
                ? "Account created. Confirm your email address, then sign in."
                : error, error != null));
    }

    private void resetPassword() {
        String email = emailInput.getText().toString().trim();
        if (email.isEmpty()) {
            show("Enter your email address first.", true);
            return;
        }
        run("Sending link…", () -> auth.requestPasswordReset(email), error -> show(error == null
                // Same wording whether or not the address has an account.
                ? "If that address has an account, a reset link is on its way."
                : error, error != null));
    }

    private interface Call {
        String run();
    }

    private interface Done {
        void accept(String error);
    }

    /** Run a provider call off the UI thread with the buttons disabled. */
    private void run(String busyLabel, Call call, Done done) {
        setBusy(true, busyLabel);
        executor.execute(() -> {
            String error = call.run();
            runOnUiThread(() -> {
                setBusy(false, null);
                done.accept(error);
            });
        });
    }

    private void setBusy(boolean busy, String label) {
        signInButton.setEnabled(!busy);
        createButton.setEnabled(!busy);
        forgotButton.setEnabled(!busy);
        signInButton.setText(busy ? label : "Sign in");
        if (busy) message.setVisibility(View.GONE);
    }

    private void show(String text, boolean isError) {
        message.setText(text);
        message.setTextColor(ContextCompat.getColor(this,
                isError ? R.color.status_red : R.color.status_green));
        message.setVisibility(View.VISIBLE);
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }
}
