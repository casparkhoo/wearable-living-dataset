-- Derived results, one row per analysis window per method.
--
-- Derived, not evidence: every row is computed from stream_samples that were
-- already stored, and can be thrown away and recomputed. A row names the method
-- and version that produced it, so results from different algorithm versions,
-- or from the live algorithm and a model, sit side by side and can be compared
-- with one query.
--
-- run_id is NULL for rows written while the recording was live. A recompute
-- (POST /api/device-recordings/:id/recompute) writes a derived_runs row first,
-- recording how many evidence rows it read and their SHA-256, so two runs over
-- the same evidence can be shown to have read identical input.

CREATE TABLE IF NOT EXISTS derived_runs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  run_kind VARCHAR(16) NOT NULL,
  methods JSON NOT NULL,
  status VARCHAR(16) NOT NULL,
  evidence_rows BIGINT,
  evidence_sha256 CHAR(64),
  window_count INT,
  error TEXT,
  started_at_ms BIGINT NOT NULL,
  completed_at_ms BIGINT,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (session_id) REFERENCES sessions(id),
  INDEX idx_derived_runs_session (session_id, started_at_ms)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS derived_windows (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  run_id BIGINT,
  method_id VARCHAR(64) NOT NULL,
  method_version VARCHAR(64) NOT NULL,
  window_start_ms BIGINT NOT NULL,
  window_end_ms BIGINT NOT NULL,
  hr_bpm DOUBLE,
  sqi DOUBLE,
  quality VARCHAR(16),
  hr_reason VARCHAR(32),
  movement VARCHAR(16),
  channel VARCHAR(16),
  details JSON,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (session_id) REFERENCES sessions(id),
  FOREIGN KEY (run_id) REFERENCES derived_runs(id),
  INDEX idx_derived_windows_session (session_id, method_id, window_end_ms),
  INDEX idx_derived_windows_run (run_id)
) ENGINE=InnoDB;
