package it.comune.trieste.ouf.onboarding.authorization;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public final class AnalysisMandate {
  private AnalysisMandate() {}

  static final List<String> OPERATIONS = List.of("semantic.search.batch", "semantic.discovery.request",
      "semantic.discovery.status", "semantic.discovery.candidates");
  static final long TTL_MS = 86_400_000;
  static final long EVIDENCE_MS = 30_000;
  private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
  private static final Pattern UUID_TEXT = Pattern.compile("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
  private static final Pattern IDENTITY = Pattern.compile("[!-~]{1,512}");
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_:./-]{1,128}");
  private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9._:-]{1,256}");

  static boolean hash(String value) { return value != null && HASH.matcher(value).matches(); }
  static boolean uuid(String value) { return value != null && UUID_TEXT.matcher(value).matches(); }
  static boolean identity(String value) { return value != null && IDENTITY.matcher(value).matches(); }
  static boolean token(String value) { return value != null && TOKEN.matcher(value).matches(); }
  static boolean reference(String value) { return value != null && REFERENCE.matcher(value).matches(); }
  static UUID id(String value) { return value == null ? null : UUID.fromString(value); }
  static boolean uuid4(String value) { return uuid(value) && id(value).version() == 4 && id(value).variant() == 2; }
  static boolean validTokens(List<String> values) {
    return values != null && values.size() <= 32 && values.stream().allMatch(AnalysisMandate::token)
        && new HashSet<>(values).size() == values.size();
  }
  static boolean validScopes(List<String> values) {
    return values != null && !values.isEmpty() && values.size() <= 2048
        && values.stream().allMatch(AnalysisMandate::identity)
        && values.stream().mapToInt(String::length).sum() + values.size() - 1 <= 4096
        && new HashSet<>(values).size() == values.size();
  }
  static List<String> sorted(List<String> values) { return values.stream().sorted().toList(); }
  static String operation(String name) { return name != null && OPERATIONS.contains(name) ? name : null; }
  static boolean jobRead(String name) {
    return "semantic.discovery.status".equals(name) || "semantic.discovery.candidates".equals(name);
  }
  static boolean closed(String reason) {
    return "CLOSED".equals(reason) || "CANCELLED".equals(reason) || "FIRST_ROUND_COMPLETE".equals(reason);
  }
  static void require(boolean condition, Error error) {
    if (!condition) throw new Failure(error);
  }

  public enum Error {
    INVALID_REQUEST(400), GATEWAY_AUTHENTICATION_REQUIRED(401), HUMAN_AUTHENTICATION_REQUIRED(401),
    GATEWAY_WORKLOAD_DENIED(403), MANDATE_BINDING_MISMATCH(403), HUMAN_PROOF_REQUIRED(403),
    HUMAN_PROOF_INVALID(403), REAUTHENTICATION_REQUIRED(403), OPERATION_DENIED(403),
    PRINCIPAL_INACTIVE(403), CURRENT_RIGHTS_DENIED(403), RIGHTS_CHANGED(403),
    POLICY_EPOCH_CHANGED(403), POLICY_CHANGED(403), MANDATE_REVOKED(403), MANDATE_EXPIRED(403),
    REVOCATION_DENIED(403), MANDATE_NOT_FOUND(404), ISSUE_KEY_CONFLICT(409),
    MANDATE_VERSION_CHANGED(409), MANDATE_GENERATION_CHANGED(409), MANDATE_NOT_RENEWABLE(409),
    DEADLINE_NOT_REACHED(409), CURRENT_RIGHTS_UNAVAILABLE(503), RIGHTS_EVIDENCE_INVALID(503),
    CLOCK_UNVERIFIED(503), REGISTRY_UNAVAILABLE(503);

    public final int status;
    Error(int status) { this.status = status; }
  }

  public static final class Failure extends RuntimeException {
    public final Error error;
    public final String retryAfter;

    Failure(Error error) { this(error, null); }
    Failure(Error error, String retryAfter) {
      super(error.name());
      this.error = error;
      this.retryAfter = retryAfter;
    }
  }

  public record View(String mandateRef, long generation, long version, String state, String analysisId,
                     long expiresAtMs) {}

  public record Issue(String analysisId, String assetId, String profileId, String schemaHash, String issuer,
                      String tenant, String subject, String client, String workload, String startRef,
                      String commandKey, String issueKey, String policyEpoch, List<String> scope,
                      long generation, Long expectedVersion, String previousMandateRef, Proof proof) {
    void validate() {
      require(hash(analysisId) && uuid(assetId) && uuid(profileId) && hash(schemaHash)
          && identity(issuer) && identity(tenant) && identity(subject) && identity(client) && identity(workload)
          && uuid4(startRef) && uuid4(commandKey) && uuid4(issueKey) && hash(policyEpoch) && validScopes(scope)
          && generation > 0 && expectedVersion != null, Error.INVALID_REQUEST);
      if (generation == 1) {
        require(expectedVersion == 0 && previousMandateRef == null && issueKey.equals(commandKey),
            Error.INVALID_REQUEST);
      } else {
        require(expectedVersion > 0 && uuid4(previousMandateRef) && !issueKey.equals(commandKey),
            Error.INVALID_REQUEST);
      }
    }

    Binding binding() {
      return new Binding(analysisId, assetId, profileId, schemaHash, issuer, tenant, subject, client,
          workload, startRef, commandKey, policyEpoch, sorted(scope));
    }
  }

  public record Proof(String actorType, String mode, String phase, long authenticatedAtMs,
                      long observedAtMs, long validUntilMs) {}

  public interface Target {
    String mandateRef();
    long generation();
    default void validate() {
      require(uuid4(mandateRef()) && generation() > 0, Error.INVALID_REQUEST);
    }
  }

  public record Read(String mandateRef, long generation) implements Target {}

  public record Admit(String mandateRef, long generation, long expectedVersion, String analysisId,
                      String workload, String policyEpoch, String operation, String argumentsHash,
                      String attemptId, String correlationId, String requestId) implements Target {
    @Override public void validate() {
      Target.super.validate();
      require(expectedVersion > 0 && hash(analysisId) && identity(workload) && hash(policyEpoch)
          && token(operation) && hash(argumentsHash) && uuid4(attemptId) && uuid4(correlationId)
          && (jobRead(operation) ? uuid(requestId) : requestId == null), Error.INVALID_REQUEST);
    }
  }

  public record Revoke(String mandateRef, long generation, long expectedVersion, String correlationId)
      implements Target {
    @Override public void validate() {
      Target.super.validate();
      require(expectedVersion > 0 && uuid4(correlationId), Error.INVALID_REQUEST);
    }
  }

  public record Expire(String mandateRef, long generation, long expectedVersion, String analysisId,
                       String reason, String correlationId) implements Target {
    @Override public void validate() {
      Target.super.validate();
      require(expectedVersion > 0 && hash(analysisId) && uuid4(correlationId)
          && (closed(reason) || "ABSOLUTE_DEADLINE".equals(reason)), Error.INVALID_REQUEST);
    }
  }

  record Binding(String analysisId, String assetId, String profileId, String schemaHash, String issuer,
                 String tenant, String subject, String client, String workload, String startRef,
                 String commandKey, String policyEpoch, List<String> scope) {
    boolean samePlan(Binding other) {
      return analysisId.equals(other.analysisId) && assetId.equals(other.assetId) && profileId.equals(other.profileId)
          && schemaHash.equals(other.schemaHash) && issuer.equals(other.issuer) && tenant.equals(other.tenant)
          && subject.equals(other.subject) && client.equals(other.client) && workload.equals(other.workload)
          && startRef.equals(other.startRef) && commandKey.equals(other.commandKey);
    }

    Actor holder() { return new Actor("HUMAN", issuer, tenant, subject); }
  }

  record Actor(String type, String issuer, String tenant, String id) {}

  record Row(View view, Binding binding, String issueHash, String rightsHash, String policyBundleHash,
             List<String> operations, long authenticatedAtMs, long issuedAtMs, String terminalReason) {}
}
