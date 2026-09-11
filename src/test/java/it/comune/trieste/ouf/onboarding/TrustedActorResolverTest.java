package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.onboarding.api.TrustedActorResolver;
import java.security.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class TrustedActorResolverTest {
  private final TrustedActorResolver resolver=new TrustedActorResolver();

  @Test void derivesAgentFromValidatedPrincipalAndContainerRole(){MockHttpServletRequest request=new MockHttpServletRequest();request.setUserPrincipal((Principal)()->"agent:mcp");request.addUserRole("OUF_AI_AGENT");var actor=resolver.actor(request);assertThat(actor.subject()).isEqualTo("agent:mcp");assertThat(actor.type()).isEqualTo("AI_AGENT");}
  @Test void ignoresSpoofableActorHeaders(){MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("X-OUF-Subject","attacker");request.addHeader("X-OUF-Actor-Type","HUMAN_USER");assertThatThrownBy(()->resolver.actor(request)).hasMessageContaining("validated request principal");}
  @Test void readsApprovalContextOnlyFromTrustedRequestAttribute(){MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("X-OUF-Authentication-Context-Ref","forged");assertThatThrownBy(()->resolver.authenticationContextRef(request)).hasMessageContaining("trusted authentication context");request.setAttribute("ouf.authenticationContextRef","acr:mfa");assertThat(resolver.authenticationContextRef(request)).isEqualTo("acr:mfa");}
}
