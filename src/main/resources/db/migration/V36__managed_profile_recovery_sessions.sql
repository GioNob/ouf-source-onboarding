-- Additive owner-local history. No retained job, counter, failed result or quarantine is reset.
ALTER TABLE ouf_onboarding.file_profile_job
  ADD COLUMN generation bigint NOT NULL DEFAULT 0 CHECK (generation >= 0),
  ADD COLUMN lock_version bigint NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
  ADD COLUMN input_binding_hash char(71),
  ADD COLUMN recovery_session_id uuid,
  ADD COLUMN budget_profile text CHECK (budget_profile='managed-profile-initial.v1'),
  ADD COLUMN failure_class text CHECK (failure_class IN ('PERMANENT','UNKNOWN','TRANSIENT','EXHAUSTED','CANCELLED','CUSTODY_CHANGED'));

CREATE TABLE ouf_onboarding.managed_profile_budget_session (
  session_id uuid PRIMARY KEY,
  job_id uuid NOT NULL REFERENCES ouf_onboarding.file_profile_job(job_id),
  asset_id uuid NOT NULL REFERENCES ouf_onboarding.managed_file_asset(asset_id),
  tenant_id text NOT NULL,
  subject_id text NOT NULL,
  command_key varchar(128) NOT NULL CHECK (command_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
  request_hash char(71) NOT NULL CHECK (request_hash ~ '^sha256:[0-9a-f]{64}$'),
  input_binding_hash char(71) NOT NULL CHECK (input_binding_hash ~ '^sha256:[0-9a-f]{64}$'),
  decision_ref uuid NOT NULL UNIQUE,
  reason varchar(500) NOT NULL CHECK (length(btrim(reason)) BETWEEN 10 AND 500),
  profile text NOT NULL CHECK (profile='managed-profile-recovery.v1'),
  base_attempts integer NOT NULL CHECK (base_attempts >= 0),
  prior_job jsonb NOT NULL CHECK (jsonb_typeof(prior_job)='object'),
  prior_session_id uuid REFERENCES ouf_onboarding.managed_profile_budget_session(session_id),
  prior_quarantine_id uuid NOT NULL REFERENCES ouf_onboarding.managed_file_quarantine(quarantine_id),
  issued_job_version bigint NOT NULL,
  issued_generation bigint NOT NULL,
  maximum_attempts integer NOT NULL CHECK (maximum_attempts=3),
  maximum_elapsed_seconds integer NOT NULL CHECK (maximum_elapsed_seconds=900),
  issued_at timestamptz NOT NULL,
  expires_at timestamptz NOT NULL,
  state text NOT NULL CHECK (state IN ('ACTIVE','EXHAUSTED','BLOCKED','COMPLETED','CANCELLED')),
  activated_at timestamptz,
  resume_request_hash char(71),
  CHECK (expires_at=issued_at+interval '900 seconds'),
  CHECK ((activated_at IS NULL)=(resume_request_hash IS NULL)),
  UNIQUE(session_id,job_id),
  UNIQUE(tenant_id,subject_id,command_key)
);
CREATE UNIQUE INDEX managed_profile_one_active_session
  ON ouf_onboarding.managed_profile_budget_session(job_id) WHERE state='ACTIVE';
ALTER TABLE ouf_onboarding.file_profile_job ADD CONSTRAINT managed_profile_session_same_job
  FOREIGN KEY(recovery_session_id,job_id) REFERENCES ouf_onboarding.managed_profile_budget_session(session_id,job_id);

CREATE TABLE ouf_onboarding.managed_profile_event (
  event_id uuid PRIMARY KEY,
  job_id uuid NOT NULL REFERENCES ouf_onboarding.file_profile_job(job_id),
  job_version bigint NOT NULL,
  generation bigint NOT NULL,
  cumulative_attempts integer NOT NULL,
  session_id uuid REFERENCES ouf_onboarding.managed_profile_budget_session(session_id),
  event_type text NOT NULL,
  safe_code text NOT NULL,
  actor_subject text,
  tenant_id text,
  command_key varchar(128),
  request_hash char(71),
  decision_ref uuid,
  reason varchar(500),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(job_id,job_version),
  UNIQUE(tenant_id,actor_subject,command_key)
);
CREATE INDEX managed_profile_event_job_idx ON ouf_onboarding.managed_profile_event(job_id,job_version DESC);

CREATE FUNCTION ouf_onboarding.guard_profile_event() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'managed profile events are append only'; END $$;
CREATE TRIGGER managed_profile_event_immutable BEFORE UPDATE OR DELETE ON ouf_onboarding.managed_profile_event
  FOR EACH ROW EXECUTE FUNCTION ouf_onboarding.guard_profile_event();
CREATE FUNCTION ouf_onboarding.guard_profile_budget() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' OR (to_jsonb(NEW)-ARRAY['state','activated_at','resume_request_hash']) IS DISTINCT FROM
      (to_jsonb(OLD)-ARRAY['state','activated_at','resume_request_hash'])
      OR OLD.state<>'ACTIVE'
      OR (OLD.activated_at IS NOT NULL AND (NEW.activated_at IS DISTINCT FROM OLD.activated_at
          OR NEW.resume_request_hash IS DISTINCT FROM OLD.resume_request_hash))
      OR (NEW.activated_at IS DISTINCT FROM OLD.activated_at AND NEW.state<>'ACTIVE') THEN
    RAISE EXCEPTION 'managed profile budget history is immutable';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER managed_profile_budget_immutable BEFORE UPDATE OR DELETE ON ouf_onboarding.managed_profile_budget_session
  FOR EACH ROW EXECUTE FUNCTION ouf_onboarding.guard_profile_budget();

CREATE FUNCTION ouf_onboarding.guard_profile_progress() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.job_id<>OLD.job_id OR NEW.asset_id<>OLD.asset_id OR NEW.idempotency_key<>OLD.idempotency_key
      OR NEW.created_at<>OLD.created_at OR NEW.budget_profile IS DISTINCT FROM OLD.budget_profile OR NEW.attempts<OLD.attempts OR NEW.generation<OLD.generation
      OR NEW.lock_version<OLD.lock_version OR
      (NEW.attempts<>OLD.attempts AND (NEW.attempts<>OLD.attempts+1 OR NEW.generation<=OLD.generation OR NEW.lock_version<=OLD.lock_version)) THEN
    RAISE EXCEPTION 'managed profile identity and cumulative progress cannot be reset';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER managed_profile_progress_monotonic BEFORE UPDATE ON ouf_onboarding.file_profile_job
  FOR EACH ROW EXECUTE FUNCTION ouf_onboarding.guard_profile_progress();
