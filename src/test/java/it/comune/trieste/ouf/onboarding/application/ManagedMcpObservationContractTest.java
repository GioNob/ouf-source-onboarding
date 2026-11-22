package it.comune.trieste.ouf.onboarding.application;

import static it.comune.trieste.ouf.onboarding.application.ManagedMcpObservation.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManagedMcpObservationContractTest {
  private final ObjectMapper json=new ObjectMapper();

  @Test void task50bC01PublishedEnumsMatchExactlyAndEveryValueIsDocumented()throws Exception{
    JsonNode defs=json.readTree(Path.of("contracts/onboarding-managed-mcp-observation-v1.json").toFile()).path("$defs");
    verifyVocabulary(defs.path("state"),State.values());
    verifyVocabulary(defs.path("reason"),Reason.values());
    verifyVocabulary(defs.path("action"),Action.values());
  }

  private void verifyVocabulary(JsonNode definition,Enum<?>[] expected){
    var values=new ArrayList<String>();
    definition.path("enum").forEach(value->values.add(value.textValue()));
    assertThat(values).containsExactlyInAnyOrderElementsOf(Arrays.stream(expected).map(Enum::name).toList());
    for(String value:values)assertThat(definition.path("x-ouf-semantics").path(value).asText()).isNotBlank();
  }

  @Test void task50bC02AllPersistedVersionAndProfileStatesHaveClosedWireObservations(){
    var versions=Map.of(
        "DRAFT",Action.PREPARE_REVIEW,"IN_REVIEW",Action.NONE,"APPROVED",Action.NONE,
        "ACTIVE",Action.CREATE_REVISION,"SUPERSEDED",Action.NONE,"REJECTED",Action.CREATE_REVISION,
        "REVOKED",Action.NONE,"CHANGES_REQUESTED",Action.CREATE_REVISION);
    versions.forEach((state,action)->assertThat(json.valueToTree(version(Map.of("state",state),true)).toString())
        .contains("\"workflowState\":\""+state+"\"","\"allowedActions\":[\""+action+"\"]"));
    var jobs=Map.of("QUEUED",Action.READ_AGAIN,"RUNNING",Action.READ_AGAIN,"RETRY_WAIT",Action.READ_AGAIN,
        "SUCCEEDED",Action.READ_AGAIN,"FAILED",Action.NONE,"CANCELLED",Action.NONE);
    jobs.forEach((state,action)->assertThat(profile(Map.of("status",state)))
        .containsEntry("workflowState",State.valueOf(state)).containsEntry("allowedActions",List.of(action)));
    assertThat(created(Map.of("state","APPROVED"))).containsEntry("reasonCode",Reason.CURRENT_REVIEW_REQUIRED)
        .containsEntry("allowedActions",List.of(Action.READ_AGAIN));
    assertThatThrownBy(()->profile(Map.of("status","FUTURE"))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void task50bC03ActivationActionUsesTheOwnerEligibilityFlags(){
    assertActivation(Map.of("humanRecheckAllowed",true),Reason.DEPENDENCY_RECHECK_REQUIRED,Action.HUMAN_RECHECK);
    assertActivation(Map.of("budgetExhausted",true,"humanBudgetGrantAvailable",true),
        Reason.READINESS_BUDGET_EXHAUSTED,Action.HUMAN_GRANT_BUDGET);
    assertActivation(Map.of("budgetExhausted",true),Reason.READINESS_BUDGET_EXHAUSTED,Action.NONE);
    assertActivation(Map.of("leased",true),Reason.READINESS_IN_PROGRESS,Action.READ_AGAIN);
    assertActivation(Map.of("delayed",true),Reason.READINESS_BACKOFF,Action.READ_AGAIN);
    assertActivation(Map.of("humanActivationAllowed",true),Reason.HUMAN_ACTIVATION_REQUIRED,Action.HUMAN_ACTIVATE);
    assertActivation(Map.of(),Reason.READINESS_BLOCKED,Action.NONE);
    assertThat(version(Map.of("state","ACTIVE"),false)).containsEntry("allowedActions",List.of(Action.NONE));
    assertThat(version(Map.of("state","CHANGES_REQUESTED","sameFileRedraftAllowed",true),false))
        .containsEntry("allowedActions",List.of(Action.REDRAFT_SAME_FILE));
  }

  private void assertActivation(Map<String,Boolean> flags,Reason reason,Action action){
    assertThat(version(Map.of("state","APPROVED","activationProgress",flags),false))
        .containsEntry("reasonCode",reason).containsEntry("allowedActions",List.of(action));
  }
}
