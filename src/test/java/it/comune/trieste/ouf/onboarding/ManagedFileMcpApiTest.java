package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.api.ManagedFileMcpApi;
import it.comune.trieste.ouf.onboarding.api.PermissionDelegationVerifier;
import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ManagedFileMcpApiTest {
  @Test void noSignedHumanReceiptCannotReachManagedAsset(){
    var files=mock(ManagedFileService.class);
    var receipts=new PermissionDelegationVerifier(new ObjectMapper(),"","https://iam.example","gateway","ouf-mcp-server");
    var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/managed-file-mcp/profile");
    var api=new ManagedFileMcpApi(receipts,files,new ObjectMapper());
    byte[] raw="{\"Arguments\":{\"assetId\":\"00000000-0000-4000-8000-000000000001\"}}".getBytes();
    assertThatThrownBy(()->api.profile(raw,request)).isInstanceOf(SecurityException.class);
    verifyNoInteractions(files);
  }
}
