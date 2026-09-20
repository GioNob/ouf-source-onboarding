create table ouf_authorization.permission_proposal (
 proposal_id uuid primary key,
 tenant_id text not null,
 proposed_by text not null,
 idempotency_key text not null,
 request_hash text not null,
 base_active_ref text not null,
 base_hash text not null,
 proposed_hash text not null,
 proposed_policy jsonb not null,
 change_payload jsonb not null,
 state text not null default 'PENDING' check(state in ('PENDING','PUBLISHED','REJECTED')),
 revision bigint not null default 0,
 expires_at timestamptz not null,
 created_at timestamptz not null default transaction_timestamp(),
 decided_by text,
 final_policy_ref text,
 unique(tenant_id,proposed_by,idempotency_key)
);
create function ouf_authorization.protect_permission_proposal() returns trigger language plpgsql as $$
begin
 if TG_OP='DELETE' then raise exception 'permission proposal history is append-only'; end if;
 if OLD.state <> 'PENDING' or NEW.state not in ('PUBLISHED','REJECTED') or NEW.revision<>OLD.revision+1
 or (to_jsonb(NEW)-array['state','revision','decided_by','final_policy_ref']) is distinct from
    (to_jsonb(OLD)-array['state','revision','decided_by','final_policy_ref'])
 then raise exception 'permission proposal is immutable'; end if;
 return NEW;
end $$;
create trigger permission_proposal_immutable before update or delete on ouf_authorization.permission_proposal
 for each row execute function ouf_authorization.protect_permission_proposal();
