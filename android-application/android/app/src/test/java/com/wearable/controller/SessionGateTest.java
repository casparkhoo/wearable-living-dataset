package com.wearable.controller;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class SessionGateTest {
    @Test public void idleBehavesAsBefore() {
        SessionGate g = new SessionGate();
        assertEquals(SessionGate.State.IDLE, g.state());
        assertTrue(g.canPublish());
        assertFalse(g.holding());
    }

    @Test public void startingHoldsUntilSessionActive() {
        SessionGate g = new SessionGate();
        g.beginStart();
        assertFalse(g.canPublish());
        assertTrue(g.holding());
        g.startSucceeded();
        assertEquals(SessionGate.State.ACTIVE, g.state());
        assertTrue(g.canPublish());
    }

    @Test public void startFailureKeepsHoldingAndRetryReleases() {
        SessionGate g = new SessionGate();
        g.beginStart();
        g.startFailed();
        assertEquals(SessionGate.State.START_FAILED, g.state());
        assertFalse(g.canPublish());
        g.beginStart();
        assertFalse(g.canPublish());
        g.startSucceeded();
        assertTrue(g.canPublish());
    }

    @Test public void successWithoutStartIsIgnored() {
        SessionGate g = new SessionGate();
        g.startSucceeded();
        g.startFailed();
        assertEquals(SessionGate.State.IDLE, g.state());
    }

    @Test public void stopFlushesBeforeRestStopAndKeepsPublishing() {
        SessionGate g = new SessionGate();
        g.beginStart();
        g.startSucceeded();
        List<String> order = new ArrayList<>();
        boolean clean = g.finishRecording(() -> {
            order.add("flush:" + g.state() + ":" + g.canPublish());
            return true;
        }, () -> order.add("rest-stop"));
        assertTrue(clean);
        assertEquals(List.of("flush:STOPPING:true", "rest-stop"), order);
        g.stopSucceeded();
        assertEquals(SessionGate.State.IDLE, g.state());
    }

    @Test public void incompleteFlushStillStopsButReportsUnsent() {
        SessionGate g = new SessionGate();
        g.beginStart();
        g.startSucceeded();
        List<String> order = new ArrayList<>();
        boolean clean = g.finishRecording(() -> false, () -> order.add("rest-stop"));
        assertFalse(clean);
        assertEquals(List.of("rest-stop"), order);
    }

    @Test public void stopFailureReturnsToActive() {
        SessionGate g = new SessionGate();
        g.beginStart();
        g.startSucceeded();
        g.finishRecording(() -> true, () -> { });
        g.stopFailed();
        assertEquals(SessionGate.State.ACTIVE, g.state());
    }

    @Test public void stopWhenNotActiveDoesNothing() {
        SessionGate g = new SessionGate();
        List<String> order = new ArrayList<>();
        assertTrue(g.finishRecording(() -> { order.add("flush"); return true; }, () -> order.add("stop")));
        assertTrue(order.isEmpty());
    }

    @Test public void restoredStateFollowsServer() {
        SessionGate g = new SessionGate();
        g.restoredActive();
        assertEquals(SessionGate.State.ACTIVE, g.state());
        g.restoredIdle();
        assertEquals(SessionGate.State.IDLE, g.state());
        g.beginStart();
        g.restoredIdle();
        assertEquals(SessionGate.State.STARTING, g.state());
    }
}
