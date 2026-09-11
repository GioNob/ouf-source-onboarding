package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.*;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/onboarding/v1/sources/{sourceId}/types/{typeCode}/mapping")
public class MappingApi {
  private final MappingService service; private final TrustedActorResolver actors; public MappingApi(MappingService service,TrustedActorResolver actors){this.service=service;this.actors=actors;}
  public record TypeMappingRequest(@NotBlank String targetClassIri,List<String> semanticRefs,List<String> eligibleStates){}
  public record FieldMappingRequest(@NotBlank String classification,String targetPropertyIri,String transform){}
  public record RelationshipRequest(@NotBlank String sourceField,@NotBlank String relationIri,@NotBlank String targetClassIri,@NotBlank String resolutionStrategy,@NotBlank String onNoMatch,@NotBlank String onMultipleMatches,@NotBlank String provenancePolicy){}
  @PostMapping ResponseEntity<Map<String,Object>> initialize(@PathVariable String sourceId,@PathVariable String typeCode){return ResponseEntity.status(HttpStatus.CREATED).body(service.initialize(sourceId,typeCode));}
  @GetMapping Map<String,Object> workspace(@PathVariable String sourceId,@PathVariable String typeCode){return service.workspace(sourceId,typeCode);}
  @PutMapping("/type") ResponseEntity<Map<String,Object>> type(@PathVariable String sourceId,@PathVariable String typeCode,@RequestBody TypeMappingRequest request,@RequestHeader(HttpHeaders.IF_MATCH) String match){var out=service.configureType(sourceId,typeCode,request.targetClassIri(),request.semanticRefs()==null?List.of():request.semanticRefs(),request.eligibleStates()==null?List.of():request.eligibleStates(),lock(match));return ResponseEntity.ok().eTag(etag(out.get("lock_version"))).body(out);}
  @PutMapping("/fields/{fieldName}") ResponseEntity<Map<String,Object>> field(@PathVariable String sourceId,@PathVariable String typeCode,@PathVariable String fieldName,@RequestBody FieldMappingRequest request,@RequestHeader(HttpHeaders.IF_MATCH) String match){var out=service.configureField(sourceId,typeCode,fieldName,request.classification(),request.targetPropertyIri(),request.transform(),lock(match));return ResponseEntity.ok().eTag(etag(out.get("lock_version"))).body(out);}
  @PostMapping("/relationships") ResponseEntity<Map<String,Object>> relationship(@PathVariable String sourceId,@PathVariable String typeCode,@RequestBody RelationshipRequest request){return ResponseEntity.status(HttpStatus.CREATED).body(service.addRelationship(sourceId,typeCode,request.sourceField(),request.relationIri(),request.targetClassIri(),request.resolutionStrategy(),request.onNoMatch(),request.onMultipleMatches(),request.provenancePolicy()));}
  @PostMapping("/build-draft") ResponseEntity<Map<String,Object>> build(@PathVariable String sourceId,@PathVariable String typeCode,@RequestHeader HttpHeaders headers,HttpServletRequest request){return ResponseEntity.status(HttpStatus.CREATED).body(service.buildDraft(sourceId,typeCode,actors.actor(request),Optional.ofNullable(headers.getFirst("X-Correlation-ID")).orElse(UUID.randomUUID().toString())));}
  private static long lock(String value){try{return Long.parseLong(value.replace("W/\"mapping:","").replace("W/\"field:","").replace("\"",""));}catch(Exception e){throw new IllegalArgumentException("invalid If-Match");}}
  private static String etag(Object lock){return "W/\"mapping:"+lock+"\"";}
}
