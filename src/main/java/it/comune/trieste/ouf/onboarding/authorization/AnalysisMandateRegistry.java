package it.comune.trieste.ouf.onboarding.authorization;

import static it.comune.trieste.ouf.onboarding.authorization.AnalysisMandate.*;
import static it.comune.trieste.ouf.onboarding.authorization.AnalysisMandate.Error.*;

import it.comune.trieste.ouf.onboarding.authorization.AnalysisMandateRights.Evidence;
import it.comune.trieste.ouf.onboarding.authorization.AnalysisMandateRights.Snapshot;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "ouf.authorization.analysis-mandate.enabled", havingValue = "true")
public class AnalysisMandateRegistry {
  private static final String TABLE = "ouf_authorization.analysis_mandate";
  private static final String ABSOLUTE_DEADLINE = "ABSOLUTE_DEADLINE";
  private final JdbcClient db;
  private final TransactionTemplate transaction;
  private final AnalysisMandateSettings settings;
  private final AnalysisMandateRights rights;
  private final CanonicalHash hashes;
  private final Actor gateway;

  AnalysisMandateRegistry(JdbcClient db, PlatformTransactionManager transactions,
                          AnalysisMandateSettings settings, AnalysisMandateRights rights, CanonicalHash hashes) {
    this.db = db;
    this.transaction = new TransactionTemplate(transactions);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setTimeout(5);
    this.settings = settings;
    this.rights = rights;
    this.hashes = hashes;
    this.gateway = new Actor("SERVICE", null, null, "gateway");
  }

  public View issue(Issue request) {
    request.validate();
    var binding = request.binding();
    var event = new Event(null, request.generation(), binding.analysisId(), binding.policyEpoch(),
        null, request.issueKey());
    String issueHash = hashes.of(List.of(binding, request.generation(), request.expectedVersion(),
        request.previousMandateRef() == null ? "" : request.previousMandateRef())).substring(7);

    RightsCheck evidence = null;
    if (request.proof() != null && installed(binding) && byKey(request, false) == null) {
      evidence = acquire(() -> rights.fetch(binding, request.expectedVersion(), request.generation(), "issue", null, null));
    }
    var check = evidence;
    return audited(event, () -> {
      require(installed(binding), MANDATE_BINDING_MISMATCH);
      // The analysis may not have a row yet. Serialize issuance before consulting the latest generation.
      lockAnalysis(binding.analysisId());
      var replay = byKey(request, true);
      if (replay != null) return replay(event, replay, issueHash);

      var previous = latest(binding.analysisId());
      validateGeneration(request, binding, previous);
      var proof = request.proof();
      require(proof != null, HUMAN_PROOF_REQUIRED);
      validateProof(proof, now());
      event.actor = binding.holder();
      if (previous != null) {
        require(proof.authenticatedAtMs() > Math.max(previous.authenticatedAtMs(), previous.issuedAtMs()),
            REAUTHENTICATION_REQUIRED);
      }
      require(check != null, CURRENT_RIGHTS_UNAVAILABLE);
      var currentEvidence = check.get();
      currentEvidence.check(now(), settings.clockSkewMs());
      var snapshot = AnalysisMandateRights.snapshot(currentEvidence, hashes);
      requirePolicy(binding, snapshot);
      long issueTime = now();
      currentEvidence.check(issueTime, settings.clockSkewMs());
      validateProof(proof, issueTime);
      var created = insert(request, binding, snapshot, issueHash, issueTime);
      if (created == null) {
        // A concurrent request on a different analysis can commit the same issuance key.
        var winner = byKey(request, true);
        require(winner != null, ISSUE_KEY_CONFLICT);
        return replay(event, winner, issueHash);
      }
      event.row = created;
      audit(event, "ISSUED", previous == null ? "VERIFIED_BATCH_PLAN" : "VERIFIED_REAUTHENTICATION");
      return created.view();
    });
  }

  public View admit(Admit request) {
    request.validate();
    var event = new Event(request.mandateRef(), request.generation(), request.analysisId(), request.policyEpoch(),
        operation(request.operation()), request.correlationId());
    Row preview = find(request.mandateRef(), false);
    RightsCheck check = preview != null && installed(preview.binding()) && matchesAdmit(request, preview)
        && preview.view().version() == request.expectedVersion() && active(preview)
        && preview.binding().policyEpoch().equals(request.policyEpoch())
        && preview.operations().contains(request.operation())
        ? acquire(() -> rights.fetch(preview.binding(), request.expectedVersion(), request.generation(),
            "dispatch", request, null)) : null;
    return audited(event, () -> {
      var row = locked(request, event);
      require(installed(row.binding()) && matchesAdmit(request, row), MANDATE_BINDING_MISMATCH);
      event.actor = row.binding().holder();
      denyIfTerminal(row);
      require(row.view().version() == request.expectedVersion(), MANDATE_VERSION_CHANGED);
      require(row.operations().contains(request.operation()), OPERATION_DENIED);
      require(row.binding().policyEpoch().equals(request.policyEpoch()), POLICY_EPOCH_CHANGED);
      require(check != null, CURRENT_RIGHTS_UNAVAILABLE);
      var evidence = check.get();
      long verifiedNow = evidence.check(now(), settings.clockSkewMs());
      denyIfTerminal(expireAtDeadline(event, row, verifiedNow));
      var snapshot = AnalysisMandateRights.snapshot(evidence, hashes);
      requirePolicy(row.binding(), snapshot);
      require(row.policyBundleHash().equals(snapshot.policyBundleHash()), POLICY_CHANGED);
      require(row.rightsHash().equals(snapshot.rightsHash()), RIGHTS_CHANGED);
      verifiedNow = evidence.check(now(), settings.clockSkewMs());
      denyIfTerminal(expireAtDeadline(event, row, verifiedNow));
      audit(event, "ADMITTED", "CURRENT_RIGHTS_VERIFIED");
      return row.view();
    });
  }

  public View revoke(Revoke request, String bearer) {
    request.validate();
    var event = new Event(request.mandateRef(), request.generation(), null, null, null, request.correlationId());
    Row preview = bearer == null ? null : find(request.mandateRef(), false);
    RightsCheck check = preview != null && preview.view().generation() == request.generation()
        ? acquire(() -> rights.fetch(preview.binding(), request.expectedVersion(), request.generation(),
            "revoke", null, bearer)) : null;
    return audited(event, () -> {
      require(bearer != null, HUMAN_AUTHENTICATION_REQUIRED);
      var row = locked(request, event);
      require(check != null, CURRENT_RIGHTS_UNAVAILABLE);
      var proof = check.get();
      proof.check(null, settings.clockSkewMs());
      event.actor = revoker(row.binding(), proof);
      require(row.view().version() == request.expectedVersion() && active(row), MANDATE_VERSION_CHANGED);
      return terminate(event, row, "REVOKED", "HUMAN_REVOCATION").view();
    });
  }

  public View expire(Expire request) {
    request.validate();
    var event = new Event(request.mandateRef(), request.generation(), request.analysisId(), null,
        null, request.correlationId());
    return audited(event, () -> {
      boolean closesAnalysis = closed(request.reason());
      // Serialize closure with issuance even when the request targets an older generation.
      if (closesAnalysis) lockAnalysis(request.analysisId());
      var row = locked(request, event);
      require(row.binding().analysisId().equals(request.analysisId()), MANDATE_BINDING_MISMATCH);
      if (!active(row)) {
        if (closesAnalysis && "REVOKED".equals(row.view().state()) && !closed(row.terminalReason())) {
          recordRevokedClosure(event, row, request.reason());
        }
        return row.view();
      }
      require(row.view().version() == request.expectedVersion(), MANDATE_VERSION_CHANGED);
      if (ABSOLUTE_DEADLINE.equals(request.reason())) {
        require(now() >= row.view().expiresAtMs(), DEADLINE_NOT_REACHED);
      }
      return terminate(event, row, "EXPIRED", request.reason()).view();
    });
  }

  public View read(Read request) {
    request.validate();
    var event = new Event(request.mandateRef(), request.generation(), null, null, null, null);
    return audited(event, () -> expireAtDeadline(event, locked(request, event), now()).view());
  }

  private View audited(Event event, Supplier<View> work) {
    View result = transaction.execute(status -> {
      db.sql("set local lock_timeout = '2s'").update();
      require(db.sql("select not pg_is_in_recovery() and current_setting('transaction_read_only') = 'off'")
          .query(Boolean.class).single(), REGISTRY_UNAVAILABLE);
      try {
        return work.get();
      } catch (Failure rejection) {
        // Return the rejection through the transaction so both denial and any expiry commit.
        event.failure = rejection;
        audit(event, "DENIED", rejection.error.name());
        return null;
      }
    });
    if (event.failure != null) throw event.failure;
    return result;
  }

  private void validateGeneration(Issue request, Binding binding, Row previous) {
    if (previous == null) {
      require(request.generation() == 1, MANDATE_GENERATION_CHANGED);
      return;
    }
    require(previous.view().generation() == request.generation() - 1, MANDATE_GENERATION_CHANGED);
    require(previous.binding().samePlan(binding) && previous.view().mandateRef().equals(request.previousMandateRef()),
        MANDATE_BINDING_MISMATCH);
    require(previous.view().version() == request.expectedVersion(), MANDATE_VERSION_CHANGED);
    require(!active(previous) && !analysisClosed(binding.analysisId()), MANDATE_NOT_RENEWABLE);
  }

  private void validateProof(Proof proof, long now) {
    require("HUMAN".equals(proof.actorType()) && "BATCH_V1".equals(proof.mode())
        && "FIRST_ROUND".equals(proof.phase()) && proof.authenticatedAtMs() > 0
        && proof.authenticatedAtMs() <= proof.observedAtMs() && proof.observedAtMs() > 0
        && proof.validUntilMs() > proof.observedAtMs() && proof.validUntilMs() - proof.observedAtMs() <= EVIDENCE_MS
        && proof.observedAtMs() <= now + settings.clockSkewMs() && now < proof.validUntilMs(), HUMAN_PROOF_INVALID);
  }

  private boolean installed(Binding binding) {
    return settings.issuer().equals(binding.issuer()) && settings.mcpWorkload().equals(binding.workload());
  }

  private void lockAnalysis(String analysisId) {
    db.sql("select pg_advisory_xact_lock(hashtextextended(:key, 104))")
        .param("key", settings.installation() + ":" + analysisId).query((rs, n) -> true).single();
  }

  private boolean analysisClosed(String analysisId) {
    return db.sql("""
        select exists (select 1 from ouf_authorization.analysis_mandate
          where installation=:installation and analysis_id=:analysis
            and terminal_reason in ('FIRST_ROUND_COMPLETE', 'CLOSED', 'CANCELLED'))
        """).param("installation", settings.installation()).param("analysis", analysisId)
        .query(Boolean.class).single();
  }

  private void requirePolicy(Binding binding, Snapshot snapshot) {
    require(binding.policyEpoch().equals(snapshot.policyEpoch()), POLICY_EPOCH_CHANGED);
    require(binding.scope().equals(snapshot.scope()) && snapshot.scope().contains("ouf.semantic.search")
        && snapshot.scope().contains("ouf.semantic.discovery"), RIGHTS_CHANGED);
    String activeHash = db.sql("""
        select b.content_hash from ouf_authorization.active_policy_bundle a
        join ouf_authorization.policy_bundle b on (a.bundle_id, a.version) = (b.bundle_id, b.version)
        where a.singleton_key for share of a
        """).query(String.class).optional().orElseThrow(() -> new Failure(CURRENT_RIGHTS_UNAVAILABLE));
    require(activeHash.equals("sha256:" + snapshot.policyBundleHash()), POLICY_CHANGED);
  }

  private Actor revoker(Binding binding, Evidence proof) {
    var value = proof.value();
    String issuer = AnalysisMandateRights.text(value, "issuer");
    String tenant = AnalysisMandateRights.text(value, "tenant");
    String subject = AnalysisMandateRights.text(value, "subject");
    String client = AnalysisMandateRights.text(value, "client");
    require(value.path("active").booleanValue() && value.path("allowed").booleanValue()
        && "HUMAN".equals(AnalysisMandateRights.text(value, "actorType"))
        && binding.issuer().equals(issuer) && binding.tenant().equals(tenant)
        && identity(subject) && identity(client)
        && (binding.subject().equals(subject)
            || (settings.thsClient().equals(client) && value.path("operatorAuthorized").isBoolean()
                && value.path("operatorAuthorized").booleanValue())), REVOCATION_DENIED);
    return new Actor("HUMAN", issuer, tenant, subject);
  }

  private static boolean matchesAdmit(Admit request, Row row) {
    return row.view().generation() == request.generation() && row.binding().analysisId().equals(request.analysisId())
        && row.binding().workload().equals(request.workload());
  }

  private Row locked(Target request, Event event) {
    var row = find(request.mandateRef(), true);
    require(row != null, MANDATE_NOT_FOUND);
    require(row.view().generation() == request.generation(), MANDATE_GENERATION_CHANGED);
    event.row = row;
    return row;
  }

  private View replay(Event event, Row row, String issueHash) {
    require(row.issueHash().equals(issueHash), ISSUE_KEY_CONFLICT);
    event.row = row;
    return expireAtDeadline(event, row, now()).view();
  }

  private static boolean active(Row row) { return "ACTIVE".equals(row.view().state()); }

  private static void denyIfTerminal(Row row) {
    if (!active(row)) throw new Failure("REVOKED".equals(row.view().state()) ? MANDATE_REVOKED : MANDATE_EXPIRED);
  }

  private Row expireAtDeadline(Event event, Row row, long now) {
    return active(row) && now >= row.view().expiresAtMs()
        ? terminate(event, row, "EXPIRED", ABSOLUTE_DEADLINE) : row;
  }

  private Row terminate(Event event, Row row, String state, String reason) {
    var changed = db.sql("""
        update ouf_authorization.analysis_mandate set state=:state, version=version+1, terminal_reason=:reason
        where mandate_ref=:ref and version=:version and state='ACTIVE' returning *
        """).param("state", state).param("reason", reason)
        .param("ref", id(row.view().mandateRef())).param("version", row.view().version())
        .query(AnalysisMandateRegistry::map).single();
    event.row = changed;
    audit(event, state, reason);
    return changed;
  }

  private void recordRevokedClosure(Event event, Row row, String reason) {
    // The row is locked. Keep the revocation state and version while recording permanent closure.
    db.sql("""
        update ouf_authorization.analysis_mandate set terminal_reason=:reason where mandate_ref=:ref
        """).param("reason", reason).param("ref", id(row.view().mandateRef())).update();
    audit(event, "EXPIRED", reason);
  }

  private long now() {
    return db.sql("select floor(extract(epoch from clock_timestamp()) * 1000)::bigint").query(Long.class).single();
  }

  private Row find(String ref, boolean lock) {
    return db.sql("select * from " + TABLE + " where installation=:installation and mandate_ref=:ref"
            + (lock ? " for update" : ""))
        .param("installation", settings.installation()).param("ref", id(ref))
        .query(AnalysisMandateRegistry::map).optional().orElse(null);
  }

  private Row latest(String analysisId) {
    return db.sql("select * from " + TABLE
            + " where installation=:installation and analysis_id=:analysis order by generation desc limit 1 for update")
        .param("installation", settings.installation()).param("analysis", analysisId)
        .query(AnalysisMandateRegistry::map).optional().orElse(null);
  }

  private Row byKey(Issue request, boolean lock) {
    return db.sql("select * from " + TABLE + """
         where installation=:installation and issuer=:issuer and tenant=:tenant and subject_id=:subject
           and issue_key=:issueKey
        """ + (lock ? " for update" : ""))
        .param("installation", settings.installation()).param("issuer", request.issuer())
        .param("tenant", request.tenant()).param("subject", request.subject()).param("issueKey", id(request.issueKey()))
        .query(AnalysisMandateRegistry::map).optional().orElse(null);
  }

  private Row insert(Issue request, Binding binding, Snapshot snapshot, String issueHash, long now) {
    return db.sql("""
        insert into ouf_authorization.analysis_mandate (
          mandate_ref, installation, analysis_id, asset_id, profile_id, schema_hash, issuer, tenant, subject_id,
          client_id, mcp_workload, operations, policy_epoch, policy_bundle_hash, rights_hash, scopes, start_ref,
          command_key, issue_key, issue_hash, generation, state, version, authenticated_at_ms, issued_at_ms, expires_at_ms)
        values (:ref, :installation, :analysis, :asset, :profile, :schemaHash, :issuer, :tenant, :subject,
          :client, :workload, string_to_array(:operations, ' '), :epoch, :bundleHash, :rightsHash,
          string_to_array(:scopes, ' '), :startRef, :commandKey, :issueKey, :issueHash, :generation, 'ACTIVE', 1,
          :authenticatedAt, :issuedAt, :expiresAt)
        on conflict (installation, issuer, tenant, subject_id, issue_key) do nothing returning *
        """).param("ref", UUID.randomUUID()).param("installation", settings.installation())
        .param("analysis", binding.analysisId()).param("asset", id(binding.assetId()))
        .param("profile", id(binding.profileId())).param("schemaHash", binding.schemaHash())
        .param("issuer", binding.issuer()).param("tenant", binding.tenant()).param("subject", binding.subject())
        .param("client", binding.client()).param("workload", binding.workload())
        .param("operations", String.join(" ", OPERATIONS)).param("epoch", binding.policyEpoch())
        .param("bundleHash", snapshot.policyBundleHash()).param("rightsHash", snapshot.rightsHash())
        .param("scopes", String.join(" ", binding.scope())).param("startRef", id(binding.startRef()))
        .param("commandKey", id(binding.commandKey())).param("issueKey", id(request.issueKey()))
        .param("issueHash", issueHash).param("generation", request.generation())
        .param("authenticatedAt", request.proof().authenticatedAtMs()).param("issuedAt", now)
        .param("expiresAt", now + TTL_MS).query(AnalysisMandateRegistry::map).optional().orElse(null);
  }

  private static Row map(ResultSet rs, int n) throws SQLException {
    var view = new View(rs.getString("mandate_ref"), rs.getLong("generation"), rs.getLong("version"),
        rs.getString("state"), rs.getString("analysis_id"), rs.getLong("expires_at_ms"));
    var binding = new Binding(view.analysisId(), rs.getString("asset_id"), rs.getString("profile_id"),
        rs.getString("schema_hash"), rs.getString("issuer"), rs.getString("tenant"), rs.getString("subject_id"),
        rs.getString("client_id"), rs.getString("mcp_workload"), rs.getString("start_ref"), rs.getString("command_key"),
        rs.getString("policy_epoch"), strings(rs, "scopes"));
    return new Row(view, binding, rs.getString("issue_hash"), rs.getString("rights_hash"),
        rs.getString("policy_bundle_hash"), strings(rs, "operations"), rs.getLong("authenticated_at_ms"),
        rs.getLong("issued_at_ms"), rs.getString("terminal_reason"));
  }

  private static List<String> strings(ResultSet rs, String column) throws SQLException {
    var array = rs.getArray(column);
    try {
      return List.copyOf(Arrays.asList((String[]) array.getArray()));
    } finally {
      array.free();
    }
  }

  private void audit(Event event, String outcome, String reason) {
    var row = event.row;
    var actor = event.actor;
    db.sql("""
        insert into ouf_authorization.analysis_mandate_audit (
          installation, mandate_ref, analysis_id, generation, policy_epoch, actor_type, actor_issuer,
          actor_tenant, actor_id, operation, correlation_id, outcome, reason)
        values (:installation,:ref,:analysis,:generation,:epoch,:actorType,:actorIssuer,:actorTenant,:actor,
          :operation,:correlation,:outcome,:reason)
        """).param("installation", settings.installation())
        .param("ref", id(row == null ? event.ref : row.view().mandateRef()))
        .param("analysis", row == null ? event.analysis : row.binding().analysisId())
        .param("generation", event.generation).param("epoch", row == null ? event.epoch : row.binding().policyEpoch())
        .param("actorType", actor.type()).param("actorIssuer", actor.issuer()).param("actorTenant", actor.tenant())
        .param("actor", actor.id()).param("operation", event.operation).param("correlation", id(event.correlation))
        .param("outcome", outcome).param("reason", reason).update();
  }

  private static RightsCheck acquire(Supplier<Evidence> request) {
    try {
      return new RightsCheck(request.get(), null);
    } catch (Failure failure) {
      return new RightsCheck(null, failure);
    }
  }

  private record RightsCheck(Evidence value, Failure failure) {
    Evidence get() {
      if (failure != null) throw failure;
      return value;
    }
  }

  private final class Event {
    final String ref;
    final long generation;
    final String analysis;
    final String epoch;
    final String operation;
    final String correlation;
    Actor actor = gateway;
    Row row;
    Failure failure;

    Event(String ref, long generation, String analysis, String epoch, String operation, String correlation) {
      this.ref = ref;
      this.generation = generation;
      this.analysis = analysis;
      this.epoch = epoch;
      this.operation = operation;
      this.correlation = correlation;
    }
  }
}
