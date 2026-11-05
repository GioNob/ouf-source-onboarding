-- Empty durable delivery state. No capability registration, grants or runtime activation.
CREATE TABLE ouf_authorization.storage_recovery_decision (
  decision_id uuid PRIMARY KEY,
  installation_ref text NOT NULL,
  tenant_id text NOT NULL,
  command_key varchar(128) NOT NULL,
  request_hash text NOT NULL,
  runtime_session_id uuid NOT NULL,
  epoch bigint NOT NULL CHECK(epoch>0),
  principal jsonb NOT NULL,
  actions jsonb NOT NULL,
  bundle_id text NOT NULL,
  bundle_version bigint NOT NULL,
  issued_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL CHECK(expires_at>issued_at),
  state text NOT NULL CHECK(state IN ('PREPARED','DELIVERED','REVOKED')),
  UNIQUE(installation_ref,command_key),
  FOREIGN KEY(bundle_id,bundle_version) REFERENCES ouf_authorization.policy_bundle(bundle_id,version),
  CHECK(expires_at<=issued_at+interval '15 minutes')
);
CREATE TABLE ouf_onboarding.storage_recovery_authority_projection (
  singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
  version bigint NOT NULL CHECK(version>=0),
  decision_id uuid REFERENCES ouf_authorization.storage_recovery_decision(decision_id)
);
INSERT INTO ouf_onboarding.storage_recovery_authority_projection VALUES(true,0,null);
CREATE TABLE ouf_authorization.storage_recovery_delivery_audit (
  event_id uuid PRIMARY KEY,
  decision_id uuid NOT NULL REFERENCES ouf_authorization.storage_recovery_decision(decision_id),
  subject_id text NOT NULL,
  event text NOT NULL,
  projection_version bigint,
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE ouf_onboarding.storage_recovery_action_audit (
  event_id uuid PRIMARY KEY,
  decision_id uuid NOT NULL REFERENCES ouf_authorization.storage_recovery_decision(decision_id),
  action text NOT NULL,
  intent_id uuid,
  outcome text NOT NULL,
  quiescence_ref text,
  runtime_session_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION ouf_authorization.guard_storage_recovery_decision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'recovery decision history immutable'; END IF;
  IF (to_jsonb(NEW)-'state') IS DISTINCT FROM (to_jsonb(OLD)-'state') OR
     NOT ((OLD.state='PREPARED' AND NEW.state IN ('DELIVERED','REVOKED')) OR
          (OLD.state='DELIVERED' AND NEW.state='REVOKED')) THEN
    RAISE EXCEPTION 'recovery decision cannot be rebound or replayed';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER storage_recovery_decision_guard BEFORE UPDATE OR DELETE ON ouf_authorization.storage_recovery_decision
  FOR EACH ROW EXECUTE FUNCTION ouf_authorization.guard_storage_recovery_decision();
CREATE FUNCTION ouf_onboarding.guard_storage_recovery_projection() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'recovery projection cannot be deleted'; END IF;
  IF NEW.singleton IS DISTINCT FROM OLD.singleton OR NEW.version<>OLD.version+1 THEN
    RAISE EXCEPTION 'recovery projection version must advance';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER storage_recovery_projection_guard BEFORE UPDATE OR DELETE ON ouf_onboarding.storage_recovery_authority_projection
  FOR EACH ROW EXECUTE FUNCTION ouf_onboarding.guard_storage_recovery_projection();
CREATE FUNCTION ouf_authorization.reject_storage_recovery_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'recovery audit immutable'; END $$;
CREATE TRIGGER storage_recovery_delivery_audit_guard BEFORE UPDATE OR DELETE ON ouf_authorization.storage_recovery_delivery_audit
  FOR EACH ROW EXECUTE FUNCTION ouf_authorization.reject_storage_recovery_audit_mutation();
CREATE TRIGGER storage_recovery_action_audit_guard BEFORE UPDATE OR DELETE ON ouf_onboarding.storage_recovery_action_audit
  FOR EACH ROW EXECUTE FUNCTION ouf_authorization.reject_storage_recovery_audit_mutation();
