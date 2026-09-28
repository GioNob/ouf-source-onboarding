package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Keep a reviewed identity proposal inactive until the UDP runtime supports it. */
final class ResolutionActivationGate {
  private ResolutionActivationGate() {}

  static void requireExecutable(Map<String,Object> configuration) {
    Object extraction = configuration.get("extractionProfile");
    if (!(extraction instanceof Map<?,?> ex)) return;
    Object runtime = ex.get("runtime");
    if (!(runtime instanceof Map<?,?> rt)) return;
    Object udp = rt.get("udp");
    if (!(udp instanceof Map<?,?> profile)) return;
    Object resolution = profile.get("resolution");
    if (resolution instanceof Map<?,?> rule && rule.containsKey("weighted"))
      throw new DomainFailure(HttpStatus.CONFLICT,"ONB_UDP_IDENTITY_RUNTIME_UNAVAILABLE",
          "UDP weighted identity runtime is unavailable; this resolution cannot be activated");
  }
}
