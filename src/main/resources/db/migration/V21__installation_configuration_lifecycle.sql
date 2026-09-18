create schema if not exists ouf_installation;

create table ouf_installation.installation_configuration_revision (
 installation_id text not null,
 revision bigint not null check(revision>=1),
 payload jsonb not null,
 checksum char(64) not null check(checksum~'^[0-9a-f]{64}$'),
 validation_state text not null check(validation_state in ('VALIDATED','REJECTED')),
 validation_findings jsonb not null default '[]'::jsonb,
 created_by text not null,
 created_at timestamptz not null default transaction_timestamp(),
 primary key(installation_id,revision)
);

create table ouf_installation.installation_configuration_lifecycle_event (
 event_id uuid primary key,
 installation_id text not null,
 revision bigint not null,
 action text not null check(action in ('VALIDATED','ACTIVATED','SUPERSEDED','REVOKED','ROLLBACK_ACTIVATED')),
 actor_subject text not null,
 correlation_id text not null,
 occurred_at timestamptz not null default transaction_timestamp(),
 foreign key(installation_id,revision)
   references ouf_installation.installation_configuration_revision(installation_id,revision)
);

create table ouf_installation.installation_configuration_active (
 installation_id text primary key,
 revision bigint not null,
 activated_at timestamptz not null default transaction_timestamp(),
 activated_by text not null,
 foreign key(installation_id,revision)
   references ouf_installation.installation_configuration_revision(installation_id,revision)
);

create or replace function ouf_installation.reject_revision_mutation()
returns trigger language plpgsql as $$
begin
 raise exception 'installation configuration revisions are immutable';
end $$;

create trigger installation_configuration_revision_immutable
before update or delete on ouf_installation.installation_configuration_revision
for each row execute function ouf_installation.reject_revision_mutation();

create or replace function ouf_installation.reject_lifecycle_mutation()
returns trigger language plpgsql as $$
begin
 raise exception 'installation configuration lifecycle is append-only';
end $$;

create trigger installation_configuration_lifecycle_immutable
before update or delete on ouf_installation.installation_configuration_lifecycle_event
for each row execute function ouf_installation.reject_lifecycle_mutation();

create index installation_configuration_lifecycle_lookup_idx
on ouf_installation.installation_configuration_lifecycle_event(installation_id,revision,occurred_at);
