package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import org.springframework.http.HttpStatus;

/** A rejected proposal, mapping or reader. Collecting validators may turn it into a finding. */
final class ReviewValidationFailure extends DomainFailure {
  ReviewValidationFailure(String code) {
    super(HttpStatus.UNPROCESSABLE_ENTITY,code,code);
  }
}
