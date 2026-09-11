ALTER TABLE ouf_onboarding.file_profile
  ADD COLUMN redacted_sample jsonb NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN ouf_onboarding.file_profile.redacted_sample IS
  'Bounded profiler output redacted before persistence and safe for governed preview APIs.';
