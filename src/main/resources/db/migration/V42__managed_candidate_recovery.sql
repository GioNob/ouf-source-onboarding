-- Existing reviews keep their original key, challenge, decisions and readiness budget.
-- A row is written only for a new explicit recovery command, including on pre-V42 candidates.
-- V35 required a non-null code for every prior grant. New grants can record a
-- reconciled interruption while preserving the distinction from a reported error.
ALTER TABLE ouf_onboarding.managed_readiness_budget_session
  ADD COLUMN prior_last_code_absent boolean NOT NULL DEFAULT false;

-- All revisions already prepared in production required an ACTIVE parent.
ALTER TABLE ouf_onboarding.source_property_revision
  ADD COLUMN parent_state_at_prepare text NOT NULL DEFAULT 'ACTIVE'
  CHECK (parent_state_at_prepare IN ('ACTIVE','REJECTED'));

CREATE TABLE ouf_onboarding.managed_candidate_recovery (
  tenant_id text NOT NULL,
  subject_id text NOT NULL,
  command_key varchar(128) NOT NULL,
  request_hash char(71) NOT NULL,
  operation text NOT NULL CHECK (operation IN ('RENEW_CHALLENGE','WITHDRAW','RESTART_DRAFT')),
  source_id varchar(160) NOT NULL,
  candidate_id uuid NOT NULL,
  result_id uuid NOT NULL,
  previous_challenge uuid REFERENCES ouf_onboarding.approval_challenge,
  issued_challenge uuid REFERENCES ouf_onboarding.approval_challenge,
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  PRIMARY KEY (tenant_id,subject_id,command_key),
  FOREIGN KEY (candidate_id,source_id) REFERENCES ouf_onboarding.onboarding_version(onboarding_version_id,source_id),
  FOREIGN KEY (result_id,source_id) REFERENCES ouf_onboarding.onboarding_version(onboarding_version_id,source_id)
);
CREATE INDEX managed_candidate_withdrawal ON ouf_onboarding.managed_candidate_recovery(source_id,candidate_id)
  WHERE operation='WITHDRAW';

-- revision.prepare rewrites these local version references on every clone. They cannot
-- establish new processing intent for a withdrawn candidate with spent readiness attempts.
CREATE FUNCTION ouf_onboarding.candidate_intent(config jsonb) RETURNS jsonb
  LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT config #- '{bundle,bundleVersion}' #- '{bundle,extractionProfileRef}'
      #- '{extractionProfile,version}' #- '{extractionProfile,runtime,execution,sourceSchemaVersion}'
  $$;

CREATE TRIGGER managed_candidate_recovery_append_only BEFORE UPDATE OR DELETE ON ouf_onboarding.managed_candidate_recovery
FOR EACH ROW EXECUTE FUNCTION ouf_onboarding.reject_audit_change();
