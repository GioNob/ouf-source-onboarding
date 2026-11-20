# Configuration register — Source Onboarding

## Source mapping and publication (D-065)

| Entry | Owner and contents | Credentials | Capture and restore | Restore check |
|---|---|---|---|---|
| `onboarding.semantic-mapping.v1` | Source Onboarding owns the reviewed mapping in `ouf_onboarding.onboarding_version.configuration`. The version pins source type, target classes, optional controlled category and semantic references. | The category contains two public IRIs. Treat the enclosing configuration snapshot as private. Credential references must keep their original bindings. | Capture the configuration JSON in a consistent database snapshot with source/version IDs, parentage, hashes, approval records and referenced semantic revision/publication IDs. Restore the referenced revisions and the version graph before dependent publications. | Recompute the version hash where present. Resolve the pinned semantic references and compare every target class and its optional category against the captured mapping. |
| `onboarding.published-mapping.v1` | Source Onboarding owns the mapping embedded in `ouf_onboarding.published_configuration.bundle` for UDP consumption. Depends on `onboarding.semantic-mapping.v1`. | Same handling as the reviewed configuration. | Capture the original bundle with its publication row, version link, checksum and lifecycle state in the same snapshot. Restore the captured rows and links. | Verify checksum and version link, then compare the published mapping with the approved version, including category absence. |

## Controlled category contract (D-059)

The local [semantic mapping profile](../contracts/onboarding/semantic-mapping-category-v1.json) extends the frozen RC3 schema. A human selects the canonical class and may declare its category on the target class. For discovery, the optional JSON body of `POST …/mapping/build-draft` accepts `category` for the configured target class. For managed files, pass the same optional field beside `targetClassIri` in the create-onboarding or MCP draft request.

```json
{
  "ontologyId": "example",
  "ontologyVersion": "1",
  "classIri": "urn:example:PointOfInterest",
  "category": {
    "schemeIri": "urn:example:place-types",
    "conceptIri": "urn:example:kiosk"
  }
}
```

Both IRIs are required and absolute. Extra keys, explicit nulls and malformed values produce `ONB_MAPPING_CATEGORY_INVALID`. Repeated target entries for one class must agree, including absence. If `extractionProfile.runtime.udp.resolution.governedIdentity.category` is present, both IRIs must match the target for its canonical class. Conflicts produce `ONB_MAPPING_CATEGORY_CONFLICT`.

Onboarding pins semantic references and, for managed execution, their published revisions and set. These bindings carry no concept-to-scheme membership assertion. The human verifies membership against the published vocabulary when reviewing the class, scheme and concept in THS.

Publication carries the approved pair at `semanticMapping.targetClasses[].category`. UDP scopes identity comparison by tenant, exact canonical class and concept IRI. Omitting the field limits comparison to uncategorized objects of the same class and tenant. Historical mappings need no backfill.
