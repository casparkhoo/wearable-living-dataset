//! Holding payloads that have no recording to go to yet, and remembering
//! recordings that just ended.
//!
//! The broker acknowledges a payload before the backend has looked at it, so a
//! payload the backend refuses is lost for good: the phone never resends it.
//! Two ordinary situations produced such refusals (19 Nov 2026, about 12,650
//! payloads): the phone publishes right after it asks for a recording, before
//! the recording exists, and it flushes its outbox right after it stops one.
//!
//! * `PendingBuffer` holds packets with no active recording for a short time
//!   and hands them back, in arrival order, when a recording for the same user
//!   and device becomes active. It is bounded in age, packets and bytes.
//! * `EndedSessions` remembers the recording that just ended on each device, so
//!   the packets the phone flushes on stop are attributed to it.
//!
//! Everything here is plain data with no I/O, so the rules are unit tested
//! without a database. User identity at this point is the `u<id>` segment of
//! the broker topic (the broker ACL ties it to the publishing account); a
//! packet from a topic with no such segment is keyed as `None`.

use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

/// How long a packet without a recording is held.
pub const PENDING_GRACE: Duration = Duration::from_secs(60);
/// Most packets held for one user (or for the unattributed owner).
pub const PENDING_MAX_PACKETS_PER_OWNER: usize = 20_000;
/// Most bytes (serialized packets) held for one user.
pub const PENDING_MAX_BYTES_PER_OWNER: usize = 8 * 1024 * 1024;
/// Most bytes held over all users. The 1 GB VM has no room for more.
pub const PENDING_MAX_BYTES_TOTAL: usize = 24 * 1024 * 1024;
/// How long after a recording ended its device still accepts packets.
pub const ENDED_GRACE: Duration = Duration::from_secs(30);
/// Most ended recordings remembered (one per device in practice).
const ENDED_MAX_ENTRIES: usize = 256;
/// Minimum time between two log lines of the same kind.
pub const LOG_INTERVAL: Duration = Duration::from_secs(10);

/// `Some(user id)` from the topic's `u<id>` segment, `None` without one.
pub type Owner = Option<i64>;

#[derive(Debug)]
pub struct HeldPacket {
    pub owner: Owner,
    /// Device key (`type:ADDRESS`), as `session_key` builds it.
    pub device_key: String,
    pub topic: String,
    /// The packet serialized as the evidence payload is.
    pub packet_json: String,
    /// When the backend received it, kept so replay stores the original time.
    pub backend_received_ms: i64,
    held_at: Instant,
}

impl HeldPacket {
    fn bytes(&self) -> usize {
        self.packet_json.len() + self.topic.len() + self.device_key.len() + 64
    }
}

pub struct PendingBuffer {
    items: VecDeque<HeldPacket>,
    per_owner: HashMap<Owner, (usize, usize)>,
    pair_counts: HashMap<(Owner, String), usize>,
    total_bytes: usize,
    max_packets_per_owner: usize,
    max_bytes_per_owner: usize,
    max_bytes_total: usize,
    grace: Duration,
}

impl PendingBuffer {
    pub fn new() -> Self {
        Self::with_limits(
            PENDING_MAX_PACKETS_PER_OWNER,
            PENDING_MAX_BYTES_PER_OWNER,
            PENDING_MAX_BYTES_TOTAL,
            PENDING_GRACE,
        )
    }

    pub fn with_limits(
        max_packets_per_owner: usize,
        max_bytes_per_owner: usize,
        max_bytes_total: usize,
        grace: Duration,
    ) -> Self {
        PendingBuffer {
            items: VecDeque::new(),
            per_owner: HashMap::new(),
            pair_counts: HashMap::new(),
            total_bytes: 0,
            max_packets_per_owner,
            max_bytes_per_owner,
            max_bytes_total,
            grace,
        }
    }

    pub fn len(&self) -> usize {
        self.items.len()
    }

    pub fn bytes(&self) -> usize {
        self.total_bytes
    }

    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }

    fn remove_accounting(&mut self, item: &HeldPacket) {
        let bytes = item.bytes();
        self.total_bytes = self.total_bytes.saturating_sub(bytes);
        if let Some(entry) = self.per_owner.get_mut(&item.owner) {
            entry.0 = entry.0.saturating_sub(1);
            entry.1 = entry.1.saturating_sub(bytes);
            if entry.0 == 0 {
                self.per_owner.remove(&item.owner);
            }
        }
        let pair = (item.owner, item.device_key.clone());
        if let Some(count) = self.pair_counts.get_mut(&pair) {
            *count -= 1;
            if *count == 0 {
                self.pair_counts.remove(&pair);
            }
        }
    }

    /// Drop the oldest packet of `owner`; false when it holds none.
    fn evict_oldest_of(&mut self, owner: Owner) -> bool {
        let Some(index) = self.items.iter().position(|item| item.owner == owner) else {
            return false;
        };
        if let Some(item) = self.items.remove(index) {
            self.remove_accounting(&item);
        }
        true
    }

    /// Hold a packet. Returns how many older packets were dropped to make room
    /// (the oldest of the same user first, then the oldest of anyone if the
    /// overall cap is still exceeded). A packet larger than a whole cap is
    /// itself refused and counted as dropped.
    pub fn hold(
        &mut self,
        now: Instant,
        owner: Owner,
        device_key: String,
        topic: String,
        packet_json: String,
        backend_received_ms: i64,
    ) -> u64 {
        let item = HeldPacket {
            owner,
            device_key,
            topic,
            packet_json,
            backend_received_ms,
            held_at: now,
        };
        let bytes = item.bytes();
        if bytes > self.max_bytes_per_owner || bytes > self.max_bytes_total {
            return 1;
        }
        let mut dropped = 0;
        loop {
            let (count, owner_bytes) = self.per_owner.get(&owner).copied().unwrap_or((0, 0));
            if count + 1 > self.max_packets_per_owner
                || owner_bytes + bytes > self.max_bytes_per_owner
            {
                if !self.evict_oldest_of(owner) {
                    break;
                }
                dropped += 1;
                continue;
            }
            if self.total_bytes + bytes > self.max_bytes_total {
                let Some(oldest) = self.items.pop_front() else {
                    break;
                };
                self.remove_accounting(&oldest);
                dropped += 1;
                continue;
            }
            break;
        }
        let entry = self.per_owner.entry(owner).or_insert((0, 0));
        entry.0 += 1;
        entry.1 += bytes;
        self.total_bytes += bytes;
        *self
            .pair_counts
            .entry((item.owner, item.device_key.clone()))
            .or_insert(0) += 1;
        self.items.push_back(item);
        dropped
    }

    /// Distinct (owner, device key) pairs currently held.
    pub fn pairs(&self) -> Vec<(Owner, String)> {
        self.pair_counts.keys().cloned().collect()
    }

    /// Whether anything is held for this user and device.
    pub fn has_pair(&self, owner: Owner, device_key: &str) -> bool {
        !self.pair_counts.is_empty()
            && self.pair_counts.contains_key(&(owner, device_key.to_string()))
    }

    /// Remove and return, in arrival order, the packets for which
    /// `ready(owner, device_key)` is true. The rest keep their place.
    pub fn take_ready(&mut self, ready: impl Fn(Owner, &str) -> bool) -> Vec<HeldPacket> {
        if self.items.is_empty() {
            return Vec::new();
        }
        let mut taken = Vec::new();
        let mut kept = VecDeque::with_capacity(self.items.len());
        for item in std::mem::take(&mut self.items) {
            if ready(item.owner, &item.device_key) {
                taken.push(item);
            } else {
                kept.push_back(item);
            }
        }
        self.items = kept;
        for item in &taken {
            self.remove_accounting(item);
        }
        taken
    }

    /// Drop packets held longer than the grace; returns how many.
    pub fn expire(&mut self, now: Instant) -> u64 {
        let mut expired = 0;
        while let Some(front) = self.items.front() {
            if now.duration_since(front.held_at) < self.grace {
                break;
            }
            if let Some(item) = self.items.pop_front() {
                self.remove_accounting(&item);
                expired += 1;
            }
        }
        expired
    }
}

#[derive(Clone, Debug)]
pub struct EndedSession<H> {
    pub handle: H,
    ended_at: Instant,
}

/// The recording that just ended on each device.
pub struct EndedSessions<H> {
    by_device: HashMap<String, EndedSession<H>>,
    grace: Duration,
}

impl<H: Clone> EndedSessions<H> {
    pub fn new() -> Self {
        Self::with_grace(ENDED_GRACE)
    }

    pub fn with_grace(grace: Duration) -> Self {
        EndedSessions { by_device: HashMap::new(), grace }
    }

    pub fn remember(&mut self, now: Instant, device_key: String, handle: H) {
        self.prune(now);
        if self.by_device.len() >= ENDED_MAX_ENTRIES && !self.by_device.contains_key(&device_key) {
            // Never reached with a handful of devices; drop the oldest anyway.
            if let Some(oldest) = self
                .by_device
                .iter()
                .min_by_key(|(_, entry)| entry.ended_at)
                .map(|(key, _)| key.clone())
            {
                self.by_device.remove(&oldest);
            }
        }
        self.by_device
            .insert(device_key, EndedSession { handle, ended_at: now });
    }

    /// The recording that ended on this device within the grace, if any.
    pub fn recent(&self, now: Instant, device_key: &str) -> Option<H> {
        let entry = self.by_device.get(device_key)?;
        (now.duration_since(entry.ended_at) <= self.grace).then(|| entry.handle.clone())
    }

    fn prune(&mut self, now: Instant) {
        let grace = self.grace;
        self.by_device
            .retain(|_, entry| now.duration_since(entry.ended_at) <= grace);
    }
}

/// Counters served in `/health`. Units are packets (one MQTT message may carry
/// a batch of them).
#[derive(Default)]
pub struct IngestCounters {
    /// Stored directly into an active recording.
    pub accepted: AtomicU64,
    /// Stored into the recording that had just ended.
    pub accepted_after_stop: AtomicU64,
    /// Put in the pending buffer because no recording existed yet.
    pub held: AtomicU64,
    /// Held, then stored into the recording that started.
    pub replayed: AtomicU64,
    /// Held until the grace ran out with no recording: lost, and counted.
    pub dropped_no_session: AtomicU64,
    /// Dropped because the pending buffer was full.
    pub dropped_buffer_full: AtomicU64,
    /// Refused for another reason (parse failure aside): wrong owner, database error.
    pub rejected_other: AtomicU64,
    /// Packets and bytes in the pending buffer now.
    pub pending_packets: AtomicU64,
    pub pending_bytes: AtomicU64,
}

impl IngestCounters {
    pub fn add(counter: &AtomicU64, n: u64) {
        counter.fetch_add(n, Ordering::Relaxed);
    }

    pub fn snapshot(&self) -> serde_json::Value {
        let get = |c: &AtomicU64| c.load(Ordering::Relaxed);
        serde_json::json!({
            "accepted": get(&self.accepted),
            "accepted_after_stop": get(&self.accepted_after_stop),
            "held": get(&self.held),
            "replayed": get(&self.replayed),
            "dropped_no_session": get(&self.dropped_no_session),
            "dropped_buffer_full": get(&self.dropped_buffer_full),
            "rejected_other": get(&self.rejected_other),
            "pending_packets": get(&self.pending_packets),
            "pending_bytes": get(&self.pending_bytes),
            "pending_grace_s": PENDING_GRACE.as_secs(),
            "after_stop_grace_s": ENDED_GRACE.as_secs(),
        })
    }
}

/// Lets a log line out at most once per interval, with the number of events it
/// stands for.
pub struct LogLimiter {
    interval: Duration,
    last: Option<Instant>,
    suppressed: u64,
}

impl LogLimiter {
    pub fn new(interval: Duration) -> Self {
        LogLimiter { interval, last: None, suppressed: 0 }
    }

    /// Record `n` events; returns the total to report if a line may be logged now.
    pub fn record(&mut self, now: Instant, n: u64) -> Option<u64> {
        self.suppressed += n;
        if self.suppressed == 0 {
            return None;
        }
        match self.last {
            Some(last) if now.duration_since(last) < self.interval => None,
            _ => {
                self.last = Some(now);
                Some(std::mem::take(&mut self.suppressed))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hold(buf: &mut PendingBuffer, now: Instant, owner: Owner, key: &str, n: u32) -> u64 {
        buf.hold(now, owner, key.to_string(), "sensors/u1/x".into(), format!("{{\"n\":{n}}}"), n as i64)
    }

    #[test]
    fn replay_keeps_order_and_original_times() {
        let now = Instant::now();
        let mut buf = PendingBuffer::new();
        for n in 0..5 {
            hold(&mut buf, now, Some(1), "ring:A", n);
        }
        let out = buf.take_ready(|_, _| true);
        let times: Vec<i64> = out.iter().map(|p| p.backend_received_ms).collect();
        assert_eq!(times, vec![0, 1, 2, 3, 4]);
        assert!(buf.is_empty());
        assert_eq!(buf.bytes(), 0);
    }

    #[test]
    fn only_the_same_owner_and_device_is_released() {
        let now = Instant::now();
        let mut buf = PendingBuffer::new();
        hold(&mut buf, now, Some(1), "ring:A", 1);
        hold(&mut buf, now, Some(2), "ring:A", 2);
        hold(&mut buf, now, Some(1), "ear:B", 3);
        let out = buf.take_ready(|owner, key| owner == Some(1) && key == "ring:A");
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].backend_received_ms, 1);
        assert_eq!(buf.len(), 2);
        let rest = buf.take_ready(|_, _| true);
        assert_eq!(rest.iter().map(|p| p.backend_received_ms).collect::<Vec<_>>(), vec![2, 3]);
    }

    #[test]
    fn user_two_never_gets_user_one_packets() {
        let now = Instant::now();
        let mut buf = PendingBuffer::new();
        hold(&mut buf, now, Some(1), "ring:A", 1);
        // A session of user 2 on the same device key becomes active.
        let out = buf.take_ready(|owner, _| owner == Some(2));
        assert!(out.is_empty());
        assert_eq!(buf.len(), 1);
    }

    #[test]
    fn packet_cap_drops_the_oldest_of_that_owner_only() {
        let now = Instant::now();
        let mut buf = PendingBuffer::with_limits(3, usize::MAX, usize::MAX, PENDING_GRACE);
        hold(&mut buf, now, Some(2), "ring:B", 100);
        let mut dropped = 0;
        for n in 0..5 {
            dropped += hold(&mut buf, now, Some(1), "ring:A", n);
        }
        assert_eq!(dropped, 2);
        let out = buf.take_ready(|owner, _| owner == Some(1));
        assert_eq!(out.iter().map(|p| p.backend_received_ms).collect::<Vec<_>>(), vec![2, 3, 4]);
        // user 2's packet survived user 1 flooding the buffer
        assert_eq!(buf.len(), 1);
    }

    #[test]
    fn byte_caps_per_owner_and_overall() {
        let now = Instant::now();
        let one = HeldPacket {
            owner: None,
            device_key: "ring:A".into(),
            topic: "sensors/u1/x".into(),
            packet_json: "{\"n\":0}".into(),
            backend_received_ms: 0,
            held_at: now,
        }
        .bytes();
        let mut buf = PendingBuffer::with_limits(usize::MAX, one * 2, one * 3, PENDING_GRACE);
        assert_eq!(hold(&mut buf, now, Some(1), "ring:A", 0), 0);
        assert_eq!(hold(&mut buf, now, Some(1), "ring:A", 1), 0);
        assert_eq!(hold(&mut buf, now, Some(1), "ring:A", 2), 1); // per-owner cap
        assert_eq!(hold(&mut buf, now, Some(2), "ring:B", 3), 0);
        assert_eq!(buf.bytes(), one * 3);
        assert_eq!(hold(&mut buf, now, Some(3), "ring:C", 4), 1); // overall cap
        assert!(buf.bytes() <= one * 3);
    }

    #[test]
    fn an_oversize_packet_is_refused_and_counted() {
        let mut buf = PendingBuffer::with_limits(10, 100, 100, PENDING_GRACE);
        let dropped = buf.hold(Instant::now(), None, "k".into(), "t".into(), "x".repeat(1000), 1);
        assert_eq!(dropped, 1);
        assert!(buf.is_empty());
    }

    #[test]
    fn packets_expire_after_the_grace() {
        let start = Instant::now();
        let mut buf = PendingBuffer::with_limits(10, usize::MAX, usize::MAX, Duration::from_secs(60));
        hold(&mut buf, start, Some(1), "ring:A", 1);
        hold(&mut buf, start + Duration::from_secs(30), Some(1), "ring:A", 2);
        assert_eq!(buf.expire(start + Duration::from_secs(59)), 0);
        assert_eq!(buf.expire(start + Duration::from_secs(61)), 1);
        assert_eq!(buf.len(), 1);
        assert_eq!(buf.expire(start + Duration::from_secs(95)), 1);
        assert!(buf.is_empty());
        assert_eq!(buf.bytes(), 0);
    }

    #[test]
    fn a_recently_ended_recording_is_found_only_within_the_grace() {
        let start = Instant::now();
        let mut ended: EndedSessions<i64> = EndedSessions::with_grace(Duration::from_secs(30));
        ended.remember(start, "ring:A".into(), 7);
        assert_eq!(ended.recent(start + Duration::from_secs(10), "ring:A"), Some(7));
        assert_eq!(ended.recent(start + Duration::from_secs(10), "ring:B"), None);
        assert_eq!(ended.recent(start + Duration::from_secs(31), "ring:A"), None);
    }

    #[test]
    fn ended_memory_is_bounded() {
        let start = Instant::now();
        let mut ended: EndedSessions<i64> = EndedSessions::with_grace(Duration::from_secs(3600));
        for n in 0..(ENDED_MAX_ENTRIES * 2) {
            ended.remember(start + Duration::from_millis(n as u64), format!("d:{n}"), n as i64);
        }
        assert!(ended.by_device.len() <= ENDED_MAX_ENTRIES);
    }

    #[test]
    fn log_limiter_reports_the_total_once_per_interval() {
        let start = Instant::now();
        let mut limiter = LogLimiter::new(Duration::from_secs(10));
        assert_eq!(limiter.record(start, 5), Some(5));
        assert_eq!(limiter.record(start + Duration::from_secs(1), 3), None);
        assert_eq!(limiter.record(start + Duration::from_secs(2), 4), None);
        assert_eq!(limiter.record(start + Duration::from_secs(11), 0), Some(7));
        assert_eq!(limiter.record(start + Duration::from_secs(30), 0), None);
    }

    #[test]
    fn counters_snapshot_has_every_field() {
        let counters = IngestCounters::default();
        IngestCounters::add(&counters.held, 3);
        let value = counters.snapshot();
        for key in [
            "accepted", "accepted_after_stop", "held", "replayed", "dropped_no_session",
            "dropped_buffer_full", "rejected_other", "pending_packets", "pending_bytes",
        ] {
            assert!(value.get(key).is_some(), "missing {key}");
        }
        assert_eq!(value["held"], 3);
    }
}
