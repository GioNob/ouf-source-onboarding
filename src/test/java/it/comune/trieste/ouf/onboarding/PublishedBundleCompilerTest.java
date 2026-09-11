package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.application.PublishedBundleCompiler;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.util.*;
import org.junit.jupiter.api.Test;

class PublishedBundleCompilerTest {
  private final PublishedBundleCompiler compiler=new PublishedBundleCompiler(new CanonicalHash(new ObjectMapper()));

  @Test @SuppressWarnings("unchecked") void compilesTheFrozenRuntimeShapeInsteadOfNestingInternalConfiguration(){
    UUID version=UUID.randomUUID();Map<String,Object> bundle=compiler.compile("source-1",version,1,"sha256:"+"a".repeat(64),configuration());
    assertThat(bundle).containsKeys("bundleId","bundleVersion","environment","source","createdFromOnboardingVersion","effectiveFrom","checksum","status","objectTypes","extractionProfileRef","extractionProfile","semanticMapping").doesNotContainKey("configuration");
    assertThat(bundle.get("bundleVersion")).isEqualTo("1.0.0");assertThat(bundle.get("createdFromOnboardingVersion")).isEqualTo(version.toString());assertThat(bundle.get("status")).isEqualTo("ACTIVE");
    assertThat((Map<String,Object>)bundle.get("source")).containsEntry("sourceId","source-1").containsEntry("sourceKind","EXTERNAL_API").containsEntry("acquisitionMode","PULL");
  }

  @Test @SuppressWarnings("unchecked") void pullPublicationFailsClosedWithoutBindingReference(){Map<String,Object> config=new LinkedHashMap<>(configuration());Map<String,Object> template=new LinkedHashMap<>((Map<String,Object>)config.get("bundle"));template.remove("bindingRef");config.put("bundle",template);assertThatThrownBy(()->compiler.compile("source-1",UUID.randomUUID(),1,"sha256:"+"a".repeat(64),config)).hasMessageContaining("bindingRef");}

  private static Map<String,Object> configuration(){Map<String,Object> sync=Map.of("bootstrap","FULL_SNAPSHOT","incremental","NONE");return Map.of(
    "syncProfile",sync,
    "extractionProfile",Map.of("profileId","ep-1","version","1","sourceId","source-1","selection",Map.of(),"projection",Map.of("TYPE",List.of("id")),"sync",sync),
    "semanticMapping",Map.of("mappingId","sm-1","sourceType",Map.of("sourceId","source-1","typeCode","TYPE"),"targetClasses",List.of(Map.of("ontologyId","core","ontologyVersion","1","classIri","https://example.org/Type")),"semanticRefs",List.of("core@1")),
    "bundle",Map.of("bundleId","bundle-1","bundleVersion","1.0.0","environment","test","source",Map.of("sourceId","source-1","sourceKind","EXTERNAL_API","acquisitionMode","PULL"),"bindingRef","onboarding://bindings/1","createdFromOnboardingVersion","pending","effectiveFrom","2026-01-01T00:00:00Z","checksum","sha256:pending","status","APPROVED"));}
}
