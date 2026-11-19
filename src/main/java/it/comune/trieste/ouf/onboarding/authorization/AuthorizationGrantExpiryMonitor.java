package it.comune.trieste.ouf.onboarding.authorization;

import static it.comune.trieste.ouf.onboarding.authorization.AuthorizationGrantExpiry.*;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Read-only operational signals from the Authorization registry's active pointer. */
@Component
public class AuthorizationGrantExpiryMonitor {
  private static final Logger LOG = LoggerFactory.getLogger(AuthorizationGrantExpiryMonitor.class);

  private record Scan(boolean available, Instant lastSuccess, Map<Window, Long> counts) {}
  private record Warning(Window window, String potentialDependency) {}

  private final AuthorizationPolicyRegistry registry;
  private final AtomicReference<Scan> lastScan = new AtomicReference<>(new Scan(false, null, Map.of()));
  private Set<Finding> previousFindings = Set.of();
  private boolean unavailableLogged;

  public AuthorizationGrantExpiryMonitor(AuthorizationPolicyRegistry registry, MeterRegistry meters) {
    this.registry = registry;
    Gauge.builder("ouf.authorization.grant.expiry.scan.available", lastScan,
        scan -> scan.get().available() ? 1 : 0).register(meters);
    Gauge.builder("ouf.authorization.grant.expiry.scan.last.success.unixtime", lastScan, scan -> {
      Instant lastSuccess = scan.get().lastSuccess();
      return lastSuccess == null ? Double.NaN : lastSuccess.getEpochSecond();
    }).register(meters);
    for (Window window : Window.values()) {
      Gauge.builder("ouf.authorization.grant.expiry.count", lastScan, state -> {
        Scan scan = state.get();
        return scan.available() ? scan.counts().getOrDefault(window, 0L) : Double.NaN;
      }).tag("window", window.name()).register(meters);
    }
  }

  @Scheduled(fixedDelayString = "${ouf.authorization.grant-expiry-check-ms:300000}")
  public synchronized void checkActivePolicy() {
    Report report;
    try {
      var active = registry.active().orElseThrow();
      var policy = registry.load(active.bundleId(), active.version());
      report = inspect(policy, Instant.now(), grant -> true);
    } catch (RuntimeException e) {
      lastScan.updateAndGet(previous -> new Scan(false, previous.lastSuccess(), Map.of()));
      if (!unavailableLogged) LOG.warn("AUTH_GRANT_EXPIRY_CHECK_UNAVAILABLE");
      unavailableLogged = true;
      return;
    }
    var counts = new EnumMap<Window, Long>(Window.class);
    var warnings = new HashMap<Warning, Long>();
    for (Finding finding : report.findings()) {
      counts.merge(finding.window(), 1L, Long::sum);
      if (!previousFindings.contains(finding)) {
        warnings.merge(new Warning(finding.window(), finding.potentialDependency()), 1L, Long::sum);
      }
    }
    lastScan.set(new Scan(true, report.checkedAt(), Map.copyOf(counts)));
    previousFindings = Set.copyOf(report.findings());
    warnings.forEach((warning, count) -> LOG.warn("AUTH_GRANT_EXPIRY window={} potentialDependency={} count={}",
        warning.window(), warning.potentialDependency(), count));
    if (unavailableLogged) LOG.info("AUTH_GRANT_EXPIRY_CHECK_AVAILABLE");
    unavailableLogged = false;
  }
}
