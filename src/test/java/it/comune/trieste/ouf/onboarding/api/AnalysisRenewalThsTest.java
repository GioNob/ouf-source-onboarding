package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AnalysisRenewalThsTest {
  private static final String REF = "Primo_link_rinnovo-001";
  private static final String HASH = "b".repeat(64);
  private static final String KEY = "d612c624-247a-4bcd-8c3e-c59558139570";
  private final ObjectMapper json = new ObjectMapper();
  private final ManagedProfileRecoveryThsTest session = new ManagedProfileRecoveryThsTest();

  @AfterEach void clear() { session.clear(); }

  private ObjectNode command() {
    var node = json.createObjectNode().put("reviewRef", REF).put("proposalVersion", 1)
        .put("proposalHash", HASH).put("expectedSeq", "4").put("commandKey", KEY)
        .put("decision", "CHANGES_REQUESTED");
    node.putArray("items").addObject().put("field", "Calls").put("comment", "Ridurre le chiamate");
    return node;
  }

  @Test @DisplayName("ONB-55D-02 / 04 · trasporto e fence congelati, ricevuta e Retry-After")
  void gatewayUsesOnlyFrozenRoutesAndPropagatesTheVerifiedOutcome() throws Exception {
    record Reply(int status, String json) {}
    var card = json.createObjectNode().put("reviewRef", REF).put("reason", "ANALYSIS_BUDGET_EXHAUSTED")
        .put("analysisId", "a".repeat(64)).put("proposalHash", HASH).put("expectedSeq", "5")
        .put("proposalVersion", 2).put("proposalState", "OPEN").put("planLabel", "ricreatori.csv · 18 colonne");
    card.putArray("remainingOrdinals").add(0).add(7);
    card.put("expiresAt", "2099-03-18T12:00:00Z");
    for (var field : List.of("Calls", "Attempts", "Objects", "Bytes", "Millis")) {
      card.withObject("/initialMaximum").put(field, 100);
      card.withObject("/consumed").put(field, 80);
      card.withObject("/increment").put(field, 10);
      card.withObject("/totalMaximum").put(field, 110);
    }
    var previous = card.deepCopy().retain("reason", "remainingOrdinals", "initialMaximum", "consumed",
        "increment", "totalMaximum", "expiresAt").put("proposalVersion", 1);
    previous.withObject("/increment").put("Calls", 20);
    previous.withObject("/totalMaximum").put("Calls", 120);
    card.set("previous", previous);
    card.putArray("requestedChanges").addObject().put("field", "general").put("comment", "Ridurre i limiti");
    var reply = new AtomicReference<>(new Reply(200, card.toString()));
    var requests = new CopyOnWriteArrayList<List<String>>();
    var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/", exchange -> {
      try (exchange) {
        requests.add(List.of(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
            exchange.getRequestHeaders().getFirst("Authorization"),
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        Reply next = reply.get();
        var bytes = next.json().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Retry-After", "17");
        exchange.sendResponseHeaders(next.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
      }
    });
    server.start();
    try {
      var gateway = new AnalysisRenewalGateway(json, "http://localhost:" + server.getAddress().getPort(), 15, 65536);
      var api = new AnalysisRenewalThs(session.session(), gateway, json);
      var r = session.request();
      r.addHeader("Accept", "application/json");
      assertThat(api.page(REF, r).getBody().toString()).contains("ANALYSIS_BUDGET_EXHAUSTED", "fixture-csrf",
          "ricreatori.csv", "previous", "requestedChanges", "Ridurre i limiti", "proposalState")
          .doesNotContain("fixture-private-token");
      assertThat(requests).containsExactly(List.of("POST", "/internal/analysis/v1/renewals/read",
          "Bearer fixture-private-token", json.createObjectNode().put("reviewRef", REF).toString()));
      var command = command();
      reply.set(new Reply(200, "{\"decisionRef\":\"ricevuta-A\"}"));
      for (int i = 0; i < 2; i++) {
        r.setContent(json.writeValueAsBytes(command));
        assertThat(api.decide(REF, r).getBody().toString()).contains("ricevuta-A");
      }
      assertThat(requests.get(1)).isEqualTo(List.of("POST", "/internal/analysis/v1/renewals/decide",
          "Bearer fixture-private-token", command.toString())).isEqualTo(requests.get(2));
      reply.set(new Reply(409, "{\"code\":\"ANALYSIS_STALE_EXECUTION\"}"));
      r.setContent(json.writeValueAsBytes(command));
      var stale = api.decide(REF, r);
      assertThat(stale.getStatusCode().value()).isEqualTo(409);
      assertThat(stale.getBody().toString()).contains("ANALYSIS_STALE_EXECUTION");
      for (int status : List.of(429, 503)) {
        String code = status == 429 ? "ANALYSIS_BACKOFF" : "ANALYSIS_AUTHORITY_UNAVAILABLE";
        reply.set(new Reply(status, "{\"code\":\"" + code + "\",\"detail\":\"private diagnostic\"}"));
        var result = api.page(REF, r);
        assertThat(result.getStatusCode().value()).isEqualTo(status);
        assertThat(result.getBody().toString()).contains(code).doesNotContain("diagnostic");
        assertThat(result.getHeaders().getFirst("Retry-After")).isEqualTo("17");
      }
    } finally { server.stop(0); }
  }

  @Test @DisplayName("ONB-55D-03 · CSRF mancante o errato e sessione assente fermano il comando")
  void csrfAndSessionAreRequiredBeforeGateway() throws Exception {
    var gateway = mock(AnalysisRenewalGateway.class);
    var clients = mock(OAuth2AuthorizedClientService.class);
    var api = new AnalysisRenewalThs(clients, gateway, json);
    var mvc = MockMvcBuilders.standaloneSetup(api)
        .addFilters(new CsrfFilter(new HttpSessionCsrfTokenRepository())).build();
    var request = post(AnalysisRenewalThs.PATH + "/" + REF + "/decision")
        .contentType(MediaType.APPLICATION_JSON).content(command().toString());
    mvc.perform(request).andExpect(status().isForbidden());
    mvc.perform(request.header("X-CSRF-TOKEN", "errato")).andExpect(status().isForbidden());
    assertThatThrownBy(() -> api.decide(REF, new MockHttpServletRequest())).hasMessageContaining("THS_SESSION_REQUIRED");
    verifyNoInteractions(gateway, clients);
  }

  @Test @DisplayName("ONB-55D-04 · link e corpo devono coincidere, nessuna autorità dal browser")
  void differentLinkOrAdditionalAuthorityFieldsCannotBeForwarded() throws Exception {
    var gateway = spy(new AnalysisRenewalGateway(json, "http://127.0.0.1", 15, 65536));
    var api = new AnalysisRenewalThs(session.session(), gateway, json);
    var r = session.request();
    var body = command();
    r.setContent(json.writeValueAsBytes(body));
    assertInvalid(api.decide("Altro_link_rinnovo-002", r));
    r.setContent(json.writeValueAsBytes(body.put("decisionRef", "untrusted")));
    assertInvalid(api.decide(REF, r));
    verify(gateway, never()).decide(anyString(), any());
  }

  private void assertInvalid(ResponseEntity<?> result) {
    assertThat(result.getStatusCode().value()).isEqualTo(400);
    assertThat(result.getBody()).isEqualTo(json.createObjectNode().put("code", "ANALYSIS_INPUT_INVALID"));
  }

  @Test @DisplayName("ONB-55D-04 · riferimento base64url obbligatorio in lettura e decisione")
  void invalidReferenceCannotReachGateway() throws Exception {
    var gateway = spy(new AnalysisRenewalGateway(json, "http://127.0.0.1", 15, 65536));
    var api = new AnalysisRenewalThs(session.session(), gateway, json);
    for (String ref : List.of("a".repeat(21), "b".repeat(129), REF + ".", REF + "=", REF + "~")) {
      // The static page explains the outcome in Italian through its JSON read; it carries no proposal data.
      var page = api.page(ref, session.request());
      assertThat(page.getStatusCode().value()).isEqualTo(200);
      assertThat(page.getHeaders().getContentType()).hasToString("text/html;charset=UTF-8");
      var request = session.request();
      request.addHeader("Accept", "application/json");
      assertInvalid(api.page(ref, request));
      request.setContent(json.writeValueAsBytes(command().put("reviewRef", ref)));
      assertInvalid(api.decide(ref, request));
    }
    verifyNoInteractions(gateway);
  }

  private void assertRejectedBeforeGateway(ObjectNode... commands) throws Exception {
    var gateway = spy(new AnalysisRenewalGateway(json, "http://127.0.0.1", 15, 65536));
    var api = new AnalysisRenewalThs(session.session(), gateway, json);
    for (var bad : commands) {
      var request = session.request();
      request.setContent(json.writeValueAsBytes(bad));
      assertInvalid(api.decide(REF, request));
    }
    verifyNoInteractions(gateway);
  }

  @Test @DisplayName("ONB-55D-03 · la chiave richiede versione 4, variante RFC e cifre minuscole")
  void commandKeyMustBeCanonicalUuidV4() throws Exception {
    assertRejectedBeforeGateway(command().put("commandKey", HASH),
        command().put("commandKey", KEY.toUpperCase(java.util.Locale.ROOT)),
        command().put("commandKey", KEY.replace("-4bcd", "-1bcd")),
        command().put("commandKey", KEY.replace("-8c3e", "-7c3e")));
  }

  @Test @DisplayName("ONB-55D-03 · la sequenza è testuale e la versione è un intero esatto positivo")
  void sequenceAndVersionRequireTheirOwnJsonTypesAndRanges() throws Exception {
    assertRejectedBeforeGateway(command().put("expectedSeq", 4), command().put("expectedSeq", "04"),
        command().put("expectedSeq", "-1"), command().put("expectedSeq", "9223372036854775808"),
        command().put("proposalVersion", 0), command().put("proposalVersion", "1"),
        command().put("proposalVersion", 1.5), command().put("proposalVersion", 9007199254740992L));
  }

  @Test @DisplayName("ONB-55D-03 · l’impronta della proposta richiede 64 cifre esadecimali minuscole")
  void proposalHashMustBeCanonicalDigest() throws Exception {
    assertRejectedBeforeGateway(command().put("proposalHash", "B".repeat(64)),
        command().put("proposalHash", "g".repeat(64)), command().put("proposalHash", HASH.substring(1)));
  }

  @Test @DisplayName("ONB-55D-02 · limiti inclusivi della revisione e dei commenti")
  void acceptsContractBoundariesAndRejectsOversizedFeedback() throws Exception {
    var gateway = spy(new AnalysisRenewalGateway(json, "http://127.0.0.1", 25, 131072));
    doReturn(ResponseEntity.ok(json.createObjectNode())).when(gateway).decide(anyString(), any());
    var api = new AnalysisRenewalThs(session.session(), gateway, json);
    var ref = "_-".repeat(64);
    var body = command().put("reviewRef", ref).put("proposalVersion", 9007199254740991L);
    var items = body.putArray("items");
    for (int i = 0; i < 16; i++)
      items.addObject().put("field", "general").put("comment", "a".repeat(500));
    var request = session.request();
    for (String seq : List.of("0", "9223372036854775807")) {
      request.setContent(json.writeValueAsBytes(body.put("expectedSeq", seq)));
      assertThat(api.decide(ref, request).getStatusCode().value()).isEqualTo(200);
    }
    items.addObject().put("field", "Calls").put("comment", "Una voce in più");
    request.setContent(json.writeValueAsBytes(body));
    assertInvalid(api.decide(ref, request));
    items.remove(16);
    var item = (ObjectNode) items.get(0);
    item.put("comment", "a".repeat(501));
    request.setContent(json.writeValueAsBytes(body));
    assertInvalid(api.decide(ref, request));
    // The limit counts Unicode characters, as the Gateway does: 500 symbols outside the BMP are 1000 UTF-16 units.
    item.put("comment", "🟢".repeat(500));
    request.setContent(json.writeValueAsBytes(body));
    assertThat(api.decide(ref, request).getStatusCode().value()).isEqualTo(200);
    item.put("comment", "🟢".repeat(501));
    request.setContent(json.writeValueAsBytes(body));
    assertInvalid(api.decide(ref, request));
    item.put("comment", "Nota").put("field", "altro");
    request.setContent(json.writeValueAsBytes(body));
    assertInvalid(api.decide(ref, request));
    item.put("field", "Bytes");
    byte[] payload = json.writeValueAsBytes(body);
    byte[] padded = java.util.Arrays.copyOf(payload, 131073);
    java.util.Arrays.fill(padded, payload.length, padded.length, (byte) ' ');
    request.setContent(java.util.Arrays.copyOf(padded, 131072));
    assertThat(api.decide(ref, request).getStatusCode().value()).isEqualTo(200);
    request.setContent(padded);
    assertInvalid(api.decide(ref, request));
    verify(gateway, times(4)).decide(anyString(), any());
  }

  @Test void gatewayRequiresFiniteExchangeLimits() {
    assertThatThrownBy(() -> new AnalysisRenewalGateway(json, "https://gateway.example", 26, 65536))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AnalysisRenewalGateway(json, "https://gateway.example", 15, 131073))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
