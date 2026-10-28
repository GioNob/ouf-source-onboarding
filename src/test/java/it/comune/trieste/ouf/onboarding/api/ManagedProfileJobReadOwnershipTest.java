package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.TestAuthorization;
import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class ManagedProfileJobReadOwnershipTest {
  @Test void delegatedJobReadStillRequiresDurableTenantAndSubjectOwnershipBeforeStateSerialization()throws Exception{
    UUID asset=UUID.randomUUID(),job=UUID.randomUUID();var files=mock(ManagedFileService.class);var receipts=mock(PermissionDelegationVerifier.class);
    var principal=TestAuthorization.principal("alice","HUMAN",Set.of("ouf.managed-source.preview")).context();
    when(receipts.verifyManagedFile(any(),any(),eq("ouf.managed-source.preview"),any())).thenReturn(new PermissionDelegationVerifier.Delegated(principal,"read-key"));
    var api=new ManagedFileMcpApi(receipts,files,new ObjectMapper(),mock(ManagedFileChatHandoff.class));
    var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/managed-file-mcp/preview");
    byte[] raw=new ObjectMapper().writeValueAsBytes(Map.of("Arguments",Map.of("assetId",asset.toString(),"jobId",job.toString())));
    doThrow(new DomainFailure(HttpStatus.FORBIDDEN,"ONB_FILE_OWNER_SCOPE_UNPROVEN","Managed file owner scope is not proven")).when(files).requireTenantOwner(asset,"tenant-a","alice");
    assertThatThrownBy(()->api.preview(raw,request)).hasMessageContaining("owner scope");
    verify(files,never()).profileJob(any(),any());verify(files,never()).preview(any(),any());
  }
}
