-- Owner-local fence. No runtime operator endpoint or deployment grant is introduced.
CREATE TABLE ouf_onboarding.managed_file_storage_control (
  singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
  epoch bigint NOT NULL CHECK(epoch > 0),
  phase text NOT NULL CHECK(phase IN ('ACTIVE','DRAINING','FROZEN'))
);
INSERT INTO ouf_onboarding.managed_file_storage_control VALUES(true,1,'ACTIVE');

CREATE FUNCTION ouf_onboarding.guard_managed_storage_control() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'storage control cannot be deleted'; END IF;
  IF NEW.singleton IS DISTINCT FROM OLD.singleton OR NEW.epoch<OLD.epoch OR
     (NEW.epoch=OLD.epoch AND ((OLD.phase='FROZEN' AND NEW.phase<>'FROZEN') OR
       (OLD.phase='DRAINING' AND NEW.phase='ACTIVE'))) THEN
    RAISE EXCEPTION 'storage fence cannot regress';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER managed_storage_control_guard BEFORE UPDATE OR DELETE ON ouf_onboarding.managed_file_storage_control
  FOR EACH ROW EXECUTE FUNCTION ouf_onboarding.guard_managed_storage_control();

CREATE TABLE ouf_onboarding.managed_file_upload_intent (
  intent_id uuid PRIMARY KEY,
  upload_kind text NOT NULL CHECK(upload_kind IN ('DIRECT','DELEGATED')),
  tenant_id text NOT NULL CHECK(length(tenant_id) BETWEEN 1 AND 256),
  subject_id text NOT NULL CHECK(length(subject_id) BETWEEN 1 AND 256),
  idempotency_key varchar(128) NOT NULL CHECK(idempotency_key ~ '^[A-Za-z0-9_.:-]{1,128}$'),
  file_id varchar(133) NOT NULL CHECK(file_id ~ '^file_[A-Za-z0-9_-]{1,128}$'),
  content_hash char(71) NOT NULL CHECK(content_hash ~ '^sha256:[0-9a-f]{64}$'),
  size_bytes bigint NOT NULL CHECK(size_bytes BETWEEN 1 AND 10485760),
  staging_ref text NOT NULL UNIQUE,
  epoch bigint NOT NULL CHECK(epoch > 0),
  state text NOT NULL CHECK(state IN ('DISPATCHED','ACKED')),
  asset_id uuid REFERENCES ouf_onboarding.managed_file_asset(asset_id) ON DELETE RESTRICT,
  reconciliation_reads integer NOT NULL DEFAULT 0 CHECK(reconciliation_reads BETWEEN 0 AND 128),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  reconcile_until timestamptz NOT NULL DEFAULT clock_timestamp()+interval '24 hours',
  acknowledged_at timestamptz,
  UNIQUE(upload_kind,tenant_id,subject_id,idempotency_key),
  CHECK(staging_ref='object://managed-files/'||intent_id::text),
  CHECK((state='ACKED')=(asset_id IS NOT NULL)),
  CHECK((state='ACKED')=(acknowledged_at IS NOT NULL))
);

CREATE FUNCTION ouf_onboarding.guard_managed_upload_intent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'upload intent history is immutable'; END IF;
  IF (to_jsonb(NEW)-ARRAY['state','asset_id','reconciliation_reads','acknowledged_at']) IS DISTINCT FROM
     (to_jsonb(OLD)-ARRAY['state','asset_id','reconciliation_reads','acknowledged_at'])
     OR OLD.state='ACKED' OR NEW.reconciliation_reads<OLD.reconciliation_reads THEN
    RAISE EXCEPTION 'upload intent binding/history is immutable';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER managed_upload_intent_guard BEFORE UPDATE OR DELETE ON ouf_onboarding.managed_file_upload_intent
  FOR EACH ROW EXECUTE FUNCTION ouf_onboarding.guard_managed_upload_intent();
