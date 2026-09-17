package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.TrustedPrincipal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class IamJwtValidationTest {
  private static Jwt jwt(Map<String, Object> overrides) {
    var claims = new HashMap<String, Object>();
    claims.put("sub", "workload:ouf-mcp-server");
    claims.put("iss", "https://auth.ouf-lab.it/realms/ouf");
    claims.put("aud", List.of("ouf-api-gateway"));
    claims.put("tenant_id", "ouf-lab");
    claims.put("ouf_actor_type", "SERVICE");
    claims.put("acr", "client-credentials");
    claims.put("client_id", "ouf-mcp-server");
    claims.put("scope", "authorization.bundle.read authorization.bundle.read");
    claims.putAll(overrides);
    return new Jwt(
        "token",
        Instant.now().minusSeconds(10),
        Instant.now().plusSeconds(300),
        Map.of("alg", "RS256"),
        claims);
  }

  @Test
  void requiredAudienceValidatorFailsClosed() {
    var validator = IamSecurityConfiguration.requiredAudienceValidator("ouf-api-gateway");

    assertThat(validator.validate(jwt(Map.of())).hasErrors()).isFalse();
    assertThat(validator.validate(jwt(Map.of("aud", List.of("other-api")))).hasErrors()).isTrue();
  }

  @Test
  void converterProducesTrustedServicePrincipalAndDeduplicatesScopes() {
    var converter = new IamSecurityConfiguration()
        .trustedJwtAuthenticationConverter("ouf-api-gateway", "ouf_actor_type");

    var authentication = converter.convert(jwt(Map.of()));
    assertThat(authentication).isNotNull();
    assertThat(authentication.getPrincipal()).isInstanceOf(TrustedPrincipal.class);
    var principal = ((TrustedPrincipal) authentication.getPrincipal()).context();
    assertThat(principal.subjectId()).isEqualTo("workload:ouf-mcp-server");
    assertThat(principal.tenantId()).isEqualTo("ouf-lab");
    assertThat(principal.actorType()).isEqualTo(PrincipalContext.ActorType.SERVICE);
    assertThat(principal.servicePrincipalId()).isEqualTo("ouf-mcp-server");
    assertThat(principal.scopes()).containsExactly("authorization.bundle.read");
  }

  @Test
  void converterRejectsMissingMandatoryIdentityClaims() {
    var converter = new IamSecurityConfiguration()
        .trustedJwtAuthenticationConverter("ouf-api-gateway", "ouf_actor_type");

    for (String claim : List.of("tenant_id", "ouf_actor_type", "acr")) {
      var claims = new HashMap<String, Object>();
      claims.put(claim, "");
      assertThatThrownBy(() -> converter.convert(jwt(claims)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("required OUF identity claims are missing");
    }
  }

  @Test
  void converterRejectsInvalidActorAndServiceWithoutClientIdentity() {
    var converter = new IamSecurityConfiguration()
        .trustedJwtAuthenticationConverter("ouf-api-gateway", "ouf_actor_type");

    assertThatThrownBy(() -> converter.convert(jwt(Map.of("ouf_actor_type", "ROBOT"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid OUF actor type");

    assertThatThrownBy(() -> converter.convert(jwt(Map.of("client_id", "", "azp", ""))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("service principal claim is required");
  }
}
