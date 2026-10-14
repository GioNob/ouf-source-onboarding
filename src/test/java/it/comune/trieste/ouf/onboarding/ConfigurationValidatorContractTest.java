package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.onboarding.application.ConfigurationValidator;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConfigurationValidatorContractTest {
  private final ConfigurationValidator validator=new ConfigurationValidator();
  @Test void managedDraftCannotPassWithoutExecutableProfilesAndSemanticPublication(){
    var draft=base();var bundle=new LinkedHashMap<String,Object>((Map<String,Object>)draft.get("bundle"));
    bundle.put("source",Map.of("sourceId","s","sourceKind","INTERNAL_MANAGED","acquisitionMode","MANAGED"));draft.put("bundle",bundle);
    assertThat(validator.validate("s",draft).findings()).extracting(ConfigurationValidator.Finding::code)
        .contains("ONB_MANAGED_RUNTIME_REQUIRED");
    var extraction=new LinkedHashMap<String,Object>((Map<String,Object>)draft.get("extractionProfile"));
    extraction.put("runtime",Map.of());draft.put("extractionProfile",extraction);
    assertThat(validator.validate("s",draft).findings()).extracting(ConfigurationValidator.Finding::code)
        .contains("ONB_MANAGED_EXECUTION_REQUIRED","ONB_MANAGED_UDP_PROFILES_REQUIRED","ONB_SEMANTIC_PUBLICATION_BINDING_REQUIRED");
  }
  @Test @SuppressWarnings("unchecked") void managedAndGovernedIdentityGatesCoexist() {
    var config=base();
    var bundle=new LinkedHashMap<>((Map<String,Object>)config.get("bundle"));
    bundle.put("source",Map.of("sourceId","s","sourceKind","INTERNAL_MANAGED","acquisitionMode","MANAGED"));
    config.put("bundle",bundle);
    var extraction=new LinkedHashMap<>((Map<String,Object>)config.get("extractionProfile"));
    extraction.put("runtime",Map.of("udp",Map.of("resolution",Map.of("governedIdentity",Map.of()))));
    config.put("extractionProfile",extraction);
    assertThat(validator.validate("s",config).findings()).extracting(ConfigurationValidator.Finding::code)
        .contains("ONB_MANAGED_EXECUTION_REQUIRED","ONB_MANAGED_UDP_PROFILES_REQUIRED","ONB_GOVERNED_IDENTITY_INVALID");
  }
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
  @Test @SuppressWarnings("unchecked") void governedIdentityProposalRequiresMappedScopedAssertedEvidence() throws Exception {
    var c=base();var semantic=new LinkedHashMap<>((Map<String,Object>)c.get("semanticMapping"));
    semantic.put("propertyMappings",List.of(Map.of("sourceField","code","targetPropertyIri","urn:key","transform","identity")));
    c.put("semanticMapping",semantic);
    Map<String,Object> resolution;
    try(var input=getClass().getResourceAsStream("/identity-governed-proposal-v1.json")) {
      resolution=new com.fasterxml.jackson.databind.ObjectMapper().readValue(input,Map.class);
    }
    var policy=(Map<String,Object>)resolution.get("governedIdentity");
    var signal=(Map<String,Object>)((List<?>)policy.get("signals")).getFirst();
    var extraction=new LinkedHashMap<>((Map<String,Object>)c.get("extractionProfile"));
    extraction.put("runtime",Map.of("udp",Map.of("resolution",resolution,"materialization",
        Map.of("properties",List.of(Map.of("propertyIri","urn:key"))))));c.put("extractionProfile",extraction);
    assertThat(validator.validate("s",c).valid()).isTrue();
    semantic.put("propertyMappings",List.of(Map.of("sourceField","code","targetPropertyIri","urn:key","transform","identity"),
        Map.of("sourceField","address","targetPropertyIri","urn:address","transform","identity")));
    assertThat(validator.validate("s",c).findings()).extracting(ConfigurationValidator.Finding::code)
        .contains("ONB_GOVERNED_IDENTITY_INVALID");
    semantic.put("propertyMappings",List.of(Map.of("sourceField","code","targetPropertyIri","urn:key","transform","identity")));
    policy.put("sourceId","other");assertThat(validator.validate("s",c).findings())
        .extracting(ConfigurationValidator.Finding::code).contains("ONB_GOVERNED_IDENTITY_INVALID");
    policy.put("sourceId","s");signal.remove("assertionRef");
    assertThat(validator.validate("s",c).findings()).extracting(ConfigurationValidator.Finding::code)
        .contains("ONB_GOVERNED_IDENTITY_INVALID");
    signal.put("assertionRef","assertion://key/1");signal.put("uniqueWithinScope",true);
    assertThat(validator.validate("s",c).findings()).extracting(ConfigurationValidator.Finding::code)
        .contains("ONB_GOVERNED_IDENTITY_INVALID");
  }
  @SuppressWarnings("unchecked") private static Map<String,Object> withSpatial(Map<String,Object> geometry){var c=base();var extraction=new LinkedHashMap<>((Map<String,Object>)c.get("extractionProfile"));extraction.put("runtime",Map.of("udp",Map.of("spatial",Map.of("policyRef","crs://v1","geometry",geometry,"relationships",List.of()))));c.put("extractionProfile",extraction);return c;}
  private static Map<String,Object> base(){Map<String,Object> config=new LinkedHashMap<>();config.put("syncProfile",Map.of("bootstrap","FULL_SNAPSHOT","incremental","NONE"));config.put("extractionProfile",Map.of("profileId","ep","version","1","sourceId","s","selection",Map.of(),"projection",Map.of(),"sync",Map.of()));config.put("semanticMapping",Map.of("mappingId","sm","sourceType",Map.of("sourceId","s","typeCode","T"),"targetClasses",List.of(Map.of("ontologyId","core","ontologyVersion","1","classIri","urn:T")),"semanticRefs",List.of("core@1")));config.put("bundle",Map.of("bundleId","b","bundleVersion","1","environment","test","source",Map.of("sourceId","s","sourceKind","EXTERNAL_API","acquisitionMode","PULL"),"bindingRef","gateway://test","createdFromOnboardingVersion","pending","effectiveFrom","2026-01-01T00:00:00Z","checksum","sha256:x","status","APPROVED"));return config;}
}

