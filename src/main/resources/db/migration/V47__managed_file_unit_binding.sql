CREATE TABLE ouf_onboarding.managed_file_unit_binding (
    asset_id uuid NOT NULL REFERENCES ouf_onboarding.managed_file_asset(asset_id),
    unit_ref text NOT NULL CHECK (unit_ref ~ '^(table|sheet|layer):(0|[1-9][0-9]{0,3})$'),
    source_id text NOT NULL REFERENCES ouf_onboarding.source(source_id),
    PRIMARY KEY (asset_id, unit_ref),
    UNIQUE (asset_id, source_id)
);

-- Resolve predecessor writes at read time, including writes after a rollback.
-- Preserve unknown legacy custody (NULL unit_ref) until the selection can be proven.
CREATE VIEW ouf_onboarding.managed_file_effective_binding AS
SELECT asset_id, unit_ref, source_id FROM ouf_onboarding.managed_file_unit_binding
UNION ALL
SELECT a.asset_id,
       CASE a.media_type
           WHEN 'text/csv' THEN 'table:0'
           WHEN 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' THEN 'sheet:0'
           WHEN 'application/geopackage+sqlite3' THEN (
               SELECT CASE WHEN count(DISTINCT layer.ordinality) = 1 THEN 'layer:' || (min(layer.ordinality)-1) END
               FROM ouf_onboarding.onboarding_version v
               JOIN ouf_onboarding.file_profile p ON p.asset_id = a.asset_id
                 AND p.profile_id::text = v.configuration #>> '{extractionProfile,selection,fileProfileId}'
               CROSS JOIN LATERAL jsonb_array_elements(p.metadata->'layers') WITH ORDINALITY AS layer(item,ordinality)
               WHERE v.source_id = a.source_id
                 AND v.configuration #>> '{extractionProfile,selection,assetId}' = a.asset_id::text
                 AND layer.item->>'layer' = v.configuration #>> '{extractionProfile,runtime,layer}'
           )
       END, a.source_id
FROM ouf_onboarding.managed_file_asset a
WHERE a.source_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM ouf_onboarding.managed_file_unit_binding b
                  WHERE b.asset_id=a.asset_id AND b.source_id=a.source_id);
