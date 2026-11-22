package it.comune.trieste.ouf.onboarding.application;

import java.util.Map;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import org.springframework.http.HttpStatus;

/** Read-only boundary to the Registry's exact references:resolve operation. */
public interface DatatypeResolver {
  static DomainFailure unresolved(){
    return new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,
        "ONB_DATATYPE_UNRESOLVED","Published datatype definition unavailable");
  }
  static DomainFailure unresolvedValueMap(){
    return new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY,
        "ONB_VALUE_MAP_UNRESOLVED","Published value map and target vocabulary required");
  }
  record ValueMapPin(String valueMapRef,String publicationSetId,String vocabularyRef) {}
  /** Return the target vocabulary attested by the publisher for this exact resolved member.
   * The current references:resolve contract does not identify published value maps. */
  default ValueMapPin valueMapPin(Map<String,Object> published){
    throw unresolvedValueMap();
  }
  Map<String,Object> resolve(String semanticId,String revisionId,String publicationSetId);
}
