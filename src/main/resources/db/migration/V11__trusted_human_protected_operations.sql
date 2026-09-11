CREATE TABLE ouf_onboarding.protected_log_access_audit (
  access_id uuid PRIMARY KEY,
  actor_subject text NOT NULL,
  operation text NOT NULL CHECK (operation IN ('SEARCH','READ','AGGREGATE','CORRELATE','EXPORT_CREATE','EXPORT_READ','DOWNLOAD')),
  purpose text NOT NULL CHECK (btrim(purpose) <> ''),
  request_scope jsonb NOT NULL DEFAULT '{}',
  result_count integer NOT NULL DEFAULT 0 CHECK (result_count >= 0),
  authorization_context_ref text NOT NULL,
  outcome text NOT NULL CHECK (outcome IN ('ALLOWED','DENIED','FAILED')),
  correlation_id text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp()
);
CREATE INDEX protected_log_access_actor_time_idx ON ouf_onboarding.protected_log_access_audit(actor_subject,created_at DESC);

CREATE OR REPLACE FUNCTION ouf_onboarding.reject_protected_log_access_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION USING ERRCODE='23001',MESSAGE='protected_log_access_audit is append-only'; END $$;
CREATE TRIGGER protected_log_access_append_only BEFORE UPDATE OR DELETE ON ouf_onboarding.protected_log_access_audit FOR EACH ROW EXECUTE FUNCTION ouf_onboarding.reject_protected_log_access_change();

CREATE TABLE ouf_onboarding.protected_log_export_job (
  export_id uuid PRIMARY KEY,
  requested_by text NOT NULL,
  purpose text NOT NULL CHECK (btrim(purpose) <> ''),
  authorization_context_ref text NOT NULL,
  request_scope jsonb NOT NULL,
  state text NOT NULL DEFAULT 'READY' CHECK (state IN ('READY','RUNNING','RETRY_WAIT','SUCCEEDED','FAILED','EXPIRED','CANCELLED')),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
  claimed_by text,
  lease_until timestamptz,
  next_attempt_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  record_count integer CHECK (record_count IS NULL OR record_count >= 0),
  result_ref text,
  checksum char(71) CHECK (checksum IS NULL OR checksum ~ '^sha256:[0-9a-f]{64}$'),
  artifact jsonb,
  expires_at timestamptz,
  error_code text,
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  completed_at timestamptz,
  CHECK ((state='RUNNING')=(claimed_by IS NOT NULL AND lease_until IS NOT NULL)),
  CHECK (state<>'SUCCEEDED' OR (record_count IS NOT NULL AND result_ref IS NOT NULL AND checksum IS NOT NULL AND artifact IS NOT NULL AND expires_at IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE INDEX protected_log_export_claim_idx ON ouf_onboarding.protected_log_export_job(state,next_attempt_at,lease_until,created_at);

COMMENT ON TABLE ouf_onboarding.protected_log_export_job IS
  'THS-only bounded export lifecycle. artifact is a bounded baseline adapter; production deployments may materialize the opaque result_ref in the shared object store.';
