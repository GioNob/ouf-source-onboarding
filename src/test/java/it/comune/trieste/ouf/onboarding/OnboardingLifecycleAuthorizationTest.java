package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.onboarding.api.TrustedActorResolver;
import it.comune.trieste.ouf.authorization.TestAuthorization;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class OnboardingLifecycleAuthorizationTest {
  private final TrustedActorResolver resolver=new TrustedActorResolver();

  @Test void humanWriteRequiresHumanCapability(){
    var request=new MockHttpServletRequest();
    TestAuthorization.bind(request,"human","HUMAN",Set.of("ouf.onboarding.configuration.write"));
    assertThat(resolver.requireHuman(request,"ouf.onboarding.configuration.write").type()).isEqualTo("HUMAN_USER");
  }

  @Test void ingestionAttestationRequiresServiceCapability(){
    var request=new MockHttpServletRequest();
    TestAuthorization.bind(request,"ingestion","SERVICE",Set.of("ouf.ingestion.configuration.attest"));
    assertThat(resolver.requireService(request,"ouf.ingestion.configuration.attest").type()).isEqualTo("SERVICE");
  }

  @Test void actorOrCapabilityMismatchIsDenied(){
    var human=new MockHttpServletRequest();
    TestAuthorization.bind(human,"human","HUMAN",Set.of("ouf.onboarding.configuration.write"));
    assertThatThrownBy(()->resolver.requireService(human,"ouf.ingestion.configuration.attest")).isInstanceOf(RuntimeException.class);
    var ingestion=new MockHttpServletRequest();
    TestAuthorization.bind(ingestion,"ingestion","SERVICE",Set.of("ouf.ingestion.configuration.attest"));
    assertThatThrownBy(()->resolver.requireService(ingestion,"ouf.onboarding.configuration.write")).isInstanceOf(RuntimeException.class);
  }
}
