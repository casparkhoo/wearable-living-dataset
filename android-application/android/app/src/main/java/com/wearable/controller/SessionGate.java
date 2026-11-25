package com.wearable.controller;

/**
 * Decides when queued sensor payloads may leave the phone. The backend rejects a payload
 * when its device has no active session, but the broker acknowledges it anyway, so the
 * phone would treat it as delivered and never resend (19 Nov: about 12,650 payloads lost).
 *
 * States: IDLE (not recording: publish as before), STARTING (the REST start call is in
 * flight: hold), START_FAILED (the start call failed: keep holding, never publish blindly;
 * the held data goes to the next session that starts), ACTIVE (session exists: publish),
 * STOPPING (flush the tail, then the REST stop call: publish).
 * Pure logic, no Android, so the order of events can be unit tested.
 */
public final class SessionGate {
    public enum State { IDLE, STARTING, START_FAILED, ACTIVE, STOPPING }

    /** Publishes everything pending and waits for the broker's confirmations; true when nothing is left. */
    public interface Flusher {
        boolean flush();
    }

    private State state = State.IDLE;

    public synchronized State state() {
        return state;
    }

    /** Whether the senders may publish right now. */
    public synchronized boolean canPublish() {
        return state != State.STARTING && state != State.START_FAILED;
    }

    /** True while payloads are kept back for a session that does not exist yet. */
    public synchronized boolean holding() {
        return !canPublish();
    }

    /** The user pressed start; the REST call is about to go out. */
    public synchronized void beginStart() {
        state = State.STARTING;
    }

    /** The REST start call returned and the session is active. */
    public synchronized void startSucceeded() {
        if (state == State.STARTING) state = State.ACTIVE;
    }

    /** The REST start call failed: data stays held. */
    public synchronized void startFailed() {
        if (state == State.STARTING) state = State.START_FAILED;
    }

    /** The server reports a recording already running (app restart, reconnect). */
    public synchronized void restoredActive() {
        state = State.ACTIVE;
    }

    /** The server reports no recording: back to normal, unless a start is in progress or failed. */
    public synchronized void restoredIdle() {
        if (state == State.ACTIVE || state == State.STOPPING) state = State.IDLE;
    }

    /**
     * Stop sequence: publish everything pending and wait for confirmations first, then
     * call the REST stop. Returns what the flush returned (false: data still unsent).
     */
    public boolean finishRecording(Flusher flusher, Runnable restStop) {
        synchronized (this) {
            if (state != State.ACTIVE) return true;
            state = State.STOPPING;
        }
        boolean clean = flusher.flush();
        restStop.run();
        return clean;
    }

    /** The REST stop call succeeded. */
    public synchronized void stopSucceeded() {
        if (state == State.STOPPING) state = State.IDLE;
    }

    /** The REST stop call failed: the session is still active. */
    public synchronized void stopFailed() {
        if (state == State.STOPPING) state = State.ACTIVE;
    }
}
