package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import it.comune.trieste.ouf.authorization.ServletAuthorization;
import it.comune.trieste.ouf.authorization.TestAuthorization;
import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import it.comune.trieste.ouf.onboarding.application.OnboardingService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ManagedFileNativeRegistrationTenantTest {
  @Test void registrationUsesOnlyTenantFromVerifiedPrincipal(){
    var request=new MockHttpServletRequest();request.setAttribute(ServletAuthorization.TRUSTED_PRINCIPAL,TestAuthorization.principal("alice","HUMAN",Set.of("ouf.managed-source.file.upload")));
    var files=mock(ManagedFileService.class);var actors=mock(TrustedActorResolver.class);var asset=UUID.randomUUID();
    when(actors.requireHuman(request,"ouf.managed-source.file.upload")).thenReturn(new OnboardingService.Actor("alice","HUMAN_USER"));
    var input=new ManagedFileApi.RegisterRequest(null,"object://fixture/original","sha256:"+"a".repeat(64),"text/csv",17,"retention://30d");
    when(files.registerOwned(null,input.stagingRef(),input.contentHash(),"text/csv",17,"alice","retention://30d","tenant-a")).thenReturn(Map.of("asset_id",asset));
    assertThat(new ManagedFileApi(files,actors).register(input,request).getStatusCode().value()).isEqualTo(201);
    verify(files).registerOwned(null,input.stagingRef(),input.contentHash(),"text/csv",17,"alice","retention://30d","tenant-a");
  }
}
