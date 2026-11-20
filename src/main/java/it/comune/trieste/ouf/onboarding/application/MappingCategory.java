package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.net.URI;
import java.util.*;
import org.springframework.http.HttpStatus;

public final class MappingCategory {
  private MappingCategory() {}

  public static Map<String,Object> fromRequest(com.fasterxml.jackson.databind.JsonNode value) {
    if (value == null) return null;
    if (value.isObject() && value.size() == 2 && value.path("schemeIri").isTextual()
        && value.path("conceptIri").isTextual()) {
      return declared(Map.of("category", Map.of("schemeIri", value.get("schemeIri").textValue(),
          "conceptIri", value.get("conceptIri").textValue())));
    }
    throw invalid();
  }

  static Map<String,Object> declared(Map<?,?> owner) {
    if (!owner.containsKey("category")) return null;
    if (owner.get("category") instanceof Map<?,?> category
        && category.keySet().equals(Set.of("schemeIri", "conceptIri"))
        && absolute(category.get("schemeIri")) && absolute(category.get("conceptIri"))) {
      return Map.of("schemeIri", category.get("schemeIri"), "conceptIri", category.get("conceptIri"));
    }
    throw invalid();
  }

  private static DomainFailure invalid() {
    return new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY, "ONB_MAPPING_CATEGORY_INVALID",
        "Category requires exactly schemeIri and conceptIri, both absolute IRIs");
  }

  static void validateConfiguration(Map<String,Object> configuration) {
    var mapping = child(configuration,"semanticMapping");
    var runtime = child(child(configuration,"extractionProfile"),"runtime");
    var resolution = child(child(runtime,"udp"),"resolution");
    validate(mapping.get("targetClasses"),child(resolution,"governedIdentity"));
  }

  private static Map<?,?> child(Map<?,?> owner, String key) {
    return owner.get(key) instanceof Map<?,?> child ? child : Map.of();
  }

  static void validate(Object targets, Map<?,?> policy) {
    Map<String,Object> categories = new HashMap<>();
    if (targets instanceof List<?> list) for (Object entry : list) {
      if (!(entry instanceof Map<?,?> target)) continue;
      var category = declared(target);
      if (!(target.get("classIri") instanceof String iri)) continue;
      if (categories.containsKey(iri) && !Objects.equals(categories.get(iri), category)) throw conflict();
      categories.put(iri, category);
    }
    if (policy.containsKey("category") && !declared(policy).equals(categories.get(policy.get("canonicalClass"))))
      throw conflict();
  }

  private static boolean absolute(Object raw) {
    if (!(raw instanceof String iri)) return false;
    try { return URI.create(iri).isAbsolute(); }
    catch (IllegalArgumentException e) { return false; }
  }

  private static DomainFailure conflict() {
    return new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY, "ONB_MAPPING_CATEGORY_CONFLICT",
        "Declarations for the same canonical class must agree on both category IRIs or absence");
  }
}
