package com.wearable.controller.fragments;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.wearable.controller.R;

/**
 * Settings page: the account first, then connection status.
 *
 * A participant only signs in. The cloud endpoints are built into the app and
 * the account supplies the broker login, so the manual broker and backend
 * fields are tucked under "advanced" and only apply to the Local profile used
 * for development on a home network.
 */
public class MqttFragment extends Fragment {

    public interface MqttActionListener {
        void onSelectProfile(String profile);
        void onConnectBroker(String brokerUrl, String username, String password);
        void onDisconnectBroker();
        void onSaveBackend(String backendUrl, String apiToken);

        /** Sign in to the identity provider. */
        void onSignIn(String email, String password);

        /** Sign out and forget the stored session. */
        void onSignOut();

        /** Open the server list (cloud, local, saved profiles). */
        void onSwitchServer();

        /** Create an account with the entered email and password. */
        void onSignUp(String email, String password);

        /** Ask the provider to email a password-reset link. */
        void onResetPassword(String email);
    }

    private View statusDot;
    private TextView statusText;
    private TextView brokerLabel;
    private TextView publishedCount;
    private TextView errorCount;
    private TextView defaultBrokerView;
    private TextView topicPrefixView;
    private TextView qosView;
    private EditText brokerInput;
    private EditText mqttUsernameInput;
    private EditText mqttPasswordInput;
    private Spinner profileInput;
    private EditText backendInput;
    private EditText apiTokenInput;
    private View backendStatusDot;
    private TextView backendStatusText;
    private TextView backendStatusDetail;
    private TextView authStatusText;
    private TextView authDetailText;
    private EditText authEmailInput;
    private EditText authPasswordInput;
    private Button authSignInButton;
    private Button authSignOutButton;
    private Button authSignUpButton;
    private Button authResetButton;
    private View authSecondaryRow;
    private View authEmailLayout;
    private View authPasswordLayout;
    private View advancedContainer;
    private View localSettingsContainer;
    private View cloudEndpointNote;
    private Button advancedToggleButton;
    private boolean advancedOpen;

    private String cachedAuthStatus = "Signed out";
    private String cachedAuthDetail = "Sign in to contribute to a study.";
    private boolean cachedSignedIn;
    private boolean cachedAuthBusy;

    private MqttActionListener actionListener;

    // Cached state so values survive view recreation (ViewPager fragment lifecycle).
    private boolean cachedConnected;
    private boolean cachedConnecting;
    private String cachedBroker = "";
    private int cachedPublished;
    private int cachedErrors;
    private String defaultBroker = "";
    private String topicPrefix = "";
    private int qos;
    private boolean brokerPrefilled;
    private String cachedBackend = "";
    private String cachedApiToken = "";
    private String cachedBackendState = "untested";
    private String cachedBackendDetail = "";
    private String cachedProfile = "cloud";
    private boolean suppressProfileCallback;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mqtt, container, false);

        statusDot = view.findViewById(R.id.mqtt_status_dot);
        statusText = view.findViewById(R.id.mqtt_status_text);
        brokerLabel = view.findViewById(R.id.mqtt_broker_label);
        publishedCount = view.findViewById(R.id.mqtt_published_count);
        errorCount = view.findViewById(R.id.mqtt_error_count);
        defaultBrokerView = view.findViewById(R.id.mqtt_default_broker);
        topicPrefixView = view.findViewById(R.id.mqtt_topic_prefix);
        qosView = view.findViewById(R.id.mqtt_qos);
        brokerInput = view.findViewById(R.id.mqtt_broker_input);
        mqttUsernameInput = view.findViewById(R.id.mqtt_username_input);
        mqttPasswordInput = view.findViewById(R.id.mqtt_password_input);
        profileInput = view.findViewById(R.id.connection_profile_input);
        backendInput = view.findViewById(R.id.settings_backend_input);
        apiTokenInput = view.findViewById(R.id.settings_api_token_input);
        backendStatusDot = view.findViewById(R.id.backend_status_dot);
        backendStatusText = view.findViewById(R.id.backend_status_text);
        backendStatusDetail = view.findViewById(R.id.backend_status_detail);

        Button connectButton = view.findViewById(R.id.mqtt_connect_button);
        Button disconnectButton = view.findViewById(R.id.mqtt_disconnect_button);
        Button saveBackendButton = view.findViewById(R.id.settings_save_backend_button);

        TextView serverText = view.findViewById(R.id.settings_server_text);
        if (serverText != null) {
            serverText.setText(com.wearable.controller.ServerSwitcher.summary(requireContext()));
        }
        View serverSwitch = view.findViewById(R.id.settings_server_switch);
        if (serverSwitch != null) {
            serverSwitch.setOnClickListener(v -> {
                if (actionListener != null) actionListener.onSwitchServer();
            });
        }

        authStatusText = view.findViewById(R.id.auth_status_text);
        authDetailText = view.findViewById(R.id.auth_detail_text);
        authEmailInput = view.findViewById(R.id.auth_email_input);
        authPasswordInput = view.findViewById(R.id.auth_password_input);
        authSignInButton = view.findViewById(R.id.auth_sign_in_button);
        authSignOutButton = view.findViewById(R.id.auth_sign_out_button);
        authSignUpButton = view.findViewById(R.id.auth_sign_up_button);
        authResetButton = view.findViewById(R.id.auth_reset_button);
        authSecondaryRow = view.findViewById(R.id.auth_secondary_row);
        authEmailLayout = view.findViewById(R.id.auth_email_layout);
        authPasswordLayout = view.findViewById(R.id.auth_password_layout);
        advancedContainer = view.findViewById(R.id.advanced_container);
        localSettingsContainer = view.findViewById(R.id.local_settings_container);
        cloudEndpointNote = view.findViewById(R.id.cloud_endpoint_note);
        advancedToggleButton = view.findViewById(R.id.advanced_toggle_button);
        advancedToggleButton.setOnClickListener(v -> {
            advancedOpen = !advancedOpen;
            applyProfileVisibility();
        });
        bindDarkModeSwitch(view.findViewById(R.id.dark_mode_switch));

        if (authSignUpButton != null) {
            authSignUpButton.setOnClickListener(v -> {
                if (actionListener == null) return;
                String email = authEmailInput == null ? "" : authEmailInput.getText().toString();
                String password = authPasswordInput == null ? "" : authPasswordInput.getText().toString();
                if (email.trim().isEmpty() || password.length() < 8) {
                    setAuthState(cachedSignedIn, cachedAuthStatus,
                            "Enter an email and a password of at least 8 characters.", false);
                    return;
                }
                actionListener.onSignUp(email, password);
            });
        }
        if (authResetButton != null) {
            authResetButton.setOnClickListener(v -> {
                if (actionListener == null) return;
                String email = authEmailInput == null ? "" : authEmailInput.getText().toString();
                if (email.trim().isEmpty()) {
                    setAuthState(cachedSignedIn, cachedAuthStatus,
                            "Enter the email address for your account first.", false);
                    return;
                }
                actionListener.onResetPassword(email);
            });
        }

        if (authSignInButton != null) {
            authSignInButton.setOnClickListener(v -> {
                if (actionListener == null) return;
                String email = authEmailInput == null ? "" : authEmailInput.getText().toString();
                String password = authPasswordInput == null ? "" : authPasswordInput.getText().toString();
                if (email.trim().isEmpty() || password.isEmpty()) {
                    setAuthState(cachedSignedIn, cachedAuthStatus,
                            "Enter both an email address and a password.", false);
                    return;
                }
                actionListener.onSignIn(email, password);
            });
        }
        if (authSignOutButton != null) {
            authSignOutButton.setOnClickListener(v -> {
                if (actionListener != null) actionListener.onSignOut();
            });
        }
        applyAuthState();

        if (profileInput != null) {
            // Set the saved profile before registering the listener. Android may
            // emit an initial selection event when the listener is attached.
            suppressProfileCallback = true;
            profileInput.setSelection("cloud".equals(cachedProfile) ? 1 : 0);
            suppressProfileCallback = false;
            profileInput.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View selected, int position, long id) {
                    if (suppressProfileCallback) return;
                    cachedProfile = position == 1 ? "cloud" : "local";
                    applyProfileVisibility();
                    if (actionListener != null) actionListener.onSelectProfile(cachedProfile);
                }

                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) {
                }
            });
        }

        if (!brokerPrefilled) {
            String prefill = !cachedBroker.isEmpty() ? cachedBroker : defaultBroker;
            brokerInput.setText(prefill);
            brokerPrefilled = true;
        }

        connectButton.setOnClickListener(v -> {
            if (actionListener != null) {
                String url = brokerInput.getText().toString().trim();
                if (!url.isEmpty()) {
                    actionListener.onConnectBroker(url,
                            mqttUsernameInput.getText().toString().trim(),
                            mqttPasswordInput.getText().toString());
                }
            }
        });

        disconnectButton.setOnClickListener(v -> {
            if (actionListener != null) {
                actionListener.onDisconnectBroker();
            }
        });
        backendInput.setText(cachedBackend);
        apiTokenInput.setText(cachedApiToken);
        saveBackendButton.setOnClickListener(v -> {
            if (actionListener != null) {
                actionListener.onSaveBackend(backendInput.getText().toString(),
                        apiTokenInput.getText().toString());
            }
        });

        applyDefaults();
        applyState();
        applyStats();
        applyBackendState();
        applyProfileVisibility();
        return view;
    }

    /**
     * Without a saved choice the app follows the system setting, so the
     * switch starts from whichever mode is showing.
     */
    private void bindDarkModeSwitch(android.widget.CompoundButton toggle) {
        android.content.SharedPreferences display =
                requireContext().getSharedPreferences("display", android.content.Context.MODE_PRIVATE);
        boolean dark = display.contains("dark_mode")
                ? display.getBoolean("dark_mode", false)
                : (getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        toggle.setChecked(dark);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            display.edit().putBoolean("dark_mode", checked).apply();
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(checked
                    ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        });
    }

    /** Manual endpoints only mean something for the Local profile. */
    private void applyProfileVisibility() {
        if (advancedContainer == null) return;
        boolean cloud = "cloud".equals(cachedProfile);
        advancedContainer.setVisibility(advancedOpen ? View.VISIBLE : View.GONE);
        advancedToggleButton.setText(advancedOpen ? "Hide advanced settings" : "Show advanced settings");
        localSettingsContainer.setVisibility(cloud ? View.GONE : View.VISIBLE);
        cloudEndpointNote.setVisibility(cloud ? View.VISIBLE : View.GONE);
    }

    public void setMqttActionListener(MqttActionListener listener) {
        this.actionListener = listener;
    }

    public void setBackendUrl(String backendUrl) {
        cachedBackend = backendUrl == null ? "" : backendUrl;
        if (backendInput != null) {
            backendInput.setText(cachedBackend);
        }
    }

    public void setProfile(String profile) {
        cachedProfile = "cloud".equals(profile) ? "cloud" : "local";
        if (profileInput != null) {
            suppressProfileCallback = true;
            profileInput.setSelection("cloud".equals(cachedProfile) ? 1 : 0);
            suppressProfileCallback = false;
        }
        applyProfileVisibility();
    }

    public void setConnectionValues(String broker, String username, String password, String backend, String apiToken) {
        cachedBroker = broker == null ? "" : broker;
        cachedBackend = backend == null ? "" : backend;
        cachedApiToken = apiToken == null ? "" : apiToken;
        brokerPrefilled = false;
        if (brokerInput != null) brokerInput.setText(cachedBroker);
        if (mqttUsernameInput != null) mqttUsernameInput.setText(username == null ? "" : username);
        if (mqttPasswordInput != null) mqttPasswordInput.setText(password == null ? "" : password);
        if (backendInput != null) backendInput.setText(cachedBackend);
        if (apiTokenInput != null) apiTokenInput.setText(cachedApiToken);
        applyState();
    }

    public void setBackendStatus(String state, String detail) {
        cachedBackendState = state == null ? "untested" : state;
        cachedBackendDetail = detail == null ? "" : detail;
        applyBackendState();
    }

    public void setDefaults(String broker, String topic, int qos) {
        this.defaultBroker = broker != null ? broker : "";
        this.topicPrefix = topic != null ? topic : "";
        this.qos = qos;
        applyDefaults();
    }

    public void setStatus(boolean connected, boolean connecting, String broker) {
        this.cachedConnected = connected;
        this.cachedConnecting = connecting;
        if (broker != null) {
            this.cachedBroker = broker;
        }
        applyState();
    }

    public void setStats(int published, int errors) {
        this.cachedPublished = published;
        this.cachedErrors = errors;
        applyStats();
    }

    private void applyDefaults() {
        if (defaultBrokerView != null) defaultBrokerView.setText(defaultBroker);
        if (topicPrefixView != null) topicPrefixView.setText(topicPrefix);
        if (qosView != null) qosView.setText(String.valueOf(qos));
    }

    private void applyStats() {
        if (publishedCount != null) publishedCount.setText(String.valueOf(cachedPublished));
        if (errorCount != null) errorCount.setText(String.valueOf(cachedErrors));
    }

    private void applyState() {
        if (statusText == null || getContext() == null) {
            return;
        }

        final int colorRes;
        final String label;
        if (cachedConnected) {
            colorRes = R.color.status_green;
            label = "Connected";
        } else if (cachedConnecting) {
            colorRes = R.color.status_amber;
            label = "Connecting…";
        } else {
            colorRes = R.color.warm_muted;
            label = "Disconnected";
        }

        int color = ContextCompat.getColor(getContext(), colorRes);
        statusText.setText(label);
        statusText.setTextColor(color);
        if (statusDot != null) {
            statusDot.setBackgroundTintList(ColorStateList.valueOf(color));
        }
        if (brokerLabel != null) {
            brokerLabel.setText(cachedBroker.isEmpty() ? defaultBroker : cachedBroker);
        }
    }

    private void applyBackendState() {
        if (backendStatusText == null || getContext() == null) return;
        final int colorRes;
        final String label;
        switch (cachedBackendState) {
            // Matches the web console: what matters is whether the service is
            // reachable, not the name of the component behind it.
            case "connected":
                colorRes = R.color.status_green;
                label = "Online";
                break;
            case "testing":
                colorRes = R.color.status_amber;
                label = "Connecting…";
                break;
            case "failed":
                colorRes = R.color.status_red;
                label = "Offline";
                break;
            default:
                colorRes = R.color.warm_muted;
                label = "Not tested";
        }
        int color = ContextCompat.getColor(getContext(), colorRes);
        backendStatusText.setText(label);
        backendStatusText.setTextColor(color);
        backendStatusDot.setBackgroundTintList(ColorStateList.valueOf(color));
        backendStatusDetail.setText(cachedBackendDetail);
    }

    /**
     * Reflect sign-in state. Called from the UI thread.
     *
     * @param signedIn whether a session is held
     * @param status   short headline, for example the signed-in email
     * @param detail   supporting line, including any error
     * @param busy     true while a sign-in request is in flight
     */
    public void setAuthState(boolean signedIn, String status, String detail, boolean busy) {
        cachedSignedIn = signedIn;
        cachedAuthStatus = status == null ? "" : status;
        cachedAuthDetail = detail == null ? "" : detail;
        cachedAuthBusy = busy;
        applyAuthState();
    }

    private void applyAuthState() {
        if (authStatusText == null) return;
        authStatusText.setText(cachedAuthStatus);
        if (authDetailText != null) authDetailText.setText(cachedAuthDetail);

        // Held as fields: during onCreateView getView() is still null, so
        // looking the layouts up through it left them visible while signed in.
        int credentialVisibility = cachedSignedIn ? View.GONE : View.VISIBLE;
        if (authEmailLayout != null) authEmailLayout.setVisibility(credentialVisibility);
        if (authPasswordLayout != null) authPasswordLayout.setVisibility(credentialVisibility);

        if (authSignInButton != null) {
            authSignInButton.setVisibility(credentialVisibility);
            authSignInButton.setEnabled(!cachedAuthBusy);
            authSignInButton.setText(cachedAuthBusy ? "Signing in..." : "Sign in");
        }
        if (authSignOutButton != null) {
            authSignOutButton.setVisibility(cachedSignedIn ? View.VISIBLE : View.GONE);
        }
        if (authSecondaryRow != null) authSecondaryRow.setVisibility(credentialVisibility);
        if (authSignUpButton != null) authSignUpButton.setEnabled(!cachedAuthBusy);
        if (authResetButton != null) authResetButton.setEnabled(!cachedAuthBusy);
        if (cachedSignedIn && authPasswordInput != null) {
            // Never leave a typed password sitting in the field after sign-in.
            authPasswordInput.setText("");
        }
    }
}
