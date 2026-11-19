-- Explicit HUMAN ownership reconciliation only. No inferred tenant or synthetic upload/review.
CREATE TABLE ouf_onboarding.managed_file_owner_reconciliation (
  asset_id uuid PRIMARY KEY REFERENCES ouf_onboarding.managed_file_asset(asset_id) ON DELETE RESTRICT,
  job_id uuid NOT NULL REFERENCES ouf_onboarding.file_profile_job(job_id) ON DELETE RESTRICT,
  tenant_id text NOT NULL CHECK (tenant_id=btrim(tenant_id) AND length(tenant_id) BETWEEN 1 AND 256),
  subject_id text NOT NULL CHECK (length(btrim(subject_id)) BETWEEN 1 AND 256),
  content_hash char(71) NOT NULL CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$'),
  size_bytes bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 10485760),
  input_binding_hash char(71) NOT NULL CHECK (input_binding_hash ~ '^sha256:[0-9a-f]{64}$'),
  review_hash char(71) NOT NULL CHECK (review_hash ~ '^sha256:[0-9a-f]{64}$'),
  command_key varchar(128) NOT NULL CHECK (command_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
  request_hash char(71) NOT NULL CHECK (request_hash ~ '^sha256:[0-9a-f]{64}$'),
  decision_ref uuid NOT NULL UNIQUE,
  reason varchar(500) NOT NULL CHECK (length(btrim(reason)) BETWEEN 10 AND 500),
  prior_job_version bigint NOT NULL CHECK (prior_job_version>=0),
  prior_generation bigint NOT NULL CHECK (prior_generation>=0),
  cumulative_attempts integer NOT NULL CHECK (cumulative_attempts>=1),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(tenant_id,subject_id,command_key)
);
CREATE FUNCTION ouf_onboarding.reject_managed_file_owner_receipt_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION USING ERRCODE='23001', MESSAGE='managed_file owner reconciliation is immutable'; END $$;
CREATE TRIGGER managed_file_owner_reconciliation_immutable BEFORE UPDATE OR DELETE
  ON ouf_onboarding.managed_file_owner_reconciliation FOR EACH ROW
  EXECUTE FUNCTION ouf_onboarding.reject_managed_file_owner_receipt_change();
