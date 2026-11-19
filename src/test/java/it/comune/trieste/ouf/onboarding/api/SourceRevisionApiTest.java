package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.application.SourceRevisionService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SourceRevisionApiTest {
  @Test void signedCommandsStillRequireClosedArgumentsAndAnExplicitParentCas()throws Exception{
    var receipts=mock(PermissionDelegationVerifier.class);var service=mock(SourceRevisionService.class);
    when(receipts.verifySourceRevision(any(),anyString(),anyString(),any())).thenReturn(new PermissionDelegationVerifier.Delegated(null,"key"));
    var json=new ObjectMapper();var api=new SourceRevisionApi(receipts,service,json);
    var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/source-revision-mcp/prepare");
    var args=new LinkedHashMap<String,Object>(Map.of("sourceId","s","parentVersionId",UUID.randomUUID().toString(),"commandKey","key","configurationHash","sha256:"+"a".repeat(64),"publicationEffect",SourceRevisionService.EFFECT,"changes",List.of()));
    assertThatThrownBy(()->api.prepare(json.writeValueAsBytes(Map.of("Arguments",args)),request)).isInstanceOf(IllegalArgumentException.class);
    args.put("expectedVersion","0");assertThatThrownBy(()->api.prepare(json.writeValueAsBytes(Map.of("Arguments",args)),request)).isInstanceOf(IllegalArgumentException.class);
    args.put("expectedVersion",0);args.put("approvedBy","caller");assertThatThrownBy(()->api.prepare(json.writeValueAsBytes(Map.of("Arguments",args)),request)).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(service);
  }
  @Test void readHasExactlyOneSelectorAndCommandReconciliationDoesNotNeedTheLostVersionId()throws Exception{
    var receipts=mock(PermissionDelegationVerifier.class);var service=mock(SourceRevisionService.class);
    when(receipts.verifySourceRevision(any(),anyString(),anyString(),any())).thenReturn(new PermissionDelegationVerifier.Delegated(null,"key"));
    var json=new ObjectMapper();var api=new SourceRevisionApi(receipts,service,json);var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/source-revision-mcp/read");
    UUID parent=UUID.randomUUID();var args=new LinkedHashMap<String,Object>(Map.of("sourceId","s","parentVersionId",parent.toString(),"commandKey","key"));
    api.read(json.writeValueAsBytes(Map.of("Arguments",args)),request);verify(service).readCommand(null,"s",parent,"key");
    args.put("onboardingVersionId",UUID.randomUUID().toString());assertThatThrownBy(()->api.read(json.writeValueAsBytes(Map.of("Arguments",args)),request)).isInstanceOf(IllegalArgumentException.class);
    verifyNoMoreInteractions(service);
  }
}
