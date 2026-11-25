-- Idempotent additive migration from the original device-session schema to the
-- phone-controlled experiment/device-recording schema.

USE wearable_living_dataset;

DROP PROCEDURE IF EXISTS migrate_experiment_control_sessions;
DELIMITER //
CREATE PROCEDURE migrate_experiment_control_sessions()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'sessions'
      AND column_name = 'experiment_id'
  ) THEN
    ALTER TABLE sessions ADD COLUMN experiment_id BIGINT NULL AFTER id;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'sessions'
      AND column_name = 'status'
  ) THEN
    ALTER TABLE sessions
      ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'active' AFTER session_label;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'sessions'
      AND column_name = 'client_started_at_ms'
  ) THEN
    ALTER TABLE sessions
      ADD COLUMN client_started_at_ms BIGINT NULL AFTER ended_at_ms;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'sessions'
      AND column_name = 'client_ended_at_ms'
  ) THEN
    ALTER TABLE sessions
      ADD COLUMN client_ended_at_ms BIGINT NULL AFTER client_started_at_ms;
  END IF;

  UPDATE sessions
  SET status = CASE WHEN ended_at_ms IS NULL THEN 'active' ELSE 'stopped' END
  WHERE status IS NULL OR status = '';

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'sessions'
      AND constraint_name = 'fk_sessions_experiment'
  ) THEN
    ALTER TABLE sessions
      ADD CONSTRAINT fk_sessions_experiment
      FOREIGN KEY (experiment_id) REFERENCES experiments(id);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'sessions'
      AND index_name = 'idx_sessions_experiment_time'
  ) THEN
    ALTER TABLE sessions
      ADD INDEX idx_sessions_experiment_time (experiment_id, started_at_ms);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'sessions'
      AND index_name = 'idx_sessions_status'
  ) THEN
    ALTER TABLE sessions ADD INDEX idx_sessions_status (status);
  END IF;
END//
DELIMITER ;

CALL migrate_experiment_control_sessions();
DROP PROCEDURE migrate_experiment_control_sessions;
