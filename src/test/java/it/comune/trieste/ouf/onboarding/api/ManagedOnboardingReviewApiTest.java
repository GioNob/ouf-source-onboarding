package it.comune.trieste.ouf.onboarding.api;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.application.ManagedOnboardingReviewService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

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

}
