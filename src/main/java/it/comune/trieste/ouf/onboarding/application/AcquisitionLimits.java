package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpStatus;

@ConfigurationProperties("ouf.onboarding.acquisition-limits")
public record AcquisitionLimits(int maxRows, int maxColumns, int maxCellChars, int marginPercent,
    int minHeadroomRows, int minHeadroomColumns, int minHeadroomCellChars) {

  public AcquisitionLimits {
    check("max-rows", maxRows, 1, 1_000_000);
    check("max-columns", maxColumns, 1, 10_000);
    check("max-cell-chars", maxCellChars, 1, 1_000_000);
    check("margin-percent", marginPercent, 0, 1000);
    check("min-headroom-rows", minHeadroomRows, 0, 1_000_000);
    check("min-headroom-columns", minHeadroomColumns, 0, 10_000);
    check("min-headroom-cell-chars", minHeadroomCellChars, 0, 1_000_000);
  }

  private static void check(String property, int value, int min, int max) {
    if (value < min || value > max)
      throw new IllegalArgumentException("ouf.onboarding.acquisition-limits." + property + " outside " + min + ".." + max);
  }

  record Measure(String limit, Integer observed, int ceiling, int installationMaximum, int marginPercent, int minHeadroom) {
    boolean exceeds(long maximum) { return observed != null && observed > maximum; }

    void requireWithinInstallation() {
      if (exceeds(installationMaximum))
        throw failure("ONB_ACQUISITION_LIMIT_INSTALLATION_EXCEEDED", "installationMaximum", installationMaximum);
    }

    int effective(Integer requested) {
      requireWithinInstallation();
      if (requested == null) return ceiling;
      if (exceeds(requested))
        throw failure("ONB_ACQUISITION_LIMIT_BELOW_PROFILE", "ceiling", ceiling);
      return Math.min(requested, ceiling);
    }

    private DomainFailure failure(String code, String bound, int value) {
      return new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY, code, code,
          Map.of("limit", limit, "observed", observed, bound, value));
    }

    Map<String,Object> basis(int effective) {
      var result = new LinkedHashMap<String,Object>();
      result.put("effective", effective);
      result.put("observed", observed);
      result.put("ceiling", ceiling);
      result.put("installationMaximum", installationMaximum);
      result.put("marginPercent", marginPercent);
      result.put("minHeadroom", minHeadroom);
      result.put("origin", effective == ceiling ? "DERIVED" : effective < ceiling ? "RESTRICTED" : "ABOVE_CEILING");
      return result;
    }
  }

  private Measure measure(String limit, Integer observed, int maximum, int headroom) {
    int ceiling = maximum;
    if (observed != null) {
      long proportional = (observed * (100L + marginPercent) + 99) / 100;
      long withHeadroom = Math.max(1L, Math.max(observed.longValue() + headroom, proportional));
      ceiling = (int) Math.min(maximum, withHeadroom);
    }
    return new Measure(limit, observed, ceiling, maximum, marginPercent, headroom);
  }

  record Measurements(Measure rows, Measure columns, Measure cells) {
    List<Measure> ordered() { return List.of(rows, columns, cells); }
  }

  Measurements measures(Map<?,?> profile) {
    var metadata = ManagedReviewSnapshot.child(profile, "metadata");
    Object rows = metadata.get("rows");
    if (rows == null) rows = profile.get("rowCount");
    if (rows == null) rows = profile.get("featureCount");
    return new Measurements(measure("maxRows", count(rows), maxRows, minHeadroomRows),
        measure("maxColumns", ManagedReviewSnapshot.list(profile.get("inferred_schema")).size(), maxColumns, minHeadroomColumns),
        measure("maxCellChars", count(metadata.get("maxCellChars")), maxCellChars, minHeadroomCellChars));
  }

  private static Integer count(Object value) {
    if (value == null) return null;
    if (value instanceof Number n && n.intValue() >= 0 && n.doubleValue() == n.intValue()) return n.intValue();
    throw new ReviewValidationFailure("ONB_MANAGED_EXECUTION_PROPOSAL_INVALID");
  }

  ManagedFileExecutionProposal.Limits resolve(Map<?,?> profile, ManagedFileExecutionProposal.Limits request) {
    var measured = measures(profile);
    if (measured.rows().observed() == null || measured.columns().observed() == 0)
      throw new ReviewValidationFailure("ONB_MANAGED_EXECUTION_PROPOSAL_INVALID");
    return new ManagedFileExecutionProposal.Limits(
        measured.rows().effective(request == null ? null : request.maxRows()),
        measured.columns().effective(request == null ? null : request.maxColumns()),
        measured.cells().effective(request == null ? null : request.maxCellChars()));
  }
}
