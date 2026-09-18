create table ouf_installation.installation_environment_validation (
 validation_id uuid primary key,
 installation_id text not null,
 revision bigint not null,
 overall_status text not null check(overall_status in ('PASS','FAIL')),
 results jsonb not null,
 checked_by text not null,
 checked_at timestamptz not null default transaction_timestamp(),
 foreign key(installation_id,revision)
   references ouf_installation.installation_configuration_revision(installation_id,revision)
);

create index installation_environment_validation_latest_idx
on ouf_installation.installation_environment_validation(installation_id,revision,checked_at desc);

create or replace function ouf_installation.reject_environment_validation_mutation()
returns trigger language plpgsql as $$
begin
 raise exception 'installation environment validation evidence is append-only';
end $$;

create trigger installation_environment_validation_immutable
before update or delete on ouf_installation.installation_environment_validation
for each row execute function ouf_installation.reject_environment_validation_mutation();
