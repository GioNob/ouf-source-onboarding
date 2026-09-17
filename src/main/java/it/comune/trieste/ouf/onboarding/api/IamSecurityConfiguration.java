package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.TrustedPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@ConditionalOnProperty(name = "ouf.iam.enabled", havingValue = "true")
public class IamSecurityConfiguration {
  @Bean
  JwtDecoder jwtDecoder(
      @Value("${ouf.iam.issuer}") String issuer,
      @Value("${ouf.iam.audience}") String audience) {
    if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
      throw new IllegalStateException("OUF IAM issuer and audience are required");
    }
    var decoder = (org.springframework.security.oauth2.jwt.NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer);
    OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
    OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(audience)
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "required OUF audience missing", null));
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator));
    return decoder;
  }

  @Bean
  Converter<Jwt, ? extends AbstractAuthenticationToken> trustedJwtAuthenticationConverter(
      @Value("${ouf.iam.audience}") String audience,
      @Value("${ouf.iam.actor-type-claim:ouf_actor_type}") String actorTypeClaim) {
    return jwt -> {
      String subject = text(jwt.getClaim("ouf_subject"));
      if (subject == null) subject = jwt.getSubject();
      String tenant = text(jwt.getClaim("tenant_id"));
      String actorText = text(jwt.getClaim(actorTypeClaim));
      String acr = text(jwt.getClaim("acr"));
      if (subject == null || tenant == null || actorText == null || acr == null) {
        throw new IllegalArgumentException("required OUF identity claims are missing");
      }
      PrincipalContext.ActorType actorType;
      try {
        actorType = PrincipalContext.ActorType.valueOf(actorText);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("invalid OUF actor type", e);
      }
      String servicePrincipal = null;
      if (actorType == PrincipalContext.ActorType.SERVICE || actorType == PrincipalContext.ActorType.AI_AGENT) {
        servicePrincipal = text(jwt.getClaim("client_id"));
        if (servicePrincipal == null) servicePrincipal = text(jwt.getClaim("azp"));
      }
      if (actorType == PrincipalContext.ActorType.SERVICE && servicePrincipal == null) {
        throw new IllegalArgumentException("service principal claim is required");
      }
      Set<String> scopes = stringSet(jwt.getClaim("scope"));
      Set<String> roles = stringSet(jwt.getClaim("external_role_refs"));
      Set<String> amr = stringSet(jwt.getClaim("amr"));
      Instant authenticatedAt = instant(jwt.getClaim("auth_time"));
      var claims = new PrincipalContext.IdentityClaims(roles, acr, amr, authenticatedAt);
      var context = new PrincipalContext(subject, tenant, actorType, servicePrincipal, acr,
          jwt.getIssuer().toString(), audience, scopes, claims);
      Set<GrantedAuthority> authorities = new LinkedHashSet<>();
      for (String scope : scopes) authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
      return new TrustedJwtAuthenticationToken(jwt, new TrustedPrincipal(context), authorities);
    };
  }

  @Bean
  SecurityFilterChain iamSecurityFilterChain(
      HttpSecurity http,
      Converter<Jwt, ? extends AbstractAuthenticationToken> trustedJwtAuthenticationConverter) throws Exception {
    http.csrf(csrf -> csrf.disable());
    http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/trusted-human/v1/authorization/**", "/api/internal/v1/authorization/policy-bundle/**").authenticated()
        .anyRequest().permitAll());
    http.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(trustedJwtAuthenticationConverter)));
    http.addFilterAfter(new TrustedBearerWriteMarkerFilter(), BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  private static String text(Object value) {
    if (value == null) return null;
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }

  private static Set<String> stringSet(Object value) {
    if (value == null) return Set.of();
    if (value instanceof String s) {
      if (s.isBlank()) return Set.of();
      return Set.of(s.trim().split("\\s+"));
    }
    if (value instanceof Collection<?> values) {
      var result = new LinkedHashSet<String>();
      for (Object item : values) {
        String text = text(item);
        if (text != null) result.add(text);
      }
      return Set.copyOf(result);
    }
    return Set.of();
  }

  private static Instant instant(Object value) {
    if (value instanceof Instant instant) return instant;
    if (value instanceof Number number) return Instant.ofEpochSecond(number.longValue());
    return null;
  }

  static final class TrustedJwtAuthenticationToken extends AbstractAuthenticationToken {
    private final Jwt jwt;
    private final TrustedPrincipal principal;

    TrustedJwtAuthenticationToken(Jwt jwt, TrustedPrincipal principal, Collection<? extends GrantedAuthority> authorities) {
      super(authorities);
      this.jwt = jwt;
      this.principal = principal;
      setAuthenticated(true);
    }

    Jwt jwt() { return jwt; }
    @Override public Object getCredentials() { return ""; }
    @Override public TrustedPrincipal getPrincipal() { return principal; }
    @Override public String getName() { return principal.getName(); }
  }

  static final class TrustedBearerWriteMarkerFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      String authorization = request.getHeader("Authorization");
      if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
          && request.getUserPrincipal() instanceof TrustedPrincipal principal
          && principal.context().actorType() == PrincipalContext.ActorType.HUMAN) {
        request.setAttribute("ouf.csrfValidated", Boolean.TRUE);
      }
      chain.doFilter(request, response);
    }
  }
}
