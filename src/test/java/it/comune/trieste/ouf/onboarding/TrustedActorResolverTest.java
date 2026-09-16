package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.onboarding.api.TrustedActorResolver;
import it.comune.trieste.ouf.authorization.TestAuthorization;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class TrustedActorResolverTest {
  private final TrustedActorResolver resolver=new TrustedActorResolver();
  @Test void localBundleOverridesCoarseCapabilities(){var r=new MockHttpServletRequest();TestAuthorization.bind(r,"agent:mcp","AI_AGENT",Set.of("ouf.source-onboarding.submit"));r.setAttribute("ouf.authorizedCapabilities",Set.of("approve"));var actor=resolver.actor(r);assertThat(actor.type()).isEqualTo("AI_AGENT");assertThat(actor.capabilities()).containsExactly("ouf.source-onboarding.submit");}
  @Test void headersAndContainerRolesAloneCannotAuthorize(){var r=new MockHttpServletRequest();r.setUserPrincipal(()->"forged");r.addUserRole("OUF_HUMAN_USER");r.addHeader("X-OUF-Subject","forged");assertThatThrownBy(()->resolver.actor(r)).hasMessageContaining("TRUSTED_PRINCIPAL_REQUIRED");}
  @Test void humanMappingIsExplicitWithoutChangingHistoricalVocabulary(){var r=new MockHttpServletRequest();TestAuthorization.bind(r,"human","HUMAN",Set.of("approve"));assertThat(resolver.actor(r).type()).isEqualTo("HUMAN_USER");assertThat(resolver.authenticationContextRef(r)).isEqualTo("fixture-authentication");}
}
