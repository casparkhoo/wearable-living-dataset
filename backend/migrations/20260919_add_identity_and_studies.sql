-- Identity and multi-study tenancy.
--
-- Additive and idempotent. Existing behaviour is unchanged: the new ownership
-- columns are nullable, so a backend that does not yet know about them keeps
-- working. Enforcement arrives with the per-user backend changes.
--
-- Tenancy model: one database scoped by study. A study is created by an admin;
-- participants join it through study_memberships. `study_id` is carried on
-- experiments and sessions so scoping and per-study deletion are cheap, and is
-- deliberately NOT carried on raw_packets or stream_samples -- those join
-- through session_id, so the per-row storage cost of the evidence tables does
-- not grow.
--
-- Credential storage is intentionally minimal. `auth_provider` / `auth_subject`
-- support an external identity provider; `password_hash` supports local
-- accounts. Neither TOTP secrets nor recovery codes are modelled here because
-- that choice belongs with the identity-provider decision.

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

DROP PROCEDURE IF EXISTS migrate_identity_and_studies;
DELIMITER //
CREATE PROCEDURE migrate_identity_and_studies()
BEGIN
  DECLARE legacy_user_id BIGINT DEFAULT NULL;
  DECLARE legacy_study_id BIGINT DEFAULT NULL;

  -- Ownership columns on experiments.
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'experiments'
      AND column_name = 'study_id'
  ) THEN
    ALTER TABLE experiments ADD COLUMN study_id BIGINT NULL AFTER id;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'experiments'
      AND column_name = 'owner_user_id'
  ) THEN
    ALTER TABLE experiments ADD COLUMN owner_user_id BIGINT NULL AFTER study_id;
  END IF;

  -- Ownership columns on sessions (device recordings).
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'sessions'
      AND column_name = 'study_id'
  ) THEN
    ALTER TABLE sessions ADD COLUMN study_id BIGINT NULL AFTER experiment_id;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'sessions'
      AND column_name = 'owner_user_id'
  ) THEN
    ALTER TABLE sessions ADD COLUMN owner_user_id BIGINT NULL AFTER study_id;
  END IF;

  -- Foreign keys and indexes.
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'experiments'
      AND constraint_name = 'fk_experiments_study'
  ) THEN
    ALTER TABLE experiments
      ADD CONSTRAINT fk_experiments_study FOREIGN KEY (study_id) REFERENCES studies(id);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'experiments'
      AND constraint_name = 'fk_experiments_owner'
  ) THEN
    ALTER TABLE experiments
      ADD CONSTRAINT fk_experiments_owner FOREIGN KEY (owner_user_id) REFERENCES users(id);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'sessions'
      AND constraint_name = 'fk_sessions_study'
  ) THEN
    ALTER TABLE sessions
      ADD CONSTRAINT fk_sessions_study FOREIGN KEY (study_id) REFERENCES studies(id);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'sessions'
      AND constraint_name = 'fk_sessions_owner'
  ) THEN
    ALTER TABLE sessions
      ADD CONSTRAINT fk_sessions_owner FOREIGN KEY (owner_user_id) REFERENCES users(id);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'experiments'
      AND index_name = 'idx_experiments_study_owner'
  ) THEN
    ALTER TABLE experiments ADD INDEX idx_experiments_study_owner (study_id, owner_user_id);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'sessions'
      AND index_name = 'idx_sessions_study_owner'
  ) THEN
    ALTER TABLE sessions ADD INDEX idx_sessions_study_owner (study_id, owner_user_id);
  END IF;

  -- Backfill: attach pre-existing evidence to a closed legacy study so that
  -- nothing is left unowned once scoping is enforced. These captures were
  -- operator-run technical tests, not participant data.
  IF EXISTS (SELECT 1 FROM experiments WHERE study_id IS NULL)
     OR EXISTS (SELECT 1 FROM sessions WHERE study_id IS NULL) THEN

    SELECT id INTO legacy_user_id FROM users
      WHERE participant_code = 'legacy-operator' LIMIT 1;

    IF legacy_user_id IS NULL THEN
      INSERT INTO users (auth_provider, participant_code, display_name, platform_role, status)
      VALUES ('local', 'legacy-operator', 'Legacy operator', 'platform_admin', 'active');
      SET legacy_user_id = LAST_INSERT_ID();
    END IF;

    SELECT id INTO legacy_study_id FROM studies
      WHERE name = 'Legacy technical captures' LIMIT 1;

    IF legacy_study_id IS NULL THEN
      INSERT INTO studies (name, description, status, created_by_user_id)
      VALUES ('Legacy technical captures',
              'Operator-run technical captures recorded before studies and user accounts existed.',
              'closed', legacy_user_id);
      SET legacy_study_id = LAST_INSERT_ID();
    END IF;

    IF NOT EXISTS (
      SELECT 1 FROM study_memberships
      WHERE study_id = legacy_study_id AND user_id = legacy_user_id
    ) THEN
      INSERT INTO study_memberships (study_id, user_id, role, status, joined_at)
      VALUES (legacy_study_id, legacy_user_id, 'study_admin', 'active', NOW(3));
    END IF;

    UPDATE experiments
      SET study_id = legacy_study_id,
          owner_user_id = COALESCE(owner_user_id, legacy_user_id)
      WHERE study_id IS NULL;

    UPDATE sessions
      SET study_id = legacy_study_id,
          owner_user_id = COALESCE(owner_user_id, legacy_user_id)
      WHERE study_id IS NULL;
  END IF;
END//
DELIMITER ;

CALL migrate_identity_and_studies();
DROP PROCEDURE migrate_identity_and_studies;
