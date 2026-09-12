CREATE TABLE ouf_onboarding.managed_file_quarantine(
 quarantine_id uuid PRIMARY KEY,
 asset_id uuid NOT NULL REFERENCES ouf_onboarding.managed_file_asset(asset_id) ON DELETE RESTRICT,
 profile_job_id uuid REFERENCES ouf_onboarding.file_profile_job(job_id) ON DELETE RESTRICT,
 reason_code text NOT NULL,
 evidence_ref text NOT NULL CHECK(evidence_ref ~ '^quarantine://'),
 safe_detail text,
 state text NOT NULL DEFAULT 'OPEN' CHECK(state IN('OPEN','RELEASED','REJECTED')),
 created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
 resolved_at timestamptz,
 resolved_by_subject text,
 authorization_context_ref text,
 correlation_id text NOT NULL,
 CHECK((state='OPEN' AND resolved_at IS NULL AND resolved_by_subject IS NULL) OR (state<>'OPEN' AND resolved_at IS NOT NULL AND resolved_by_subject IS NOT NULL))
);
CREATE UNIQUE INDEX managed_file_one_open_quarantine ON ouf_onboarding.managed_file_quarantine(asset_id) WHERE state='OPEN';
CREATE INDEX managed_file_quarantine_search ON ouf_onboarding.managed_file_quarantine(state,created_at DESC);

CREATE FUNCTION ouf_onboarding.managed_file_quarantine_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF TG_OP='DELETE' THEN RAISE EXCEPTION USING ERRCODE='23001',MESSAGE='managed file quarantine evidence is append-only';END IF;
 IF OLD.state='OPEN' AND NEW.state IN('RELEASED','REJECTED') AND
    (to_jsonb(NEW)-'state'-'resolved_at'-'resolved_by_subject'-'authorization_context_ref')=(to_jsonb(OLD)-'state'-'resolved_at'-'resolved_by_subject'-'authorization_context_ref') THEN RETURN NEW;END IF;
 RAISE EXCEPTION USING ERRCODE='23001',MESSAGE='managed file quarantine transition forbidden';END $$;
CREATE TRIGGER managed_file_quarantine_immutable BEFORE UPDATE OR DELETE ON ouf_onboarding.managed_file_quarantine FOR EACH ROW EXECUTE FUNCTION ouf_onboarding.managed_file_quarantine_guard();
