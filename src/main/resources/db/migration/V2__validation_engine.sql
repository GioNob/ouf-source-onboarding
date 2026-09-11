CREATE TABLE ouf_onboarding.validation_run (
  validation_run_id uuid PRIMARY KEY,
  onboarding_version_id uuid NOT NULL,
  source_id varchar(160) NOT NULL,
  configuration_hash char(71) NOT NULL,
  result text NOT NULL CHECK (result IN ('PASS','FAIL')),
  error_count integer NOT NULL CHECK (error_count >= 0),
  warning_count integer NOT NULL CHECK (warning_count >= 0),
  findings jsonb NOT NULL,
  actor_subject text NOT NULL,
  actor_type text NOT NULL CHECK (actor_type IN ('HUMAN_USER','AI_AGENT','SERVICE')),
  correlation_id text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  FOREIGN KEY(onboarding_version_id,source_id) REFERENCES ouf_onboarding.onboarding_version(onboarding_version_id,source_id) ON DELETE RESTRICT
);
CREATE INDEX ix_validation_run_version_created ON ouf_onboarding.validation_run(onboarding_version_id,created_at DESC);
CREATE OR REPLACE FUNCTION ouf_onboarding.reject_validation_run_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION USING ERRCODE='23001',MESSAGE='validation_run is append-only'; END $$;
CREATE TRIGGER validation_run_append_only BEFORE UPDATE OR DELETE ON ouf_onboarding.validation_run FOR EACH ROW EXECUTE FUNCTION ouf_onboarding.reject_validation_run_change();
