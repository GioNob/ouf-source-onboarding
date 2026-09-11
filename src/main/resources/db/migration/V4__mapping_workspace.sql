CREATE TABLE ouf_onboarding.mapping_workspace (
  source_id varchar(160) NOT NULL,
  type_code varchar(200) NOT NULL,
  state text NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT','COMPLETE')),
  target_class_iri text,
  semantic_refs jsonb NOT NULL DEFAULT '[]',
  eligible_states jsonb NOT NULL DEFAULT '[]',
  lock_version bigint NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  PRIMARY KEY(source_id,type_code),
  FOREIGN KEY(source_id,type_code) REFERENCES ouf_onboarding.discovered_type(source_id,type_code) ON DELETE RESTRICT,
  CHECK (state <> 'COMPLETE' OR (target_class_iri IS NOT NULL AND jsonb_array_length(semantic_refs) > 0))
);

CREATE TABLE ouf_onboarding.field_mapping (
  source_id varchar(160) NOT NULL,
  type_code varchar(200) NOT NULL,
  field_name varchar(300) NOT NULL,
  classification text NOT NULL DEFAULT 'UNCLASSIFIED' CHECK (classification IN ('UNCLASSIFIED','INCLUDE','EXCLUDE')),
  target_property_iri text,
  transform text,
  lock_version bigint NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
  updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  PRIMARY KEY(source_id,type_code,field_name),
  FOREIGN KEY(source_id,type_code) REFERENCES ouf_onboarding.mapping_workspace(source_id,type_code) ON DELETE CASCADE,
  CHECK ((classification='INCLUDE') = (target_property_iri IS NOT NULL)),
  CHECK (classification<>'EXCLUDE' OR (target_property_iri IS NULL AND transform IS NULL))
);

CREATE TABLE ouf_onboarding.relationship_mapping_draft (
  relationship_mapping_id uuid PRIMARY KEY,
  source_id varchar(160) NOT NULL,
  type_code varchar(200) NOT NULL,
  source_field varchar(300) NOT NULL,
  relation_iri text NOT NULL,
  target_class_iri text NOT NULL,
  resolution_strategy text NOT NULL CHECK (resolution_strategy IN ('CANONICAL_KEY','EXTERNAL_ID','SEMANTIC_MATCH','SPATIAL_MATCH')),
  on_no_match text NOT NULL CHECK (on_no_match IN ('IGNORE_RELATION','QUARANTINE_RELATION','REVIEW_REQUIRED')),
  on_multiple_matches text NOT NULL CHECK (on_multiple_matches IN ('QUARANTINE_RELATION','REVIEW_REQUIRED')),
  provenance_policy text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  FOREIGN KEY(source_id,type_code,source_field) REFERENCES ouf_onboarding.field_mapping(source_id,type_code,field_name) ON DELETE RESTRICT
);
