package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** The JSON body shared by publication admission and the exact runtime reads. */
@Component
public class RuntimePublicationEnvelope {
  public static final String TOO_LARGE = "ONB_PUBLICATION_ENVELOPE_TOO_LARGE";
  private final ObjectMapper json;
  private final String tenant;
  private final int maxBytes;

  public RuntimePublicationEnvelope(ObjectMapper json, @Value("${ouf.runtime-publications.tenant-id:}") String tenant,
      @Value("${ouf.onboarding.publication.max-runtime-envelope-bytes:2097152}") int maxBytes) {
    if (maxBytes < 1) throw new IllegalArgumentException("ONB_PUBLICATION_ENVELOPE_LIMIT_INVALID");
    this.json = json;
    this.tenant = tenant;
    this.maxBytes = maxBytes;
  }

  public String tenant() { return tenant; }

  public Map<String,Object> body(Object id, String source, Object version, Object sequence, Object checksum,
      Object status, String storedBundle) {
    try {
      return Map.of("publicationId", id, "sourceId", source, "onboardingVersionId", version,
          "publicationSequence", sequence, "checksum", checksum, "sourceStatus", status,
          "tenantId", tenant, "bundle", json.readValue(storedBundle, Map.class));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("ONB_PUBLICATION_ENCODING_FAILED", e);
    }
  }

  public byte[] encode(Map<String,Object> body) {
    try {
      return json.writeValueAsBytes(body);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("ONB_PUBLICATION_ENCODING_FAILED", e);
    }
  }

  public byte[] encodePage(List<Map<String,Object>> items, String nextAfter) {
    return encode(Map.of("items", items, "nextAfter", nextAfter));
  }

  public Size measure(UUID id, String source, UUID version, Object sequence, Object checksum, String storedBundle) {
    // Source status can change after publication. REGISTERED is the longest value allowed by the source constraint.
    var envelope = body(id, source, version, sequence, checksum, "REGISTERED", storedBundle);
    // Ingestion can reduce its discovery page to one item. Include the framing and cursor of that response.
    return new Size(encodePage(List.of(envelope), source).length, maxBytes);
  }

  public record Size(int bytes, int limitBytes) {
    public boolean exceedsLimit() { return bytes > limitBytes; }

    public void requireFits() {
      if (exceedsLimit()) {
        throw new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY, TOO_LARGE,
            "La pubblicazione occupa %d byte. Il limite di lettura è %d byte. Prepara una revisione più piccola con l’agente."
                .formatted(bytes, limitBytes), Map.of("envelopeBytes", bytes, "maxEnvelopeBytes", limitBytes));
      }
    }
  }
}
