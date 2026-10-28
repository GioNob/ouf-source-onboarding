package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real pinned Gateway checkout, not an invented route fixture or target assertion. */
class ManagedProfileGatewayContractTest {
  @Test void existingHumanWildcardsCoverExactRecoveryPathsAndRetainOidcActorAndCapabilityGates()throws Exception{
    Path root=Path.of(Objects.requireNonNull(System.getenv("OUF_GATEWAY_PAIRWISE_ROOT"),"pinned Gateway checkout required"));var yaml=new ObjectMapper(new YAMLFactory());
    for(var entry:List.of(new String[]{"trusted-human-managed-file-preview-get","GET","ouf.managed-source.preview"},new String[]{"trusted-human-managed-file-actions-post","POST","ouf.managed-source.file.profile"})){
      var doc=yaml.readTree(root.resolve("ouf-config/routes/northbound/"+entry[0]+".yaml").toFile()).path("spec");
      assertThat(doc.path("match").path("method").asText()).isEqualTo(entry[1]);assertThat(doc.path("match").path("path").asText()).isEqualTo("/api/onboarding/v1/managed-files/*");
      assertThat(doc.path("backendBinding").path("service").asText()).isEqualTo("ouf-onboarding");assertThat(doc.path("backendBinding").path("path").asText()).isEqualTo("/api/onboarding/v1/managed-files/*");
      assertThat(doc.path("capabilityRef").asText()).isEqualTo(entry[2]+"@1.0.0");assertThat(doc.path("policy").path("identity").asText()).isEqualTo("OIDC");assertThat(doc.path("policy").path("allowedActorTypes").toString()).isEqualTo("[\"HUMAN\"]");
    }
    String ui=Files.readString(root.resolve("tools/materialize_managed_file_ths.py"));assertThat(ui).contains("URI = '/trusted-human/managed-files/*'","n=='authorization'","'retries': 0");
  }
}
