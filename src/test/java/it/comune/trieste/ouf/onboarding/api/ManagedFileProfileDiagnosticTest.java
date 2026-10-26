package it.comune.trieste.ouf.onboarding.api;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.onboarding.application.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
class ManagedFileProfileDiagnosticTest {
 @Test void fixedCategoriesDistinguishReadAndAnalysisWithoutLeakingEvidence(){
  var examples=Map.of("CSV must be valid UTF-8 text","CSV_ENCODING_INVALID","headers must be non-empty and unique","CSV_HEADERS_INVALID","invalid CSV","CSV_SYNTAX_INVALID","Gateway object read failed with status 401","OBJECT_READ_AUTHENTICATION_REQUIRED","Gateway object read failed with status 403","OBJECT_READ_FORBIDDEN","Staged content hash does not match downloaded content","FILE_HASH_MISMATCH","Onboarding workload token is unavailable","WORKLOAD_CREDENTIAL_UNAVAILABLE");
  examples.forEach((detail,expected)->assertThat(ManagedFileMcpApi.profileFailureCategory(detail)).isEqualTo(expected));
  for(Object unknown:Arrays.asList(null,123,"https://secret.invalid/path?token=PRIVATE_CANARY","invalid CSV PRIVATE_CANARY","x".repeat(513),"Gateway object read failed with status 500"))
   assertThat(ManagedFileMcpApi.profileFailureCategory(unknown)).isEqualTo("UNCLASSIFIED");
 }
 @Test void ownedFailedJobReturnsCategoryButNoRawDetailOrControlReferences(){
  var files=mock(ManagedFileService.class);var receipts=mock(PermissionDelegationVerifier.class);
  UUID asset=UUID.randomUUID(),job=UUID.randomUUID();
  byte[] raw=("{\"Arguments\":{\"assetId\":\""+asset+"\",\"jobId\":\""+job+"\"}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
  var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/managed-file-mcp/preview");
  var p=new PrincipalContext("human:alice","tenant-a",PrincipalContext.ActorType.HUMAN,"ouf-mcp-server","1","https://iam.example","gateway",Set.of("ouf.managed-source.preview"),new PrincipalContext.IdentityClaims(Set.of(),"1",Set.of(),null));
  when(receipts.verifyManagedFile(null,request.getRequestURI(),"ouf.managed-source.preview",raw)).thenReturn(new PermissionDelegationVerifier.Delegated(p,"idem"));
  when(files.profileJob(asset,job)).thenReturn(Map.of("jobId",job,"status","FAILED","errorCode","ONB_FILE_PROFILE_FAILED","last_error","Gateway object read failed with status 401","staging_ref","object://PRIVATE_CANARY","claimed_by","PRIVATE_WORKER"));
  var out=new ManagedFileMcpApi(receipts,files,new ObjectMapper(),mock(ManagedFileChatHandoff.class)).preview(raw,request);
  assertThat(out).containsOnlyKeys("jobId","status","resultRef","errorCode","diagnosticCategory").containsEntry("diagnosticCategory","OBJECT_READ_AUTHENTICATION_REQUIRED");
  assertThat(out.toString()).doesNotContain("PRIVATE_CANARY","PRIVATE_WORKER","Gateway object read");
  var order=inOrder(files);order.verify(files).requireOwner(asset,"human:alice");order.verify(files).profileJob(asset,job);
  doThrow(new SecurityException("owner denied")).when(files).requireOwner(asset,"human:alice");
  clearInvocations(files);
  assertThatThrownBy(()->new ManagedFileMcpApi(receipts,files,new ObjectMapper(),mock(ManagedFileChatHandoff.class)).preview(raw,request)).isInstanceOf(SecurityException.class);
  verify(files,never()).profileJob(any(),any());
 }
}
