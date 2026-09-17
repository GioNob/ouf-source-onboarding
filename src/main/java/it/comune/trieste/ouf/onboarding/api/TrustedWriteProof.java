package it.comune.trieste.ouf.onboarding.api;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Server-side proof that a state-changing Trusted Human request arrived through
 * the stateless bearer authentication chain. This is deliberately distinct
 * from CSRF validation: the protected Authorization endpoints do not use
 * cookie/session authentication and Spring Security CSRF is disabled there.
 */
final class TrustedWriteProof {
  static final String STATELESS_BEARER_WRITE = "ouf.statelessBearerWriteValidated";

  private TrustedWriteProof() {}

  static void markStatelessBearer(HttpServletRequest request) {
    request.setAttribute(STATELESS_BEARER_WRITE, Boolean.TRUE);
  }

  static void require(HttpServletRequest request) {
    if (!Boolean.TRUE.equals(request.getAttribute(STATELESS_BEARER_WRITE))) {
      throw new SecurityException("AUTH_TRUSTED_WRITE_PROOF_REQUIRED");
    }
  }
}
