alter table ouf_installation.installation_configuration_revision
  add column created_correlation_id text;

update ouf_installation.installation_configuration_revision
set created_correlation_id = 'legacy:' || installation_id || ':' || revision::text
where created_correlation_id is null;

alter table ouf_installation.installation_configuration_revision
  alter column created_correlation_id set not null;

alter table ouf_installation.installation_environment_validation
  add column correlation_id text;

update ouf_installation.installation_environment_validation
set correlation_id = 'legacy:' || validation_id::text
where correlation_id is null;

alter table ouf_installation.installation_environment_validation
  alter column correlation_id set not null;
