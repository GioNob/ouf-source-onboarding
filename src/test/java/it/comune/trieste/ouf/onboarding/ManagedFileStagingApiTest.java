package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.api.ManagedFileStagingApi;
import it.comune.trieste.ouf.onboarding.api.TrustedActorResolver;
import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import it.comune.trieste.ouf.onboarding.application.ManagedFileStagingStore;
import it.comune.trieste.ouf.onboarding.application.OnboardingService;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ManagedFileStagingApiTest {
  @Test void uploadsOriginalBomBytesAndRegistersExactHash() throws Exception {
    byte[] csv="\uFEFFcinema,indirizzo\r\nA,Trieste\r\n".getBytes(StandardCharsets.UTF_8);
    var request=new MockHttpServletRequest();request.setContent(csv);
    var store=mock(ManagedFileStagingStore.class);var files=mock(ManagedFileService.class);
    var actors=mock(TrustedActorResolver.class);var hashes=new CanonicalHash(new ObjectMapper());
    var actor=new OnboardingService.Actor("human:operator","HUMAN_USER",Set.of("ouf.managed-source.file.upload"));
    when(actors.requireHuman(request,"ouf.managed-source.file.upload")).thenReturn(actor);
    when(store.put(any(InputStream.class),eq((long)csv.length),eq("text/csv"))).thenAnswer(invocation->{
      assertThat(((InputStream)invocation.getArgument(0)).readAllBytes()).isEqualTo(csv);
      return "object://managed-files/91f68ba6-4849-451b-a3d7-5856d685e6d8";
    });
    UUID asset=UUID.randomUUID();when(files.register(eq(null),anyString(),anyString(),eq("text/csv"),eq((long)csv.length),eq("human:operator"),anyString())).thenReturn(Map.of("asset_id",asset));
    var api=new ManagedFileStagingApi(store,files,hashes,actors);
    var response=api.upload(request,"sha256:"+hashes.ofBytes(csv).substring(7));
    assertThat(response.getStatusCode().value()).isEqualTo(201);
    verify(files).register(null,"object://managed-files/91f68ba6-4849-451b-a3d7-5856d685e6d8",hashes.ofBytes(csv),"text/csv",csv.length,"human:operator","retention://managed-files/30d");
  }

  @Test void mismatchedChecksumCannotWriteToMinio() throws Exception {
    var request=new MockHttpServletRequest();request.setContent("id\n1\n".getBytes(StandardCharsets.UTF_8));
    var store=mock(ManagedFileStagingStore.class);var files=mock(ManagedFileService.class);
    var actors=mock(TrustedActorResolver.class);
    when(actors.requireHuman(request,"ouf.managed-source.file.upload"))
        .thenReturn(new OnboardingService.Actor("human:operator","HUMAN_USER",Set.of("ouf.managed-source.file.upload")));
    var api=new ManagedFileStagingApi(store,files,new CanonicalHash(new ObjectMapper()),actors);
    assertThatThrownBy(()->api.upload(request,"sha256:"+"0".repeat(64))).isInstanceOf(DomainFailure.class).hasMessageContaining("checksum");
    verifyNoInteractions(store,files);
  }

  @Test void oversizedStreamIsRejectedBeforeStorageWrite() throws Exception {
    var request=new MockHttpServletRequest();request.setContent(new byte[10*1024*1024+1]);
    var store=mock(ManagedFileStagingStore.class);var files=mock(ManagedFileService.class);
    var actors=mock(TrustedActorResolver.class);
    when(actors.requireHuman(request,"ouf.managed-source.file.upload"))
        .thenReturn(new OnboardingService.Actor("human:operator","HUMAN_USER",Set.of("ouf.managed-source.file.upload")));
    var api=new ManagedFileStagingApi(store,files,new CanonicalHash(new ObjectMapper()),actors);
    assertThatThrownBy(()->api.upload(request,null)).isInstanceOf(DomainFailure.class).hasMessageContaining("10 MiB");
    verifyNoInteractions(store,files);
  }
}
