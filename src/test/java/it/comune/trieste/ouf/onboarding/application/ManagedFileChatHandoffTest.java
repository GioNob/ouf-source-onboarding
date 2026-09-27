package it.comune.trieste.ouf.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManagedFileChatHandoffTest {
  @Test void resultIsBoundToAssetOwnerAndCannotBeReassigned() {
    var files = mock(ManagedFileService.class);
    var handoffs = new ManagedFileChatHandoff(files);
    UUID id = UUID.randomUUID(), asset = UUID.randomUUID();
    assertThat(handoffs.result(id, "human:alice")).containsEntry("status", "PENDING");
    handoffs.complete(id, asset, "human:alice");
    assertThat(handoffs.result(id, "human:alice"))
        .containsEntry("assetId", asset).containsEntry("status", "STAGED");
    verify(files, times(2)).requireOwner(asset, "human:alice");
    assertThatThrownBy(() -> handoffs.result(id, "human:bob")).isInstanceOf(SecurityException.class);
    verify(files, never()).requireOwner(asset, "human:bob");
    assertThatThrownBy(() -> handoffs.complete(id, UUID.randomUUID(), "human:bob"))
        .isInstanceOf(SecurityException.class);
  }
}
