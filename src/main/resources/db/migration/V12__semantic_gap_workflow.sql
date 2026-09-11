CREATE TABLE ouf_onboarding.semantic_gap (
  gap_id uuid PRIMARY KEY,
  source_id varchar(160) NOT NULL,
  onboarding_version_id uuid NOT NULL,
  type_code varchar(200) NOT NULL,
  field_path varchar(300),
  description text NOT NULL CHECK (btrim(description) <> ''),
  state text NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN','SEARCH_REQUESTED','CANDIDATES_AVAILABLE','SELECTED','RESOLVED','CANCELLED')),
  discovery_request_ref text,
  selected_candidate_id uuid,
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  FOREIGN KEY(onboarding_version_id,source_id) REFERENCES ouf_onboarding.onboarding_version(onboarding_version_id,source_id) ON DELETE RESTRICT
);
CREATE INDEX semantic_gap_version_idx ON ouf_onboarding.semantic_gap(onboarding_version_id,state,created_at);

CREATE TABLE ouf_onboarding.semantic_candidate (
  candidate_id uuid PRIMARY KEY,
  gap_id uuid NOT NULL REFERENCES ouf_onboarding.semantic_gap(gap_id) ON DELETE RESTRICT,
  semantic_ref text NOT NULL CHECK (btrim(semantic_ref) <> ''),
  label text NOT NULL,
  status text NOT NULL CHECK (status IN ('CANDIDATE','ADOPTED','PUBLISHED','REJECTED')),
  evidence jsonb NOT NULL DEFAULT '{}',
  created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
  UNIQUE(gap_id,semantic_ref)
);
ALTER TABLE ouf_onboarding.semantic_gap ADD CONSTRAINT semantic_gap_selected_candidate_fk FOREIGN KEY(selected_candidate_id) REFERENCES ouf_onboarding.semantic_candidate(candidate_id) ON DELETE RESTRICT;

COMMENT ON TABLE ouf_onboarding.semantic_gap IS 'Onboarding-owned gap lifecycle; authority to adopt or publish semantic artifacts remains in Semantic Model / Registry.';
