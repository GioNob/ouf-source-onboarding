CREATE TABLE ouf_onboarding.runtime_projection (
  projection_id uuid PRIMARY KEY,
  publication_id uuid NOT NULL REFERENCES ouf_onboarding.published_configuration(publication_id) ON DELETE RESTRICT,
  source_id varchar(160) NOT NULL REFERENCES ouf_onboarding.source(source_id) ON DELETE RESTRICT,
  projection_type text NOT NULL CHECK (projection_type IN ('SOURCE_RUNTIME_PROFILE','SOURCE_SCHEMA_BINDING','ROUTE_BINDING')),
  document jsonb NOT NULL,
  checksum char(71) NOT NULL CHECK (checksum ~ '^sha256:[0-9a-f]{64}$'),
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  UNIQUE(publication_id,projection_type)
);

CREATE TABLE ouf_onboarding.schema_surveillance_issue (
  issue_id uuid PRIMARY KEY,
  source_id varchar(160) NOT NULL REFERENCES ouf_onboarding.source(source_id) ON DELETE RESTRICT,
  observed_schema_ref text NOT NULL,
  previous_fingerprint text,
  observed_fingerprint text NOT NULL,
  classification text NOT NULL CHECK (classification IN ('UNCHANGED','ADDITIVE','BREAKING','UNKNOWN')),
  detail jsonb NOT NULL DEFAULT '{}',
  state text NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN','ACKNOWLEDGED','RESOLVED')),
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  resolved_at timestamptz
);
CREATE INDEX schema_surveillance_activation_gate_idx ON ouf_onboarding.schema_surveillance_issue(source_id,state,classification);

CREATE OR REPLACE FUNCTION ouf_onboarding.reject_runtime_projection_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION USING ERRCODE='23001',MESSAGE='runtime_projection is immutable'; END $$;
CREATE TRIGGER runtime_projection_immutable BEFORE UPDATE OR DELETE ON ouf_onboarding.runtime_projection FOR EACH ROW EXECUTE FUNCTION ouf_onboarding.reject_runtime_projection_change();
