CREATE SCHEMA IF NOT EXISTS ouf_onboarding;

CREATE TABLE ouf_onboarding.source (
  source_id varchar(160) PRIMARY KEY,
  name text NOT NULL CHECK (btrim(name) <> ''),
  source_kind text NOT NULL CHECK (source_kind IN ('EXTERNAL_API','OGC','EXTERNAL_FILE','EXTERNAL_DB','INTERNAL_MANAGED','DERIVED')),
  acquisition_mode text NOT NULL CHECK (acquisition_mode IN ('PULL','MANAGED')),
  status text NOT NULL DEFAULT 'REGISTERED' CHECK (status IN ('REGISTERED','ENABLED','DISABLED')),
  owner_ref text NOT NULL CHECK (btrim(owner_ref) <> ''),
  metadata jsonb NOT NULL DEFAULT '{}',
  lock_version bigint NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  CHECK ((source_kind='INTERNAL_MANAGED' AND acquisition_mode='MANAGED') OR source_kind<>'INTERNAL_MANAGED')
);

CREATE TABLE ouf_onboarding.onboarding_version (
  onboarding_version_id uuid PRIMARY KEY,
  source_id varchar(160) NOT NULL REFERENCES ouf_onboarding.source(source_id) ON DELETE RESTRICT,
  version integer NOT NULL CHECK (version > 0),
  state text NOT NULL CHECK (state IN ('DRAFT','IN_REVIEW','APPROVED','ACTIVE','SUPERSEDED','REJECTED','REVOKED')),
  parent_version_id uuid REFERENCES ouf_onboarding.onboarding_version(onboarding_version_id) ON DELETE RESTRICT,
  lock_version bigint NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
  configuration jsonb NOT NULL DEFAULT '{}',
  configuration_hash char(71),
  frozen_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  UNIQUE(source_id,version),
  UNIQUE(onboarding_version_id,source_id),
  CHECK ((state IN ('IN_REVIEW','APPROVED','ACTIVE','SUPERSEDED','REJECTED','REVOKED')) = (configuration_hash IS NOT NULL)),
  CHECK ((state='DRAFT' AND frozen_at IS NULL) OR (state<>'DRAFT' AND frozen_at IS NOT NULL))
);
CREATE INDEX ix_onboarding_version_source_state ON ouf_onboarding.onboarding_version(source_id,state);

CREATE TABLE ouf_onboarding.approval_challenge (
  challenge_id uuid PRIMARY KEY,
  onboarding_version_id uuid NOT NULL,
  source_id varchar(160) NOT NULL,
  configuration_hash char(71) NOT NULL,
  status text NOT NULL CHECK (status IN ('CREATED','CONFIRMED','EXPIRED','INVALIDATED','CANCELLED')),
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  confirmed_at timestamptz,
  FOREIGN KEY(onboarding_version_id,source_id) REFERENCES ouf_onboarding.onboarding_version(onboarding_version_id,source_id) ON DELETE RESTRICT,
  CHECK ((status='CONFIRMED') = (confirmed_at IS NOT NULL))
);

CREATE TABLE ouf_onboarding.approval_decision (
  decision_id uuid PRIMARY KEY,
  challenge_id uuid NOT NULL UNIQUE REFERENCES ouf_onboarding.approval_challenge(challenge_id) ON DELETE RESTRICT,
  onboarding_version_id uuid NOT NULL,
  actor_subject text NOT NULL,
  decision text NOT NULL CHECK (decision IN ('APPROVE','REJECT')),
  target_hash char(71) NOT NULL,
  authentication_context_ref text NOT NULL,
  decided_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  FOREIGN KEY(onboarding_version_id) REFERENCES ouf_onboarding.onboarding_version(onboarding_version_id) ON DELETE RESTRICT
);

CREATE TABLE ouf_onboarding.published_configuration (
  publication_id uuid PRIMARY KEY,
  source_id varchar(160) NOT NULL REFERENCES ouf_onboarding.source(source_id) ON DELETE RESTRICT,
  onboarding_version_id uuid NOT NULL UNIQUE REFERENCES ouf_onboarding.onboarding_version(onboarding_version_id) ON DELETE RESTRICT,
  bundle_version integer NOT NULL CHECK (bundle_version > 0),
  bundle jsonb NOT NULL,
  checksum char(71) NOT NULL,
  active boolean NOT NULL DEFAULT false,
  effective_from timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  UNIQUE(source_id,bundle_version)
);
CREATE UNIQUE INDEX ux_publication_one_active_per_source ON ouf_onboarding.published_configuration(source_id) WHERE active;

CREATE TABLE ouf_onboarding.audit_event (
  audit_event_id uuid PRIMARY KEY,
  source_id varchar(160),
  onboarding_version_id uuid,
  correlation_id text NOT NULL,
  actor_subject text NOT NULL,
  actor_type text NOT NULL CHECK (actor_type IN ('HUMAN_USER','AI_AGENT','SERVICE')),
  event_type text NOT NULL,
  payload_summary jsonb NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp()
);

CREATE OR REPLACE FUNCTION ouf_onboarding.reject_immutable_decision_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION USING ERRCODE='23001',MESSAGE='approval_decision is append-only'; END $$;
CREATE TRIGGER approval_decision_append_only BEFORE UPDATE OR DELETE ON ouf_onboarding.approval_decision FOR EACH ROW EXECUTE FUNCTION ouf_onboarding.reject_immutable_decision_change();
CREATE OR REPLACE FUNCTION ouf_onboarding.reject_audit_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION USING ERRCODE='23001',MESSAGE='audit_event is append-only'; END $$;
CREATE TRIGGER audit_event_append_only BEFORE UPDATE OR DELETE ON ouf_onboarding.audit_event FOR EACH ROW EXECUTE FUNCTION ouf_onboarding.reject_audit_change();
