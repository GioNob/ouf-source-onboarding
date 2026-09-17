create table ouf_authorization.capability_registration (
 capability_id text primary key, owner_ref text not null, descriptor jsonb not null,
 registered_by text not null, registered_at timestamptz not null default transaction_timestamp()
);
create table ouf_authorization.policy_draft (
 draft_id uuid primary key, revision bigint not null default 0, state text not null check(state in ('DRAFT','PUBLISHED','ABANDONED')),
 base_active_ref text not null, payload jsonb not null, created_by text not null, updated_at timestamptz not null default transaction_timestamp()
);
create table ouf_authorization.admin_audit (
 event_id uuid primary key, action text not null, target_ref text not null, subject_id text not null, tenant_id text not null,
 actor_type text not null check(actor_type='HUMAN'), policy_ref text not null, correlation_id text not null,
 occurred_at timestamptz not null default transaction_timestamp()
);
create function ouf_authorization.reject_admin_history_mutation() returns trigger language plpgsql as $$
begin raise exception 'authorization history is append-only'; end; $$;
create trigger capability_registration_immutable before update or delete on ouf_authorization.capability_registration for each row execute function ouf_authorization.reject_admin_history_mutation();
create trigger admin_audit_immutable before update or delete on ouf_authorization.admin_audit for each row execute function ouf_authorization.reject_admin_history_mutation();
create trigger decision_audit_immutable before update or delete on ouf_authorization.authorization_decision_audit for each row execute function ouf_authorization.reject_admin_history_mutation();
