package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.*;
import it.comune.trieste.ouf.onboarding.application.SourceRevisionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/internal/v1/onboarding/source-revision-mcp")
public class SourceRevisionApi {
  private final PermissionDelegationVerifier receipts;private final SourceRevisionService revisions;private final ObjectMapper json;
  public record Read(String sourceId,UUID onboardingVersionId,UUID parentVersionId,String commandKey){}
  public SourceRevisionApi(PermissionDelegationVerifier receipts,SourceRevisionService revisions,ObjectMapper json){
    this.receipts=receipts;this.revisions=revisions;
    this.json=json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
  }
  private <T>T arguments(byte[] raw,Class<T> type){
    try{var args=json.readTree(raw).required("Arguments");
      if(!args.isObject()||!args.path("sourceId").isTextual())throw new IllegalArgumentException();
      if(type==Read.class){
        boolean version=args.path("onboardingVersionId").isTextual(), command=args.path("parentVersionId").isTextual()&&args.path("commandKey").isTextual();
        if(version==command||(version&&(args.has("parentVersionId")||args.has("commandKey")))||(!version&&args.has("onboardingVersionId")))throw new IllegalArgumentException();
      }
      if(type==SourceRevisionService.Prepare.class&&(!args.path("parentVersionId").isTextual()
          ||!args.path("commandKey").isTextual()||!args.path("configurationHash").isTextual()
          ||!args.path("publicationEffect").isTextual()||!args.path("changes").isArray()
          ||!args.path("expectedVersion").isIntegralNumber()||!args.path("expectedVersion").canConvertToLong()))throw new IllegalArgumentException();
      return json.treeToValue(args,type);
    }catch(java.io.IOException e){throw new IllegalArgumentException("ONB_REVISION_ARGUMENTS_INVALID",e);}
  }
  @PostMapping("/prepare") public Map<String,Object> prepare(@RequestBody byte[] raw,HttpServletRequest request){
    var caller=receipts.verifySourceRevision(request.getHeader("X-OUF-Source-Revision-Receipt"),request.getRequestURI(),SourceRevisionService.PREPARE,raw);
    return revisions.prepare(caller.principal(),arguments(raw,SourceRevisionService.Prepare.class),UUID.randomUUID().toString());
  }
  @PostMapping("/read") public Map<String,Object> read(@RequestBody byte[] raw,HttpServletRequest request){
    var caller=receipts.verifySourceRevision(request.getHeader("X-OUF-Source-Revision-Receipt"),request.getRequestURI(),SourceRevisionService.READ,raw);
    var command=arguments(raw,Read.class);return command.onboardingVersionId()!=null
        ?revisions.read(caller.principal(),command.sourceId(),command.onboardingVersionId())
        :revisions.readCommand(caller.principal(),command.sourceId(),command.parentVersionId(),command.commandKey());
  }
  @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(){return ResponseEntity.unprocessableEntity().body(Map.of("code","ONB_REVISION_ARGUMENTS_INVALID"));}
  @ExceptionHandler(SecurityException.class) ResponseEntity<?> denied(){return ResponseEntity.status(403).body(Map.of("code","ONB_REVISION_RECEIPT_INVALID"));}
}
