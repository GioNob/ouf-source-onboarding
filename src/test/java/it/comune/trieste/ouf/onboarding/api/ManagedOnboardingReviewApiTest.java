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
}
