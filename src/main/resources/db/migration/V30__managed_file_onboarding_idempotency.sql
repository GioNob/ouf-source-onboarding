CREATE TABLE ouf_onboarding.managed_file_onboarding_attempt (
  asset_id uuid NOT NULL REFERENCES ouf_onboarding.managed_file_asset(asset_id) ON DELETE RESTRICT,
  subject_id text NOT NULL,
  idempotency_key varchar(128) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9_.:-]{1,128}$'),
  request_hash char(71) NOT NULL CHECK (request_hash ~ '^sha256:[0-9a-f]{64}$'),
  source_id varchar(160),
  onboarding_version_id uuid REFERENCES ouf_onboarding.onboarding_version(onboarding_version_id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  PRIMARY KEY (asset_id,subject_id,idempotency_key),
  CONSTRAINT completed_attempt_pair CHECK ((source_id IS NULL) = (onboarding_version_id IS NULL))
);
