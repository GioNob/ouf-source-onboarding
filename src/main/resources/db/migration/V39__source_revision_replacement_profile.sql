-- An immutable profile identifies one asset. NULL preserves the original property-only commands.
ALTER TABLE ouf_onboarding.source_property_revision
  ADD COLUMN replacement_profile_id uuid REFERENCES ouf_onboarding.file_profile(profile_id);
