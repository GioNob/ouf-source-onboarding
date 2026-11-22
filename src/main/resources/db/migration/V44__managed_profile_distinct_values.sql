-- Keep the predecessor's inferred_schema and metadata shapes intact.
-- Text preserves JSON escapes (including U+0000, which jsonb cannot store).
ALTER TABLE ouf_onboarding.file_profile ADD COLUMN distinct_cell_values text;
