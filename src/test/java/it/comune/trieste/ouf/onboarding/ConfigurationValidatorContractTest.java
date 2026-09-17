package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.onboarding.application.ConfigurationValidator;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConfigurationValidatorContractTest {
  private final ConfigurationValidator validator=new ConfigurationValidator();
  @Test void deltaPatchFailsClosedWithoutDirectBindings(){Map<String,Object> config=base();config.put("changeRepresentationProfile",Map.of("mode","DELTA_PATCH"));config.put("deltaPatchContract",Map.of("mode","DELTA_PATCH","baseReference","source-version","operations",List.of("SET"),"propertyBindings",List.of()));assertThat(validator.validate("s",config).findings()).extracting(ConfigurationValidator.Finding::code).contains("ONB_DELTA_BINDINGS_REQUIRED");}
  @Test void propertyEventsRequirePinnedEventContract(){Map<String,Object> config=base();config.put("changeRepresentationProfile",Map.of("mode","PROPERTY_EVENTS"));assertThat(validator.validate("s",config).findings()).extracting(ConfigurationValidator.Finding::path).contains("/changeRepresentationProfile/eventContractRef");}
  @Test void spatialMismatchRequiresExplicitHumanDecisionAndKnownCrs(){
    var g=new LinkedHashMap<String,Object>(Map.of("sourceField","urn:geometry","expectedSourceCrs","EPSG:4326","canonicalSrid",6708,"normalizationVersion","1","accessLabel","RESTRICTED"));
    var c=withSpatial(g);assertThat(validator.validate("s",c).findings()).extracting(ConfigurationValidator.Finding::code).contains("ONB_CRS_DECISION_REQUIRED");
    g.put("crsPolicy",Map.of("sourceAxisOrder","XY","mismatchAction","REJECT"));assertThat(validator.validate("s",c).valid()).isTrue();
    g.put("crsPolicy",Map.of("sourceAxisOrder","XY","mismatchAction","CONVERT"));assertThat(validator.validate("s",c).findings()).extracting(ConfigurationValidator.Finding::code).contains("ONB_CRS_OPERATION_REQUIRED");
    g.put("expectedSourceCrs","UNKNOWN");assertThat(validator.validate("s",c).findings()).extracting(ConfigurationValidator.Finding::code).contains("ONB_CRS_UNKNOWN");
  }
  @Test void geometryApprovalSummaryPreservesTheWholeVersionedDecision(){
    var policy=Map.of("sourceAxisOrder","YX","mismatchAction","REJECT");
    var c=withSpatial(Map.of("sourceField","urn:geometry","expectedSourceCrs","EPSG:4326","canonicalSrid",6708,"normalizationVersion","1","accessLabel","RESTRICTED","crsPolicy",policy));
    assertThat(ConfigurationValidator.spatial(c).orElseThrow()).containsKey("geometry");
    assertThat(validator.validate("s",c).findings()).filteredOn(f->f.code().equals("ONB_CRS_HUMAN_DECISION")).singleElement().satisfies(f->assertThat(f.message()).contains("REJECT","6708","YX"));
  }
  @Test void weightedIdentityCannotApproveInvalidWeightsOrUnmappedEvidence(){
    var weights=new LinkedHashMap<String,Object>(Map.of("signals",List.of(Map.of("property","urn:name","comparator","TEXT","weight",1.0)),"blockingProperties",List.of("urn:name"),"maxCandidates",20,"highThreshold",0.9,"reviewThreshold",0.5,"minimumMargin",0.1,"allowSpatialIdentity",false));
    var policy=Map.of("strategyId","ATTRIBUTE_WEIGHTED","strategyVersion","1","policyRef","identity://v1","weighted",weights);
    var c=base();var extraction=new LinkedHashMap<String,Object>();extraction.putAll((Map<String,Object>)c.get("extractionProfile"));
    extraction.put("runtime",Map.of("udp",Map.of("resolution",policy,"materialization",Map.of("properties",List.of(Map.of("propertyIri","urn:name"))))));c.put("extractionProfile",extraction);
    assertThat(validator.validate("s",c).valid()).isTrue();
    weights.put("minimumMargin",0);assertThat(validator.validate("s",c).findings()).extracting(ConfigurationValidator.Finding::code).contains("ONB_WEIGHTED_IDENTITY_INVALID");
    weights.put("minimumMargin",0.1);weights.put("signals",List.of(Map.of("property","urn:unknown","comparator","TEXT","weight",1.0)));
    assertThat(validator.validate("s",c).findings()).extracting(ConfigurationValidator.Finding::code).contains("ONB_WEIGHTED_IDENTITY_INVALID");
  }
  @SuppressWarnings("unchecked") private static Map<String,Object> withSpatial(Map<String,Object> geometry){var c=base();var extraction=new LinkedHashMap<>((Map<String,Object>)c.get("extractionProfile"));extraction.put("runtime",Map.of("udp",Map.of("spatial",Map.of("policyRef","crs://v1","geometry",geometry,"relationships",List.of()))));c.put("extractionProfile",extraction);return c;}
  private static Map<String,Object> base(){Map<String,Object> config=new LinkedHashMap<>();config.put("syncProfile",Map.of("bootstrap","FULL_SNAPSHOT","incremental","NONE"));config.put("extractionProfile",Map.of("profileId","ep","version","1","sourceId","s","selection",Map.of(),"projection",Map.of(),"sync",Map.of()));config.put("semanticMapping",Map.of("mappingId","sm","sourceType",Map.of("sourceId","s","typeCode","T"),"targetClasses",List.of(Map.of("ontologyId","core","ontologyVersion","1","classIri","urn:T")),"semanticRefs",List.of("core@1")));config.put("bundle",Map.of("bundleId","b","bundleVersion","1","environment","test","source",Map.of("sourceId","s","sourceKind","INTERNAL_MANAGED","acquisitionMode","MANAGED"),"createdFromOnboardingVersion","pending","effectiveFrom","2026-01-01T00:00:00Z","checksum","sha256:x","status","APPROVED"));return config;}
}
