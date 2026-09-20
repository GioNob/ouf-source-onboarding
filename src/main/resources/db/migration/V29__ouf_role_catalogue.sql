-- Policy-authoring metadata; runtime bundles retain the canonical grant contract.
create table ouf_authorization.role_catalogue (
 tenant_id text primary key,
 payload jsonb not null,
 policy_ref text not null,
 changed_by text not null,
 changed_at timestamptz not null default transaction_timestamp()
);
alter table ouf_authorization.permission_proposal add column before_role_catalogue jsonb;
