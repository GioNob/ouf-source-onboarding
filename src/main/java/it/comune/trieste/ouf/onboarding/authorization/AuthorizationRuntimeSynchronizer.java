package it.comune.trieste.ouf.onboarding.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import it.comune.trieste.ouf.authorization.LocalAuthorization;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps the owner process pinned to the exact locally governed ACTIVE Authorization bundle. */
@Component
public class AuthorizationRuntimeSynchronizer {
  private final AuthorizationPolicyRegistry registry;
  private final LocalAuthorization runtime;
  private final ObjectMapper json;

  public AuthorizationRuntimeSynchronizer(AuthorizationPolicyRegistry registry, LocalAuthorization runtime, ObjectMapper json) {
    this.registry = registry;
    this.runtime = runtime;
    this.json = json;
  }

  @PostConstruct
  public void initialize() {
    if (registry.active().isPresent()) refreshActive();
  }

  @Scheduled(fixedDelayString = "${ouf.authorization.owner-refresh-ms:60000}")
  public void scheduledRefresh() {
    if (registry.active().isEmpty()) return;
    try {
      refreshActive();
    } catch (RuntimeException e) {
      runtime.refreshFailure("OWNER_ACTIVE_REFRESH_FAILED");
    }
  }

  public synchronized void refreshActive() {
    var active = registry.active().orElseThrow(() -> new IllegalStateException("no active Authorization policy bundle"));
    var bundle = registry.load(active.bundleId(), active.version());
    Path temporary = null;
    try {
      byte[] bytes = json.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsBytes(bundle);
      String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      String registryHash = registry.transportHash(bundle);
      if (!sha256.equals(registryHash)) throw new IllegalStateException("Authorization ACTIVE transport hash mismatch");
      temporary = Files.createTempFile("ouf-authorization-active-", ".json");
      Files.write(temporary, bytes);
      var result = runtime.refresh(new LocalAuthorization.BundleReference(
          temporary, active.bundleId(), active.version(), sha256, 1));
      if (!result.installed()) throw new IllegalStateException("Authorization ACTIVE install failed: " + result.reason());
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Authorization ACTIVE synchronization failed", e);
    } finally {
      if (temporary != null) {
        try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
      }
    }
  }
}
