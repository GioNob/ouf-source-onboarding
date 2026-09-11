ALTER TABLE ouf_onboarding.file_profile_job ADD COLUMN updated_at timestamptz NOT NULL DEFAULT transaction_timestamp();

ALTER TABLE ouf_onboarding.managed_file_asset DROP CONSTRAINT managed_file_asset_size_bytes_check;
ALTER TABLE ouf_onboarding.managed_file_asset ADD CONSTRAINT managed_file_asset_size_bytes_check CHECK (size_bytes BETWEEN 1 AND 10485760);

COMMENT ON COLUMN ouf_onboarding.file_profile_job.updated_at IS
  'Canonical JobStatus updatedAt projection source.';
