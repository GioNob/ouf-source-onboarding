-- This association is not part of the ordinary editable PolicyBundle.
create table ouf_authorization.superadmin_binding (
 tenant_id text primary key check(length(tenant_id)>0),
 issuer text not null check(length(issuer)>0),
 role_ref text not null check(role_ref ~ '^[A-Za-z0-9_:./-]{1,128}$'),
 revision bigint not null check(revision>=0),
 changed_by text not null, changed_at timestamptz not null default transaction_timestamp()
);
create table ouf_authorization.superadmin_transfer (
 transfer_id uuid primary key,
 tenant_id text not null references ouf_authorization.superadmin_binding(tenant_id),
 issuer text not null, source_role text not null, target_role text not null,
 binding_revision bigint not null, revision bigint not null default 0,
 state text not null check(state in ('PENDING','ACCEPTED','CANCELLED')),
 proposed_by text not null, accepted_by text,
 reason text not null check(length(reason) between 1 and 1000),
 expires_at timestamptz not null,
 created_at timestamptz not null default transaction_timestamp(),
 check(source_role<>target_role)
);
create unique index one_pending_superadmin_transfer_per_tenant
 on ouf_authorization.superadmin_transfer(tenant_id) where state='PENDING';
create table ouf_authorization.superadmin_history (
 tenant_id text not null, revision bigint not null, issuer text not null,
 role_ref text not null, changed_by text not null,
 transfer_id uuid references ouf_authorization.superadmin_transfer(transfer_id),
 changed_at timestamptz not null default transaction_timestamp(),
 primary key(tenant_id,revision)
);
create trigger superadmin_history_immutable before update or delete
 on ouf_authorization.superadmin_history for each row
 execute function ouf_authorization.reject_admin_history_mutation();
create function ouf_authorization.protect_superadmin_binding() returns trigger language plpgsql as $$
begin
 if tg_op='DELETE' then raise exception 'superadmin binding cannot be deleted'; end if;
 if new.tenant_id<>old.tenant_id or new.issuer<>old.issuer or new.revision<>old.revision+1 then
  raise exception 'invalid superadmin binding transition';
 end if;
 return new;
end; $$;
create trigger superadmin_binding_monotonic before update or delete
 on ouf_authorization.superadmin_binding for each row execute function ouf_authorization.protect_superadmin_binding();
-- Old drafts are assigned from their existing audit record; unresolvable drafts remain inaccessible.
alter table ouf_authorization.policy_draft add column tenant_id text;
update ouf_authorization.policy_draft d set tenant_id=a.tenant_id
 from ouf_authorization.admin_audit a where a.action='CREATE_DRAFT' and a.target_ref=d.draft_id::text;
