alter table ouf_installation.installation_environment_validation
  add column correlation_id text;

update ouf_installation.installation_environment_validation
set correlation_id = 'legacy:' || validation_id::text
where correlation_id is null;

alter table ouf_installation.installation_environment_validation
  alter column correlation_id set not null;
