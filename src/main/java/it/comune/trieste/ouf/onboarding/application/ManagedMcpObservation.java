package it.comune.trieste.ouf.onboarding.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Closed owner vocabulary. Actions describe eligibility on the observed version. */
public final class ManagedMcpObservation {
  public enum State {
    QUEUED, RUNNING, RETRY_WAIT, SUCCEEDED, FAILED, CANCELLED,
    ASSETS, PROFILE_AVAILABLE, PROFILE_COLUMNS, PENDING, STAGED, PROFILED, QUARANTINED, COMMAND_ABSENT, ERROR,
    DRAFT, IN_REVIEW, APPROVED, ACTIVE, SUPERSEDED, REJECTED, REVOKED, CHANGES_REQUESTED, EXPIRED
  }

  public enum Reason {
    PROFILE_PENDING, PROFILE_READY, PROFILE_FAILED, PROFILE_CANCELLED,
    ASSET_LIST, COLUMN_PAGE, UPLOAD_PENDING, UPLOAD_STAGED, UPLOAD_REJECTED, UPLOAD_QUARANTINED, WAIT_DEADLINE_EXPIRED,
    DRAFT_READY, HUMAN_DECISION_PENDING, CHALLENGE_EXPIRED, REVIEW_UNAVAILABLE,
    HUMAN_ACTIVATION_REQUIRED, READINESS_IN_PROGRESS, READINESS_BACKOFF,
    DEPENDENCY_RECHECK_REQUIRED, READINESS_BUDGET_EXHAUSTED, READINESS_BLOCKED,
    PUBLICATION_ACTIVE, PUBLICATION_SUPERSEDED, PUBLICATION_REVOKED,
    REVIEW_REJECTED, REVIEW_CHANGES_REQUESTED, CANDIDATE_WITHDRAWN,
    REVISION_COMMAND_ABSENT, CURRENT_REVIEW_REQUIRED, OWNER_ERROR, ONB_PUBLICATION_ENVELOPE_TOO_LARGE
  }

  public enum Action {
    READ_AGAIN, READ_DISTINCT_VALUES, PREPARE_REVIEW, WAIT_HUMAN_REVIEW, RENEW_REVIEW,
    HUMAN_ACTIVATE, HUMAN_RECHECK, HUMAN_GRANT_BUDGET, CREATE_REVISION, REDRAFT_SAME_FILE, WITHDRAW_CANDIDATE, NONE
  }

  public static Map<String,Object> with(Map<String,Object> body, State state, Reason reason, Action... actions) {
    var result = new LinkedHashMap<>(body);
    result.put("workflowState", state);
    result.put("reasonCode", reason);
    result.put("allowedActions", actions.length == 0 ? List.of(Action.NONE) : List.of(actions));
    return result;
  }

  public static Map<String,Object> profile(Map<String,Object> body) {
    State state = State.valueOf((String) body.get("status"));
    return switch (state) {
      case QUEUED, RUNNING, RETRY_WAIT -> with(body, state, Reason.PROFILE_PENDING, Action.READ_AGAIN);
      case SUCCEEDED -> preview(body, state, Reason.PROFILE_READY);
      case FAILED -> with(body, state, Reason.PROFILE_FAILED);
      case CANCELLED -> with(body, state, Reason.PROFILE_CANCELLED);
      default -> throw new IllegalStateException("Invalid profile job state");
    };
  }

  public static Map<String,Object> preview(Map<String,Object> body, State state, Reason reason) {
    boolean continuation = incompleteColumns(body.get("columns"));
    if (!continuation && body.get("metadata") instanceof Map<?,?> metadata
        && metadata.get("layers") instanceof List<?> layers) {
      continuation = layers.stream().anyMatch(layer -> layer instanceof Map<?,?> map && incompleteColumns(map.get("columns")));
    }
    return continuation ? with(body, state, reason, Action.READ_AGAIN, Action.READ_DISTINCT_VALUES)
        : with(body, state, reason, Action.READ_AGAIN);
  }

  private static boolean incompleteColumns(Object columns) {
    return columns instanceof List<?> list && list.stream().anyMatch(column -> column instanceof Map<?,?> map
        && Boolean.FALSE.equals(map.get("distinctValuesComplete")));
  }

  public static Map<String,Object> handoff(Map<String,Object> body) {
    State state = State.valueOf((String) body.get("status"));
    if (state == State.PENDING && Boolean.TRUE.equals(body.get("legacy"))) return body;
    return switch (state) {
      case EXPIRED -> with(body, state, Reason.WAIT_DEADLINE_EXPIRED);
      case PENDING -> with(body, state, Reason.UPLOAD_PENDING, Action.READ_AGAIN);
      case STAGED -> with(body, state, Reason.UPLOAD_STAGED);
      case PROFILED -> with(body, state, Reason.PROFILE_READY, Action.READ_AGAIN);
      case QUARANTINED -> with(body, state, Reason.UPLOAD_QUARANTINED);
      case REJECTED -> with(body, state, Reason.UPLOAD_REJECTED);
      default -> throw new IllegalStateException("Invalid upload handoff state");
    };
  }

  public static boolean draftState(Map<String,Object> version) {
    return "DRAFT".equals(version.get("state"));
  }

  public static Map<String,Object> created(Map<String,Object> body) {
    if (draftState(body)) return version(body, false);
    // An idempotent create replay returns only the identity, even after the review advances:
    // the current state is not known here, so the only allowed action is to read the version again.
    if (!(body.get("state") instanceof String observed)) {
      var result = with(body, null, Reason.CURRENT_REVIEW_REQUIRED, Action.READ_AGAIN);
      result.remove("workflowState");
      return result;
    }
    return with(body, State.valueOf(observed), Reason.CURRENT_REVIEW_REQUIRED, Action.READ_AGAIN);
  }

  public static Map<String,Object> version(Map<String,Object> body, boolean revisionAllowed) {
    State state = State.valueOf((String) body.get("state"));
    if (body.containsKey("publicationEnvelopeBlock"))
      return with(body, state, Reason.ONB_PUBLICATION_ENVELOPE_TOO_LARGE, Action.WITHDRAW_CANDIDATE);
    Action revision = revisionAllowed ? Action.CREATE_REVISION : Action.NONE;
    return switch (state) {
      case DRAFT -> with(body, state, Reason.DRAFT_READY, Action.PREPARE_REVIEW);
      case IN_REVIEW -> review(body, state);
      case APPROVED -> activation(body, state);
      case ACTIVE -> with(body, state, Reason.PUBLICATION_ACTIVE, revision);
      case SUPERSEDED -> with(body, state, Reason.PUBLICATION_SUPERSEDED);
      case REVOKED -> with(body, state, Reason.PUBLICATION_REVOKED);
      case REJECTED -> refusal(body, state, "WITHDRAWN".equals(body.get("candidateDisposition"))
          ? Reason.CANDIDATE_WITHDRAWN : Reason.REVIEW_REJECTED, revisionAllowed);
      case CHANGES_REQUESTED -> refusal(body, state, Reason.REVIEW_CHANGES_REQUESTED, revisionAllowed);
      default -> throw new IllegalStateException("Invalid onboarding version state");
    };
  }

  private static Map<String,Object> refusal(Map<String,Object> body, State state, Reason reason, boolean revisionAllowed) {
    var actions = new java.util.ArrayList<Action>();
    if (Boolean.TRUE.equals(body.get("sameFileRedraftAllowed"))) actions.add(Action.REDRAFT_SAME_FILE);
    if (revisionAllowed) actions.add(Action.CREATE_REVISION);
    return with(body, state, reason, actions.toArray(Action[]::new));
  }

  private static Map<String,Object> review(Map<String,Object> body, State state) {
    if (Boolean.TRUE.equals(body.get("challengeRenewable")))
      return with(body, state, Reason.CHALLENGE_EXPIRED, Action.RENEW_REVIEW);
    if (Boolean.TRUE.equals(body.get("humanReviewPending")))
      return with(body, state, Reason.HUMAN_DECISION_PENDING, Action.WAIT_HUMAN_REVIEW, Action.READ_AGAIN);
    return with(body, state, Reason.REVIEW_UNAVAILABLE);
  }

  private static Map<String,Object> activation(Map<String,Object> body, State state) {
    if (!(body.get("activationProgress") instanceof Map<?,?> progress))
      return with(body, state, Reason.REVIEW_UNAVAILABLE);
    if (Boolean.TRUE.equals(progress.get("humanRecheckAllowed")))
      return with(body, state, Reason.DEPENDENCY_RECHECK_REQUIRED, Action.HUMAN_RECHECK);
    if (Boolean.TRUE.equals(progress.get("budgetExhausted")))
      return with(body, state, Reason.READINESS_BUDGET_EXHAUSTED,
          Boolean.TRUE.equals(progress.get("humanBudgetGrantAvailable")) ? Action.HUMAN_GRANT_BUDGET : Action.NONE);
    if (Boolean.TRUE.equals(progress.get("leased")))
      return with(body, state, Reason.READINESS_IN_PROGRESS, Action.READ_AGAIN);
    if (Boolean.TRUE.equals(progress.get("delayed")))
      return with(body, state, Reason.READINESS_BACKOFF, Action.READ_AGAIN);
    if (Boolean.TRUE.equals(progress.get("humanActivationAllowed")))
      return with(body, state, Reason.HUMAN_ACTIVATION_REQUIRED, Action.HUMAN_ACTIVATE);
    return with(body, state, Reason.READINESS_BLOCKED);
  }

  private ManagedMcpObservation() {}
}
