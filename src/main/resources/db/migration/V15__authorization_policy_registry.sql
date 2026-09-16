create schema if not exists ouf_authorization;

create table if not exists ouf_authorization.policy_bundle (
    bundle_id text not null,
    version bigint not null check (version > 0),
    published_at timestamptz not null,
    published_by text not null,
    content_hash text not null,
    bundle_payload jsonb not null,
    primary key (bundle_id, version),
    unique (content_hash)
);

create table if not exists ouf_authorization.active_policy_bundle (
    singleton_key boolean primary key default true check (singleton_key),
    bundle_id text not null,
    version bigint not null,
    activated_at timestamptz not null default transaction_timestamp(),
    foreign key (bundle_id, version) references ouf_authorization.policy_bundle(bundle_id, version)
);

create table if not exists ouf_authorization.authorization_decision_audit (
    decision_id uuid primary key,
    decision_ref text not null,
    bundle_id text not null,
    bundle_version bigint not null,
    tenant_id text not null,
    subject_id text not null,
    service_principal_id text,
    capability_id text not null,
    operation text not null,
    allowed boolean not null,
    decision_code text not null,
    authentication_context_ref text not null,
    decided_at timestamptz not null default transaction_timestamp(),
    foreign key (bundle_id, bundle_version) references ouf_authorization.policy_bundle(bundle_id, version)
);

create index if not exists authorization_decision_audit_tenant_time_idx
    on ouf_authorization.authorization_decision_audit(tenant_id, decided_at desc);

create or replace function ouf_authorization.reject_policy_bundle_mutation()
returns trigger language plpgsql as $$
begin
    raise exception 'policy_bundle is immutable';
end;
$$;

create trigger policy_bundle_immutable_update
before update or delete on ouf_authorization.policy_bundle
for each row execute function ouf_authorization.reject_policy_bundle_mutation();
