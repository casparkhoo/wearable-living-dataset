-- Apply once to an existing wearable_living_dataset database before running backend 0.2.
-- Existing rows are preserved as legacy timing evidence.
USE wearable_living_dataset;

ALTER TABLE raw_packets
  ADD COLUMN schema_version INT NULL AFTER parser_version,
  ADD COLUMN source_timestamp_value BIGINT NULL AFTER schema_version,
  ADD COLUMN source_timestamp_unit VARCHAR(16) NULL AFTER source_timestamp_value,
  ADD COLUMN android_received_timestamp_ms BIGINT NULL AFTER source_timestamp_unit,
  ADD COLUMN backend_received_timestamp_ms BIGINT NULL AFTER android_received_timestamp_ms,
  ADD COLUMN configured_sample_rate_hz DOUBLE NULL AFTER backend_received_timestamp_ms,
  ADD COLUMN effective_sample_rate_hz DOUBLE NULL AFTER configured_sample_rate_hz,
  ADD COLUMN clock_source VARCHAR(64) NULL AFTER effective_sample_rate_hz,
  ADD COLUMN clock_quality VARCHAR(64) NULL AFTER clock_source;

ALTER TABLE stream_samples
  ADD COLUMN schema_version INT NULL AFTER quality_flags,
  ADD COLUMN source_timestamp_value BIGINT NULL AFTER schema_version,
  ADD COLUMN source_timestamp_unit VARCHAR(16) NULL AFTER source_timestamp_value,
  ADD COLUMN android_received_timestamp_ms BIGINT NULL AFTER source_timestamp_unit,
  ADD COLUMN backend_received_timestamp_ms BIGINT NULL AFTER android_received_timestamp_ms,
  ADD COLUMN sequence_number BIGINT NULL AFTER backend_received_timestamp_ms,
  ADD COLUMN sample_index BIGINT NULL AFTER sequence_number,
  ADD COLUMN configured_sample_rate_hz DOUBLE NULL AFTER sample_index,
  ADD COLUMN effective_sample_rate_hz DOUBLE NULL AFTER configured_sample_rate_hz,
  ADD COLUMN clock_source VARCHAR(64) NULL AFTER effective_sample_rate_hz,
  ADD COLUMN clock_quality VARCHAR(64) NULL AFTER clock_source,
  ADD INDEX idx_stream_samples_clock (session_id, stream_name, sample_index),
  ADD INDEX idx_stream_samples_source_time (session_id, stream_name, source_timestamp_value);
