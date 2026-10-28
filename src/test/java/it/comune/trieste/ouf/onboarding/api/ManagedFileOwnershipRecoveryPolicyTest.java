package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.onboarding.application.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ManagedFileOwnershipRecoveryPolicyTest {
  final ManagedProfileRecoveryPolicyApiTest f=new ManagedProfileRecoveryPolicyApiTest();
  ManagedFileOwnershipRecovery owner(){var o=mock(ManagedFileOwnershipRecovery.class);when(o.resource(eq(f.asset),eq(f.job),any())).thenReturn(f.resource(f.job));return o;}
  @Test void bothExactJobCapabilitiesAreEvaluatedBeforeLegacyMetadataOrWrites()throws Exception{
    var r=f.request(List.of(f.grant(f.profile,f.job),f.grant(f.release,f.job)));var o=owner();var api=new ManagedFileOwnershipRecoveryApi(o,new TrustedActorResolver());
    when(o.preview(eq(f.asset),eq(f.job),any(),any())).thenReturn(Map.of("status","OWNER_BINDING_REVIEW_REQUIRED"));
    assertThat(api.card(f.asset,f.job,r).getHeaders().getCacheControl()).contains("no-store");verify(o).preview(eq(f.asset),eq(f.job),any(),argThat(a->a.capabilities().containsAll(Set.of(f.profile,f.release))));
  }
  @Test void missingPermissionOrOtherJobGrantCannotPreviewOrRestore()throws Exception{
    for(var grants:List.of(List.of(f.grant(f.profile,f.job)),List.of(f.grant(f.profile,UUID.randomUUID()),f.grant(f.release,UUID.randomUUID())))){
      var r=f.request(grants);var o=owner();var api=new ManagedFileOwnershipRecoveryApi(o,new TrustedActorResolver());
      assertThatThrownBy(()->api.card(f.asset,f.job,r)).isInstanceOf(RuntimeException.class);
      assertThatThrownBy(()->api.decide(f.asset,f.job,null,r)).isInstanceOf(RuntimeException.class);
      verify(o,never()).preview(any(),any(),any(),any());verify(o,never()).reconcile(any(),any(),any(),any(),any());
    }
  }
  @Test void noStatelessWriteProofCannotReachLegacyOwner()throws Exception{
    var r=f.request(List.of(f.grant(f.profile,f.job),f.grant(f.release,f.job)));var o=owner();var fresh=new org.springframework.mock.web.MockHttpServletRequest();
    assertThatThrownBy(()->new ManagedFileOwnershipRecoveryApi(o,new TrustedActorResolver()).decide(f.asset,f.job,null,fresh)).hasMessageContaining("WRITE_PROOF");verifyNoInteractions(o);
  }
}
