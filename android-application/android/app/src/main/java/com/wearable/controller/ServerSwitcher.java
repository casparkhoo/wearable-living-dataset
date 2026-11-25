package com.wearable.controller;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.List;

/**
 * The dialogs for choosing a server: a one-tap list of profiles (built-in "Cloud" plus the
 * saved ones), entering or pasting an address, saving it as a named profile. Used by the
 * login screen and the main app. Every change goes through {@link InstanceConfig}, which
 * drops the broker login, topic settings and API token of the old server; the
 * {@link Host} then ends the session and shows the sign-in screen.
 */
public final class ServerSwitcher {

    /** What the screen using the switcher does around a change. */
    public interface Host {
        /** False when switching now would be harmful (for example during a recording). */
        boolean canSwitch();

        /** The server was changed and stale state cleared; sign out and show sign-in. */
        void onServerChanged(String message);
    }

    private ServerSwitcher() {}

    /** "Cloud: api.example.org" for the status line. */
    public static String summary(Context context) {
        String host = SetupLink.fromInput(InstanceConfig.backendUrl(context)).backendHost();
        if (host.isEmpty()) return "Server: none chosen";
        return "Server: " + InstanceConfig.activeLabel(context)
                + (host.equals(InstanceConfig.activeLabel(context)) ? "" : " (" + host + ")");
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** The one-tap list. */
    public static void showPicker(Activity activity, Host host) {
        List<ServerProfile> saved = ServerProfiles.sorted(InstanceConfig.profiles(activity));
        boolean builtInActive = !InstanceConfig.hasOverride(activity);
        String activeName = InstanceConfig.activeName(activity);

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(activity, 12), dp(activity, 4), dp(activity, 12), 0);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("Switch server")
                .setView(list)
                .setNegativeButton("Close", null)
                .setPositiveButton("Add server", null);
        final AlertDialog dialog = builder.create();

        if (!InstanceConfig.noBuiltIn()) {
            SetupLink built = SetupLink.fromInput(InstanceConfig.builtInBackend());
            list.addView(row(activity, ServerProfiles.BUILT_IN_NAME, built.backendHost(),
                    builtInActive, false, () -> {
                        dialog.dismiss();
                        if (builtInActive) return;
                        if (!host.canSwitch()) { blocked(activity); return; }
                        InstanceConfig.reset(activity);
                        host.onServerChanged("Switched to Cloud. Sign in to continue.");
                    }, null));
        }
        for (ServerProfile p : saved) {
            boolean active = !builtInActive && p.name.equalsIgnoreCase(activeName)
                    && p.sameServer(InstanceConfig.backendUrl(activity), InstanceConfig.brokerUrl(activity));
            list.addView(row(activity, p.name, p.backendHost(), active, !active, () -> {
                dialog.dismiss();
                if (active) return;
                if (!host.canSwitch()) { blocked(activity); return; }
                InstanceConfig.switchTo(activity, p);
                host.onServerChanged("Switched to " + p.name + ". Sign in to continue.");
            }, () -> {
                dialog.dismiss();
                confirmDelete(activity, p, host);
            }));
        }
        if (list.getChildCount() == 0) {
            TextView none = new TextView(activity);
            none.setText("No servers saved yet. Add one with a setup link or an address.");
            none.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8));
            list.addView(none);
        }
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            dialog.dismiss();
            showAddressDialog(activity, host);
        });
    }

    private static void blocked(Activity activity) {
        Toast.makeText(activity, "Stop the current recording before switching server.", Toast.LENGTH_LONG).show();
    }

    private static View row(Activity activity, String name, String hostText, boolean active, boolean deletable,
                            Runnable onTap, Runnable onDelete) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(activity, 8), dp(activity, 10), dp(activity, 4), dp(activity, 10));
        TextView text = new TextView(activity);
        text.setText((active ? "●  " : "○  ") + name + (hostText.isEmpty() ? "" : "\n     " + hostText));
        text.setTextSize(15);
        if (active) text.setTypeface(null, android.graphics.Typeface.BOLD);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(text);
        row.setOnClickListener(v -> onTap.run());
        if (deletable && onDelete != null) {
            TextView del = new TextView(activity);
            del.setText("Delete");
            del.setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8));
            del.setOnClickListener(v -> onDelete.run());
            row.addView(del);
        }
        return row;
    }

    private static void confirmDelete(Activity activity, ServerProfile p, Host host) {
        new AlertDialog.Builder(activity)
                .setTitle("Delete \"" + p.name + "\"?")
                .setMessage("Removes the saved server " + p.backendHost() + ". Your account on it is not affected.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    InstanceConfig.saveProfiles(activity,
                            ServerProfiles.without(InstanceConfig.profiles(activity), p.name));
                    Toast.makeText(activity, "Deleted " + p.name, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /** Type or paste a server address or a setup link. */
    public static void showAddressDialog(Activity activity, Host host) {
        EditText input = new EditText(activity);
        input.setHint("https://server or setup link");
        input.setSingleLine(true);
        LinearLayout box = new LinearLayout(activity);
        box.setPadding(dp(activity, 20), dp(activity, 10), dp(activity, 20), 0);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(activity)
                .setTitle("Add a server")
                .setMessage("Paste the setup link from your study console, or type the server address.")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (d, w) -> {
                    SetupLink link = SetupLink.fromInput(input.getText().toString());
                    if (link.isValid()) confirmLink(activity, link, host);
                    else Toast.makeText(activity, link.error, Toast.LENGTH_LONG).show();
                })
                .show();
    }

    /** A setup link is untrusted: show where it points, offer to save it, ask before using it. */
    public static void confirmLink(Activity activity, SetupLink link, Host host) {
        if (!InstanceConfig.differsFromCurrent(activity, link)) {
            Toast.makeText(activity, "Already using " + link.backendHost() + ".", Toast.LENGTH_LONG).show();
            return;
        }
        if (!host.canSwitch()) { blocked(activity); return; }
        List<ServerProfile> saved = InstanceConfig.profiles(activity);
        ServerProfile existing = ServerProfiles.findByServer(saved, link.backend, link.broker);
        boolean isBuiltIn = !InstanceConfig.noBuiltIn()
                && link.backend.equals(InstanceConfig.builtInBackend())
                && link.broker.equals(InstanceConfig.builtInBroker());
        String broker = link.broker.isEmpty() ? "none given" : link.brokerHost();

        CheckBox save = new CheckBox(activity);
        save.setText("Save as profile");
        save.setChecked(true);
        EditText name = new EditText(activity);
        name.setSingleLine(true);
        name.setText(ServerProfiles.defaultName(link.backendHost(), saved));
        name.setSelectAllOnFocus(true);
        save.setOnCheckedChangeListener((b, checked) -> name.setEnabled(checked));
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(activity, 20), dp(activity, 6), dp(activity, 20), 0);
        if (existing == null && !isBuiltIn) {
            box.addView(save);
            box.addView(name);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("Use this server?")
                .setMessage("Server: " + link.backendHost() + "\nBroker: " + broker
                        + (existing != null ? "\nSaved as: " + existing.name : "")
                        + "\n\nOnly continue if you trust this server. You will be signed out and "
                        + "stored broker logins for the old server are removed.")
                .setNegativeButton("Cancel", null);
        if (box.getChildCount() > 0) builder.setView(box);
        AlertDialog dialog = builder.setPositiveButton("Use this server", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String profileName = "";
            if (isBuiltIn) {
                InstanceConfig.reset(activity);
                dialog.dismiss();
                host.onServerChanged("Switched to Cloud. Sign in to continue.");
                return;
            }
            if (existing != null) {
                profileName = existing.name;
            } else if (save.isChecked()) {
                String error = ServerProfiles.validateName(name.getText().toString(), saved);
                if (error != null) {
                    name.setError(error);
                    return;
                }
                profileName = name.getText().toString().trim();
                InstanceConfig.saveProfiles(activity,
                        ServerProfiles.with(saved, new ServerProfile(profileName, link.backend, link.broker)));
            }
            InstanceConfig.apply(activity, link, profileName);
            dialog.dismiss();
            host.onServerChanged("Server set to "
                    + (profileName.isEmpty() ? link.backendHost() : profileName) + ". Sign in to continue.");
        });
    }
}
