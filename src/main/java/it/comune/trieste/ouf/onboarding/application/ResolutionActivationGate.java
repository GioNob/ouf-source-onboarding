package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** Keep a reviewed identity proposal inactive until the UDP runtime supports it. */
final class ResolutionActivationGate {
  private static final Set<String> LEGACY_FIELDS=Set.of("strategyId","strategyVersion","policyRef",
      "canonicalType","canonicalKeyProperty","matchProperty");
  private static final Set<String> GOVERNED_FIELDS=Set.of("strategyId","strategyVersion","policyRef","governedIdentity");
  private ResolutionActivationGate() {}

  static void requireExecutable(Map<String,Object> configuration) {
    requireExecutable(configuration,null,null,null);
  }
  static void requireExecutable(Map<String,Object> configuration,String sourceId,String configurationHash,
      UdpIdentityActivationVerifier verifier) {
    Object extraction = configuration.get("extractionProfile");
    if (!(extraction instanceof Map<?,?> ex)) return;
    Object runtime = ex.get("runtime");
    if (!(runtime instanceof Map<?,?> rt)) return;
    Object udp = rt.get("udp");
    if (!(udp instanceof Map<?,?> profile)) return;
    Object resolution = profile.get("resolution");
    if (!profile.containsKey("resolution")) return;
    if (resolution instanceof Map<?,?> rule && rule.keySet().equals(GOVERNED_FIELDS)
        && "GOVERNED_IDENTITY".equals(rule.get("strategyId"))
        && rule.get("governedIdentity") instanceof Map<?,?> raw
        && sourceId!=null && configurationHash!=null && verifier!=null) {
      @SuppressWarnings("unchecked") Map<String,Object> policy=(Map<String,Object>)raw;
      if(!configurationHash.matches("sha256:[0-9a-f]{64}")
          ||!sourceId.equals(policy.get("sourceId"))
          ||!Objects.equals(rule.get("policyRef"),policy.get("ref"))
          ||!Objects.equals(rule.get("strategyVersion"),policy.get("version")))throw unavailable();
      verifier.requireCurrent(sourceId,configurationHash,policy);
      return;
    }
    if (!(resolution instanceof Map<?,?> rule) || !rule.keySet().equals(LEGACY_FIELDS)
        || LEGACY_FIELDS.stream().anyMatch(key -> !(rule.get(key) instanceof String value) || value.isBlank()))
      throw unavailable();
  }
  private static DomainFailure unavailable(){
    return new DomainFailure(HttpStatus.CONFLICT,"ONB_UDP_IDENTITY_RUNTIME_UNAVAILABLE",
        "UDP cannot execute this identity resolution profile; activation is blocked");
  }
}
