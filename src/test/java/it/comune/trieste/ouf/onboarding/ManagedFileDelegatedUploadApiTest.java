package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.onboarding.api.ManagedFileDelegatedUploadApi;
import it.comune.trieste.ouf.onboarding.api.PermissionDelegationVerifier;
import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import it.comune.trieste.ouf.onboarding.application.ManagedFileStagingStore;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ManagedFileDelegatedUploadApiTest {
  private static final String PATH="/api/internal/v1/onboarding/managed-file-mcp/upload";

  @Test void hashesExactBytesBeforeStorageAndOnlyReturnsSafeAssetIdentity() throws Exception {
    byte[] csv="\uFEFFcinema,indirizzo\r\nA,Trieste\r\n".getBytes(StandardCharsets.UTF_8);
    var hash=new CanonicalHash(new ObjectMapper()).ofBytes(csv);
    var verifier=mock(PermissionDelegationVerifier.class);
    var files=mock(ManagedFileService.class);var storage=mock(ManagedFileStagingStore.class);
    var principal=new PrincipalContext("human:operator","tenant-a",PrincipalContext.ActorType.HUMAN,"ouf-mcp-server","1","issuer","audience",Set.of("ouf.managed-source.file.upload"),null);
    when(verifier.verifyManagedUpload(eq("signed"),eq(PATH))).thenReturn(new PermissionDelegationVerifier.DelegatedUpload(principal,"once","file_123",hash,csv.length));
    var request=new MockHttpServletRequest("POST",PATH);request.setContent(csv);
    request.addHeader("X-OUF-Managed-File-Receipt","signed");
    UUID asset=UUID.randomUUID();
    when(files.registerDelegatedUpload(eq("tenant-a"),eq("human:operator"),eq("file_123"),eq("once"),eq(hash),eq((long)csv.length),any()))
        .thenAnswer(invocation->{
          @SuppressWarnings("unchecked") Supplier<String> put=invocation.getArgument(6);
          when(storage.put(any(),eq((long)csv.length),eq("text/csv"))).thenAnswer(call->{
            assertThat(((java.io.InputStream)call.getArgument(0)).readAllBytes()).isEqualTo(csv);
            return "object://managed-files/91f68ba6-4849-451b-a3d7-5856d685e6d8";
          });
          assertThat(put.get()).startsWith("object://managed-files/");
          return Map.of("assetId",asset,"status","STAGED");
        });
    var api=new ManagedFileDelegatedUploadApi(verifier,files,storage);
    var response=api.upload(request);
    assertThat(response.getStatusCode().value()).isEqualTo(201);
    assertThat(response.getBody()).containsOnlyKeys("assetId","status");
    assertThat(response.getBody().get("assetId")).isEqualTo(asset);
  }

  @Test void checksumAndSizeMismatchCannotReachStorageOrRegistration() throws Exception {
    byte[] csv="cinema\nA\n".getBytes(StandardCharsets.UTF_8);
    var verifier=mock(PermissionDelegationVerifier.class);var files=mock(ManagedFileService.class);var storage=mock(ManagedFileStagingStore.class);
    var principal=new PrincipalContext("human:operator","tenant-a",PrincipalContext.ActorType.HUMAN,"ouf-mcp-server","1","issuer","audience",Set.of("ouf.managed-source.file.upload"),null);
    var api=new ManagedFileDelegatedUploadApi(verifier,files,storage);
    for(long length:new long[]{csv.length,csv.length+1}){
      var request=new MockHttpServletRequest("POST",PATH);request.setContent(csv);
      when(verifier.verifyManagedUpload(isNull(),eq(PATH))).thenReturn(new PermissionDelegationVerifier.DelegatedUpload(principal,"once","file_123","sha256:"+"0".repeat(64),length));
      assertThatThrownBy(()->api.upload(request)).isInstanceOf(DomainFailure.class);
    }
    verifyNoInteractions(files,storage);
  }
}
