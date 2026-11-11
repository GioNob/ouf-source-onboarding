package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.PrincipalContext.ActorType;
import it.comune.trieste.ouf.authorization.TrustedPrincipal;
import it.comune.trieste.ouf.onboarding.authorization.PermissionProposalService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.UriComponentsBuilder;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringJUnitWebConfig(PermissionThsSessionTest.Config.class)
@TestPropertySource(properties = {"ouf.authorization.ths.enabled=true",
        "ouf.workflow.ths.gateway-base-url=http://127.0.0.1"})
class PermissionThsSessionTest {
    private static final String REGISTRATION = "ouf-ths";
    private static final String LOGIN = "/oauth2/authorization/" + REGISTRATION;
    private static final String PAGE = "/trusted-human/authorization/";
    private static final String PROPOSAL = "00000000-0000-0000-0000-000000000001";
    private static final String API = PAGE + "api/proposals/" + PROPOSAL;
    private static final String SUBJECT = "test-human";

    @Autowired WebApplicationContext context;
    @Autowired OAuth2AuthorizedClientService clients;
    @Autowired ClientRegistrationRepository registrations;
    @Autowired PermissionProposalService proposals;
    @Autowired Clock clock;
    @Autowired StubIssuer issuer;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(Instant.now());
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void refreshesAnExpiredClientAndServesThePageWithoutExposingTokens() throws Exception {
        var session = session(REGISTRATION, "expired-token", true, "accepted-refresh");
        mvc.perform(get(PAGE).param("scope", "not-a-client-scope").session(session)).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("renewed-access"))))
                .andExpect(content().string(not(containsString("renewed-refresh"))));
        var saved = clients.<OAuth2AuthorizedClient>loadAuthorizedClient(REGISTRATION, SUBJECT);
        assertThat(saved.getAccessToken().getTokenValue()).isEqualTo("renewed-access");
        assertThat(saved.getRefreshToken().getTokenValue()).isEqualTo("renewed-refresh");
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void rejectedRefreshReturnsToTheOriginalPageAndQueryAfterLogin() throws Exception {
        var expired = session(REGISTRATION, "expired-token", true, "rejected-refresh");
        var original = PAGE + "?proposal=" + PROPOSAL + "&view=summary";
        var page = mvc.perform(get(original).session(expired)).andExpect(redirectedUrl(LOGIN)).andReturn();
        assertThat(expired.isInvalid()).isTrue();
        var resumed = (MockHttpSession) page.getRequest().getSession(false);
        assertThat(resumed).isNotNull().isNotSameAs(expired);
        var login = mvc.perform(get(LOGIN).session(resumed)).andExpect(status().is3xxRedirection()).andReturn();
        var params = UriComponentsBuilder.fromUriString(login.getResponse().getRedirectedUrl())
                .build().getQueryParams();
        assertThat(params.getFirst("code_challenge_method")).isEqualTo("S256");
        var state = URLDecoder.decode(params.getFirst("state"), UTF_8);
        mvc.perform(get("/login/oauth2/code/" + REGISTRATION).session(resumed)
                        .param("code", "login-code").param("state", state))
                .andExpect(redirectedUrl("http://localhost" + original));
        mvc.perform(get(original).session(resumed)).andExpect(status().isOk());
    }

    @Test
    void rejectedRefreshOnApiReturnsJsonAndDoesNotSaveTheRequest() throws Exception {
        var expired = session(REGISTRATION, "expired-token", true, "rejected-refresh");
        var request = mvc.perform(get(API).session(expired)).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("THS_SESSION_EXPIRED"))
                .andExpect(header().doesNotExist("Location")).andReturn();
        assertThat(expired.isInvalid()).isTrue();
        assertThat(request.getRequest().getSession(false)).isNull();
        mvc.perform(get(API)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("THS_SESSION_EXPIRED"));
        verifyNoInteractions(proposals);
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "reject"})
    void expiredDecisionIsNeverDispatchedOrSaved(String action) throws Exception {
        var expired = session(REGISTRATION, "expired-token", true, null);
        var request = mvc.perform(post(API + "/" + action).session(expired).with(csrf().asHeader())
                        .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedHash\":\"fixture-hash\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("THS_SESSION_EXPIRED"))
                .andExpect(header().doesNotExist("Location")).andReturn();
        assertThat(expired.isInvalid()).isTrue();
        assertThat(request.getRequest().getSession(false)).isNull();
        verifyNoInteractions(proposals);
    }

    @Test
    void anExpiredDecisionWithoutCsrfStillFailsCsrfValidation() throws Exception {
        var session = session(REGISTRATION, "expired-token", true, "accepted-refresh");
        mvc.perform(post(API + "/confirm").session(session)).andExpect(status().isForbidden());
        assertThat(clients.<OAuth2AuthorizedClient>loadAuthorizedClient(REGISTRATION, SUBJECT)
                .getAccessToken().getTokenValue()).isEqualTo("expired-token");
        verifyNoInteractions(proposals);
    }

    @Test
    void aDifferentRegistrationRemainsForbidden() throws Exception {
        var session = session("other-registration", "expired-token", true, "accepted-refresh");
        mvc.perform(get(PAGE).session(session)).andExpect(status().isForbidden());
        assertThat(clients.<OAuth2AuthorizedClient>loadAuthorizedClient("other-registration", SUBJECT)
                .getAccessToken().getTokenValue()).isEqualTo("expired-token");
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void aServiceTokenRemainsForbidden() throws Exception {
        var session = session(REGISTRATION, "service-token", false, null);
        mvc.perform(get(PAGE).session(session)).andExpect(status().isForbidden());
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void aThreeHundredSecondTokenDoesNotRefreshAtAgeOneHundred() throws Exception {
        var now = clock.instant();
        var session = session(REGISTRATION, "original-access", now.minusSeconds(100), now.plusSeconds(200),
                "accepted-refresh");
        var attempts = issuer.refreshAttempts.get();
        mvc.perform(get(PAGE).session(session)).andExpect(status().isOk());
        assertThat(savedAccessToken()).isEqualTo("original-access");
        assertThat(issuer.refreshAttempts.get()).isEqualTo(attempts);
    }

    @Test
    void refreshesAtAgeOneHundredSixtyAndLaterRequestsUseTheRenewedToken() throws Exception {
        var now = clock.instant();
        var session = session(REGISTRATION, "original-access", now.minusSeconds(160), now.plusSeconds(140),
                "accepted-refresh");
        mvc.perform(get(workflowCard()).session(session)).andExpect(status().isOk());
        assertThat(issuer.lastOwnerBearer.get()).isEqualTo("Bearer original-access");
        assertThat(savedAccessToken()).isEqualTo("renewed-access");

        mvc.perform(get(workflowCard()).session(session)).andExpect(status().isOk());
        assertThat(issuer.lastOwnerBearer.get()).isEqualTo("Bearer renewed-access");
    }

    @Test
    void aRequestAdmittedBeforeRefreshForwardsItsOriginalBearerAfterRefreshCompletes() throws Exception {
        var now = clock.instant();
        var session = session(REGISTRATION, "original-access", now.minusSeconds(100), now.plusSeconds(200),
                "accepted-refresh");
        var hold = new RequestHold(new CountDownLatch(1), new CountDownLatch(1));
        issuer.hold.set(hold);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var activation = executor.submit(() -> mvc.perform(post(workflowCard() + "/activate")
                    .session(session).with(csrf().asHeader()).header("X-Test-Hold-Request", "true")
                    .contentType(MediaType.APPLICATION_JSON).content(
                            "{\"configurationHash\":\"sha256:" + "a".repeat(64) + "\",\"expectedVersion\":1}"))
                    .andExpect(status().isOk()).andReturn());
            try {
                assertThat(hold.admitted().await(10, TimeUnit.SECONDS)).isTrue();
                when(clock.instant()).thenReturn(now.plusSeconds(60));
                mvc.perform(get(PAGE).session(session)).andExpect(status().isOk());
                assertThat(savedAccessToken()).isEqualTo("renewed-access");
            } finally {
                hold.release().countDown();
                issuer.hold.set(null);
            }
            activation.get(10, TimeUnit.SECONDS);
            assertThat(issuer.lastOwnerBearer.get()).isEqualTo("Bearer original-access");
            assertThat(issuer.lastOwnerMethod.get()).isEqualTo("POST");
            assertThat(issuer.lastOwnerPath.get()).endsWith("/review/activate");
        }
    }

    @Test
    void aFailedRefreshKeepsTheLiveTokenAndRetriesOnTheNextRequest() throws Exception {
        var now = clock.instant();
        var session = session(REGISTRATION, "original-access", now.minusSeconds(160), now.plusSeconds(140),
                "rejected-refresh");
        var attempts = issuer.refreshAttempts.get();
        for (int request = 1; request <= 2; request++) {
            mvc.perform(get(workflowCard()).session(session)).andExpect(status().isOk());
            assertThat(session.isInvalid()).isFalse();
            assertThat(savedAccessToken()).isEqualTo("original-access");
            assertThat(issuer.lastOwnerBearer.get()).isEqualTo("Bearer original-access");
            assertThat(issuer.refreshAttempts.get()).isEqualTo(attempts + request);
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aNewOneHundredTwentySecondTokenLogsTheConfigurationErrorWithoutBlocking(CapturedOutput output)
            throws Exception {
        var now = clock.instant();
        var session = session(REGISTRATION, "original-access", now.minusSeconds(160), now.plusSeconds(140),
                "short-refresh");
        mvc.perform(get(PAGE).session(session)).andExpect(status().isOk());
        var token = clients.<OAuth2AuthorizedClient>loadAuthorizedClient(REGISTRATION, SUBJECT).getAccessToken();
        assertThat(token.getTokenValue()).isEqualTo("short-access");
        assertThat(output.getAll()).contains("THS_ACCESS_TOKEN_HALF_LIFE_TOO_SHORT halfLifeMs=60000 minimumMs=105000");
        assertThat(output.getAll()).doesNotContain("short-access", "short-refresh");
        var timing = new ThsAccessTokenRefresh(clock);
        when(clock.instant()).thenReturn(token.getIssuedAt().plusSeconds(14));
        assertThat(timing.refreshDue(token)).isFalse();
        when(clock.instant()).thenReturn(token.getIssuedAt().plusSeconds(15));
        assertThat(timing.refreshDue(token)).isTrue();
    }

    @Test
    void aTokenWithoutIssuedAtUsesTheStrictOneHundredFiftySecondFallback() {
        var now = clock.instant();
        var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "legacy-access", null,
                now.plusSeconds(150));
        var timing = new ThsAccessTokenRefresh(clock);
        assertThat(timing.refreshDue(token)).isFalse();
        when(clock.instant()).thenReturn(now.plusNanos(1));
        assertThat(timing.refreshDue(token)).isTrue();
    }

    @Test
    void aTokenWithoutIssuedAtUsesTheLifetimeObservedAtIssuance() {
        var now = clock.instant();
        var access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "observed-access", null,
                now.plusSeconds(300));
        var timing = new ThsAccessTokenRefresh(clock);
        var client = new OAuth2AuthorizedClient(registrations.findByRegistrationId(REGISTRATION), SUBJECT, access);
        var token = timing.observeIssuance(client).getAccessToken();
        assertThat(token.getIssuedAt()).isEqualTo(now);
        when(clock.instant()).thenReturn(now.plusSeconds(150).minusNanos(1));
        assertThat(timing.refreshDue(token)).isFalse();
        when(clock.instant()).thenReturn(now.plusSeconds(150));
        assertThat(timing.refreshDue(token)).isTrue();
    }

    private static String workflowCard() {
        return "/trusted-human/onboarding/api/" + PROPOSAL;
    }

    private String savedAccessToken() {
        return clients.<OAuth2AuthorizedClient>loadAuthorizedClient(REGISTRATION, SUBJECT)
                .getAccessToken().getTokenValue();
    }

    private record RequestHold(CountDownLatch admitted, CountDownLatch release) {}

    private MockHttpSession session(String registrationId, String value, boolean expired, String refreshValue) {
        var now = clock.instant();
        return session(registrationId, value, now.minusSeconds(600),
                expired ? now.minusSeconds(120) : now.plusSeconds(600), refreshValue);
    }

    private MockHttpSession session(String registrationId, String value, Instant issued, Instant expiry,
            String refreshValue) {
        var authorities = List.of(new SimpleGrantedAuthority("OAUTH2_USER"));
        var user = new DefaultOAuth2User(authorities, Map.of("sub", SUBJECT), "sub");
        var auth = new OAuth2AuthenticationToken(user, authorities, registrationId);
        var access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, value, issued, expiry, Set.of("profile"));
        var refresh = refreshValue == null ? null : new OAuth2RefreshToken(refreshValue, issued);
        clients.saveAuthorizedClient(new OAuth2AuthorizedClient(registrations.findByRegistrationId(registrationId),
                auth.getName(), access, refresh), auth);
        var security = SecurityContextHolder.createEmptyContext();
        security.setAuthentication(auth);
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
        return session;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({PermissionThsSecurity.class, PermissionThsApi.class})
    static class Config {
        @Bean Clock clock() {
            return mock(Clock.class);
        }

        @Bean @Primary ThsAccessTokenRefresh controlledTiming(Clock clock) {
            return new ThsAccessTokenRefresh(clock);
        }

        @Bean WorkflowThsApi workflow(StubIssuer issuer) {
            return new WorkflowThsApi(new ObjectMapper(), issuer.origin());
        }

        @Bean WebMvcConfigurer holdAdmittedRequest(StubIssuer issuer) {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(new HandlerInterceptor() {
                        @Override
                        public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                Object handler) throws InterruptedException {
                            var hold = issuer.hold.get();
                            if (hold != null && "true".equals(request.getHeader("X-Test-Hold-Request"))) {
                                hold.admitted().countDown();
                                return hold.release().await(10, TimeUnit.SECONDS);
                            }
                            return true;
                        }
                    });
                }
            };
        }

        @Bean StubIssuer stubIssuer() throws IOException {
            return new StubIssuer();
        }

        @Bean ClientRegistrationRepository registrations(StubIssuer issuer) {
            return new InMemoryClientRegistrationRepository(registration(REGISTRATION, issuer),
                    registration("other-registration", issuer));
        }

        private ClientRegistration registration(String id, StubIssuer issuer) {
            return ClientRegistration.withRegistrationId(id).clientId(id).clientSecret("test-secret")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .authorizationUri(issuer.origin() + "/authorize").tokenUri(issuer.origin() + "/token")
                    .userInfoUri(issuer.origin() + "/userinfo").userNameAttributeName("sub")
                    .scope("profile").clientName(id).build();
        }

        @Bean OAuth2AuthorizedClientService clients(ClientRegistrationRepository registrations) {
            return new InMemoryOAuth2AuthorizedClientService(registrations);
        }

        @Bean PermissionProposalService proposals() {
            return mock(PermissionProposalService.class);
        }

        @Bean JwtDecoder decoder() {
            return value -> {
                if ("expired-token".equals(value)) throw new JwtException("expired");
                return Jwt.withTokenValue(value).header("alg", "RS256").subject(SUBJECT).build();
            };
        }

        @Bean Converter<Jwt, ? extends AbstractAuthenticationToken> converter() {
            return jwt -> {
                boolean service = "service-token".equals(jwt.getTokenValue());
                var principal = new PrincipalContext(SUBJECT, "ouf-lab", service ? ActorType.SERVICE : ActorType.HUMAN,
                        service ? "fixture-service" : null, "test-auth", "test-issuer", "test-audience", Set.of());
                return new UsernamePasswordAuthenticationToken(new TrustedPrincipal(principal), null);
            };
        }
    }

    private static final class StubIssuer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger refreshAttempts = new AtomicInteger();
        private final AtomicReference<String> lastOwnerBearer = new AtomicReference<>();
        private final AtomicReference<String> lastOwnerMethod = new AtomicReference<>();
        private final AtomicReference<String> lastOwnerPath = new AtomicReference<>();
        private final AtomicReference<RequestHold> hold = new AtomicReference<>();

        private StubIssuer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", this::token);
            server.createContext("/userinfo", exchange -> reply(exchange, 200, "{\"sub\":\"test-human\"}"));
            server.createContext("/api/trusted-human/v1/approval-challenges/", exchange -> {
                lastOwnerBearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
                lastOwnerMethod.set(exchange.getRequestMethod());
                lastOwnerPath.set(exchange.getRequestURI().getPath());
                reply(exchange, 200, "{}");
            });
            server.start();
        }

        private String origin() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void token(HttpExchange exchange) throws IOException {
            var body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
            if (body.contains("grant_type=refresh_token")) refreshAttempts.incrementAndGet();
            if (body.contains("grant_type=refresh_token") && body.contains("refresh_token=short-refresh")) {
                reply(exchange, 200, """
                        {"access_token":"short-access","refresh_token":"short-refresh","token_type":"Bearer","expires_in":120}
                        """);
            } else if (body.contains("grant_type=refresh_token") && body.contains("refresh_token=accepted-refresh")
                    && !body.contains("scope=")) {
                reply(exchange, 200, """
                        {"access_token":"renewed-access","refresh_token":"renewed-refresh","token_type":"Bearer","expires_in":300}
                        """);
            } else if (body.contains("grant_type=authorization_code") && body.contains("code_verifier=")) {
                reply(exchange, 200, """
                        {"access_token":"login-access","token_type":"Bearer","expires_in":300}
                        """);
            } else {
                reply(exchange, 400, "{\"error\":\"invalid_grant\"}");
            }
        }

        private static void reply(HttpExchange exchange, int status, String json) throws IOException {
            try {
                var body = json.getBytes(UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        }

        @Override public void close() {
            server.stop(0);
        }
    }
}
