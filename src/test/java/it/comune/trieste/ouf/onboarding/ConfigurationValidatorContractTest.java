package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.onboarding.application.ConfigurationValidator;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConfigurationValidatorContractTest {
  private final ConfigurationValidator validator=new ConfigurationValidator();
  @Test void deltaPatchFailsClosedWithoutDirectBindings(){Map<String,Object> config=base();config.put("changeRepresentationProfile",Map.of("mode","DELTA_PATCH"));config.put("deltaPatchContract",Map.of("mode","DELTA_PATCH","baseReference","source-version","operations",List.of("SET"),"propertyBindings",List.of()));assertThat(validator.validate("s",config).findings()).extracting(ConfigurationValidator.Finding::code).contains("ONB_DELTA_BINDINGS_REQUIRED");}
  @Test void propertyEventsRequirePinnedEventContract(){Map<String,Object> config=base();config.put("changeRepresentationProfile",Map.of("mode","PROPERTY_EVENTS"));assertThat(validator.validate("s",config).findings()).extracting(ConfigurationValidator.Finding::path).contains("/changeRepresentationProfile/eventContractRef");}
  private static Map<String,Object> base(){Map<String,Object> config=new LinkedHashMap<>();config.put("syncProfile",Map.of("bootstrap","FULL_SNAPSHOT","incremental","NONE"));config.put("extractionProfile",Map.of("profileId","ep","version","1","sourceId","s","selection",Map.of(),"projection",Map.of(),"sync",Map.of()));config.put("semanticMapping",Map.of("mappingId","sm","sourceType",Map.of("sourceId","s","typeCode","T"),"targetClasses",List.of(Map.of("ontologyId","core","ontologyVersion","1","classIri","urn:T")),"semanticRefs",List.of("core@1")));config.put("bundle",Map.of("bundleId","b","bundleVersion","1","environment","test","source",Map.of("sourceId","s","sourceKind","INTERNAL_MANAGED","acquisitionMode","MANAGED"),"createdFromOnboardingVersion","pending","effectiveFrom","2026-01-01T00:00:00Z","checksum","sha256:x","status","APPROVED"));return config;}
}
