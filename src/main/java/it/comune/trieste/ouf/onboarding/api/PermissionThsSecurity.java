package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.ServletAuthorization;
import it.comune.trieste.ouf.authorization.TrustedPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthenticatedPrincipalOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Server-side OIDC session: no bearer is exposed to JavaScript or the chatbot. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ouf.authorization.ths.enabled", havingValue = "true")
public class PermissionThsSecurity {
    private static final String REGISTRATION = "ouf-ths";
    private static final String LOGIN = "/oauth2/authorization/" + REGISTRATION;
    static final String REQUEST_ACCESS_TOKEN = PermissionThsSecurity.class.getName() + ".accessToken";
    private static final Set<String> PAGES = Set.of(
            "/trusted-human/authorization/", "/trusted-human/managed-files/",
            "/trusted-human/resolution/", "/trusted-human/onboarding/", "/trusted-human/semantic/");

    @Bean
    ThsAccessTokenRefresh thsAccessTokenRefresh() {
        return new ThsAccessTokenRefresh(Clock.systemUTC());
    }

    @Bean @Order(1)
    SecurityFilterChain permissionThsChain(HttpSecurity http, OAuth2AuthorizedClientService clients,
            JwtDecoder decoder, Converter<Jwt, ? extends AbstractAuthenticationToken> converter,
            ClientRegistrationRepository registrations, ThsAccessTokenRefresh timing) throws Exception {
        http.securityMatcher("/trusted-human/authorization/**", "/trusted-human/managed-files/**",
                "/trusted-human/resolution/**", "/trusted-human/onboarding/**",
                "/trusted-human/semantic/**", "/oauth2/**", "/login/oauth2/**");
        http.authorizeHttpRequests(a -> a.requestMatchers("/oauth2/**", "/login/oauth2/**")
                .permitAll().anyRequest().authenticated());

        var requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(PermissionThsSecurity::isPage);
        requestCache.setMatchingRequestParameterName(null);
        http.requestCache(c -> c.requestCache(requestCache));
        http.exceptionHandling(e -> e.authenticationEntryPoint((request, response, failure) ->
                signIn(request, response)));

        // Spring retains the PKCE verifier in the server session until the token exchange.
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        var repository = new ThsAuthorizedClientRepository(clients, timing);
        http.oauth2Login(o -> o.loginPage(LOGIN).authorizedClientRepository(repository)
                .authorizationEndpoint(e -> e.authorizationRequestResolver(resolver)));
        var manager = new DefaultOAuth2AuthorizedClientManager(registrations, repository);
        // Use the client's existing authorization. A page or API parameter cannot request scopes.
        manager.setContextAttributesMapper(request -> Map.of());
        // Retain the current access token after a failed refresh, including invalid_grant.
        manager.setAuthorizationFailureHandler((failure, principal, attributes) -> {});
        var tokenEndpoint = new RestClientRefreshTokenTokenResponseClient();
        manager.setAuthorizedClientProvider(context -> {
            var client = context.getAuthorizedClient();
            if (!isThsClient(client) || client.getRefreshToken() == null
                    || !timing.refreshDue(client.getAccessToken())) return null;
            var tokens = tokenEndpoint.getTokenResponse(new OAuth2RefreshTokenGrantRequest(
                    client.getClientRegistration(), client.getAccessToken(), client.getRefreshToken(), Set.of()));
            return new OAuth2AuthorizedClient(client.getClientRegistration(), context.getPrincipal().getName(),
                    tokens.getAccessToken(), tokens.getRefreshToken());
        });

        // Keep Spring CSRF enabled before the session filter and before controller dispatch.
        http.addFilterAfter(new ThsSessionFilter(clients, manager, decoder, converter, requestCache, timing),
                AuthorizationFilter.class);
        return http.build();
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private static boolean isPage(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) && PAGES.contains(path(request));
    }

    private static void signIn(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        if (isPage(request)) {
            response.sendRedirect(request.getContextPath() + LOGIN);
        } else {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"THS_SESSION_EXPIRED\"}");
        }
    }

    static String requestAccessToken(HttpServletRequest request) {
        if (!Boolean.TRUE.equals(request.getAttribute("ouf.permissionThsSession"))
                || !(request.getAttribute(REQUEST_ACCESS_TOKEN) instanceof OAuth2AccessToken accessToken)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "THS_SESSION_REQUIRED");
        }
        return accessToken.getTokenValue();
    }

    private static boolean isThsClient(OAuth2AuthorizedClient client) {
        return client != null && REGISTRATION.equals(client.getClientRegistration().getRegistrationId());
    }

    private static final class ThsAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {
        private final OAuth2AuthorizedClientRepository delegate;
        private final ThsAccessTokenRefresh timing;

        private ThsAuthorizedClientRepository(OAuth2AuthorizedClientService clients, ThsAccessTokenRefresh timing) {
            this.delegate = new AuthenticatedPrincipalOAuth2AuthorizedClientRepository(clients);
            this.timing = timing;
        }

        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String registration, Authentication principal,
                HttpServletRequest request) {
            return delegate.loadAuthorizedClient(registration, principal, request);
        }

        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
            delegate.saveAuthorizedClient(isThsClient(client) ? timing.observeIssuance(client) : client,
                    principal, request, response);
        }

        @Override
        public void removeAuthorizedClient(String registration, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
            delegate.removeAuthorizedClient(registration, principal, request, response);
        }
    }

    private static final class ThsSessionFilter extends OncePerRequestFilter {
        private final OAuth2AuthorizedClientService clients;
        private final OAuth2AuthorizedClientManager manager;
        private final JwtDecoder decoder;
        private final Converter<Jwt, ? extends AbstractAuthenticationToken> converter;
        private final HttpSessionRequestCache requestCache;
        private final ThsAccessTokenRefresh timing;
        private final ConcurrentHashMap<String, CompletableFuture<OAuth2AuthorizedClient>> refreshes =
                new ConcurrentHashMap<>();

        private ThsSessionFilter(OAuth2AuthorizedClientService clients, OAuth2AuthorizedClientManager manager,
                JwtDecoder decoder, Converter<Jwt, ? extends AbstractAuthenticationToken> converter,
                HttpSessionRequestCache requestCache, ThsAccessTokenRefresh timing) {
            this.clients = clients;
            this.manager = manager;
            this.decoder = decoder;
            this.converter = converter;
            this.requestCache = requestCache;
            this.timing = timing;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            var uri = path(request);
            return PAGES.stream().noneMatch(uri::startsWith);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            if (!(SecurityContextHolder.getContext().getAuthentication() instanceof OAuth2AuthenticationToken auth)
                    || !REGISTRATION.equals(auth.getAuthorizedClientRegistrationId())) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }

            var client = clients.loadAuthorizedClient(REGISTRATION, auth.getName());
            if (client != null && !isThsClient(client)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }

            TrustedPrincipal principal = null;
            if (client != null && !timing.expired(client.getAccessToken())) {
                principal = humanPrincipal(client);
                if (principal == null) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    return;
                }
            }
            if (client != null && timing.refreshDue(client.getAccessToken())) {
                var renewed = refresh(auth, request, response);
                // A live token and its validated principal stay bound to this request.
                if (timing.expired(client.getAccessToken())) {
                    client = renewed;
                    principal = null;
                }
            }
            if (client == null || timing.expired(client.getAccessToken())) {
                endSession(request, response);
                return;
            }
            if (principal == null) principal = humanPrincipal(client);
            if (principal == null) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            request.setAttribute(ServletAuthorization.TRUSTED_PRINCIPAL, principal);
            request.setAttribute("ouf.permissionThsSession", Boolean.TRUE);
            request.setAttribute(REQUEST_ACCESS_TOKEN, client.getAccessToken());
            chain.doFilter(request, response);
        }

        private TrustedPrincipal humanPrincipal(OAuth2AuthorizedClient client) {
            if (!isThsClient(client)) return null;
            try {
                var token = converter.convert(decoder.decode(client.getAccessToken().getTokenValue()));
                if (token != null && token.getPrincipal() instanceof TrustedPrincipal principal
                        && principal.context().actorType() == PrincipalContext.ActorType.HUMAN) {
                    return principal;
                }
            } catch (RuntimeException denied) {
                // The same decoder and converter govern renewed and original access tokens.
            }
            return null;
        }

        private void endSession(HttpServletRequest request, HttpServletResponse response) throws IOException {
            SecurityContextHolder.clearContext();
            var session = request.getSession(false);
            if (session != null) {
                try {
                    session.invalidate();
                } catch (IllegalStateException ignored) {
                    // A concurrent request already invalidated this session.
                }
            }
            if (isPage(request)) requestCache.saveRequest(request, response);
            signIn(request, response);
        }

        private OAuth2AuthorizedClient refresh(OAuth2AuthenticationToken auth, HttpServletRequest request,
                HttpServletResponse response) {
            // The service stores one client per principal, so concurrent requests share the refresh.
            var pending = new CompletableFuture<OAuth2AuthorizedClient>();
            var inFlight = refreshes.putIfAbsent(auth.getName(), pending);
            if (inFlight != null) return inFlight.join();

            try {
                var current = clients.loadAuthorizedClient(REGISTRATION, auth.getName());
                if (isThsClient(current) && current.getRefreshToken() != null
                        && timing.refreshDue(current.getAccessToken())) {
                    current = manager.authorize(OAuth2AuthorizeRequest.withAuthorizedClient(current)
                            .principal(auth).attributes(attributes -> {
                                attributes.put(HttpServletRequest.class.getName(), request);
                                attributes.put(HttpServletResponse.class.getName(), response);
                            }).build());
                }
                pending.complete(current);
                return current;
            } catch (RuntimeException refreshFailed) {
                return null;
            } finally {
                pending.complete(null);
                refreshes.remove(auth.getName(), pending);
            }
        }
    }
}
