package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import it.comune.trieste.ouf.onboarding.api.ManagedFileApi;
import it.comune.trieste.ouf.onboarding.api.TrustedActorResolver;
import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import it.comune.trieste.ouf.onboarding.application.OnboardingService;

class ManagedFileAuthorizationTest {
  @Test void profileCannotBeQueuedWithoutItsHumanCapability(){
    var files=mock(ManagedFileService.class);
    var actors=mock(TrustedActorResolver.class);
    var request=new MockHttpServletRequest();
    var asset=UUID.randomUUID();
    when(actors.requireHuman(request,"ouf.managed-source.file.profile"))
        .thenThrow(new DomainFailure(HttpStatus.FORBIDDEN,"ONB_CAPABILITY_DENIED","Required capability"));
    var api=new ManagedFileApi(files,actors);
    assertThatThrownBy(()->api.requestProfile(asset,"key",request))
        .isInstanceOf(DomainFailure.class).hasMessageContaining("Required capability");
    verifyNoInteractions(files);
  }

  @Test void previewRequiresItsOwnHumanCapability(){
    var files=mock(ManagedFileService.class);
    var actors=mock(TrustedActorResolver.class);
    var request=new MockHttpServletRequest();
    UUID asset=UUID.randomUUID(),profile=UUID.randomUUID();
    when(actors.requireHuman(request,"ouf.managed-source.preview"))
        .thenThrow(new DomainFailure(HttpStatus.FORBIDDEN,"ONB_CAPABILITY_DENIED","Required capability"));
    var api=new ManagedFileApi(files,actors);
    assertThatThrownBy(()->api.preview(asset,profile,request)).isInstanceOf(DomainFailure.class);
    verifyNoInteractions(files);
  }

  @Test void assetPreviewDoesNotExposeStorageReferenceOrUploadedBy(){
    var files=mock(ManagedFileService.class);
    var actors=mock(TrustedActorResolver.class);
    var request=new MockHttpServletRequest();
    var asset=UUID.randomUUID();
    when(actors.requireHuman(request,"ouf.managed-source.preview"))
        .thenReturn(new OnboardingService.Actor("human:operator","HUMAN_USER",Set.of("ouf.managed-source.preview")));
    when(files.asset(asset)).thenReturn(Map.of("asset_id",asset,"status","STAGED","media_type","text/csv",
        "size_bytes",509,"staging_ref","object://managed-files/private","content_hash","sha256:private",
        "uploaded_by","human:operator"));
    var view=new ManagedFileApi(files,actors).asset(asset,request);
    assertThat(view).containsEntry("assetId",asset).containsEntry("sizeBytes",509);
    assertThat(view).doesNotContainKeys("staging_ref","content_hash","uploaded_by");
  }
}
