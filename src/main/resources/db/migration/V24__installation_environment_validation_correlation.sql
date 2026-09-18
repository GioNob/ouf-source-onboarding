alter table ouf_installation.installation_configuration_revision
  add column created_correlation_id text not null default 'legacy:pre-v24';

alter table ouf_installation.installation_configuration_revision
  alter column created_correlation_id drop default;

alter table ouf_installation.installation_environment_validation
  add column correlation_id text not null default 'legacy:pre-v24';

alter table ouf_installation.installation_environment_validation
  alter column correlation_id drop default;
