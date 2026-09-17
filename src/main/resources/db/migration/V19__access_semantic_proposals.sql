alter table ouf_onboarding.semantic_gap add column source_evidence jsonb not null default '{}';
alter table ouf_onboarding.semantic_gap add column source_evidence_hash text;
create unique index semantic_gap_source_evidence_idx on ouf_onboarding.semantic_gap(onboarding_version_id,source_evidence_hash) where source_evidence_hash is not null;
