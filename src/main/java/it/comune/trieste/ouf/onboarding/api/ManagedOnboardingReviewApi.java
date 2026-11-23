package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import it.comune.trieste.ouf.onboarding.application.ManagedOnboardingReviewService;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;

@RestController
@RequestMapping("/api/internal/v1/onboarding/managed-file-mcp")
public class ManagedOnboardingReviewApi {
  private static final Logger LOG=LoggerFactory.getLogger(ManagedOnboardingReviewApi.class);
  private static final String INVALID="ONB_REVIEW_ARGUMENTS_INVALID";
  private static final String INTERNAL="ONB_REVIEW_INTERNAL_ERROR";
  // Only schema names and array indices may appear in diagnostic paths.
  private static final Set<String> FIELDS=Set.of("sourceId","onboardingVersionId","view","ifObservationVersion",
      "expectedVersion","commandKey","candidateAction","executionProposal","semanticBindings","semanticPublicationSetRef",
      "semanticId","semanticVersion","revisionId","publicationSetId","reviewTerms","iri","labelIt","descriptionIt",
      "license","udp","limits","maxRows","maxColumns","maxCellChars");
  private final PermissionDelegationVerifier receipts;
  private final ManagedOnboardingReviewService reviews;
  private final ObjectMapper json;
  public record Read(String sourceId,UUID onboardingVersionId,String ifObservationVersion){}
  public ManagedOnboardingReviewApi(PermissionDelegationVerifier receipts,ManagedOnboardingReviewService reviews,ObjectMapper json){
    this.receipts=receipts;
    this.reviews=reviews;
    this.json=json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
        DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    for(var shape:List.of(CoercionInputShape.Integer,CoercionInputShape.Float,CoercionInputShape.Boolean))
      this.json.coercionConfigFor(LogicalType.Textual).setCoercion(shape,CoercionAction.Fail);
  }
  private static final class InvalidArguments extends IllegalArgumentException {
    final String path;
    InvalidArguments(String path){super(INVALID);this.path=path;}
  }

  private JsonNode arguments(byte[] raw){
    try{
      var envelope=json.readTree(raw);
      if(envelope==null || !envelope.path("Arguments").isObject())throw new InvalidArguments("Arguments");
      var args=envelope.get("Arguments");
      requireText(args,"sourceId");
      return args;
    }catch(java.io.IOException malformed){throw new InvalidArguments("Arguments");}
  }
  private static void requireText(JsonNode args,String field){
    if(!args.path(field).isTextual())throw new InvalidArguments("Arguments."+field);
  }
  private <T>T decode(JsonNode args,Class<T> type){
    try{return json.treeToValue(args,type);}
    catch(JsonMappingException malformed){
      var path=new StringBuilder("Arguments");
      for(var part:malformed.getPath()){
        if(part.getIndex()>=0)path.append('[').append(part.getIndex()).append(']');
        else path.append('.').append(part.getFieldName()!=null && FIELDS.contains(part.getFieldName())?part.getFieldName():"*");
      }
      throw new InvalidArguments(path.toString());
    }catch(com.fasterxml.jackson.core.JsonProcessingException malformed){throw new InvalidArguments("Arguments");}
  }

  private static <T>T invoke(String phase,String correlation,Supplier<T> operation){
    try{return operation.get();}
    catch(InvalidArguments invalid){
      LOG.warn("code={} phase=decode correlationId={} path={}",INVALID,correlation,invalid.path);
      throw invalid;
    }catch(DomainFailure | SecurityException | DataAccessException | RestClientException expected){
      throw expected;
    }catch(RuntimeException unexpected){
      LOG.error("code={} phase={} correlationId={} class={}",INTERNAL,phase,correlation,unexpected.getClass().getName());
      throw new DomainFailure(HttpStatus.INTERNAL_SERVER_ERROR,INTERNAL,"Unexpected review error");
    }
  }

  @PostMapping("/review-prepare") public Map<String,Object> prepare(@RequestBody(required=false) byte[] raw,HttpServletRequest request){
    var correlation=CorrelationFilter.get(request);
    byte[] body=raw==null?new byte[0]:raw;
    return invoke("prepare",correlation,()->{
      var caller=receipts.verifyManagedFile(request.getHeader("X-OUF-Managed-File-Receipt"),request.getRequestURI(),ManagedOnboardingReviewService.PREPARE,body);
      var args=arguments(body);
      requireText(args,"onboardingVersionId");
      requireText(args,"commandKey");
      if(!args.path("expectedVersion").isIntegralNumber() || !args.path("expectedVersion").canConvertToLong())
        throw new InvalidArguments("Arguments.expectedVersion");
      return reviews.prepare(caller.principal(),decode(args,ManagedOnboardingReviewService.Prepare.class),correlation);
    });
  }

  @PostMapping("/review-read") public Map<String,Object> read(@RequestBody(required=false) byte[] raw,HttpServletRequest request){
    byte[] body=raw==null?new byte[0]:raw;
    return invoke("read",CorrelationFilter.get(request),()->{
      var caller=receipts.verifyManagedFile(request.getHeader("X-OUF-Managed-File-Receipt"),request.getRequestURI(),ManagedOnboardingReviewService.READ,body);
      var args=arguments(body);
      var validator=args.path("ifObservationVersion");
      if(!validator.isMissingNode()&&(!validator.isTextual()||!validator.textValue().matches("sha256:[a-f0-9]{64}")))
        throw new InvalidArguments("Arguments.ifObservationVersion");
      if(args.has("view")){
        if(!"LATEST".equals(args.path("view").textValue()))throw new InvalidArguments("Arguments.view");
        for(var keys=args.fieldNames();keys.hasNext();){
          var key=keys.next();
          if(!Set.of("sourceId","view","ifObservationVersion").contains(key))
            throw new InvalidArguments("Arguments."+(FIELDS.contains(key)?key:"*"));
        }
        return validator.isMissingNode()?reviews.readLatest(caller.principal(),args.path("sourceId").textValue())
            :reviews.readLatest(caller.principal(),args.path("sourceId").textValue(),validator.textValue());
      }
      requireText(args,"onboardingVersionId");
      var command=decode(args,Read.class);
      return command.ifObservationVersion()==null?reviews.read(caller.principal(),command.sourceId(),command.onboardingVersionId())
          :reviews.read(caller.principal(),command.sourceId(),command.onboardingVersionId(),command.ifObservationVersion());
    });
  }
  @ExceptionHandler(InvalidArguments.class) ResponseEntity<?> invalid(){return ResponseEntity.unprocessableEntity().body(Map.of("code",INVALID));}
  @ExceptionHandler(SecurityException.class) ResponseEntity<?> denied(){return ResponseEntity.status(403).body(Map.of("code","ONB_MANAGED_FILE_RECEIPT_INVALID"));}
}
