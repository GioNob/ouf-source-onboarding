package it.comune.trieste.ouf.onboarding.api;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.application.ManagedOnboardingReviewService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ManagedOnboardingReviewApiTest {
  @Test void authenticatedEnvelopeStillRequiresExplicitVersionAndClosedTypedArguments()throws Exception{
    var receipts=mock(PermissionDelegationVerifier.class);var service=mock(ManagedOnboardingReviewService.class);
    when(receipts.verifyManagedFile(any(),anyString(),anyString(),any())).thenReturn(new PermissionDelegationVerifier.Delegated(null,"test-key"));
    var json=new ObjectMapper();var api=new ManagedOnboardingReviewApi(receipts,service,json);
    var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/managed-file-mcp/review-prepare");
    var args=new LinkedHashMap<String,Object>(Map.of("sourceId","test","onboardingVersionId",UUID.randomUUID().toString(),"commandKey","test-key"));
    assertThatThrownBy(()->api.prepare(json.writeValueAsBytes(Map.of("Arguments",args)),request)).isInstanceOf(IllegalArgumentException.class);
    args.put("expectedVersion","0");assertThatThrownBy(()->api.prepare(json.writeValueAsBytes(Map.of("Arguments",args)),request)).isInstanceOf(IllegalArgumentException.class);
    args.put("expectedVersion",0);args.put("executionProposal",Map.of("allowAutoApproval",true));
    assertThatThrownBy(()->api.prepare(json.writeValueAsBytes(Map.of("Arguments",args)),request)).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(service);
  }

  @Test void signedPrepareCarriesExplicitCandidateActionAndKeepsHistoricalFingerprints()throws Exception{
    var json=new ObjectMapper();
    record Original(String sourceId,UUID onboardingVersionId,long expectedVersion,String commandKey,
        it.comune.trieste.ouf.onboarding.application.ManagedFileExecutionProposal.Proposal executionProposal){}
    var version=UUID.randomUUID();
    assertThat(json.writeValueAsBytes(new ManagedOnboardingReviewService.Prepare("s",version,1,"key",null)))
        .isEqualTo(json.writeValueAsBytes(new Original("s",version,1,"key",null)));
    var receipts=mock(PermissionDelegationVerifier.class);var service=mock(ManagedOnboardingReviewService.class);
    var api=new ManagedOnboardingReviewApi(receipts,service,json);
    var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/managed-file-mcp/review-prepare");
    var command=new ManagedOnboardingReviewService.Prepare("s",version,1,"key",null,"WITHDRAW");
    byte[] body=json.writeValueAsBytes(Map.of("Arguments",command));
    when(receipts.verifyManagedFile(null,request.getRequestURI(),ManagedOnboardingReviewService.PREPARE,body))
        .thenReturn(new PermissionDelegationVerifier.Delegated(null,"key"));
    api.prepare(body,request);
    verify(service).prepare(isNull(),eq(command),anyString());
  }

  @Test void latestViewIsExplicitReadOnlyAndCannotMixAnUntrustedVersion()throws Exception{
    var receipts=mock(PermissionDelegationVerifier.class);var service=mock(ManagedOnboardingReviewService.class);
    when(receipts.verifyManagedFile(any(),anyString(),anyString(),any())).thenReturn(new PermissionDelegationVerifier.Delegated(null,"test-key"));
    var json=new ObjectMapper();var api=new ManagedOnboardingReviewApi(receipts,service,json);
    var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/managed-file-mcp/review-read");
    when(service.readLatest(null,"test")).thenReturn(Map.of("state","DRAFT"));
    assertThat(api.read(json.writeValueAsBytes(Map.of("Arguments",Map.of("sourceId","test","view","LATEST"))),request)).containsEntry("state","DRAFT");
    verify(service).readLatest(null,"test");clearInvocations(service);
    for(var args:List.of(Map.of("sourceId","test","view","LATEST","onboardingVersionId",UUID.randomUUID().toString()),
        Map.of("sourceId","test","view","AUTO"),Map.of("sourceId",42,"view","LATEST"))){
      byte[] raw=json.writeValueAsBytes(Map.of("Arguments",args));assertThatThrownBy(()->api.read(raw,request)).isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(service);
  }

  @Test void task50bV01ConditionalReadAcceptsBothReferencesAndRejectsInvalidValidators()throws Exception{
    var receipts=mock(PermissionDelegationVerifier.class);
    var service=mock(ManagedOnboardingReviewService.class);
    when(receipts.verifyManagedFile(any(),anyString(),anyString(),any()))
        .thenReturn(new PermissionDelegationVerifier.Delegated(null,"key"));
    var json=new ObjectMapper();
    var api=new ManagedOnboardingReviewApi(receipts,service,json);
    var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/managed-file-mcp/review-read");
    var version=UUID.randomUUID();
    String validator="sha256:"+"a".repeat(64);
    for(var reference:List.of(Map.of("onboardingVersionId",version.toString()),Map.of("view","LATEST"))){
      var args=new LinkedHashMap<String,Object>(reference);
      args.put("sourceId","test");
      args.put("ifObservationVersion",validator);
      api.read(json.writeValueAsBytes(Map.of("Arguments",args)),request);
      for(Object malformed:Arrays.asList(1, true, "sha256:"+"A".repeat(64), null)){
        args.put("ifObservationVersion",malformed);
        byte[] raw=json.writeValueAsBytes(Map.of("Arguments",args));
        assertThatThrownBy(()->api.read(raw,request)).isInstanceOf(IllegalArgumentException.class);
      }
    }
    verify(service).read(null,"test",version,validator);
    verify(service).readLatest(null,"test",validator);
    verifyNoMoreInteractions(service);
  }

  @Test void task50bC04ManagedMcpErrorPreservesItsCodeAndHasClosedActions()throws Exception{
    var receipts=mock(PermissionDelegationVerifier.class);
    var service=mock(ManagedOnboardingReviewService.class);
    when(receipts.verifyManagedFile(any(),anyString(),anyString(),any()))
        .thenReturn(new PermissionDelegationVerifier.Delegated(null,"key"));
    var http=MockMvcBuilders.standaloneSetup(new ManagedOnboardingReviewApi(receipts,service,new ObjectMapper()))
        .setControllerAdvice(new ManagedMcpErrorObservation()).build();
    http.perform(post("/api/internal/v1/onboarding/managed-file-mcp/review-read")
        .contentType(MediaType.APPLICATION_JSON).content("{\"Arguments\":{\"sourceId\":\"s\",\"view\":\"INVALID\"}}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("ONB_REVIEW_ARGUMENTS_INVALID"))
        .andExpect(jsonPath("$.workflowState").value("ERROR"))
        .andExpect(jsonPath("$.reasonCode").value("OWNER_ERROR"))
        .andExpect(jsonPath("$.allowedActions[0]").value("NONE"));
  }

}
