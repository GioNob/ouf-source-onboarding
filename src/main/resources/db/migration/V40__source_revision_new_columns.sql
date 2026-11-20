ALTER TABLE ouf_onboarding.source_property_revision
  ADD COLUMN new_columns jsonb CHECK (
    new_columns IS NULL OR (replacement_profile_id IS NOT NULL AND jsonb_typeof(new_columns) = 'array')
  );
