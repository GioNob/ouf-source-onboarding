package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.api.ManagedFileMcpApi;
import it.comune.trieste.ouf.onboarding.api.PermissionDelegationVerifier;
import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
  @Test void jobStatusExposesOnlyBoundedFieldsAndChecksAssetOwner(){
    var files=mock(ManagedFileService.class);
    var receipts=mock(PermissionDelegationVerifier.class);
    UUID asset=UUID.randomUUID(),job=UUID.randomUUID();
    byte[] raw=("{\"Arguments\":{\"assetId\":\""+asset+"\",\"jobId\":\""+job+"\"}}").getBytes();
    var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/managed-file-mcp/preview");
    var principal=new PrincipalContext("human:alice","tenant-a",PrincipalContext.ActorType.HUMAN,"ouf-mcp-server","1",
        "https://iam.example","gateway",Set.of("ouf.managed-source.preview"),
        new PrincipalContext.IdentityClaims(Set.of(),"1",Set.of(),null));
    when(receipts.verifyManagedFile(null,request.getRequestURI(),"ouf.managed-source.preview",raw))
        .thenReturn(new PermissionDelegationVerifier.Delegated(principal,"idem"));
    when(files.profileJob(asset,job)).thenReturn(Map.of("jobId",job,"status","SUCCEEDED","resultRef",UUID.randomUUID(),
        "staging_ref","object://private/secret","claimed_by","worker"));
    var result=new ManagedFileMcpApi(receipts,files,new ObjectMapper()).preview(raw,request);
    verify(files).requireOwner(asset,"human:alice");
    assertThat(result).containsEntry("jobId",job).containsEntry("status","SUCCEEDED");
    assertThat(result).doesNotContainKeys("staging_ref","claimed_by");
  }
}
