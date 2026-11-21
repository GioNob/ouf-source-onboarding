-- Legacy issues retain their original identity and activation semantics.
ALTER TABLE ouf_onboarding.schema_surveillance_issue
  ADD COLUMN tenant_id text,
  ADD COLUMN incident_key varchar(512),
  ADD COLUMN expected_schema_ref text,
  ADD COLUMN observed_schema jsonb,
  ADD COLUMN differences jsonb,
  ADD COLUMN frozen_checkpoint_ref text,
  ADD COLUMN observation_polls bigint CHECK (observation_polls >= 0),
  ADD COLUMN last_observed_at timestamptz,
  ADD COLUMN resolution_reason text CHECK (resolution_reason IN ('SCHEMA_RETURNED','REVISION_ACTIVATED')),
  ADD CONSTRAINT schema_notice_scope UNIQUE (tenant_id,source_id,incident_key),
  ADD CHECK (incident_key IS NULL OR tenant_id IS NOT NULL);

CREATE INDEX schema_notice_page_idx ON ouf_onboarding.schema_surveillance_issue
  (tenant_id,state,issue_id) WHERE observed_schema IS NOT NULL;

ALTER TABLE ouf_onboarding.source_property_revision ADD COLUMN schema_notice_id uuid
  REFERENCES ouf_onboarding.schema_surveillance_issue(issue_id);
CREATE INDEX schema_notice_revision_idx ON ouf_onboarding.source_property_revision (schema_notice_id)
  WHERE schema_notice_id IS NOT NULL;
