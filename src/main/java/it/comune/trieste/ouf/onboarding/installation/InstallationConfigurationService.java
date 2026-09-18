package it.comune.trieste.ouf.onboarding.installation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstallationConfigurationService {
  private final JdbcClient db;
  private final ObjectMapper json;
  private final InstallationEnvironmentProbe environmentProbe;

  public InstallationConfigurationService(
      JdbcClient db,
      ObjectMapper json,
      InstallationEnvironmentProbe environmentProbe) {
    this.db = db;
    this.json = json.copy();
    this.environmentProbe = environmentProbe;
  }

  public record Actor(String subject, String correlationId) {
    public Actor {
      if (subject == null || subject.isBlank()) throw new IllegalArgumentException("INSTALLATION_ACTOR_REQUIRED");
      if (correlationId == null || correlationId.isBlank()) throw new IllegalArgumentException("INSTALLATION_CORRELATION_REQUIRED");
    }
  }

  public record Revision(
      String installationId,
      long revision,
      String checksum,
      String validationState,
      JsonNode validationFindings,
      JsonNode payload) {}

  public record Active(String installationId, long revision, String checksum) {}

  public record EnvironmentValidation(
      UUID validationId,
      String installationId,
      long revision,
      String overallStatus,
      JsonNode results,
      String correlationId) {}

  public record ValidationResult(boolean valid, ArrayNode findings) {}

  public ValidationResult validate(JsonNode input) {
    ArrayNode findings = json.createArrayNode();
    if (input == null || !input.isObject()) {
      findings.add("INSTALLATION_CONFIGURATION_OBJECT_REQUIRED");
      return new ValidationResult(false, findings);
    }
    requireText(input, "/installationId", findings);
    requireText(input, "/organization/organizationId", findings);
    requireText(input, "/organization/tenantId", findings);
    requireHttps(input, "/iam/issuerUrl", findings);
    requireHttps(input, "/iam/tokenEndpoint", findings);
    requireText(input, "/iam/realm", findings);
    requireText(input, "/iam/workloadClients/mcpServer", findings);
    requireHttps(input, "/gateway/publicApiBaseUrl", findings);

    JsonNode refs = input.at("/secrets/references");
    if (!refs.isObject()) {
      findings.add("INSTALLATION_SECRET_REFERENCES_REQUIRED");
    } else {
      refs.fields().forEachRemaining(e -> {
        if (!e.getValue().isTextual() || e.getValue().asText().isBlank())
          findings.add("INSTALLATION_SECRET_REFERENCE_INVALID:" + e.getKey());
      });
    }

    scanForbiddenSecretValues(input, "", findings);
    return new ValidationResult(findings.isEmpty(), findings);
  }

  private void requireText(JsonNode input, String pointer, ArrayNode findings) {
    JsonNode n = input.at(pointer);
    if (!n.isTextual() || n.asText().isBlank()) findings.add("INSTALLATION_REQUIRED:" + pointer);
  }

  private void requireHttps(JsonNode input, String pointer, ArrayNode findings) {
    JsonNode n = input.at(pointer);
    if (!n.isTextual() || !n.asText().startsWith("https://"))
      findings.add("INSTALLATION_HTTPS_REQUIRED:" + pointer);
  }

  private void scanForbiddenSecretValues(JsonNode node, String path, ArrayNode findings) {
    if (node == null) return;
    if ("/secrets/references".equals(path)) return;
    if (node.isObject()) {
      node.fields().forEachRemaining(e -> {
        String p = path + "/" + e.getKey();
        String k = e.getKey().toLowerCase(Locale.ROOT);
        if ((k.contains("password") || k.equals("clientsecret") || k.equals("accesstoken")
            || k.equals("bearertoken") || k.equals("privatekey")) && e.getValue().isValueNode())
          findings.add("INSTALLATION_SECRET_VALUE_FORBIDDEN:" + p);
        scanForbiddenSecretValues(e.getValue(), p, findings);
      });
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) scanForbiddenSecretValues(node.get(i), path + "/" + i, findings);
    }
  }

  private JsonNode canonical(JsonNode node) {
    if (node == null || node.isValueNode()) return node;
    if (node.isArray()) {
      ArrayNode out = json.createArrayNode();
      node.forEach(n -> out.add(canonical(n)));
      return out;
    }
    ObjectNode out = json.createObjectNode();
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    Collections.sort(names);
    names.forEach(n -> out.set(n, canonical(node.get(n))));
    return out;
  }

  private String encode(JsonNode node) {
    try { return json.writeValueAsString(node); }
    catch (Exception e) { throw new IllegalArgumentException("INSTALLATION_CONFIGURATION_INVALID", e); }
  }

  private String checksum(JsonNode node) {
    try {
      byte[] raw = encode(canonical(node)).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
    } catch (Exception e) {
      throw new IllegalStateException("INSTALLATION_CHECKSUM_FAILED", e);
    }
  }

  private Revision decodeRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    try {
      return new Revision(
          rs.getString("installation_id"),
          rs.getLong("revision"),
          rs.getString("checksum"),
          rs.getString("validation_state"),
          json.readTree(rs.getString("validation_findings")),
          json.readTree(rs.getString("payload")));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new java.sql.SQLException(e);
    }
  }

  @Transactional
  public Revision create(JsonNode payload, Actor actor) {
    String id = payload == null ? null : payload.path("installationId").asText(null);
    if (id == null || id.isBlank()) throw new IllegalArgumentException("INSTALLATION_ID_REQUIRED");
    db.sql("select pg_advisory_xact_lock(hashtext(:id))").param("id", id).query(Object.class).single();
    long revision = db.sql("""
        select coalesce(max(revision),0)+1
        from ouf_installation.installation_configuration_revision
        where installation_id=:id
        """).param("id", id).query(Long.class).single();
    ValidationResult validation = validate(payload);
    String state = validation.valid() ? "VALIDATED" : "REJECTED";
    String hash = checksum(payload);
    db.sql("""
        insert into ouf_installation.installation_configuration_revision
        (installation_id,revision,payload,checksum,validation_state,validation_findings,created_by,created_correlation_id)
        values(:id,:rev,cast(:payload as jsonb),:hash,:state,cast(:findings as jsonb),:actor,:correlation)
        """)
        .param("id", id).param("rev", revision).param("payload", encode(payload))
        .param("hash", hash).param("state", state).param("findings", encode(validation.findings()))
        .param("actor", actor.subject()).param("correlation", actor.correlationId()).update();
    if (validation.valid()) event(id, revision, "VALIDATED", actor);
    return get(id, revision);
  }

  public Revision get(String installationId, long revision) {
    return db.sql("""
        select installation_id,revision,checksum,validation_state,
               validation_findings::text,payload::text
        from ouf_installation.installation_configuration_revision
        where installation_id=:id and revision=:rev
        """).param("id", installationId).param("rev", revision)
        .query((rs, n) -> decodeRow(rs)).optional()
        .orElseThrow(() -> new NoSuchElementException("INSTALLATION_REVISION_NOT_FOUND"));
  }

  public Optional<Active> active(String installationId) {
    return db.sql("""
        select a.installation_id,a.revision,r.checksum
        from ouf_installation.installation_configuration_active a
        join ouf_installation.installation_configuration_revision r
          on r.installation_id=a.installation_id and r.revision=a.revision
        where a.installation_id=:id
        """).param("id", installationId)
        .query((rs,n) -> new Active(rs.getString(1), rs.getLong(2), rs.getString(3))).optional();
  }

  @Transactional
  public EnvironmentValidation validateEnvironment(String installationId, long revision, Actor actor) {
    Revision target = get(installationId, revision);
    if (!"VALIDATED".equals(target.validationState()))
      throw new IllegalStateException("INSTALLATION_REVISION_NOT_VALIDATED");

    List<InstallationEnvironmentProbe.Finding> findings = environmentProbe.inspect(target.payload());
    boolean pass = findings.stream().allMatch(f -> "PASS".equals(f.status()));
    UUID validationId = UUID.randomUUID();
    db.sql("""
        insert into ouf_installation.installation_environment_validation
        (validation_id,installation_id,revision,overall_status,results,checked_by,correlation_id)
        values(:validation,:id,:rev,:status,cast(:results as jsonb),:actor,:correlation)
        """)
        .param("validation", validationId)
        .param("id", installationId)
        .param("rev", revision)
        .param("status", pass ? "PASS" : "FAIL")
        .param("results", encode(json.valueToTree(findings)))
        .param("actor", actor.subject())
        .param("correlation", actor.correlationId())
        .update();
    return environmentValidation(validationId);
  }

  public Optional<EnvironmentValidation> latestEnvironmentValidation(String installationId, long revision) {
    return db.sql("""
        select validation_id,installation_id,revision,overall_status,results::text,correlation_id
        from ouf_installation.installation_environment_validation
        where installation_id=:id and revision=:rev
        order by checked_at desc,validation_id desc
        limit 1
        """)
        .param("id", installationId).param("rev", revision)
        .query((rs,n) -> new EnvironmentValidation(
            rs.getObject(1, UUID.class),
            rs.getString(2),
            rs.getLong(3),
            rs.getString(4),
            readJson(rs.getString(5)),
            rs.getString(6)))
        .optional();
  }

  private EnvironmentValidation environmentValidation(UUID id) {
    return db.sql("""
        select validation_id,installation_id,revision,overall_status,results::text,correlation_id
        from ouf_installation.installation_environment_validation
        where validation_id=:id
        """)
        .param("id", id)
        .query((rs,n) -> new EnvironmentValidation(
            rs.getObject(1, UUID.class),
            rs.getString(2),
            rs.getLong(3),
            rs.getString(4),
            readJson(rs.getString(5)),
            rs.getString(6)))
        .single();
  }

  private JsonNode readJson(String raw) {
    try { return json.readTree(raw); }
    catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("INSTALLATION_EVIDENCE_INVALID", e);
    }
  }

  private boolean environmentPasses(String installationId, long revision) {
    return latestEnvironmentValidation(installationId, revision)
        .map(v -> "PASS".equals(v.overallStatus()))
        .orElse(false);
  }

  @Transactional
  public Active activate(String installationId, long revision, Actor actor) {
    db.sql("select pg_advisory_xact_lock(hashtext(:id))").param("id", installationId).query(Object.class).single();
    Revision target = get(installationId, revision);
    if (!"VALIDATED".equals(target.validationState()))
      throw new IllegalStateException("INSTALLATION_REVISION_NOT_VALIDATED");
    if (revoked(installationId, revision))
      throw new IllegalStateException("INSTALLATION_REVISION_REVOKED");
    if (!environmentPasses(installationId, revision))
      throw new IllegalStateException("INSTALLATION_ENVIRONMENT_VALIDATION_REQUIRED");

    Optional<Active> current = active(installationId);
    boolean rollback = current.isPresent() && revision < current.get().revision();
    current.filter(a -> a.revision() != revision)
        .ifPresent(a -> event(installationId, a.revision(), "SUPERSEDED", actor));

    db.sql("""
        insert into ouf_installation.installation_configuration_active
          (installation_id,revision,activated_by)
        values(:id,:rev,:actor)
        on conflict(installation_id) do update
          set revision=excluded.revision,
              activated_at=transaction_timestamp(),
              activated_by=excluded.activated_by
        """).param("id", installationId).param("rev", revision).param("actor", actor.subject()).update();

    event(installationId, revision, rollback ? "ROLLBACK_ACTIVATED" : "ACTIVATED", actor);
    return active(installationId).orElseThrow();
  }

  @Transactional
  public void revoke(String installationId, long revision, Actor actor) {
    db.sql("select pg_advisory_xact_lock(hashtext(:id))").param("id", installationId).query(Object.class).single();
    get(installationId, revision);
    if (active(installationId).map(a -> a.revision() == revision).orElse(false))
      db.sql("delete from ouf_installation.installation_configuration_active where installation_id=:id")
        .param("id", installationId).update();
    event(installationId, revision, "REVOKED", actor);
  }

  private boolean revoked(String installationId, long revision) {
    return db.sql("""
        select exists(
          select 1 from ouf_installation.installation_configuration_lifecycle_event
          where installation_id=:id and revision=:rev and action='REVOKED')
        """).param("id", installationId).param("rev", revision).query(Boolean.class).single();
  }

  private void event(String installationId, long revision, String action, Actor actor) {
    db.sql("""
        insert into ouf_installation.installation_configuration_lifecycle_event
        (event_id,installation_id,revision,action,actor_subject,correlation_id)
        values(:event,:id,:rev,:action,:actor,:correlation)
        """).param("event", UUID.randomUUID()).param("id", installationId).param("rev", revision)
        .param("action", action).param("actor", actor.subject()).param("correlation", actor.correlationId()).update();
  }
}
