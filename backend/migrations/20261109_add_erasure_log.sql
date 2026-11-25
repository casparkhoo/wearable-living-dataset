-- Audit log for participant data erasure.
--
-- Erasure deletes a participant's recordings and everything derived from them
-- (backend/src/erasure.rs). This table records each erasure and deliberately has
-- no foreign keys, so the record survives the rows it describes.

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
