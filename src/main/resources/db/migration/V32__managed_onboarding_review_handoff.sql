CREATE TABLE ouf_onboarding.managed_onboarding_review (
  tenant_id text NOT NULL,
  subject_id text NOT NULL,
  command_key varchar(128) NOT NULL CHECK (command_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
  request_hash char(71) NOT NULL CHECK (request_hash ~ '^sha256:[0-9a-f]{64}$'),
  source_id varchar(160) NOT NULL REFERENCES ouf_onboarding.source(source_id),
  onboarding_version_id uuid NOT NULL UNIQUE REFERENCES ouf_onboarding.onboarding_version(onboarding_version_id),
  challenge_id uuid NOT NULL UNIQUE REFERENCES ouf_onboarding.approval_challenge(challenge_id),
  configuration_hash char(71) NOT NULL CHECK (configuration_hash ~ '^sha256:[0-9a-f]{64}$'),
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  PRIMARY KEY (tenant_id, subject_id, command_key)
);
