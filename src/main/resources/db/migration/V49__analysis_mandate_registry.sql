create table ouf_authorization.analysis_mandate (
    mandate_ref uuid primary key,
    installation text not null,
    analysis_id text not null check (analysis_id ~ '^[0-9a-f]{64}$'),
    asset_id uuid not null,
    profile_id uuid not null,
    schema_hash text not null check (schema_hash ~ '^[0-9a-f]{64}$'),
    issuer text not null,
    tenant text not null,
    subject_id text not null,
    client_id text not null,
    mcp_workload text not null,
    operations text[] not null check (operations = array[
        'semantic.search.batch', 'semantic.discovery.request',
        'semantic.discovery.status', 'semantic.discovery.candidates']),
    policy_epoch text not null check (policy_epoch ~ '^[0-9a-f]{64}$'),
    policy_bundle_hash text not null check (policy_bundle_hash ~ '^[0-9a-f]{64}$'),
    rights_hash text not null check (rights_hash ~ '^[0-9a-f]{64}$'),
    scopes text[] not null,
    start_ref uuid not null,
    command_key uuid not null,
    issue_key uuid not null,
    issue_hash text not null check (issue_hash ~ '^[0-9a-f]{64}$'),
    generation bigint not null check (generation > 0),
    state text not null check (state in ('ACTIVE', 'REVOKED', 'EXPIRED')),
    version bigint not null,
    terminal_reason text,
    authenticated_at_ms bigint not null,
    issued_at_ms bigint not null,
    expires_at_ms bigint not null check (expires_at_ms = issued_at_ms + 86400000),
    check ((state = 'ACTIVE' and version = 1 and terminal_reason is null)
        or (state <> 'ACTIVE' and version = 2 and terminal_reason is not null)),
    unique (installation, analysis_id, generation),
    unique (installation, issuer, tenant, subject_id, issue_key)
);

create unique index analysis_mandate_one_active
    on ouf_authorization.analysis_mandate (installation, analysis_id) where state = 'ACTIVE';

create table ouf_authorization.analysis_mandate_audit (
    event_id bigint generated always as identity primary key,
    installation text not null,
    mandate_ref uuid,
    analysis_id text,
    generation bigint not null,
    policy_epoch text,
    actor_type text not null check (actor_type in ('HUMAN', 'SERVICE')),
    actor_issuer text,
    actor_tenant text,
    actor_id text not null,
    operation text,
    correlation_id uuid,
    outcome text not null check (outcome in ('ISSUED', 'ADMITTED', 'DENIED', 'REVOKED', 'EXPIRED')),
    reason text not null,
    occurred_at timestamptz not null default clock_timestamp()
);

create index analysis_mandate_audit_analysis
    on ouf_authorization.analysis_mandate_audit (installation, analysis_id, event_id);

create trigger analysis_mandate_audit_immutable before update or delete
    on ouf_authorization.analysis_mandate_audit for each row
    execute function ouf_authorization.reject_admin_history_mutation();
