-- Move experiment control, evidence-session membership, annotations, and
-- processor-independent derived artifacts into wearable_living_dataset.
-- Apply once to an existing MySQL 8 database after taking a backup.

USE wearable_living_dataset;

CREATE TABLE experiments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  experiment_label VARCHAR(128) NOT NULL,
  protocol_name VARCHAR(128),
  protocol_version VARCHAR(64),
  operator_label VARCHAR(128),
  notes TEXT,
  status VARCHAR(24) NOT NULL DEFAULT 'planned',
  started_at_ms BIGINT,
  ended_at_ms BIGINT,
  client_started_at_ms BIGINT,
  client_ended_at_ms BIGINT,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  INDEX idx_experiments_status_time (status, started_at_ms),
  INDEX idx_experiments_created_at (created_at)
) ENGINE=InnoDB;

ALTER TABLE sessions
  ADD COLUMN experiment_id BIGINT NULL AFTER id,
  ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'active' AFTER session_label,
  ADD COLUMN client_started_at_ms BIGINT NULL AFTER ended_at_ms,
  ADD COLUMN client_ended_at_ms BIGINT NULL AFTER client_started_at_ms,
  ADD CONSTRAINT fk_sessions_experiment FOREIGN KEY (experiment_id) REFERENCES experiments(id),
  ADD INDEX idx_sessions_experiment_time (experiment_id, started_at_ms),
  ADD INDEX idx_sessions_status (status);

CREATE TABLE annotations (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  experiment_id BIGINT NOT NULL,
  session_id BIGINT,
  label_name VARCHAR(128) NOT NULL,
  label_value VARCHAR(255) NOT NULL,
  started_at_ms BIGINT NOT NULL,
  ended_at_ms BIGINT,
  client_timestamp_ms BIGINT,
  client_monotonic_ns BIGINT,
  server_received_at_ms BIGINT NOT NULL,
  source VARCHAR(32) NOT NULL DEFAULT 'manual_app',
  confidence DOUBLE,
  timing_uncertainty_ms DOUBLE,
  notes TEXT,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (experiment_id) REFERENCES experiments(id),
  FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE SET NULL,
  INDEX idx_annotations_experiment_time (experiment_id, started_at_ms),
  INDEX idx_annotations_session_time (session_id, started_at_ms),
  INDEX idx_annotations_label (label_name, label_value)
) ENGINE=InnoDB;

CREATE TABLE processing_runs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  processor_id VARCHAR(128) NOT NULL,
  processor_version VARCHAR(64) NOT NULL,
  manifest_version VARCHAR(64),
  parameters_json JSON,
  status VARCHAR(24) NOT NULL DEFAULT 'pending',
  started_at_ms BIGINT,
  ended_at_ms BIGINT,
  error_message TEXT,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_processing_runs_processor (processor_id, processor_version),
  INDEX idx_processing_runs_status (status)
) ENGINE=InnoDB;

CREATE TABLE derived_artifacts (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  processing_run_id BIGINT NOT NULL,
  artifact_type VARCHAR(64) NOT NULL,
  media_type VARCHAR(128) NOT NULL DEFAULT 'application/json',
  shape_json JSON,
  payload_json JSON,
  storage_uri VARCHAR(1024),
  checksum_sha256 CHAR(64),
  quality_json JSON,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (processing_run_id) REFERENCES processing_runs(id),
  INDEX idx_derived_artifacts_run_type (processing_run_id, artifact_type)
) ENGINE=InnoDB;

CREATE TABLE artifact_sources (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  artifact_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  stream_name VARCHAR(64) NOT NULL,
  channel_name VARCHAR(64),
  start_sample_index BIGINT,
  end_sample_index BIGINT,
  start_time_ms BIGINT,
  end_time_ms BIGINT,
  continuity_segment INT,
  selection_json JSON,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (artifact_id) REFERENCES derived_artifacts(id) ON DELETE CASCADE,
  FOREIGN KEY (session_id) REFERENCES sessions(id),
  INDEX idx_artifact_sources_session_range (session_id, stream_name, start_sample_index, end_sample_index)
) ENGINE=InnoDB;

CREATE TABLE dataset_snapshots (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  snapshot_name VARCHAR(128) NOT NULL,
  description TEXT,
  query_json JSON NOT NULL,
  created_at_ms BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_dataset_snapshot_name (snapshot_name)
) ENGINE=InnoDB;

CREATE TABLE dataset_snapshot_sessions (
  snapshot_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  PRIMARY KEY (snapshot_id, session_id),
  FOREIGN KEY (snapshot_id) REFERENCES dataset_snapshots(id) ON DELETE CASCADE,
  FOREIGN KEY (session_id) REFERENCES sessions(id)
) ENGINE=InnoDB;
