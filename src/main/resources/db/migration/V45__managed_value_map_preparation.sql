-- The V43/V44 draft, review and receipt contracts stay readable by predecessors.
CREATE TABLE ouf_onboarding.managed_value_map_preparation (
    tenant_id text NOT NULL,
    subject_id text NOT NULL,
    command_key varchar(128) NOT NULL,
    request_hash char(71) NOT NULL,
    onboarding_version_id uuid NOT NULL REFERENCES ouf_onboarding.onboarding_version ON DELETE CASCADE,
    selection_hash char(71) NOT NULL,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 128),
    deadline timestamptz NOT NULL DEFAULT (clock_timestamp() + interval '24 hours'),
    lease_until timestamptz,
    next_at timestamptz,
    phase text NOT NULL DEFAULT 'READY' CHECK (phase IN ('READY','CHECKING','RETRY_WAIT','EXHAUSTED','BLOCKED','CANCELLED','COMPLETED')),
    last_code text,
    last_status integer,
    digests jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (tenant_id, subject_id, command_key),
    UNIQUE (onboarding_version_id, selection_hash)
);

CREATE INDEX managed_value_map_preparation_lease
    ON ouf_onboarding.managed_value_map_preparation (lease_until)
    WHERE lease_until IS NOT NULL;
