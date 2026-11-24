package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.authorization.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.time.Duration;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.*;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** Server-side OIDC session. The manager persists renewed credentials in the login client service. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="ouf.authorization.ths.enabled",havingValue="true")
public class PermissionThsSecurity {
  static final String CLIENT = "ouf-ths";
  static final String LOGIN = "/oauth2/authorization/" + CLIENT;
  private static final String RETURN_PAGE = PermissionThsSecurity.class.getName() + ".returnPage";

  @Bean OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> thsRefreshClient() {
    var transport = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build());
    transport.setReadTimeout(Duration.ofSeconds(15));
    var rest = RestClient.builder().requestFactory(transport).messageConverters(converters -> {
      converters.clear();
      converters.add(new FormHttpMessageConverter());
      converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
    }).defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build();
    var refresh = new RestClientRefreshTokenTokenResponseClient();
    refresh.setRestClient(rest);
    return refresh;
  }

  @Bean OAuth2AuthorizedClientManager thsClientManager(ClientRegistrationRepository registrations,
      OAuth2AuthorizedClientService clients, OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refresh) {
    var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
    manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
        .refreshToken(options -> options.accessTokenResponseClient(refresh)).build());
    return manager;
  }

  @Bean @Order(1) SecurityFilterChain permissionThsChain(HttpSecurity http, OAuth2AuthorizedClientManager manager,
      JwtDecoder decoder, Converter<Jwt,? extends AbstractAuthenticationToken> converter,
      ClientRegistrationRepository registrations, AuthenticationSuccessHandler thsLoginSuccess) throws Exception {
    http.securityMatcher("/trusted-human/authorization/**","/trusted-human/managed-files/**","/trusted-human/resolution/**",
        "/trusted-human/onboarding/**","/trusted-human/semantic/**","/trusted-human/ingestion-run-recovery/**",
        "/oauth2/**","/login/oauth2/**");
    http.authorizeHttpRequests(a -> a.requestMatchers("/oauth2/**","/login/oauth2/**").permitAll().anyRequest().authenticated());
    var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations,"/oauth2/authorization");
    resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
    http.requestCache(cache -> cache.disable());
    http.oauth2Login(o -> o.loginPage(LOGIN).authorizationEndpoint(e -> e.authorizationRequestResolver(resolver))
        .successHandler(thsLoginSuccess)
        .failureHandler((r, s, failure) -> ThsErrors.page(s, 403)));
    // Session loss takes precedence over a stale CSRF token. All authenticated writes still traverse CsrfFilter.
    http.addFilterBefore(new OncePerRequestFilter() {
      // The client service is keyed by registration and principal. Concurrent page/asset requests
      // share the in-flight exchange so they cannot consume the same rotating refresh token twice.
      private final ConcurrentHashMap<String, CompletableFuture<OAuth2AuthorizedClient>> authorizations = new ConcurrentHashMap<>();

      private OAuth2AuthorizedClient authorize(OAuth2AuthenticationToken login) {
        var flight = new CompletableFuture<OAuth2AuthorizedClient>();
        var active = authorizations.putIfAbsent(login.getName(), flight);
        if (active != null) {
          try { return active.join(); }
          catch (CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException cause) throw cause;
            throw ex;
          }
        }
        try {
          var client = manager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(CLIENT).principal(login).build());
          flight.complete(client);
          return client;
        } catch (RuntimeException | Error ex) {
          flight.completeExceptionally(ex);
          throw ex;
        } finally {
          authorizations.remove(login.getName(), flight);
        }
      }

      @Override protected boolean shouldNotFilter(HttpServletRequest r) {
        return !r.getRequestURI().substring(r.getContextPath().length()).startsWith("/trusted-human/");
      }
      @Override protected void doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain chain)
          throws ServletException, IOException {
        String query = r.getQueryString();
        if (ThsErrors.isPage(r, r.getRequestURI()) && query != null &&
            (query.equals("__ths_login") || query.endsWith("&__ths_login"))) {
          int separator = query.lastIndexOf('&');
          beginLogin(r, s, separator < 0 ? null : query.substring(0, separator));
          return;
        }
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof OAuth2AuthenticationToken login) || !login.isAuthenticated()) {
          expired(r, s);
          return;
        }
        if (!CLIENT.equals(login.getAuthorizedClientRegistrationId())) {
          ThsErrors.respond(r, s, 403);
          return;
        }
        OAuth2AuthorizedClient client;
        try {
          client = authorize(login);
        } catch (OAuth2AuthorizationException ex) {
          if (OAuth2ErrorCodes.INVALID_GRANT.equals(ex.getError().getErrorCode())) expired(r, s);
          else ThsErrors.respond(r, s, 503);
          return;
        }
        if (client == null || client.getAccessToken().getExpiresAt() == null ||
            !client.getAccessToken().getExpiresAt().isAfter(Instant.now())) {
          expired(r, s);
          return;
        }
        if (!CLIENT.equals(client.getClientRegistration().getRegistrationId())) {
          ThsErrors.respond(r, s, 403);
          return;
        }
        try {
          var token = converter.convert(decoder.decode(client.getAccessToken().getTokenValue()));
          if (token == null || !(token.getPrincipal() instanceof TrustedPrincipal principal) ||
              principal.context().actorType() != PrincipalContext.ActorType.HUMAN) {
            ThsErrors.respond(r, s, 403);
            return;
          }
          r.setAttribute(ServletAuthorization.TRUSTED_PRINCIPAL, principal);
          r.setAttribute("ouf.permissionThsSession", Boolean.TRUE);
          if ("GET".equals(r.getMethod()) && r.getRequestURI().contains("/api/"))
            s.setHeader("X-OUF-Review-Actor", draftBinding(principal.context()));
        } catch (BadJwtException | IllegalArgumentException | OAuth2AuthenticationException invalidIdentity) {
          ThsErrors.respond(r, s, 403);
          return;
        } catch (JwtException unavailable) {
          ThsErrors.respond(r, s, 503);
          return;
        }
        chain.doFilter(r, s);
      }
    }, CsrfFilter.class);
    return http.build();
  }

  @Bean AuthenticationSuccessHandler thsLoginSuccess() {
    return (r, s, auth) -> {
      var session = r.getSession();
      var page = (String) session.getAttribute(RETURN_PAGE);
      session.removeAttribute(RETURN_PAGE);
      s.sendRedirect(page != null ? page : r.getContextPath() + "/trusted-human/onboarding/");
    };
  }

  private static String draftBinding(PrincipalContext principal) {
    // An opaque comparison value for restoring browser choices after login. Carries no authority.
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (String part : new String[]{principal.issuer(), principal.tenantId(), principal.subjectId()}) {
        digest.update((part.length() + ":" + part).getBytes(StandardCharsets.UTF_8));
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static void expired(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (!ThsErrors.isPage(request, request.getRequestURI())) {
      ThsErrors.respond(request, response, 401);
      return;
    }
    beginLogin(request, response, request.getQueryString());
  }

  private static void beginLogin(HttpServletRequest request, HttpServletResponse response, String query) throws IOException {
    // Only a servlet-local GET path is saved. No Host, Referer, request body or caller-supplied redirect.
    request.getSession().setAttribute(RETURN_PAGE, request.getRequestURI() + (query == null ? "" : "?" + query));
    response.setHeader("Cache-Control", "no-store");
    response.sendRedirect(request.getContextPath() + LOGIN);
  }
}
