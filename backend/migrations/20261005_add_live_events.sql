-- Live analysis results.
--
-- Derived, not evidence: rows here are computed from stream_samples that were
-- already stored, and nothing in the evidence tables refers back to them.
-- Each row carries the recipe version and the window it was computed over, so
-- a summary or alert can be traced to exactly the samples that produced it.
--
-- event_type is 'summary' (a periodic snapshot of every stream's health, PPG
-- quality and movement), 'alert_raised' or 'alert_cleared'.

CREATE TABLE IF NOT EXISTS live_events (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  owner_user_id BIGINT,
  stream_name VARCHAR(64),
  event_type VARCHAR(32) NOT NULL,
  alert_code VARCHAR(64),
  severity VARCHAR(16),
  message VARCHAR(255),
  window_start_ms BIGINT NOT NULL,
  window_end_ms BIGINT NOT NULL,
  detected_at_ms BIGINT NOT NULL,
  recipe_version VARCHAR(32) NOT NULL,
  details JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (session_id) REFERENCES sessions(id),
  INDEX idx_live_events_session_time (session_id, detected_at_ms),
  INDEX idx_live_events_alerts (event_type, alert_code, detected_at_ms)
) ENGINE=InnoDB;
