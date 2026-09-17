package it.comune.trieste.ouf.onboarding.application;

import java.util.*;

/** PET UDP §§22–24: deterministic, bounded scoring; a score is evidence, not authority. */
public final class WeightedIdentityPolicy {
  private WeightedIdentityPolicy() {}
  public record Signal(String property, String comparator, double weight, Double tolerance) {
    public Signal {
      if (property == null || property.isBlank() || !Set.of("EXACT", "TEXT", "NUMBER", "DISTANCE", "OVERLAP").contains(comparator)
          || !Double.isFinite(weight) || weight <= 0 || weight > 1) throw invalid();
      if (Set.of("NUMBER", "DISTANCE").contains(comparator)
          && (tolerance == null || !Double.isFinite(tolerance) || tolerance <= 0)) throw invalid();
    }
    boolean spatial() { return comparator.equals("DISTANCE") || comparator.equals("OVERLAP"); }
  }
  public record Policy(List<Signal> signals, List<String> blockingProperties, Double blockingDistanceMeters,
      int maxCandidates, double highThreshold, double reviewThreshold, double minimumMargin,
      boolean allowSpatialIdentity) {
    public Policy {
      signals = List.copyOf(signals); blockingProperties = List.copyOf(blockingProperties);
      if (signals.isEmpty() || signals.size() > 16 || blockingProperties.size() > 8
          || new HashSet<>(blockingProperties).size() != blockingProperties.size()
          || blockingProperties.stream().anyMatch(p -> p == null || p.isBlank())
          || maxCandidates < 1 || maxCandidates > 100
          || !unit(highThreshold) || highThreshold == 0 || !unit(reviewThreshold) || reviewThreshold >= highThreshold
          || !unit(minimumMargin) || minimumMargin == 0
          || Math.abs(signals.stream().mapToDouble(Signal::weight).sum() - 1) > 1e-9
          || signals.stream().map(s -> s.property() + ":" + s.comparator()).distinct().count() != signals.size()) throw invalid();
      if (blockingDistanceMeters != null && (!Double.isFinite(blockingDistanceMeters) || blockingDistanceMeters <= 0 || blockingDistanceMeters > 10000)) throw invalid();
      if (blockingProperties.isEmpty() && blockingDistanceMeters == null) throw invalid();
    }
  }
  private static boolean unit(double value) { return Double.isFinite(value) && value >= 0 && value <= 1; }
  private static IllegalArgumentException invalid() { return new IllegalArgumentException("ONB_WEIGHTED_IDENTITY_INVALID"); }
}
