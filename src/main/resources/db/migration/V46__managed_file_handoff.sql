create table ouf_onboarding.managed_file_handoff (
    handoff_id uuid primary key,
    tenant_id text not null check (btrim(tenant_id) <> ''),
    subject_id text not null check (btrim(subject_id) <> ''),
    issued_at timestamptz not null,
    wait_until timestamptz,
    command_key uuid unique,
    legacy boolean not null,
    state text not null check (state in ('PENDING', 'STAGED', 'REJECTED', 'EXPIRED')),
    observation_version bigint not null check (observation_version > 0),
    asset_id uuid,
    code text check (code in ('FORMAT_UNSUPPORTED', 'SIZE_INVALID')),
    completed_at timestamptz,
    receipt_retired boolean not null default false,
    check ((legacy and command_key is null and wait_until is null and state in ('STAGED', 'REJECTED'))
        or (not legacy and command_key is not null and wait_until is not null and wait_until > issued_at)),
    check ((state = 'PENDING') = (completed_at is null)),
    check (not receipt_retired or (not legacy and completed_at is not null)),
    check ((asset_id is not null) = (state = 'STAGED' and not receipt_retired)),
    check ((code is not null) = (state = 'REJECTED' and not receipt_retired))
);

create index managed_file_handoff_retention on ouf_onboarding.managed_file_handoff (legacy, completed_at)
    where completed_at is not null and not receipt_retired;
create index managed_file_handoff_deadline on ouf_onboarding.managed_file_handoff (wait_until)
    where state = 'PENDING';
