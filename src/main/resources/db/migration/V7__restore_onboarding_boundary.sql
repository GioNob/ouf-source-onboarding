CREATE SCHEMA IF NOT EXISTS ouf_onboarding_legacy;

DO $$
BEGIN
  IF to_regclass('ouf_onboarding.pull_dispatch') IS NOT NULL THEN
    ALTER TABLE ouf_onboarding.pull_dispatch SET SCHEMA ouf_onboarding_legacy;
  END IF;
  IF to_regclass('ouf_onboarding.pull_schedule') IS NOT NULL THEN
    ALTER TABLE ouf_onboarding.pull_schedule SET SCHEMA ouf_onboarding_legacy;
  END IF;
  IF to_regclass('ouf_onboarding.managed_file_ingestion') IS NOT NULL THEN
    ALTER TABLE ouf_onboarding.managed_file_ingestion SET SCHEMA ouf_onboarding_legacy;
  END IF;
END $$;

COMMENT ON SCHEMA ouf_onboarding_legacy IS
  'Preserved pre-v1.5-boundary data. Runtime scheduling and ingestion are owned by OUF Ingestion Runtime.';

CREATE TABLE ouf_onboarding.file_profile_job (
  job_id uuid PRIMARY KEY,
  asset_id uuid NOT NULL REFERENCES ouf_onboarding.managed_file_asset(asset_id) ON DELETE RESTRICT,
  idempotency_key text NOT NULL CHECK (btrim(idempotency_key) <> ''),
  state text NOT NULL DEFAULT 'READY' CHECK (state IN ('READY','RUNNING','RETRY_WAIT','SUCCEEDED','FAILED','CANCELLED')),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  claimed_by text,
  lease_until timestamptz,
  next_attempt_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  profile_id uuid REFERENCES ouf_onboarding.file_profile(profile_id) ON DELETE RESTRICT,
  error_code text,
  last_error text,
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  completed_at timestamptz,
  UNIQUE(asset_id,idempotency_key),
  CHECK ((state='RUNNING')=(claimed_by IS NOT NULL AND lease_until IS NOT NULL)),
  CHECK ((state='SUCCEEDED')=(profile_id IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE INDEX file_profile_job_claim_idx
  ON ouf_onboarding.file_profile_job(state,next_attempt_at,lease_until,created_at);

COMMENT ON TABLE ouf_onboarding.file_profile_job IS
  'Onboarding-owned asynchronous profiling work. It never represents or gates ingestion execution.';

CREATE TABLE ouf_onboarding.consumer_compatibility_attestation (
  attestation_id uuid PRIMARY KEY,
  onboarding_version_id uuid NOT NULL REFERENCES ouf_onboarding.onboarding_version(onboarding_version_id) ON DELETE RESTRICT,
  consumer text NOT NULL CHECK (consumer IN ('INGESTION_RUNTIME')),
  configuration_hash char(71) NOT NULL,
  compatible boolean NOT NULL,
  detail text NOT NULL,
  actor_subject text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp()
);

CREATE INDEX consumer_compatibility_lookup_idx
  ON ouf_onboarding.consumer_compatibility_attestation(onboarding_version_id,consumer,configuration_hash,created_at DESC);

COMMENT ON TABLE ouf_onboarding.consumer_compatibility_attestation IS
  'Cross-module reference-integrity evidence. It records consumer acceptance and never records ingestion outcome.';
