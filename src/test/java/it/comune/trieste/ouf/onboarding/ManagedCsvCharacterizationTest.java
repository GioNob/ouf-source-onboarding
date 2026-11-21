package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.application.ManagedFileProfiler;
import it.comune.trieste.ouf.onboarding.application.ManagedFileProfiler.*;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class ManagedCsvCharacterizationTest {
  @Test void retainedCsvProfileAndColumnsViewFingerprint() {
    var bytes="\uFEFFid;email\r\n1;private@example.test\r\n2;\r\n".getBytes(StandardCharsets.UTF_8);
    var profile=new ManagedFileProfiler().profile(bytes,"text/csv");
    assertThat(profile).isEqualTo(new Profile("CSV",
        Map.of("encoding","UTF-8","delimiter",";","headerRow",1,"rows",2,"columns",2),
        List.of(new ColumnProfile("id","LONG",false,2),new ColumnProfile("email","STRING",true,1)),
        List.of("id"),List.of(Map.of("id","1","email","[REDACTED]"),Map.of("id","2","email","[REDACTED]"))));
    var json=new ObjectMapper();
    assertThat(new CanonicalHash(json).of(json.convertValue(profile.columns(),List.class)))
        .isEqualTo("sha256:da3d063f0d8675dce286960c91f2bddbab14f680be997337ea6c512fd4e17729");
  }
}
