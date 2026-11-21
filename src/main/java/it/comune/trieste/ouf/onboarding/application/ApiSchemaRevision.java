package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Applies only owner-stored evidence to the clone of the fenced ACTIVE parent. */
@Component
public class ApiSchemaRevision {
  private final JdbcClient db;
  private final ObjectMapper json;
  public ApiSchemaRevision(JdbcClient db,ObjectMapper json) { this.db=db; this.json=json; }

  UUID apply(String tenant,String source,String ref,Map<String,Object> config) {
    var rows=db.sql("""
        select i.issue_id,i.expected_schema_ref,i.observed_schema::text schema
        from ouf_onboarding.schema_surveillance_issue i join ouf_onboarding.source s using(source_id)
        where i.tenant_id=:t and i.source_id=:s and i.observed_schema_ref=:ref and i.state='OPEN'
          and i.observed_schema is not null and s.source_kind='EXTERNAL_API' and s.acquisition_mode='PULL'
        """).param("t",tenant).param("s",source).param("ref",ref).query().listOfRows();
    if(rows.size()!=1) throw SchemaSurveillanceService.conflict("No matching open API schema notice");
    var row=rows.getFirst();
    Map<String,Object> schema;
    try { schema=json.readValue((String)row.get("schema"),new TypeReference<>(){}); }
    catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Invalid stored schema",e); }
    var bundle=object(config,"bundle");
    var extraction=object(config,"extractionProfile");
    var execution=object(object(extraction,"runtime"),"execution");
    if(!Objects.equals(execution.get("sourceSchemaRef"),row.get("expected_schema_ref")))
      throw SchemaSurveillanceService.conflict("The reviewed parent no longer uses the expected schema");
    Set<String> names=new HashSet<>();
    for(var field:(List<?>)schema.get("fields")) names.add((String)((Map<?,?>)field).get("name"));
    var identity=object(config,"sourceObjectIdentityPolicy").get("sourceFields");
    if(!(identity instanceof List<?> keys) || keys.isEmpty() || !names.containsAll(keys)
        || schema.containsKey("key") && !keys.equals(schema.get("key")))
      throw SchemaSurveillanceService.conflict("Observed schema requires a new identity decision");
    var approved=new LinkedHashMap<>(schema);
    approved.putIfAbsent("key",identity);
    // A v1 observation may omit geometry when only the fields changed.
    if(!approved.containsKey("geometry") && execution.get("sourceSchema") instanceof Map<?,?> old)
      approved.put("geometry",old.get("geometry"));
    var unit=extraction.containsKey("sourceUnitProfile")?object(extraction,"sourceUnitProfile"):extraction;
    if(unit.containsKey("readParameters")) {
      if(unit.containsKey("schemaHash") || unit.containsKey("profileVersion"))
        throw SchemaSurveillanceService.conflict("A pinned unit profile requires new owner-verified profile evidence");
      // Some PULL bundles carry inline API parameters ahead of runtime.execution in the consumer.
      var api=object(object(unit,"readParameters"),"api");
      api.put("schemaVersion",approved.get("schemaVersion"));
      api.put("fieldsSha256",approved.get("fieldsSha256"));
      if(unit.get("origin") instanceof Map<?,?> origin && schema.containsKey("collection")
          && !Objects.equals(origin.get("collection"),schema.get("collection")))
        throw SchemaSurveillanceService.conflict("Observed collection differs from the reviewed source");
      if(unit.containsKey("geometry") && !schema.containsKey("geometry")) approved.put("geometry",unit.get("geometry"));
      if(schema.containsKey("geometry")) unit.put("geometry",schema.get("geometry"));
    }

    var semantic=object(config,"semanticMapping");
    String type=(String)object(semantic,"sourceType").get("typeCode");
    var projection=object(extraction,"projection");
    if(!(projection.get(type) instanceof List<?> fields))
      throw SchemaSurveillanceService.conflict("Missing approved field projection");
    projection.put(type,fields.stream().filter(names::contains).toList());
    // New fields remain outside the approved semantic projection. Remove only obsolete bindings.
    if(semantic.get("propertyMappings") instanceof List<?> mappings) {
      var removedProperties=new HashSet<Object>();
      var retainedProperties=new HashSet<Object>();
      var retained=new ArrayList<Object>();
      for(var item:mappings) {
        var mapping=(Map<?,?>)item;
        if(names.contains(mapping.get("sourceField"))) {
          retained.add(mapping);
          retainedProperties.add(mapping.get("targetPropertyIri"));
        } else removedProperties.add(mapping.get("targetPropertyIri"));
      }
      if(retained.isEmpty()) throw SchemaSurveillanceService.conflict("Observed schema requires new property mappings");
      semantic.put("propertyMappings",retained);
      removedProperties.removeAll(retainedProperties);
      if(!removedProperties.isEmpty()) {
        var material=object(object(object(extraction,"runtime"),"udp"),"materialization");
        if(material.get("properties") instanceof List<?> properties)
          material.put("properties",properties.stream().filter(p->!removedProperties.contains(((Map<?,?>)p).get("propertyIri"))).toList());
      }
    }
    execution.put("sourceSchema",approved);
    execution.put("sourceSchemaVersion",schema.get("schemaVersion"));
    execution.put("sourceSchemaRef",ref);
    bundle.put("schemaRef",ref);
    return (UUID)row.get("issue_id");
  }

  @SuppressWarnings("unchecked")
  private static Map<String,Object> object(Map<String,Object> parent,String key) {
    if(!(parent.get(key) instanceof Map<?,?> value)) throw SchemaSurveillanceService.conflict("Missing reviewed "+key);
    return (Map<String,Object>)value;
  }
}
