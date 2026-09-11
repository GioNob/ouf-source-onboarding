ALTER TABLE ouf_onboarding.file_profile ADD CONSTRAINT file_profile_asset_scope_uk UNIQUE(profile_id,asset_id);
ALTER TABLE ouf_onboarding.managed_file_asset ADD CONSTRAINT managed_file_asset_source_scope_uk UNIQUE(asset_id,source_id);

CREATE TABLE ouf_onboarding.managed_file_ingestion (
  ingestion_id uuid PRIMARY KEY,
  asset_id uuid NOT NULL,
  profile_id uuid NOT NULL,
  source_id varchar(160) NOT NULL,
  onboarding_version_id uuid NOT NULL,
  state text NOT NULL DEFAULT 'READY' CHECK (state IN ('READY','RUNNING','SUCCEEDED','QUARANTINED')),
  claimed_by text,
  lease_until timestamptz,
  output_ref text,
  ingested_rows bigint CHECK (ingested_rows IS NULL OR ingested_rows>=0),
  error_code text,
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  completed_at timestamptz,
  UNIQUE(profile_id), UNIQUE(onboarding_version_id),
  FOREIGN KEY(profile_id,asset_id) REFERENCES ouf_onboarding.file_profile(profile_id,asset_id) ON DELETE RESTRICT,
  FOREIGN KEY(asset_id,source_id) REFERENCES ouf_onboarding.managed_file_asset(asset_id,source_id) ON DELETE RESTRICT,
  FOREIGN KEY(onboarding_version_id,source_id) REFERENCES ouf_onboarding.onboarding_version(onboarding_version_id,source_id) ON DELETE RESTRICT,
  CHECK ((state='RUNNING')=(claimed_by IS NOT NULL AND lease_until IS NOT NULL)),
  CHECK ((state='SUCCEEDED')=(output_ref IS NOT NULL AND ingested_rows IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE INDEX managed_file_ingestion_claim_idx ON ouf_onboarding.managed_file_ingestion(state,lease_until,created_at);

CREATE TABLE ouf_onboarding.pull_schedule (
  schedule_id uuid PRIMARY KEY,
  source_id varchar(160) NOT NULL UNIQUE REFERENCES ouf_onboarding.source ON DELETE RESTRICT,
  onboarding_version_id uuid NOT NULL UNIQUE REFERENCES ouf_onboarding.onboarding_version ON DELETE RESTRICT,
  interval_seconds integer NOT NULL CHECK (interval_seconds BETWEEN 60 AND 2678400),
  timezone text NOT NULL DEFAULT 'UTC',
  timeout_seconds integer NOT NULL CHECK (timeout_seconds BETWEEN 1 AND 3600),
  max_attempts integer NOT NULL CHECK (max_attempts BETWEEN 1 AND 10),
  overlap_policy text NOT NULL DEFAULT 'SKIP' CHECK (overlap_policy='SKIP'),
  enabled boolean NOT NULL DEFAULT true,
  next_fire_at timestamptz NOT NULL,
  lock_version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT transaction_timestamp()
);
CREATE INDEX pull_schedule_due_idx ON ouf_onboarding.pull_schedule(next_fire_at) WHERE enabled;

CREATE TABLE ouf_onboarding.pull_dispatch (
  dispatch_id uuid PRIMARY KEY,
  schedule_id uuid NOT NULL REFERENCES ouf_onboarding.pull_schedule ON DELETE RESTRICT,
  source_id varchar(160) NOT NULL REFERENCES ouf_onboarding.source ON DELETE RESTRICT,
  onboarding_version_id uuid NOT NULL REFERENCES ouf_onboarding.onboarding_version ON DELETE RESTRICT,
  due_at timestamptz NOT NULL,
  state text NOT NULL DEFAULT 'READY' CHECK (state IN ('READY','RUNNING','SUCCEEDED','RETRY_WAIT','FAILED')),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts>=0),
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  UNIQUE(schedule_id,due_at)
);
CREATE INDEX pull_dispatch_ready_idx ON ouf_onboarding.pull_dispatch(state,created_at);
