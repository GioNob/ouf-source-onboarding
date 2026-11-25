-- Contract ouf.extraction-unit.v1 §8.1: an XLSX profiling operation is identified by the asset, the reader
-- version and the effective read options (declared header rows and the installation zone at request time).
-- Additive (D-080): the predecessor ignores both columns. Its rows keep operation_key NULL, which is the
-- historical "one operation per asset" profile; CSV and GeoPackage requests keep writing NULL.
ALTER TABLE ouf_onboarding.file_profile_job
  ADD COLUMN operation_key text CHECK (operation_key IS NULL OR operation_key ~ '^xlsx-v1:sha256:[0-9a-f]{64}$'),
  ADD COLUMN read_options jsonb CHECK (read_options IS NULL OR jsonb_typeof(read_options) = 'object'),
  ADD CONSTRAINT file_profile_job_operation_options CHECK ((operation_key IS NULL) = (read_options IS NULL));

CREATE UNIQUE INDEX file_profile_job_operation_uk
  ON ouf_onboarding.file_profile_job(asset_id, operation_key) WHERE operation_key IS NOT NULL;
