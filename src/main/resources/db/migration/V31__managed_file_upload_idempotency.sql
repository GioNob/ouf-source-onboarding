CREATE TABLE ouf_onboarding.managed_file_upload_attempt (
  tenant_id text NOT NULL,
  subject_id text NOT NULL,
  file_id varchar(133) NOT NULL CHECK (file_id ~ '^file_[A-Za-z0-9_-]{1,128}$'),
  idempotency_key varchar(128) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9_.:-]{1,128}$'),
  content_hash char(71) NOT NULL CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$'),
  size_bytes bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 10485760),
  asset_id uuid NOT NULL REFERENCES ouf_onboarding.managed_file_asset(asset_id) ON DELETE RESTRICT,
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  PRIMARY KEY (tenant_id,subject_id,idempotency_key)
);
