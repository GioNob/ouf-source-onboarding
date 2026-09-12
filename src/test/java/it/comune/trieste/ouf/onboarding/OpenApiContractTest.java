package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OpenApiContractTest {
  @Test void specificationsAreOpenApi31AndOperationIdsAreUnique() throws Exception {YAMLMapper yaml=new YAMLMapper();for(String file:List.of("openapi/onboarding-v1.yaml","openapi/ths-v1.yaml")){Map<String,Object> spec=yaml.readValue(Files.readString(Path.of(file)),new TypeReference<>(){});assertThat(spec.get("openapi")).isEqualTo("3.1.0");@SuppressWarnings("unchecked") Map<String,Map<String,Object>> paths=(Map<String,Map<String,Object>>)spec.get("paths");Set<String> ids=new HashSet<>();for(Map<String,Object> path:paths.values())for(Object operation:path.values())if(operation instanceof Map<?,?> map&&map.get("operationId")!=null)assertThat(ids.add(String.valueOf(map.get("operationId")))).as(file+" duplicate operationId").isTrue();}}
  @Test void trustedHumanContractCannotDeclareMcpCapability() throws Exception {String ths=Files.readString(Path.of("openapi/ths-v1.yaml"));assertThat(ths).contains("x-ouf-mcp-exposed: false").doesNotContain("x-ouf-mcp-capability","x-ouf-internal-capability");}
}
