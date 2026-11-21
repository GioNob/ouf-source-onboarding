package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.time.Instant;
import java.util.*;

/** Cumulative producer observation. UPDATE and RESOLVED can omit the original evidence. */
public record SchemaObservation(String incidentKey, String state, String expectedSchemaRef,
    Map<String,Object> observedSchema, List<Difference> differences, String frozenCheckpointRef,
    Long observationPolls, Instant observedAt, String reason) {
  public record Difference(String field, String change) {}
  private static final Set<String> CHANGES = Set.of("ADDED","REMOVED","TYPE_CHANGED","NULLABILITY_CHANGED",
      "KEY_CHANGED","GEOMETRY_CHANGED");

  void validate(CanonicalHash hashes) {
    if (!text(incidentKey) || incidentKey.length()>512 || state==null || !Set.of("OPEN","UPDATE","RESOLVED").contains(state)
        || observationPolls==null || observationPolls<0 || observedAt==null)
      throw SchemaSurveillanceService.invalid("Invalid incident identity, state or observation");
    if ("RESOLVED".equals(state)) {
      if (reason!=null && !Set.of("SCHEMA_RETURNED","REVISION_ACTIVATED").contains(reason))
        throw SchemaSurveillanceService.invalid("Invalid resolution reason");
      return;
    }
    if (reason!=null) throw SchemaSurveillanceService.invalid("Resolution reason requires RESOLVED");
    if ("UPDATE".equals(state)) return;
    if (!text(expectedSchemaRef) || !text(frozenCheckpointRef) || observedSchema==null
        || !text(observedSchema.get("schemaVersion")) || differences==null || differences.isEmpty()
        || differences.size()>4096 || differences.stream().anyMatch(d->d==null || !text(d.field())
            || d.change()==null || !CHANGES.contains(d.change())))
      throw SchemaSurveillanceService.invalid("Incomplete schema evidence");
    if (!(observedSchema.get("fields") instanceof List<?> fields) || fields.isEmpty() || fields.size()>1024)
      throw SchemaSurveillanceService.invalid("Invalid observed fields");
    if (differences.stream().anyMatch(d->"KEY_CHANGED".equals(d.change())) && !(observedSchema.get("key") instanceof List<?>)
        || differences.stream().anyMatch(d->"GEOMETRY_CHANGED".equals(d.change())) && !observedSchema.containsKey("geometry"))
      throw SchemaSurveillanceService.invalid("Missing changed key or geometry declaration");
    var sorted = new TreeMap<String,Object>();
    for (var item : fields) {
      if (!(item instanceof Map<?,?> field) || !text(field.get("name")) || !text(field.get("type"))
          || !(field.get("nullable") instanceof Boolean) || sorted.put((String)field.get("name"), field)!=null)
        throw SchemaSurveillanceService.invalid("Invalid or duplicate field descriptor");
    }
    String digest = hashes.of(sorted.values());
    if (!digest.equals(observedSchema.get("fieldsSha256")) && !digest.substring(7).equals(observedSchema.get("fieldsSha256")))
      throw SchemaSurveillanceService.invalid("Observed fields do not match fieldsSha256");
  }

  private static boolean text(Object value) { return value instanceof String s && !s.isBlank(); }
}
