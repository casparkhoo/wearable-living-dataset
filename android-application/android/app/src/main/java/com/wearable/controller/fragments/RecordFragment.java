package com.wearable.controller.fragments;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.wearable.controller.R;
import com.wearable.controller.impl.GlassesImage;
import com.wearable.controller.views.GlassesPreviewView;
import com.wearable.controller.views.TraceView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The home screen: who is recording, whether the cloud and wearables are
 * connected, one button to start or stop, and activity labels as large chips.
 *
 * It holds no recording logic. MainActivity pushes state in and receives the
 * participant's taps; everything is cached so it survives the pager
 * recreating this page.
 */
public class RecordFragment extends Fragment {

    /** A study the signed-in account may record into. */
    public static final class StudyOption {
        public final Long id;
        public final String name;

        public StudyOption(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public interface ActionListener {
        /** Start when idle, stop when recording. Both fields may be blank. */
        void onToggleRecording(String name, String protocol);

        /**
         * A label chip was tapped: start it, stop it if it is the active one,
         * or switch to it from another.
         */
        void onLabelTapped(String label);

        void onStudySelected(Long studyId);

        void onOpenSettings();

        void onOpenDevices();
    }

    private ActionListener actionListener;

    private TextView identityCaption;
    private TextView identityName;
    private Button signInButton;
    private TextView cloudChip;
    private TextView wearablesChip;
    private View studyRow;
    private Spinner studySpinner;
    private Button toggleButton;
    private TextView recordStatus;
    private View optionsContainer;
    private Button optionsToggle;
    private EditText nameInput;
    private EditText protocolInput;
    private TextView labelStatus;
    private View liveCard;
    private TextView liveText;
    private View liveTraces;
    private GlassesPreviewView glassesPreview;
    private View glassesCard;
    private boolean glassesConnected;
    private TextView glassesState;
    private TextView glassesLeftChip;
    private TextView glassesRightChip;
    private TraceView tracePpg;
    private TraceView traceAccel;
    private TraceView traceHr;
    private TextView traceHrLabel;
    private android.widget.LinearLayout liveDevices;
    private final java.util.List<LiveDevice> liveDeviceList = new java.util.ArrayList<>();

    /** What one wearable is reporting live; shown as its own row. */
    public static final class LiveDevice {
        public final String name;
        /** DeviceProfile type of the wearable, for lookups that must not depend on its name. */
        public final String type;
        public final String heartRate;
        public final String detail;
        public final float[] movement;
        public final float[] heartRateTrend;
        public final String trendRange;

        public LiveDevice(String name, String heartRate, String detail,
                          float[] movement, float[] heartRateTrend, String trendRange) {
            this(name, "", heartRate, detail, movement, heartRateTrend, trendRange);
        }

        public LiveDevice(String name, String type, String heartRate, String detail,
                          float[] movement, float[] heartRateTrend, String trendRange) {
            this.name = name;
            this.type = type == null ? "" : type;
            this.heartRate = heartRate;
            this.detail = detail;
            this.movement = movement == null ? new float[0] : movement;
            this.heartRateTrend = heartRateTrend == null ? new float[0] : heartRateTrend;
            this.trendRange = trendRange == null ? "" : trendRange;
        }
    }

    /** One row per recording wearable; replaces the single combined chart. */
    public void setLiveDevices(java.util.List<LiveDevice> devices) {
        liveDeviceList.clear();
        if (devices != null) liveDeviceList.addAll(devices);
        applyLiveDevices();
    }

    private void applyLiveDevices() {
        if (liveDevices == null || getContext() == null) return;
        android.content.Context context = getContext();
        float density = context.getResources().getDisplayMetrics().density;
        // Rebuilt only when the set of wearables changes; otherwise updated in place.
        boolean same = liveDevices.getChildCount() == liveDeviceList.size();
        for (int i = 0; same && i < liveDeviceList.size(); i++) {
            same = liveDeviceList.get(i).name.equals(liveDevices.getChildAt(i).getTag());
        }
        if (!same) {
            liveDevices.removeAllViews();
            for (LiveDevice device : liveDeviceList) {
                android.widget.LinearLayout row = new android.widget.LinearLayout(context);
                row.setOrientation(android.widget.LinearLayout.VERTICAL);
                row.setTag(device.name);
                row.setPadding(0, (int) (8 * density), 0, 0);
                TextView title = new TextView(context);
                title.setTextSize(13);
                title.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
                title.setTextColor(ContextCompat.getColor(context, R.color.warm_text));
                row.addView(title);
                TextView detail = new TextView(context);
                detail.setTextSize(11);
                detail.setTextColor(ContextCompat.getColor(context, R.color.warm_muted));
                row.addView(detail);
                android.widget.LinearLayout charts = new android.widget.LinearLayout(context);
                charts.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                for (int k = 0; k < 2; k++) {
                    android.widget.LinearLayout column = new android.widget.LinearLayout(context);
                    column.setOrientation(android.widget.LinearLayout.VERTICAL);
                    android.widget.LinearLayout.LayoutParams params =
                            new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    params.setMargins(k == 0 ? 0 : (int) (4 * density), (int) (4 * density), k == 0 ? (int) (4 * density) : 0, 0);
                    column.setLayoutParams(params);
                    TextView label = new TextView(context);
                    label.setTextSize(10);
                    label.setTextColor(ContextCompat.getColor(context, R.color.warm_muted));
                    column.addView(label);
                    TraceView trace = new TraceView(context, null);
                    trace.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, (int) (40 * density)));
                    trace.setBackgroundColor(ContextCompat.getColor(context, R.color.surface_variant));
                    trace.setLineColor(ContextCompat.getColor(context, k == 0 ? R.color.brand : R.color.status_red));
                    column.addView(trace);
                    charts.addView(column);
                }
                row.addView(charts);
                liveDevices.addView(row);
            }
        }
        for (int i = 0; i < liveDeviceList.size(); i++) {
            LiveDevice device = liveDeviceList.get(i);
            android.widget.LinearLayout row = (android.widget.LinearLayout) liveDevices.getChildAt(i);
            ((TextView) row.getChildAt(0)).setText(device.name + "   " + device.heartRate);
            ((TextView) row.getChildAt(1)).setText(device.detail);
            android.widget.LinearLayout charts = (android.widget.LinearLayout) row.getChildAt(2);
            android.widget.LinearLayout movement = (android.widget.LinearLayout) charts.getChildAt(0);
            android.widget.LinearLayout trend = (android.widget.LinearLayout) charts.getChildAt(1);
            ((TextView) movement.getChildAt(0)).setText("Movement · 10 s");
            ((TraceView) movement.getChildAt(1)).setValues(device.movement);
            ((TextView) trend.getChildAt(0)).setText("Heart rate · 5 min" + (device.trendRange.isEmpty() ? "" : " · " + device.trendRange));
            ((TraceView) trend.getChildAt(1)).setValues(device.heartRateTrend);
        }
    }
    private final Map<String, MaterialButton> labelButtons = new LinkedHashMap<>();
    private MaterialButton otherButton;

    private boolean signedIn;
    private String name = "Sign in to start";
    private String caption = "Not signed in";
    private boolean cloudOnline;
    private String cloudLabel = "Cloud offline";
    private int wearablesConnected;
    private int wearablesKnown;
    private boolean recording;
    private boolean transitioning;
    private String status = "Connect a wearable to start";
    private String activeLabel;
    private String labelSince = "";
    private String live = "";
    private float[] ppgTrace = new float[0];
    private float[] accelTrace = new float[0];
    private float[] hrTrace = new float[0];
    private String hrRange = "";
    private GlassesImage.Dashboard glassesDashboard;
    private String glassesLeftText = "left --";
    private String glassesRightText = "right --";
    private boolean glassesHeadUp;
    private final List<StudyOption> studies = new ArrayList<>();
    private Long selectedStudyId;
    private boolean optionsOpen;
    private boolean suppressStudyCallback;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_record, container, false);
        // The participant strip lives in the activity's top bar.
        android.app.Activity host = requireActivity();
        identityCaption = host.findViewById(R.id.record_identity_caption);
        identityName = host.findViewById(R.id.record_identity_name);
        signInButton = view.findViewById(R.id.record_sign_in_button);
        cloudChip = host.findViewById(R.id.record_cloud_chip);
        wearablesChip = host.findViewById(R.id.record_wearables_chip);
        studyRow = view.findViewById(R.id.record_study_row);
        studySpinner = view.findViewById(R.id.study_spinner);
        toggleButton = view.findViewById(R.id.record_toggle_button);
        recordStatus = view.findViewById(R.id.record_status);
        optionsContainer = view.findViewById(R.id.record_options);
        optionsToggle = view.findViewById(R.id.record_options_toggle);
        nameInput = view.findViewById(R.id.experiment_label_input);
        protocolInput = view.findViewById(R.id.protocol_input);
        labelStatus = view.findViewById(R.id.label_status);
        liveCard = view.findViewById(R.id.live_card);
        liveText = view.findViewById(R.id.live_text);
        liveTraces = view.findViewById(R.id.live_traces);
        liveDevices = view.findViewById(R.id.live_devices);
        glassesPreview = view.findViewById(R.id.glasses_preview);
        glassesCard = view.findViewById(R.id.glasses_card);
        glassesCard.setVisibility(glassesConnected ? View.VISIBLE : View.GONE);
        glassesState = view.findViewById(R.id.glasses_state);
        glassesLeftChip = view.findViewById(R.id.glasses_left_chip);
        glassesRightChip = view.findViewById(R.id.glasses_right_chip);
        if (glassesDashboard != null) glassesPreview.setDashboard(glassesDashboard);
        glassesLeftChip.setText(glassesLeftText);
        glassesRightChip.setText(glassesRightText);
        tracePpg = view.findViewById(R.id.trace_ppg);
        traceAccel = view.findViewById(R.id.trace_accel);
        traceHr = view.findViewById(R.id.trace_hr);
        traceHrLabel = view.findViewById(R.id.trace_hr_label);
        tracePpg.setLineColor(ContextCompat.getColor(requireContext(), R.color.status_green));
        traceAccel.setLineColor(ContextCompat.getColor(requireContext(), R.color.brand));
        traceHr.setLineColor(ContextCompat.getColor(requireContext(), R.color.status_red));

        labelButtons.clear();
        labelButtons.put("Sitting", view.findViewById(R.id.label_sitting));
        labelButtons.put("Standing", view.findViewById(R.id.label_standing));
        labelButtons.put("Walking", view.findViewById(R.id.label_walking));
        labelButtons.put("Running", view.findViewById(R.id.label_running));
        labelButtons.put("Lying down", view.findViewById(R.id.label_lying));
        otherButton = view.findViewById(R.id.label_other);
        for (Map.Entry<String, MaterialButton> entry : labelButtons.entrySet()) {
            entry.getValue().setOnClickListener(v -> {
                if (actionListener != null) actionListener.onLabelTapped(entry.getKey());
            });
        }
        otherButton.setOnClickListener(v -> onOtherTapped());

        signInButton.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onOpenSettings();
        });
        wearablesChip.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onOpenDevices();
        });
        cloudChip.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onOpenSettings();
        });
        toggleButton.setOnClickListener(v -> {
            if (actionListener == null) return;
            actionListener.onToggleRecording(nameInput.getText().toString(),
                    protocolInput.getText().toString());
        });
        optionsToggle.setOnClickListener(v -> {
            optionsOpen = !optionsOpen;
            apply();
        });
        studySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View selected, int position, long id) {
                if (suppressStudyCallback || position < 0 || position >= studies.size()) return;
                selectedStudyId = studies.get(position).id;
                if (actionListener != null) actionListener.onStudySelected(selectedStudyId);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        applyStudies();
        apply();
        return view;
    }

    public void setActionListener(ActionListener listener) {
        this.actionListener = listener;
    }

    public void setIdentity(boolean signedIn, String name, String caption) {
        this.signedIn = signedIn;
        this.name = name == null ? "" : name;
        this.caption = caption == null ? "" : caption;
        apply();
    }

    public void setConnectionStatus(boolean cloudOnline, String cloudLabel,
                                    int wearablesConnected, int wearablesKnown) {
        this.cloudOnline = cloudOnline;
        this.cloudLabel = cloudLabel == null ? "" : cloudLabel;
        this.wearablesConnected = wearablesConnected;
        this.wearablesKnown = wearablesKnown;
        apply();
    }

    public void setRecordingState(boolean recording, boolean transitioning, String status) {
        this.recording = recording;
        this.transitioning = transitioning;
        this.status = status == null ? "" : status;
        if (!recording && optionsOpen && nameInput != null) {
            // A finished recording's name should not be reused by accident.
            nameInput.setText("");
        }
        apply();
    }

    /** @param label the running label, or null; @param since e.g. "1:05" */
    public void setActiveLabel(String label, String since) {
        this.activeLabel = label;
        this.labelSince = since == null ? "" : since;
        apply();
    }

    public void setLive(String text) {
        this.live = text == null ? "" : text;
        apply();
    }

    /** The dashboard as the glasses show it, drawn on the Glasses view card. */
    public void setGlassesPreview(GlassesImage.Dashboard dashboard) {
        this.glassesDashboard = dashboard;
        if (glassesPreview != null && dashboard != null) glassesPreview.setDashboard(dashboard);
    }

    /** Battery text for each arm, and whether the wearer is looking up. */
    public void setGlassesArms(String left, String right, boolean headUp, boolean connected) {
        this.glassesConnected = connected;
        if (glassesCard != null) glassesCard.setVisibility(connected ? View.VISIBLE : View.GONE);
        this.glassesLeftText = left;
        this.glassesRightText = right;
        this.glassesHeadUp = headUp;
        if (glassesLeftChip != null) glassesLeftChip.setText(left);
        if (glassesRightChip != null) glassesRightChip.setText(right);
        if (glassesState != null) glassesState.setText(headUp ? "looking up: showing" : "shown when you look up");
    }

    /** What to plot from the latest backend window; any may be empty. */
    public void setLiveTraces(float[] ppg, float[] accel, float[] hr, String hrRange) {
        this.ppgTrace = ppg == null ? new float[0] : ppg;
        this.accelTrace = accel == null ? new float[0] : accel;
        this.hrTrace = hr == null ? new float[0] : hr;
        this.hrRange = hrRange == null ? "" : hrRange;
        apply();
    }

    /**
     * Offer the studies this account may record into, preselecting
     * {@code selected} when it is still offered. "No study" is always
     * available, because recording outside a study remains valid.
     */
    public void setStudyOptions(List<StudyOption> options, Long selected) {
        studies.clear();
        studies.add(new StudyOption(null, "No study"));
        if (options != null) studies.addAll(options);
        selectedStudyId = null;
        for (StudyOption option : studies) {
            if (option.id != null && option.id.equals(selected)) selectedStudyId = selected;
        }
        applyStudies();
    }

    public Long getSelectedStudyId() {
        return selectedStudyId;
    }

    private void onOtherTapped() {
        if (actionListener == null || getContext() == null) return;
        if (activeLabel != null && !labelButtons.containsKey(activeLabel)) {
            // The custom label is running: tapping Other stops it, like any chip.
            actionListener.onLabelTapped(activeLabel);
            return;
        }
        EditText input = new EditText(getContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint("e.g. Cycling");
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        new AlertDialog.Builder(getContext())
                .setTitle("Other activity")
                .setView(input)
                .setPositiveButton("Start", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isEmpty()) actionListener.onLabelTapped(value);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // State keeps arriving while this page has no view; onCreateView
        // applies the cached state again. (getView() cannot be the test: it is
        // still null inside onCreateView.)
        studySpinner = null;
        identityName = null;
    }

    private void applyStudies() {
        if (studySpinner == null || getContext() == null) return;
        ArrayAdapter<StudyOption> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, new ArrayList<>(studies));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        suppressStudyCallback = true;
        studySpinner.setAdapter(adapter);
        for (int i = 0; i < studies.size(); i++) {
            Long id = studies.get(i).id;
            if (id == null ? selectedStudyId == null : id.equals(selectedStudyId)) {
                studySpinner.setSelection(i);
            }
        }
        suppressStudyCallback = false;
        // With only "No study" on offer there is nothing to choose.
        studyRow.setVisibility(studies.size() > 1 ? View.VISIBLE : View.GONE);
        studySpinner.setEnabled(!recording);
    }

    private void apply() {
        if (identityName == null || getContext() == null) return;
        identityCaption.setText(caption);
        identityName.setText(name);
        signInButton.setVisibility(signedIn ? View.GONE : View.VISIBLE);

        int green = ContextCompat.getColor(getContext(), R.color.status_green);
        int muted = ContextCompat.getColor(getContext(), R.color.warm_muted);
        int amber = ContextCompat.getColor(getContext(), R.color.status_amber);
        int red = ContextCompat.getColor(getContext(), R.color.status_red);
        int brand = ContextCompat.getColor(getContext(), R.color.brand);
        int white = ContextCompat.getColor(getContext(), R.color.white);

        cloudChip.setText("● " + cloudLabel);
        cloudChip.setTextColor(cloudOnline ? green : muted);
        if (wearablesConnected > 0) {
            wearablesChip.setText("● " + wearablesConnected
                    + (wearablesConnected == 1 ? " wearable" : " wearables"));
            wearablesChip.setTextColor(green);
        } else if (wearablesKnown > 0) {
            wearablesChip.setText("● Searching…");
            wearablesChip.setTextColor(amber);
        } else {
            wearablesChip.setText("● Add a wearable");
            wearablesChip.setTextColor(muted);
        }

        toggleButton.setText(transitioning ? "Please wait…" : recording ? "Stop recording" : "Start recording");
        toggleButton.setEnabled(!transitioning);
        toggleButton.setBackgroundTintList(ColorStateList.valueOf(recording ? red : brand));
        recordStatus.setText(status);
        optionsToggle.setVisibility(recording ? View.GONE : View.VISIBLE);
        optionsToggle.setText(optionsOpen ? "Fewer options" : "More options");
        optionsContainer.setVisibility(optionsOpen && !recording ? View.VISIBLE : View.GONE);
        if (studySpinner != null) studySpinner.setEnabled(!recording);

        boolean customActive = activeLabel != null && !labelButtons.containsKey(activeLabel);
        for (Map.Entry<String, MaterialButton> entry : labelButtons.entrySet()) {
            styleChip(entry.getValue(), entry.getKey().equals(activeLabel), brand, white);
        }
        styleChip(otherButton, customActive, brand, white);
        otherButton.setText(customActive ? activeLabel : "Other…");
        if (!recording) {
            labelStatus.setText("Start a recording, then tap an activity. Tap it again to stop.");
        } else if (activeLabel == null) {
            labelStatus.setText("Tap what you are doing now.");
        } else {
            labelStatus.setText(activeLabel + (labelSince.isEmpty() ? "" : " · " + labelSince)
                    + " — tap again to stop");
        }

        liveCard.setVisibility(live.isEmpty() ? View.GONE : View.VISIBLE);
        liveText.setText(live);
        boolean plotted = ppgTrace.length > 1 || accelTrace.length > 1 || hrTrace.length > 1;
        // Per-wearable rows (live_devices) replace the single combined chart
        // whenever the backend reports any wearable.
        liveTraces.setVisibility(live.isEmpty() || !plotted || !liveDeviceList.isEmpty() ? View.GONE : View.VISIBLE);
        applyLiveDevices();
        tracePpg.setValues(ppgTrace);
        traceAccel.setValues(accelTrace);
        traceHr.setValues(hrTrace);
        traceHrLabel.setText("Heart rate · last 5 min" + (hrRange.isEmpty() ? "" : " · " + hrRange));
    }

    private void styleChip(MaterialButton button, boolean active, int brand, int white) {
        button.setEnabled(recording && !transitioning);
        if (active) {
            button.setBackgroundTintList(ColorStateList.valueOf(brand));
            button.setTextColor(white);
        } else {
            button.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
            button.setTextColor(ContextCompat.getColorStateList(requireContext(), R.color.label_chip_text));
        }
    }
}
