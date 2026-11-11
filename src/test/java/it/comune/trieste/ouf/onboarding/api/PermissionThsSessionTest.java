package it.comune.trieste.ouf.onboarding.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.PrincipalContext.ActorType;
import it.comune.trieste.ouf.authorization.TrustedPrincipal;
import it.comune.trieste.ouf.onboarding.authorization.PermissionProposalService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
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
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.util.UriComponentsBuilder;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringJUnitWebConfig(PermissionThsSessionTest.Config.class)
@TestPropertySource(properties = "ouf.authorization.ths.enabled=true")
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
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void refreshesAnExpiredClientAndServesThePageWithoutExposingTokens() throws Exception {
        var session = session(REGISTRATION, "expired-token", true, "accepted-refresh");
        mvc.perform(get(PAGE).session(session)).andExpect(status().isOk())
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

    private MockHttpSession session(String registrationId, String value, boolean expired, String refreshValue) {
        var authorities = List.of(new SimpleGrantedAuthority("OAUTH2_USER"));
        var user = new DefaultOAuth2User(authorities, Map.of("sub", SUBJECT), "sub");
        var auth = new OAuth2AuthenticationToken(user, authorities, registrationId);
        var now = Instant.now();
        var access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, value, now.minusSeconds(600),
                expired ? now.minusSeconds(120) : now.plusSeconds(600), Set.of("profile"));
        var refresh = refreshValue == null ? null : new OAuth2RefreshToken(refreshValue, now.minusSeconds(600));
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

        private StubIssuer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", this::token);
            server.createContext("/userinfo", exchange -> reply(exchange, 200, "{\"sub\":\"test-human\"}"));
            server.start();
        }

        private String origin() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void token(HttpExchange exchange) throws IOException {
            var body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
            if (body.contains("grant_type=refresh_token") && body.contains("refresh_token=accepted-refresh")) {
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
