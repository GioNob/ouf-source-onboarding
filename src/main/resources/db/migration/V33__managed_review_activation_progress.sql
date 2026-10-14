ALTER TABLE ouf_onboarding.managed_onboarding_review
  ADD COLUMN readiness_started_at timestamptz,
  ADD COLUMN readiness_attempts integer NOT NULL DEFAULT 0 CHECK (readiness_attempts BETWEEN 0 AND 128),
  ADD COLUMN readiness_generation bigint NOT NULL DEFAULT 0,
  ADD COLUMN readiness_phase text NOT NULL DEFAULT 'NOT_STARTED',
  ADD COLUMN readiness_lease_until timestamptz,
  ADD COLUMN readiness_next_at timestamptz,
  ADD COLUMN readiness_last_code varchar(100);
