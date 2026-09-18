create table ouf_installation.installation_projection_export_event (
 export_id uuid primary key,
 installation_id text not null,
 revision bigint not null,
 configuration_checksum char(64) not null check(configuration_checksum~'^[0-9a-f]{64}$'),
 actor_subject text not null,
 correlation_id text not null,
 exported_at timestamptz not null default transaction_timestamp(),
 foreign key(installation_id,revision)
   references ouf_installation.installation_configuration_revision(installation_id,revision)
);

create index installation_projection_export_lookup_idx
on ouf_installation.installation_projection_export_event(installation_id,revision,exported_at);

create or replace function ouf_installation.reject_projection_export_mutation()
returns trigger language plpgsql as $$
begin
 raise exception 'installation projection export audit is append-only';
end $$;

create trigger installation_projection_export_immutable
before update or delete on ouf_installation.installation_projection_export_event
for each row execute function ouf_installation.reject_projection_export_mutation();
