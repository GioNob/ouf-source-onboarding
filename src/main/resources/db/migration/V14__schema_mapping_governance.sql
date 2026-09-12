alter table ouf_onboarding.discovery_run
  add column discovery_mode text not null default 'SCHEMA_ONLY'
    check (discovery_mode in ('SCHEMA_ONLY','GOVERNED_PROFILE'));

alter table ouf_onboarding.field_mapping
  add column data_access_label text not null default 'OPEN'
    check (data_access_label in ('OPEN','ANONYMOUS','PERSONAL','SENSITIVE','RESTRICTED')),
  add column vocabulary_id text,
  add column vocabulary_version text,
  add column value_map_ref text,
  add constraint field_mapping_vocabulary_complete check
    (num_nonnulls(vocabulary_id,vocabulary_version,value_map_ref)=0 or
     (num_nonnulls(vocabulary_id,vocabulary_version,value_map_ref)=3 and btrim(vocabulary_id)<>'' and btrim(vocabulary_version)<>'' and btrim(value_map_ref)<>''));

create index relationship_mapping_workspace_idx
  on ouf_onboarding.relationship_mapping_draft(source_id,type_code,created_at);
