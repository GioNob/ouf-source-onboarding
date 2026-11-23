package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.net.URI;
import org.springframework.http.HttpStatus;

final class ThsPublicOrigin {
  private ThsPublicOrigin() {}

  static String base(String configured) {
    if (configured == null) throw invalid();
    URI uri;
    try {
      uri = URI.create(configured);
    } catch (IllegalArgumentException ex) {
      throw invalid();
    }
    if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null || uri.getRawFragment() != null
        || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) throw invalid();
    return "https://" + uri.getRawAuthority();
  }

  private static DomainFailure invalid() {
    return new DomainFailure(HttpStatus.SERVICE_UNAVAILABLE, "ONB_REVIEW_THS_ORIGIN_REQUIRED",
        "A public HTTPS origin is required for the trusted human surface");
  }
}
