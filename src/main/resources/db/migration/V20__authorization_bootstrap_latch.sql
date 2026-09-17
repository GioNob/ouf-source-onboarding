create table ouf_authorization.bootstrap_latch (
    singleton_key boolean primary key default true check (singleton_key),
    completed boolean not null default false,
    completed_at timestamptz,
    completed_by text,
    check ((completed and completed_at is not null and completed_by is not null) or (not completed and completed_at is null and completed_by is null))
);

insert into ouf_authorization.bootstrap_latch(singleton_key, completed, completed_at, completed_by)
select true,
       exists(select 1 from ouf_authorization.active_policy_bundle),
       case when exists(select 1 from ouf_authorization.active_policy_bundle) then transaction_timestamp() end,
       case when exists(select 1 from ouf_authorization.active_policy_bundle) then 'migration:existing-active-policy' end;

create or replace function ouf_authorization.reject_bootstrap_latch_regression()
returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'authorization bootstrap latch cannot be deleted';
    end if;
    if old.completed and not new.completed then
        raise exception 'authorization bootstrap latch cannot be reopened';
    end if;
    if old.completed and (new.completed_at is distinct from old.completed_at or new.completed_by is distinct from old.completed_by) then
        raise exception 'authorization bootstrap completion is immutable';
    end if;
    return new;
end;
$$;

create trigger bootstrap_latch_monotonic
before update or delete on ouf_authorization.bootstrap_latch
for each row execute function ouf_authorization.reject_bootstrap_latch_regression();
