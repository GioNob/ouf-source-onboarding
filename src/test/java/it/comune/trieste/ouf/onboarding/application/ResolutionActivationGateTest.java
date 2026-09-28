package it.comune.trieste.ouf.onboarding.application;

import static org.assertj.core.api.Assertions.*;

import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResolutionActivationGateTest {
  @Test void aFutureGovernedPolicyCannotActivateThroughTheLegacyPath() {
    var config=Map.<String,Object>of("extractionProfile",Map.of("runtime",Map.of("udp",
        Map.of("resolution",Map.of("matchProperty","name","governedIdentity",Map.of("ref","identity://v2"))))));
    assertThatThrownBy(() -> ResolutionActivationGate.requireExecutable(config))
        .isInstanceOfSatisfying(DomainFailure.class,
            failure -> assertThat(failure.code()).isEqualTo("ONB_UDP_IDENTITY_RUNTIME_UNAVAILABLE"));
  }
}
