-- Older consumers see a closed, rejected version and cannot activate it.
ALTER TABLE ouf_onboarding.approval_decision
  ADD COLUMN review_outcome text,
  ADD COLUMN review_feedback jsonb,
  ADD CONSTRAINT managed_review_feedback_valid CHECK (
    (review_outcome IS NULL AND review_feedback IS NULL) OR
    (review_outcome IS NOT NULL AND review_outcome = 'CHANGES_REQUESTED' AND decision = 'REJECT'
      AND review_feedback IS NOT NULL AND jsonb_typeof(review_feedback) = 'array'
      AND jsonb_array_length(review_feedback) BETWEEN 1 AND 128));
ALTER TABLE ouf_onboarding.managed_file_asset ADD COLUMN display_name varchar(255);

CREATE TABLE ouf_onboarding.managed_review_snapshot (
  onboarding_version_id uuid PRIMARY KEY REFERENCES ouf_onboarding.managed_onboarding_review(onboarding_version_id),
  snapshot jsonb NOT NULL
);
CREATE TRIGGER managed_review_snapshot_append_only BEFORE UPDATE OR DELETE ON ouf_onboarding.managed_review_snapshot
FOR EACH ROW EXECUTE FUNCTION ouf_onboarding.reject_audit_change();
