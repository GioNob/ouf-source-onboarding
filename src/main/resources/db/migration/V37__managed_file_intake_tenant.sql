-- Existing files retain NULL. No tenant or historical ownership is inferred.
ALTER TABLE ouf_onboarding.managed_file_asset ADD COLUMN tenant_id text
  CHECK (tenant_id IS NULL OR (tenant_id = btrim(tenant_id) AND length(tenant_id) BETWEEN 1 AND 256));

CREATE FUNCTION ouf_onboarding.reject_managed_file_owner_rebinding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.uploaded_by IS DISTINCT FROM OLD.uploaded_by THEN
    RAISE EXCEPTION USING ERRCODE='23001', MESSAGE='managed_file owner binding is immutable';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER managed_file_owner_immutable BEFORE UPDATE OF tenant_id,uploaded_by
  ON ouf_onboarding.managed_file_asset FOR EACH ROW
  EXECUTE FUNCTION ouf_onboarding.reject_managed_file_owner_rebinding();
