CREATE TABLE ouf_onboarding.managed_file_asset (
  asset_id uuid PRIMARY KEY,
  source_id varchar(160) REFERENCES ouf_onboarding.source(source_id) ON DELETE RESTRICT,
  staging_ref text NOT NULL CHECK (staging_ref ~ '^object://'),
  content_hash char(71) NOT NULL CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$'),
  media_type text NOT NULL CHECK (media_type IN ('text/csv','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')),
  size_bytes bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 52428800),
  uploaded_by text NOT NULL,
  retention_ref text NOT NULL,
  status text NOT NULL DEFAULT 'STAGED' CHECK (status IN ('STAGED','PROFILED','QUARANTINED')),
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  UNIQUE(staging_ref,content_hash)
);

CREATE TABLE ouf_onboarding.file_profile (
  profile_id uuid PRIMARY KEY,
  asset_id uuid NOT NULL REFERENCES ouf_onboarding.managed_file_asset(asset_id) ON DELETE RESTRICT,
  version integer NOT NULL CHECK (version > 0),
  format text NOT NULL CHECK (format IN ('CSV','XLSX')),
  metadata jsonb NOT NULL,
  inferred_schema jsonb NOT NULL,
  candidate_keys jsonb NOT NULL,
  sample_ref text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  UNIQUE(asset_id,version)
);
CREATE OR REPLACE FUNCTION ouf_onboarding.reject_file_profile_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION USING ERRCODE='23001',MESSAGE='file_profile is immutable'; END $$;
CREATE TRIGGER file_profile_immutable BEFORE UPDATE OR DELETE ON ouf_onboarding.file_profile FOR EACH ROW EXECUTE FUNCTION ouf_onboarding.reject_file_profile_change();
