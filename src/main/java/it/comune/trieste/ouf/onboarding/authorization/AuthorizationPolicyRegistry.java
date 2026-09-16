package it.comune.trieste.ouf.onboarding.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.AuthorizationDecision;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.PolicyBundle;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AuthorizationPolicyRegistry {
  private final JdbcClient db;
  private final ObjectMapper json;
  private final CanonicalHash canonicalHash;

  public AuthorizationPolicyRegistry(JdbcClient db, ObjectMapper json, CanonicalHash canonicalHash) {
    this.db = db;
    this.json = json;
    this.canonicalHash = canonicalHash;
  }

  @Transactional
  public PublishedBundle publish(PolicyBundle bundle, String publishedBy) {
    require(publishedBy, "publishedBy");
    try {
      String payload = json.writeValueAsString(bundle);
      String hash = canonicalHash.of(bundle);
      int inserted = db.sql("""
          insert into ouf_authorization.policy_bundle(bundle_id,version,published_at,published_by,content_hash,bundle_payload)
          values(:id,:version,:publishedAt,:publishedBy,:hash,cast(:payload as jsonb))
          on conflict(bundle_id,version) do nothing
          """)
          .param("id", bundle.bundleId())
          .param("version", bundle.version())
          .param("publishedAt", Timestamp.from(bundle.publishedAt()))
          .param("publishedBy", publishedBy)
          .param("hash", hash)
          .param("payload", payload)
          .update();
      if (inserted == 0) {
        String existing = db.sql("select content_hash from ouf_authorization.policy_bundle where bundle_id=:id and version=:version")
            .param("id", bundle.bundleId()).param("version", bundle.version()).query(String.class).single();
        if (!hash.equals(existing)) {
          throw new IllegalStateException("policy bundle version already exists with different content");
        }
      }
      return new PublishedBundle(bundle.bundleId(), bundle.version(), hash, inserted == 1);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("policy bundle serialization failed", e);
    }
  }

  @Transactional
  public ActiveBundle activate(String bundleId, long version) {
    require(bundleId, "bundleId");
    db.sql("select 1 from ouf_authorization.policy_bundle where bundle_id=:id and version=:version")
        .param("id", bundleId).param("version", version).query(Integer.class).single();
    db.sql("""
        insert into ouf_authorization.active_policy_bundle(singleton_key,bundle_id,version,activated_at)
        values(true,:id,:version,transaction_timestamp())
        on conflict(singleton_key) do update set bundle_id=excluded.bundle_id,version=excluded.version,activated_at=excluded.activated_at
        """)
        .param("id", bundleId).param("version", version).update();
    return active().orElseThrow();
  }

  @Transactional
  public ActiveBundle publishAndActivate(PolicyBundle bundle, String publishedBy) {
    publish(bundle, publishedBy);
    return activate(bundle.bundleId(), bundle.version());
  }

  public Optional<ActiveBundle> active() {
    return db.sql("select bundle_id,version,activated_at from ouf_authorization.active_policy_bundle where singleton_key=true")
        .query((rs, rowNum) -> new ActiveBundle(rs.getString("bundle_id"), rs.getLong("version"), rs.getTimestamp("activated_at").toInstant()))
        .optional();
  }

  public PolicyBundle load(String bundleId, long version) {
    String payload = db.sql("select bundle_payload::text from ouf_authorization.policy_bundle where bundle_id=:id and version=:version")
        .param("id", bundleId).param("version", version).query(String.class).single();
    try {
      return json.readValue(payload, PolicyBundle.class);
    } catch (Exception e) {
      throw new IllegalStateException("stored policy bundle decode failed", e);
    }
  }

  @Transactional
  public AuthorizationDecision authorizeActive(
      PrincipalContext principal,
      ResourceContext resource,
      String capabilityId,
      String operation,
      Instant now) {
    ActiveBundle pointer = active().orElseThrow(() -> new IllegalStateException("no active policy bundle"));
    PolicyBundle bundle = load(pointer.bundleId(), pointer.version());
    AuthorizationDecision decision = AuthorizationPolicy.evaluate(bundle, principal, resource, capabilityId, operation, now);
    db.sql("""
        insert into ouf_authorization.authorization_decision_audit(
          decision_id,decision_ref,bundle_id,bundle_version,tenant_id,subject_id,service_principal_id,
          capability_id,operation,allowed,decision_code,authentication_context_ref,decided_at)
        values(:decisionId,:decisionRef,:bundleId,:bundleVersion,:tenantId,:subjectId,:servicePrincipalId,
          :capabilityId,:operation,:allowed,:decisionCode,:authenticationContextRef,:decidedAt)
        """)
        .param("decisionId", UUID.randomUUID())
        .param("decisionRef", decision.decisionRef())
        .param("bundleId", decision.bundleId())
        .param("bundleVersion", decision.bundleVersion())
        .param("tenantId", principal.tenantId())
        .param("subjectId", principal.subjectId())
        .param("servicePrincipalId", principal.servicePrincipalId())
        .param("capabilityId", capabilityId)
        .param("operation", operation)
        .param("allowed", decision.allowed())
        .param("decisionCode", decision.decisionCode())
        .param("authenticationContextRef", principal.authenticationContextRef())
        .param("decidedAt", Timestamp.from(now))
        .update();
    return decision;
  }

  public long decisionAuditCount(String tenantId) {
    return db.sql("select count(*) from ouf_authorization.authorization_decision_audit where tenant_id=:tenant")
        .param("tenant", tenantId).query(Long.class).single();
  }

  public Map<String, Object> activeMetadata() {
    return active().<Map<String, Object>>map(a -> Map.of(
        "bundleId", a.bundleId(),
        "version", a.version(),
        "activatedAt", a.activatedAt())).orElseGet(Map::of);
  }

  public record PublishedBundle(String bundleId, long version, String contentHash, boolean inserted) {}
  public record ActiveBundle(String bundleId, long version, Instant activatedAt) {}

  private static String require(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value;
  }
}
