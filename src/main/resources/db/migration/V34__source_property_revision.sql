-- A command journal, not a second publication/approval authority. No historical rows are rewritten.
CREATE TABLE ouf_onboarding.source_property_revision (
  tenant_id text NOT NULL,
  subject_id text NOT NULL,
  command_key varchar(128) NOT NULL CHECK (command_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
  request_hash char(71) NOT NULL CHECK (request_hash ~ '^sha256:[a-f0-9]{64}$'),
  source_id varchar(160) NOT NULL REFERENCES ouf_onboarding.source(source_id),
  parent_version_id uuid NOT NULL,
  parent_configuration_hash char(71) NOT NULL CHECK (parent_configuration_hash ~ '^sha256:[a-f0-9]{64}$'),
  onboarding_version_id uuid NOT NULL UNIQUE REFERENCES ouf_onboarding.managed_onboarding_review(onboarding_version_id),
  publication_effect text NOT NULL CHECK (publication_effect='NEW_PUBLICATION_MAY_START_ACQUISITION'),
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  PRIMARY KEY(tenant_id,subject_id,command_key),
  FOREIGN KEY(parent_version_id,source_id) REFERENCES ouf_onboarding.onboarding_version(onboarding_version_id,source_id),
  FOREIGN KEY(onboarding_version_id,source_id) REFERENCES ouf_onboarding.onboarding_version(onboarding_version_id,source_id)
);
CREATE TRIGGER source_property_revision_append_only BEFORE UPDATE OR DELETE ON ouf_onboarding.source_property_revision
FOR EACH ROW EXECUTE FUNCTION ouf_onboarding.reject_audit_change();
