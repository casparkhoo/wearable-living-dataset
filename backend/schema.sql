-- Living Dataset MySQL schema
-- MySQL 8.0+
--
-- This is the single logical living-dataset schema. It stores experiment
-- context, one evidence session per device recording, untouched raw packets,
-- canonical native-rate samples and interval activity labels. A processor such
-- as PaPaGei consumes query-time exports from this schema; it does not own a
-- separate application dataset or write model-ready copies back as evidence.
--
-- Tenancy: one database scoped by study. `users`, `studies` and
-- `study_memberships` carry identity and membership; `experiments` and
-- `sessions` carry `study_id` / `owner_user_id`. `raw_packets` and
-- `stream_samples` deliberately do NOT carry them -- they join through
-- session_id, so the per-row cost of the evidence tables does not grow.

CREATE DATABASE IF NOT EXISTS wearable_living_dataset
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE wearable_living_dataset;

CREATE TABLE IF NOT EXISTS users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  auth_provider VARCHAR(32) NOT NULL DEFAULT 'local',
  auth_subject VARCHAR(255),
  email VARCHAR(320),
  participant_code VARCHAR(64),
  display_name VARCHAR(128),
  password_hash VARCHAR(255),
  mfa_enrolled BOOLEAN NOT NULL DEFAULT FALSE,
  platform_role VARCHAR(32) NOT NULL DEFAULT 'participant',
  status VARCHAR(24) NOT NULL DEFAULT 'active',
  last_login_at DATETIME(3),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_users_provider_subject (auth_provider, auth_subject),
  UNIQUE KEY uq_users_email (email),
  UNIQUE KEY uq_users_participant_code (participant_code),
  INDEX idx_users_platform_role (platform_role),
  INDEX idx_users_status (status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS studies (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  description TEXT,
  status VARCHAR(24) NOT NULL DEFAULT 'draft',
  created_by_user_id BIGINT NOT NULL,
  consent_version VARCHAR(32),
  ethics_reference VARCHAR(128),
  data_retention_until DATE,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  FOREIGN KEY (created_by_user_id) REFERENCES users(id),
  INDEX idx_studies_status (status),
  INDEX idx_studies_created_by (created_by_user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS study_memberships (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  study_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role VARCHAR(32) NOT NULL DEFAULT 'participant',
  status VARCHAR(24) NOT NULL DEFAULT 'invited',
  consent_version VARCHAR(32),
  consent_recorded_at DATETIME(3),
  joined_at DATETIME(3),
  withdrawn_at DATETIME(3),
  withdrawal_reason TEXT,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  FOREIGN KEY (study_id) REFERENCES studies(id),
  FOREIGN KEY (user_id) REFERENCES users(id),
  UNIQUE KEY uq_study_memberships_study_user (study_id, user_id),
  INDEX idx_study_memberships_user (user_id),
  INDEX idx_study_memberships_study_role (study_id, role),
  INDEX idx_study_memberships_status (status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS devices (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  device_type VARCHAR(128) NOT NULL,
  device_name VARCHAR(128),
  device_address VARCHAR(128),
  device_address_hash CHAR(64),
  firmware_version VARCHAR(128),
  hardware_revision VARCHAR(128),
  metadata JSON,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_devices_type_address (device_type, device_address),
  INDEX idx_devices_type (device_type),
  INDEX idx_devices_address_hash (device_address_hash)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS experiments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  study_id BIGINT,
  owner_user_id BIGINT,
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
  FOREIGN KEY (study_id) REFERENCES studies(id),
  FOREIGN KEY (owner_user_id) REFERENCES users(id),
  INDEX idx_experiments_status_time (status, started_at_ms),
  INDEX idx_experiments_created_at (created_at),
  INDEX idx_experiments_study_owner (study_id, owner_user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS sessions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  experiment_id BIGINT,
  study_id BIGINT,
  owner_user_id BIGINT,
  device_id BIGINT NOT NULL,
  session_label VARCHAR(128),
  status VARCHAR(24) NOT NULL DEFAULT 'active',
  started_at_ms BIGINT NOT NULL,
  ended_at_ms BIGINT,
  client_started_at_ms BIGINT,
  client_ended_at_ms BIGINT,
  mqtt_broker_url VARCHAR(255),
  source_topic_prefix VARCHAR(128) DEFAULT 'sensors',
  operator_label VARCHAR(128),
  notes TEXT,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (experiment_id) REFERENCES experiments(id),
  FOREIGN KEY (study_id) REFERENCES studies(id),
  FOREIGN KEY (owner_user_id) REFERENCES users(id),
  FOREIGN KEY (device_id) REFERENCES devices(id),
  INDEX idx_sessions_experiment_time (experiment_id, started_at_ms),
  INDEX idx_sessions_status (status),
  INDEX idx_sessions_device_time (device_id, started_at_ms),
  INDEX idx_sessions_started_at (started_at_ms),
  INDEX idx_sessions_study_owner (study_id, owner_user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS annotations (
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

CREATE TABLE IF NOT EXISTS measurement_configs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  device_id BIGINT NOT NULL,
  command_name VARCHAR(128) NOT NULL,
  requested_streams JSON,
  effective_streams JSON,
  requested_sample_rate_hz DOUBLE,
  effective_sample_rate_hz DOUBLE,
  device_config JSON,
  started_at_ms BIGINT NOT NULL,
  ended_at_ms BIGINT,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (session_id) REFERENCES sessions(id),
  FOREIGN KEY (device_id) REFERENCES devices(id),
  INDEX idx_measurement_configs_session_time (session_id, started_at_ms),
  INDEX idx_measurement_configs_device_time (device_id, started_at_ms)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS raw_packets (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  device_id BIGINT NOT NULL,
  measurement_config_id BIGINT,
  packet_type VARCHAR(64),
  source_topic VARCHAR(255),
  device_timestamp_ms BIGINT,
  received_timestamp_ms BIGINT NOT NULL,
  packet_sample_rate_hz DOUBLE,
  sequence_number BIGINT,
  payload JSON NOT NULL,
  parser_name VARCHAR(128),
  parser_version VARCHAR(64),
  schema_version INT,
  source_timestamp_value BIGINT,
  source_timestamp_unit VARCHAR(16),
  android_received_timestamp_ms BIGINT,
  backend_received_timestamp_ms BIGINT,
  configured_sample_rate_hz DOUBLE,
  effective_sample_rate_hz DOUBLE,
  clock_source VARCHAR(64),
  clock_quality VARCHAR(64),
  parse_status VARCHAR(32) NOT NULL DEFAULT 'parsed',
  parse_error TEXT,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (session_id) REFERENCES sessions(id),
  FOREIGN KEY (device_id) REFERENCES devices(id),
  FOREIGN KEY (measurement_config_id) REFERENCES measurement_configs(id) ON DELETE SET NULL,
  INDEX idx_raw_packets_session_time (session_id, received_timestamp_ms),
  INDEX idx_raw_packets_device_time (device_id, received_timestamp_ms),
  INDEX idx_raw_packets_type_time (packet_type, received_timestamp_ms)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS stream_samples (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  device_id BIGINT NOT NULL,
  raw_packet_id BIGINT,
  measurement_config_id BIGINT,
  stream_name VARCHAR(64) NOT NULL,
  device_timestamp_ms BIGINT,
  received_timestamp_ms BIGINT NOT NULL,
  sample_rate_hz DOUBLE,
  channel_values JSON NOT NULL,
  channel_units JSON,
  quality_flags JSON,
  schema_version INT,
  source_timestamp_value BIGINT,
  source_timestamp_unit VARCHAR(16),
  android_received_timestamp_ms BIGINT,
  backend_received_timestamp_ms BIGINT,
  sequence_number BIGINT,
  sample_index BIGINT,
  configured_sample_rate_hz DOUBLE,
  effective_sample_rate_hz DOUBLE,
  clock_source VARCHAR(64),
  clock_quality VARCHAR(64),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (session_id) REFERENCES sessions(id),
  FOREIGN KEY (device_id) REFERENCES devices(id),
  FOREIGN KEY (raw_packet_id) REFERENCES raw_packets(id) ON DELETE SET NULL,
  FOREIGN KEY (measurement_config_id) REFERENCES measurement_configs(id) ON DELETE SET NULL,
  INDEX idx_stream_samples_session_time (session_id, received_timestamp_ms),
  INDEX idx_stream_samples_stream_time (session_id, stream_name, received_timestamp_ms),
  INDEX idx_stream_samples_device_time (device_id, received_timestamp_ms)
  , INDEX idx_stream_samples_clock (session_id, stream_name, sample_index)
  , INDEX idx_stream_samples_source_time (session_id, stream_name, source_timestamp_value)
) ENGINE=InnoDB;

-- Post-processing is deliberately query-time only. Windowing, feature selection,
-- resampling, model inputs and CSV files are returned with an export manifest;
-- they are not inserted back into the evidence schema.

-- Live analysis results are derived, not evidence: computed from stored
-- stream_samples, versioned by recipe and window, never referenced by evidence.
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

-- Derived results, stored apart from evidence (migration 20261107).
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

-- Erasure audit log (migration 20261109); no foreign keys, so it outlives what it describes.
CREATE TABLE IF NOT EXISTS erasure_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  subject_user_id BIGINT NOT NULL,
  study_id BIGINT,
  requested_by_user_id BIGINT,
  scope VARCHAR(16) NOT NULL,
  reason TEXT,
  status VARCHAR(16) NOT NULL,
  counts JSON,
  started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at DATETIME(3),
  INDEX idx_erasure_log_subject (subject_user_id),
  INDEX idx_erasure_log_study (study_id, started_at)
) ENGINE=InnoDB;
