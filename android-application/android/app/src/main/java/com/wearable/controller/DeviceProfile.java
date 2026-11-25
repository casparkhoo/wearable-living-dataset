package com.wearable.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * What the app needs to know about a kind of wearable that is not about talking to it:
 * the names people see, where it is worn, which of its streams carry pulse and
 * movement, and a few behaviours the app-level code used to special-case by name.
 *
 * Each adapter declares one as a public static final field and registers it in
 * {@link WearableDeviceRegistry#createDefault()}. Nothing outside the adapter should
 * compare a device type against a literal; ask the profile instead
 * ({@code WearableDeviceRegistry.profileOf(type)}).
 *
 * Pure Java: it is unit-tested without Android.
 */
public final class DeviceProfile {

    /** What a stream (the sensor name an adapter puts on its samples) carries. */
    public enum StreamRole { PULSE, MOVEMENT, AUDIO, TEMPERATURE, OTHER }

    /** Icon key for a plain dot; DeviceIconView draws it for any key it does not know. */
    public static final String ICON_DOT = "dot";

    private final String type;
    private final String displayName;
    private final String firmwareName;
    private final boolean fixedTitle;
    private final String shortName;
    private final String pickerLabel;
    private final String wearPosition;
    private final String iconKey;
    private final Map<String, Set<StreamRole>> streams;
    private final double dashboardPriority;
    private final boolean dashboardDefault;
    private final String dashboardNoun;
    private final List<String> resumeCommands;
    private final Function<HeadUpDisplay.Host, HeadUpDisplay> displayFactory;
    private final boolean bondBeforeConnect;
    private final String pairingStartText;
    private final String pairingFailText;
    private final String pairingFailLog;
    private final String pairedLog;
    private final Function<String, String> dashboardTag;
    private final Function<String, String> groupKey;
    private final Function<String, String> partLabel;
    private final Function<String, String> groupTitle;
    private final String groupSubtitle;

    private DeviceProfile(Builder b) {
        this.type = b.type;
        this.displayName = b.displayName == null ? b.type : b.displayName;
        this.firmwareName = b.firmwareName;
        this.fixedTitle = b.fixedTitle;
        this.shortName = b.shortName == null ? b.type : b.shortName;
        this.pickerLabel = b.pickerLabel == null ? this.displayName : b.pickerLabel;
        this.wearPosition = b.wearPosition;
        this.iconKey = b.iconKey;
        this.streams = Collections.unmodifiableMap(new LinkedHashMap<>(b.streams));
        this.dashboardPriority = b.dashboardPriority;
        this.dashboardDefault = b.dashboardDefault;
        this.dashboardNoun = b.dashboardNoun == null ? this.displayName.toLowerCase(java.util.Locale.ROOT) : b.dashboardNoun;
        this.resumeCommands = Collections.unmodifiableList(new ArrayList<>(b.resumeCommands));
        this.displayFactory = b.displayFactory;
        this.bondBeforeConnect = b.bondBeforeConnect;
        this.pairingStartText = b.pairingStartText;
        this.pairingFailText = b.pairingFailText;
        this.pairingFailLog = b.pairingFailLog;
        this.pairedLog = b.pairedLog;
        this.dashboardTag = b.dashboardTag;
        this.groupKey = b.groupKey;
        this.partLabel = b.partLabel;
        this.groupTitle = b.groupTitle;
        this.groupSubtitle = b.groupSubtitle;
    }

    /** A profile for a type nobody described: it is shown under its own type name. */
    public static DeviceProfile generic(String type) {
        return new Builder(type == null ? "" : type).build();
    }

    /** Identifier the registry and the saved device list use; unique per adapter. */
    public String getType() { return type; }
    /** The name people use for this kind of wearable. */
    public String getDisplayName() { return displayName; }
    /** The name the firmware advertises when it differs from the display name; null otherwise. */
    public String getFirmwareName() { return firmwareName; }
    /** True when the list shows the display name, not the advertised name, as the title. */
    public boolean hasFixedTitle() { return fixedTitle; }
    /** A short name for the dashboard. */
    public String getShortName() { return shortName; }
    /** The line offered when the participant picks which wearable to add. */
    public String getPickerLabel() { return pickerLabel; }
    /** Where it is worn, in a word: finger, ear, face, chest, wrist. */
    public String getWearPosition() { return wearPosition; }
    public String getIconKey() { return iconKey; }
    /** Sensor name on this adapter's samples -> what it carries (one stream can carry several roles). */
    public Map<String, Set<StreamRole>> getStreamRoles() { return streams; }

    /** The first stream that carries the given role, or null. */
    public String streamFor(StreamRole role) {
        for (Map.Entry<String, Set<StreamRole>> entry : streams.entrySet()) {
            if (entry.getValue().contains(role)) return entry.getKey();
        }
        return null;
    }

    /** Added to a live recording's score when the dashboard picks which wearable to describe. */
    public double getDashboardPriority() { return dashboardPriority; }
    /** True for the wearable the dashboard describes when no live recording says otherwise. */
    public boolean isDashboardDefault() { return dashboardDefault; }
    /** Lower-case word for this wearable in the dashboard's plain-text page ("ring 87%"). */
    public String getDashboardNoun() { return dashboardNoun; }
    /** Commands to send first when streaming resumes after a reconnect. */
    public List<String> getResumeCommands() { return resumeCommands; }
    /** True when the wearable shows the live dashboard to its wearer. */
    public boolean hasDisplay() { return displayFactory != null; }

    /** The object that drives this wearable's display, or null when it has none. */
    public HeadUpDisplay createDisplay(HeadUpDisplay.Host host) {
        return displayFactory == null ? null : displayFactory.apply(host);
    }
    public boolean requiresBondBeforeConnect() { return bondBeforeConnect; }
    public String getPairingStartText() { return pairingStartText; }
    public String getPairingFailText() { return pairingFailText; }
    public String getPairingFailLog() { return pairingFailLog; }
    public String getPairedLog() { return pairedLog; }

    /** Row tag on the head-up dashboard for a recording wearable called {@code displayedName}. */
    public String dashboardTag(String displayedName) {
        String tag = dashboardTag == null ? null : dashboardTag.apply(displayedName);
        if (tag == null) {
            tag = displayedName.length() > 6
                    ? displayedName.substring(0, 6).toUpperCase(java.util.Locale.ROOT)
                    : displayedName.toUpperCase(java.util.Locale.ROOT);
        }
        return tag.length() > 7 ? tag.substring(0, 7) : tag;
    }

    /**
     * Key shared by the parts of one multi-part wearable (the two arms of a pair of
     * glasses), read from the advertised name; empty for a single-part wearable.
     */
    public String groupKeyOf(String advertisedName) {
        String key = groupKey == null || advertisedName == null ? null : groupKey.apply(advertisedName);
        return key == null ? "" : key;
    }

    /** Row label for one part ("Even G1 - left arm"), or null to use the advertised name. */
    public String partLabelOf(String advertisedName) {
        return partLabel == null || advertisedName == null ? null : partLabel.apply(advertisedName);
    }

    /** Title of a nearby group of parts, for the search list. */
    public String groupTitleOf(String groupKey) {
        return groupTitle == null ? groupKey : groupTitle.apply(groupKey);
    }

    public String getGroupSubtitle() { return groupSubtitle; }

    public static final class Builder {
        private final String type;
        private String displayName;
        private String firmwareName;
        private boolean fixedTitle;
        private String shortName;
        private String pickerLabel;
        private String wearPosition = "";
        private String iconKey = ICON_DOT;
        private final Map<String, Set<StreamRole>> streams = new LinkedHashMap<>();
        private double dashboardPriority = 0;
        private boolean dashboardDefault;
        private String dashboardNoun;
        private final List<String> resumeCommands = new ArrayList<>();
        private Function<HeadUpDisplay.Host, HeadUpDisplay> displayFactory;
        private boolean bondBeforeConnect;
        private String pairingStartText = "";
        private String pairingFailText = "";
        private String pairingFailLog = "";
        private String pairedLog = "";
        private Function<String, String> dashboardTag;
        private Function<String, String> groupKey;
        private Function<String, String> partLabel;
        private Function<String, String> groupTitle;
        private String groupSubtitle = "";

        public Builder(String type) { this.type = type; }

        public Builder displayName(String name) { this.displayName = name; return this; }
        /** The firmware's own name for it, replaced by the display name wherever text mentions it. */
        public Builder firmwareName(String name) { this.firmwareName = name; return this; }
        public Builder fixedTitle(boolean fixed) { this.fixedTitle = fixed; return this; }
        public Builder shortName(String name) { this.shortName = name; return this; }
        public Builder pickerLabel(String label) { this.pickerLabel = label; return this; }
        public Builder wearPosition(String position) { this.wearPosition = position; return this; }
        public Builder iconKey(String key) { this.iconKey = key; return this; }
        public Builder stream(String sensorName, StreamRole first, StreamRole... more) {
            streams.put(sensorName, EnumSet.of(first, more));
            return this;
        }
        public Builder dashboardPriority(double priority) { this.dashboardPriority = priority; return this; }
        public Builder dashboardDefault(boolean isDefault) { this.dashboardDefault = isDefault; return this; }
        public Builder dashboardNoun(String noun) { this.dashboardNoun = noun; return this; }
        public Builder resumeCommand(String command) { resumeCommands.add(command); return this; }
        /** The wearable shows the live dashboard; the factory makes the object that drives it. */
        public Builder display(Function<HeadUpDisplay.Host, HeadUpDisplay> factory) { this.displayFactory = factory; return this; }
        public Builder dashboardTag(Function<String, String> tag) { this.dashboardTag = tag; return this; }

        /** The wearable must be bonded to the phone before it keeps a connection. */
        public Builder bondBeforeConnect(String startText, String failText, String failLog, String pairedLog) {
            this.bondBeforeConnect = true;
            this.pairingStartText = startText;
            this.pairingFailText = failText;
            this.pairingFailLog = failLog;
            this.pairedLog = pairedLog;
            return this;
        }

        /** The wearable is several Bluetooth devices (parts) that are connected together. */
        public Builder parts(Function<String, String> groupKey, Function<String, String> partLabel,
                             Function<String, String> groupTitle, String groupSubtitle) {
            this.groupKey = groupKey;
            this.partLabel = partLabel;
            this.groupTitle = groupTitle;
            this.groupSubtitle = groupSubtitle;
            return this;
        }

        public DeviceProfile build() {
            if (type == null) throw new IllegalArgumentException("A device profile needs a type");
            return new DeviceProfile(this);
        }
    }
}
