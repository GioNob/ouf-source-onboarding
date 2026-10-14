package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import it.comune.trieste.ouf.onboarding.application.ManagedFileProfiler;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ManagedFileProfilerBomTest {
  @Test void utf8BomIsRemovedBeforeHeadersAndQuotedCommasRemainValues() {
    byte[] csv=("\uFEFFcinema,indirizzo\r\n"
        +"Cinema Ambasciatori,\"Viale XX Settembre, 35, 34126 Trieste TS\"\r\n"
        +"Cinema Ariston,\"Viale Romolo Gessi, 14, 34123 Trieste TS\"\r\n")
        .getBytes(StandardCharsets.UTF_8);

    var profile=new ManagedFileProfiler().profile(csv,"text/csv");

    assertThat(profile.format()).isEqualTo("CSV");
    assertThat(profile.columns()).extracting(ManagedFileProfiler.ColumnProfile::name)
        .containsExactly("cinema","indirizzo");
    assertThat(profile.metadata()).containsEntry("rows",2).containsEntry("delimiter",",");
    assertThat(profile.candidateKeys()).containsExactly("cinema","indirizzo");
    assertThat(profile.sample().getFirst()).containsEntry("cinema","Cinema Ambasciatori")
        .containsEntry("indirizzo","[REDACTED]");
  }
}
