alter table ouf_installation.installation_projection_export_event
  add column export_purpose text not null default 'ACTIVE';

alter table ouf_installation.installation_projection_export_event
  alter column export_purpose drop default;

alter table ouf_installation.installation_projection_export_event
  add constraint installation_projection_export_purpose_check
  check (export_purpose in ('ACTIVE','CANDIDATE'));
