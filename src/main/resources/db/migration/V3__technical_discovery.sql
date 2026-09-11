CREATE TABLE ouf_onboarding.source_binding (
  source_id varchar(160) NOT NULL REFERENCES ouf_onboarding.source(source_id) ON DELETE RESTRICT,
  binding_id varchar(160) NOT NULL,
  protocol text NOT NULL CHECK (protocol IN ('REST','OGC_API','WFS','DATABASE','FILE')),
  route_ref text NOT NULL CHECK (btrim(route_ref) <> ''),
  credential_ref text,
  configuration jsonb NOT NULL DEFAULT '{}',
  lock_version bigint NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  PRIMARY KEY(source_id,binding_id),
  CHECK (credential_ref IS NULL OR credential_ref ~ '^(secret|workload)://')
);

CREATE TABLE ouf_onboarding.discovery_run (
  discovery_run_id uuid PRIMARY KEY,
  source_id varchar(160) NOT NULL,
  binding_id varchar(160) NOT NULL,
  idempotency_key varchar(200) NOT NULL,
  request_hash char(71) NOT NULL,
  status text NOT NULL CHECK (status IN ('READY','RUNNING','SUCCEEDED','RETRY_WAIT','FAILED','CANCELLED')),
  attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
  max_attempts integer NOT NULL DEFAULT 5 CHECK (max_attempts BETWEEN 1 AND 5),
  claimed_by text,
  lease_until timestamptz,
  retry_at timestamptz,
  snapshot jsonb,
  error_code text,
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  completed_at timestamptz,
  FOREIGN KEY(source_id,binding_id) REFERENCES ouf_onboarding.source_binding(source_id,binding_id) ON DELETE RESTRICT,
  UNIQUE(source_id,idempotency_key),
  CHECK ((status='RUNNING') = (claimed_by IS NOT NULL AND lease_until IS NOT NULL)),
  CHECK ((status='SUCCEEDED') = (snapshot IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE INDEX ix_discovery_claim ON ouf_onboarding.discovery_run(status,retry_at,lease_until,created_at);

CREATE TABLE ouf_onboarding.discovered_type (
  source_id varchar(160) NOT NULL,
  type_code varchar(200) NOT NULL,
  discovery_run_id uuid NOT NULL REFERENCES ouf_onboarding.discovery_run(discovery_run_id) ON DELETE RESTRICT,
  label text,
  schema_ref text,
  PRIMARY KEY(source_id,type_code)
);
CREATE TABLE ouf_onboarding.discovered_field (
  source_id varchar(160) NOT NULL,
  type_code varchar(200) NOT NULL,
  field_name varchar(300) NOT NULL,
  data_type text NOT NULL,
  nullable boolean NOT NULL,
  discovery_run_id uuid NOT NULL REFERENCES ouf_onboarding.discovery_run(discovery_run_id) ON DELETE RESTRICT,
  PRIMARY KEY(source_id,type_code,field_name),
  FOREIGN KEY(source_id,type_code) REFERENCES ouf_onboarding.discovered_type(source_id,type_code) ON DELETE CASCADE
);
CREATE TABLE ouf_onboarding.discovered_state (
  source_id varchar(160) NOT NULL,
  type_code varchar(200) NOT NULL,
  state_code varchar(200) NOT NULL,
  label text,
  discovery_run_id uuid NOT NULL REFERENCES ouf_onboarding.discovery_run(discovery_run_id) ON DELETE RESTRICT,
  PRIMARY KEY(source_id,type_code,state_code),
  FOREIGN KEY(source_id,type_code) REFERENCES ouf_onboarding.discovered_type(source_id,type_code) ON DELETE CASCADE
);
