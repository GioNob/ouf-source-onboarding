-- Existing V40 decisions remain history, not implicitly attested current authority.
-- Generation zero is deliberately unusable by the new delivery service.
ALTER TABLE ouf_authorization.storage_recovery_decision
  ADD COLUMN authority_generation bigint NOT NULL DEFAULT 0 CHECK(authority_generation>=0);
