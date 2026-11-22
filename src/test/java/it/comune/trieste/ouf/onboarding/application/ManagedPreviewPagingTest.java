package it.comune.trieste.ouf.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ManagedPreviewPagingTest {
  private static final int DEFAULT_BUDGET = 786432;
  private static final String TENANT = "synthetic-tenant", SUBJECT = "synthetic-subject";
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final List<String> LARGE_VALUES = IntStream.range(1000, 3000)
      .mapToObj(i -> "X" + "\u0001".repeat(120) + "\\\"\uD83D\uDE00" + i).toList();
  private final ObjectMapper json = new ObjectMapper();
  private final byte[] testKey = new byte[32];

  @Test void d084_40f_01_escapedUtf8ProfileAndSingleColumnPagesPreserveAllValues() throws Exception {
    assertThat(LARGE_VALUES).hasSize(2000).allSatisfy(value -> assertThat(value.codePointCount(0, value.length())).isEqualTo(128));
    assertThat(json.writeValueAsBytes(LARGE_VALUES).length).isGreaterThan(1_048_576);
    var preview = preview(List.of("code", "other"));
    var values = Map.of("", Map.of("code", LARGE_VALUES, "other", List.of("z")));
    var paging = paging(DEFAULT_BUDGET, NOW);

    var profile = body(paging.profile(preview, null, values, TENANT, SUBJECT), DEFAULT_BUDGET);
    var fromProfile = profile.path("columns").get(0);
    assertThat(fromProfile.path("distinctValuesComplete").asBoolean()).isFalse();
    assertThat(fromProfile.path("distinctValues").size()).isBetween(1, 1999);
    assertThat(profile.path("columns").get(1).path("distinctValuesComplete").asBoolean()).isTrue();

    var first = body(paging.columns(preview, values, TENANT, SUBJECT, null, 0, 128, null), DEFAULT_BUDGET);
    assertThat(first.path("hasMore").asBoolean()).isTrue();
    assertThat(first.path("nextOffset").asInt()).isEqualTo(1);
    assertThat(first.path("columns").size()).isEqualTo(1);
    var fromColumns = first.path("columns").get(0);
    assertThat(fromColumns.path("distinctValues").size()).isBetween(1, 1999);
    for (var initial : List.of(fromProfile, fromColumns)) {
      var received = new ArrayList<String>();
      var slice = initial;
      for (int i = 0; i < 4; i++) {
        assertThat(slice.path("name").asText()).isEqualTo("code");
        assertThat(slice.path("dataType").asText()).isEqualTo("STRING");
        assertThat(slice.path("distinctCount").asInt()).isEqualTo(2000);
        slice.path("distinctValues").forEach(value -> received.add(value.asText()));
        if (slice.path("distinctValuesComplete").asBoolean()) break;
        String cursor = slice.path("distinctValuesCursor").asText();
        assertThat(cursor).matches("[A-Za-z0-9_-]+");
        var next = body(paging.columns(preview, values, TENANT, SUBJECT, null, 0, 128, cursor), DEFAULT_BUDGET);
        assertThat(next.path("columns").size()).isEqualTo(1);
        assertThat(next.path("offset").asInt()).isZero();
        assertThat(next.path("hasMore").asBoolean()).isFalse();
        assertThat(next.path("nextOffset").isNull()).isTrue();
        assertThat(next.path("schemaHash")).isEqualTo(first.path("schemaHash"));
        slice = next.path("columns").get(0);
      }
      assertThat(slice.path("distinctValuesComplete").asBoolean()).isTrue();
      assertThat(slice.has("distinctValuesCursor")).isFalse();
      assertThat(received).containsExactlyElementsOf(LARGE_VALUES);
    }
  }

  @Test void d084_40f_02_manyLargeColumnsReducePageWithoutSkippingColumns() throws Exception {
    var names = IntStream.range(0, 15).mapToObj(i -> "c" + i).toList();
    var preview = preview(names);
    var byName = new LinkedHashMap<String,List<String>>();
    var retained = LARGE_VALUES.stream().map(value -> "p".repeat(124) + value.substring(value.length()-4)).toList();
    names.forEach(name -> byName.put(name, retained));
    Map<String,Map<String,List<String>>> values = Map.of("", byName);
    var paging = paging(DEFAULT_BUDGET, NOW);
    var received = new ArrayList<String>();
    for (int offset = 0; offset < names.size();) {
      var page = body(paging.columns(preview, values, TENANT, SUBJECT, null, offset, 128, null), DEFAULT_BUDGET);
      assertThat(page.path("offset").asInt()).isEqualTo(offset);
      assertThat(page.path("columns").size()).isBetween(1, 2);
      for (var column : page.path("columns")) {
        assertThat(column.path("distinctValuesComplete").asBoolean()).isTrue();
        assertThat(column.path("distinctValues").size()).isEqualTo(2000);
        received.add(column.path("name").asText());
      }
      offset += page.path("columns").size();
      assertThat(page.path("hasMore").asBoolean()).isEqualTo(offset < names.size());
      if (offset < names.size()) assertThat(page.path("nextOffset").asInt()).isEqualTo(offset);
      else assertThat(page.path("nextOffset").isNull()).isTrue();
    }
    assertThat(received).containsExactlyElementsOf(names);
  }

  @Test void d084_40f_03_cursorRejectsTamperingDifferentScopeAndExpiry() throws Exception {
    int budget = 8192;
    var preview = preview(List.of("code"));
    var values = Map.of("", Map.of("code", LARGE_VALUES));
    var paging = paging(budget, NOW);
    String cursor = body(paging.columns(preview, values, TENANT, SUBJECT, null, 0, 1, null), budget)
        .path("columns").get(0).path("distinctValuesCursor").asText();
    assertThat(cursor).isNotEmpty();
    var otherProfile = new LinkedHashMap<>(preview);
    otherProfile.put("profileId", UUID.randomUUID());
    var otherAsset = new LinkedHashMap<>(preview);
    otherAsset.put("assetId", UUID.randomUUID());
    var otherSchema = new LinkedHashMap<>(preview);
    otherSchema.put("columns", preview(List.of("new-column")).get("columns"));

    int signatureIndex = cursor.length() - 5;
    String tampered = cursor.substring(0, signatureIndex) + (cursor.charAt(signatureIndex) == 'A' ? 'B' : 'A')
        + cursor.substring(signatureIndex + 1);
    assertInvalid(() -> paging.columns(preview, values, TENANT, SUBJECT, null, 0, 1, tampered));
    assertInvalid(() -> paging.columns(otherProfile, values, TENANT, SUBJECT, null, 0, 1, cursor));
    assertInvalid(() -> paging.columns(otherAsset, values, TENANT, SUBJECT, null, 0, 1, cursor));
    assertInvalid(() -> paging.columns(otherSchema, values, TENANT, SUBJECT, null, 0, 1, cursor));
    assertInvalid(() -> paging.columns(preview, values, "different-tenant", SUBJECT, null, 0, 1, cursor));
    assertInvalid(() -> paging.columns(preview, values, TENANT, "different-subject", null, 0, 1, cursor));
    var restarted = paging(budget, NOW.plusSeconds(899));
    var continuation = body(restarted.columns(preview, values, TENANT, SUBJECT, null, 0, 1, cursor), budget)
        .path("columns").get(0).path("distinctValuesCursor").asText();
    assertThat(continuation).isNotEmpty();
    var expired = paging(budget, NOW.plusSeconds(900));
    assertInvalid(() -> expired.columns(preview, values, TENANT, SUBJECT, null, 0, 1, continuation));
  }

  @Test void d084_40f_04_smallBudgetCoversSafeJobAndLayerColumns() throws Exception {
    int budget = 8192;
    var paging = paging(budget, NOW);
    var preview = preview(List.of("code"));
    var job = Map.<String,Object>of("jobId", UUID.randomUUID(), "status", "SUCCEEDED", "resultRef", preview.get("profileId"));
    var jobBody = body(paging.profile(preview, job, Map.of("", Map.of("code", LARGE_VALUES)), TENANT, SUBJECT), budget);
    assertThat(jobBody.has("redactedSample")).isFalse();
    assertThat(jobBody.path("columns").get(0).path("distinctValuesComplete").asBoolean()).isFalse();
    var layered = preview(List.of());
    layered.put("format", "GEOPACKAGE");
    layered.put("metadata", Map.of("layers", List.of(Map.of("layer", "roads", "columns", preview.get("columns")))));
    var values = Map.of("roads", Map.of("code", LARGE_VALUES));
    var layer = body(paging.profile(layered, null, values, TENANT, SUBJECT), budget).path("metadata").path("layers").get(0);
    String cursor = layer.path("columns").get(0).path("distinctValuesCursor").asText();
    var next = body(paging.columns(layered, values, TENANT, SUBJECT, null, 0, 128, cursor), budget);
    assertThat(next.path("layer").asText()).isEqualTo("roads");
    assertThat(next.path("columns").get(0).path("name").asText()).isEqualTo("code");
  }

  @Test void d084_40f_05_retainedMetadataFallbackKeepsWholeColumnsAndCounts() throws Exception {
    int budget = 8192;
    var paging = paging(budget, NOW);
    var names = IntStream.range(1000, 2024).mapToObj(i -> "x".repeat(252) + i).toList();
    var preview = preview(names);
    preview.put("candidateKeys", names);
    preview.put("redactedSample", List.of(Map.of(names.getFirst(), "\u0001".repeat(budget))));
    var profile = body(paging.profile(preview, null, Map.of(), TENANT, SUBJECT), budget);
    assertThat(profile.path("redactedSampleTruncated").asBoolean()).isTrue();
    assertThat(profile.path("candidateKeysTruncated").asBoolean()).isTrue();
    assertThat(profile.path("columnCount").asInt()).isEqualTo(1024);
    int offset = profile.path("nextOffset").asInt();
    assertThat(offset).isPositive().isEqualTo(profile.path("columns").size());
    for (int i = 0; i < offset; i++) {
      var column = profile.path("columns").get(i);
      assertThat(column.path("name").asText()).isEqualTo(names.get(i));
      assertThat(column.path("distinctCount").asInt()).isEqualTo(2000);
      assertThat(column.has("distinctValues")).isFalse();
      assertThat(column.has("distinctValuesCursor")).isFalse();
    }
    var next = body(paging.columns(preview, Map.of(), TENANT, SUBJECT, null, offset, 128, null), budget);
    assertThat(next.path("columns").get(0).path("name").asText()).isEqualTo(names.get(offset));
  }

  private static Map<String,Object> preview(List<String> names) {
    var out = new LinkedHashMap<String,Object>();
    out.put("assetId", UUID.randomUUID());
    out.put("profileId", UUID.randomUUID());
    out.put("profileVersion", 1);
    out.put("format", "CSV");
    out.put("columns", names.stream().map(name -> Map.of("name", name, "nullable", false, "dataType", "STRING", "distinctValues", 2000)).toList());
    out.put("metadata", Map.of());
    return out;
  }

  private ManagedPreviewPaging paging(int budget, Instant time) {
    return new ManagedPreviewPaging(json, new CanonicalHash(json),
        new DistinctValuesCursors(json, testKey, Clock.fixed(time, ZoneOffset.UTC)), budget);
  }

  private JsonNode body(Map<String,Object> response, int budget) throws Exception {
    byte[] body = json.writeValueAsBytes(response);
    assertThat(body.length).isLessThanOrEqualTo(budget);
    return json.readTree(body);
  }

  private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable request) {
    assertThatExceptionOfType(DomainFailure.class).isThrownBy(request).satisfies(error -> {
      assertThat(error.code()).isEqualTo("ONB_DISTINCT_VALUES_CURSOR_INVALID");
      assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(error.getMessage()).isNotBlank();
    });
  }
}
