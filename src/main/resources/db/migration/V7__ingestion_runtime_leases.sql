ALTER TABLE ouf_onboarding.managed_file_ingestion
  ADD COLUMN attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  ADD COLUMN max_attempts integer NOT NULL DEFAULT 3 CHECK (max_attempts BETWEEN 1 AND 10),
  ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  ADD COLUMN last_error text;

DROP INDEX ouf_onboarding.managed_file_ingestion_claim_idx;
CREATE INDEX managed_file_ingestion_claim_idx
  ON ouf_onboarding.managed_file_ingestion(state,next_attempt_at,lease_until,created_at);

ALTER TABLE ouf_onboarding.pull_dispatch
  ADD COLUMN claimed_by text,
  ADD COLUMN lease_until timestamptz,
  ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  ADD COLUMN error_code text,
  ADD COLUMN last_error text,
  ADD COLUMN output_ref text,
  ADD COLUMN completed_at timestamptz,
  ADD CONSTRAINT pull_dispatch_runtime_shape_ck CHECK (
    (state='RUNNING')=(claimed_by IS NOT NULL AND lease_until IS NOT NULL)
    AND (state='SUCCEEDED')=(output_ref IS NOT NULL AND completed_at IS NOT NULL)
    AND (state='FAILED')=(error_code IS NOT NULL AND completed_at IS NOT NULL)
  );

DROP INDEX ouf_onboarding.pull_dispatch_ready_idx;
CREATE INDEX pull_dispatch_ready_idx
  ON ouf_onboarding.pull_dispatch(state,next_attempt_at,lease_until,created_at);
