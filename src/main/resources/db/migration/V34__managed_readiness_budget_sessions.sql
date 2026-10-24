-- The original start and cumulative counter are retained across HUMAN sessions.
ALTER TABLE ouf_onboarding.managed_onboarding_review
  DROP CONSTRAINT managed_onboarding_review_readiness_attempts_check,
  ADD CONSTRAINT managed_review_cumulative_attempts_nonnegative CHECK (readiness_attempts >= 0),
  ADD COLUMN readiness_session_id uuid;
ALTER TABLE ouf_onboarding.managed_onboarding_review
  ADD CONSTRAINT managed_review_legacy_attempt_limit
  CHECK (readiness_session_id IS NOT NULL OR readiness_attempts <= 128);

CREATE TABLE ouf_onboarding.managed_readiness_budget_session (
  session_id uuid PRIMARY KEY,
  challenge_id uuid NOT NULL REFERENCES ouf_onboarding.managed_onboarding_review(challenge_id),
  tenant_id text NOT NULL,
  subject_id text NOT NULL,
  command_key varchar(128) NOT NULL CHECK (command_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
  request_hash char(71) NOT NULL CHECK (request_hash ~ '^sha256:[0-9a-f]{64}$'),
  configuration_hash char(71) NOT NULL CHECK (configuration_hash ~ '^sha256:[0-9a-f]{64}$'),
  decision_ref uuid NOT NULL UNIQUE,
  reason varchar(500) NOT NULL CHECK (length(btrim(reason)) BETWEEN 10 AND 500),
  profile text NOT NULL CHECK (profile = 'managed-review-recovery.v1'),
  base_attempts integer NOT NULL CHECK (base_attempts >= 0),
  prior_generation bigint NOT NULL CHECK (prior_generation >= 0),
  prior_started_at timestamptz NOT NULL,
  prior_phase text NOT NULL,
  prior_last_code varchar(100) NOT NULL,
  prior_session_id uuid REFERENCES ouf_onboarding.managed_readiness_budget_session(session_id),
  maximum_attempts integer NOT NULL CHECK (maximum_attempts = 3),
  maximum_elapsed_seconds integer NOT NULL CHECK (maximum_elapsed_seconds = 900),
  issued_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  state text NOT NULL CHECK (state IN ('ACTIVE','EXHAUSTED','COMPLETED')),
  CHECK (expires_at = issued_at + interval '900 seconds'),
  UNIQUE (session_id, challenge_id),
  UNIQUE (tenant_id, subject_id, command_key)
);
CREATE UNIQUE INDEX managed_readiness_one_active_budget
  ON ouf_onboarding.managed_readiness_budget_session(challenge_id) WHERE state = 'ACTIVE';
ALTER TABLE ouf_onboarding.managed_onboarding_review
  ADD CONSTRAINT managed_readiness_same_challenge_session
  FOREIGN KEY (readiness_session_id, challenge_id)
  REFERENCES ouf_onboarding.managed_readiness_budget_session(session_id, challenge_id);
