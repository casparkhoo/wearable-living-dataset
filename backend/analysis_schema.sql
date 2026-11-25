-- LEGACY REFERENCE ONLY — DO NOT APPLY FOR THE CURRENT MVP.
-- The active backend neither loads nor writes this schema. Current persistence is
-- raw/native evidence in schema.sql; post-processing and export run at query time
-- and return a reproducibility manifest without database inserts.

CREATE DATABASE IF NOT EXISTS wearable_analysis_dataset
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE wearable_analysis_dataset;

CREATE TABLE IF NOT EXISTS experiment_sessions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  experiment_label VARCHAR(128) NOT NULL,
  protocol_name VARCHAR(128),
  protocol_version VARCHAR(64),
  started_at_ms BIGINT,
  ended_at_ms BIGINT,
  operator_label VARCHAR(128),
  notes TEXT,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_experiment_sessions_time (started_at_ms)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS experiment_device_sessions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  experiment_session_id BIGINT NOT NULL,
  evidence_session_id BIGINT NOT NULL,
  evidence_device_id BIGINT NOT NULL,
  device_role VARCHAR(64) NOT NULL DEFAULT 'wearable',
  clock_source VARCHAR(64) NOT NULL,
  clock_quality VARCHAR(64) NOT NULL,
  clock_offset_ms DOUBLE,
  clock_drift_ppm DOUBLE,
  clock_notes TEXT,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (experiment_session_id) REFERENCES experiment_sessions(id),
  UNIQUE KEY uq_experiment_evidence_session (experiment_session_id, evidence_session_id),
  INDEX idx_experiment_device_evidence (evidence_session_id, evidence_device_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS canonical_channels (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  stream_name VARCHAR(64) NOT NULL,
  channel_name VARCHAR(64) NOT NULL,
  canonical_unit VARCHAR(32) NOT NULL,
  modality VARCHAR(64) NOT NULL,
  description VARCHAR(255),
  calibration_policy VARCHAR(128),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE KEY uq_canonical_channel (stream_name, channel_name)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS model_definitions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  model_id VARCHAR(128) NOT NULL,
  model_version VARCHAR(64) NOT NULL,
  display_name VARCHAR(255) NOT NULL,
  manifest_json JSON NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_model_definition (model_id, model_version)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS analysis_windows (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  experiment_device_session_id BIGINT,
  evidence_session_id BIGINT NOT NULL,
  evidence_device_id BIGINT NOT NULL,
  stream_name VARCHAR(64) NOT NULL,
  channel_name VARCHAR(64) NOT NULL,
  sample_index_epoch INT NOT NULL DEFAULT 0,
  start_sample_index BIGINT NOT NULL,
  end_sample_index BIGINT NOT NULL,
  source_sample_rate_hz DOUBLE NOT NULL,
  window_duration_s DOUBLE NOT NULL,
  source_sample_count INT NOT NULL,
  source_first_backend_ms BIGINT,
  source_last_backend_ms BIGINT,
  clock_source VARCHAR(64) NOT NULL,
  clock_quality VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'candidate',
  rejection_reason VARCHAR(255),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (experiment_device_session_id) REFERENCES experiment_device_sessions(id),
  UNIQUE KEY uq_analysis_window_source (evidence_session_id, stream_name, channel_name, sample_index_epoch, start_sample_index, end_sample_index),
  INDEX idx_analysis_windows_evidence (evidence_session_id, stream_name, channel_name, sample_index_epoch, start_sample_index),
  INDEX idx_analysis_windows_status (status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS preprocessing_runs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  analysis_window_id BIGINT NOT NULL,
  pipeline_id VARCHAR(128) NOT NULL,
  pipeline_version VARCHAR(64) NOT NULL,
  parameters_json JSON NOT NULL,
  target_sample_rate_hz DOUBLE NOT NULL,
  target_sample_count INT NOT NULL,
  quality_metrics_json JSON NOT NULL,
  accepted BOOLEAN NOT NULL,
  rejection_reason VARCHAR(255),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (analysis_window_id) REFERENCES analysis_windows(id),
  INDEX idx_preprocessing_window (analysis_window_id),
  INDEX idx_preprocessing_accepted (accepted)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS model_runs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  preprocessing_run_id BIGINT NOT NULL,
  model_definition_id BIGINT NOT NULL,
  worker_url VARCHAR(255),
  status VARCHAR(32) NOT NULL DEFAULT 'queued',
  started_at DATETIME(3),
  completed_at DATETIME(3),
  error_message TEXT,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (preprocessing_run_id) REFERENCES preprocessing_runs(id),
  FOREIGN KEY (model_definition_id) REFERENCES model_definitions(id),
  INDEX idx_model_runs_status (status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS model_outputs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  model_run_id BIGINT NOT NULL,
  output_type VARCHAR(64) NOT NULL,
  output_dimension INT,
  output_json JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (model_run_id) REFERENCES model_runs(id),
  INDEX idx_model_outputs_run (model_run_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS validation_labels (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  analysis_window_id BIGINT NOT NULL,
  label_name VARCHAR(64) NOT NULL,
  label_value VARCHAR(128),
  numeric_value DOUBLE,
  reference_source VARCHAR(128),
  annotator VARCHAR(128),
  notes TEXT,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (analysis_window_id) REFERENCES analysis_windows(id),
  INDEX idx_validation_window (analysis_window_id),
  INDEX idx_validation_name (label_name)
) ENGINE=InnoDB;

INSERT IGNORE INTO canonical_channels
  (stream_name, channel_name, canonical_unit, modality, description, calibration_policy)
VALUES
  ('ppg', 'green', 'count', 'ppg', 'Green optical PPG channel', 'device_native_counts'),
  ('ppg', 'red', 'count', 'ppg', 'Red optical PPG channel', 'device_native_counts'),
  ('ppg', 'ir', 'count', 'ppg', 'Infrared optical PPG channel', 'device_native_counts'),
  ('ppg', 'ambient', 'count', 'ppg', 'Ambient light channel', 'device_native_counts'),
  ('accel', 'x', 'device_native', 'accelerometer', 'Accelerometer x axis', 'per_device_mapping'),
  ('accel', 'y', 'device_native', 'accelerometer', 'Accelerometer y axis', 'per_device_mapping'),
  ('accel', 'z', 'device_native', 'accelerometer', 'Accelerometer z axis', 'per_device_mapping'),
  ('gyro', 'x', 'device_native', 'gyroscope', 'Gyroscope x axis', 'per_device_mapping'),
  ('gyro', 'y', 'device_native', 'gyroscope', 'Gyroscope y axis', 'per_device_mapping'),
  ('gyro', 'z', 'device_native', 'gyroscope', 'Gyroscope z axis', 'per_device_mapping'),
  ('temperature', 'value', 'device_native', 'temperature', 'Temperature channel', 'per_device_mapping');
