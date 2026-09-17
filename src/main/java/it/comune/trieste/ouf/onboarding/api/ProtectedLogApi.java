package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.ProtectedLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/trusted-human/v1")
public class ProtectedLogApi {
  private final ProtectedLogService logs;private final TrustedActorResolver actors;
  @org.springframework.beans.factory.annotation.Value("${ouf.protected-log.tenant-id:}") private String logTenant;
  public ProtectedLogApi(ProtectedLogService logs,TrustedActorResolver actors){this.logs=logs;this.actors=actors;}
  public record SearchRequest(@NotNull OffsetDateTime from,@NotNull OffsetDateTime to,String service,String severity,String correlationId,String eventType,@Min(1) @Max(200) Integer limit,String cursor,@NotBlank @Size(max=500) String purpose){}
  public record AggregateRequest(@NotNull OffsetDateTime from,@NotNull OffsetDateTime to,String service,String severity,String correlationId,String eventType,@Min(1) @Max(200) Integer limit,@NotBlank String dimension,@NotBlank @Size(max=500) String purpose){}
  public record CorrelateRequest(@NotBlank String key,@NotBlank String value,@NotNull OffsetDateTime from,@NotNull OffsetDateTime to,@Min(1) @Max(200) Integer limit,@NotBlank @Size(max=500) String purpose){}
  public record PurposeRequest(@NotBlank @Size(max=500) String purpose){}
  @PostMapping("/logs/search") Map<String,Object> search(@Valid @RequestBody SearchRequest r,HttpServletRequest request,@RequestHeader HttpHeaders h){return logs.search(search(r),protectedActor(request,"ouf.ths.log.read",null),actors.authenticationContextRef(request),correlation(h));}
  @GetMapping("/logs/{logRef}") Map<String,Object> read(@PathVariable String logRef,@RequestParam @NotBlank String purpose,HttpServletRequest request,@RequestHeader HttpHeaders h){return logs.read(logRef,purpose,protectedActor(request,"ouf.ths.log.read.detail",logRef),actors.authenticationContextRef(request),correlation(h));}
  @PostMapping("/logs/aggregate") Map<String,Object> aggregate(@Valid @RequestBody AggregateRequest r,HttpServletRequest request,@RequestHeader HttpHeaders h){var q=new ProtectedLogService.Search(r.from(),r.to(),r.service(),r.severity(),r.correlationId(),r.eventType(),r.limit(),null,r.purpose());return logs.aggregate(q,r.dimension(),protectedActor(request,"ouf.ths.log.aggregate",null),actors.authenticationContextRef(request),correlation(h));}
  @PostMapping("/logs/correlate") Map<String,Object> correlate(@Valid @RequestBody CorrelateRequest r,HttpServletRequest request,@RequestHeader HttpHeaders h){return logs.correlate(new ProtectedLogService.Correlation(r.key(),r.value(),r.from(),r.to(),r.limit(),r.purpose()),protectedActor(request,"ouf.ths.log.correlate",null),actors.authenticationContextRef(request),correlation(h));}
  @PostMapping("/log-exports") ResponseEntity<Map<String,Object>> export(@Valid @RequestBody SearchRequest r,HttpServletRequest request,@RequestHeader HttpHeaders h){return ResponseEntity.accepted().body(logs.createExport(search(r),protectedActor(request,"ouf.ths.log.export",null),actors.authenticationContextRef(request),correlation(h)));}
  @GetMapping("/log-exports/{exportId}") Map<String,Object> exportStatus(@PathVariable UUID exportId,@RequestParam @NotBlank String purpose,HttpServletRequest request,@RequestHeader HttpHeaders h){return logs.export(exportId,purpose,protectedActor(request,"ouf.ths.log.export.read",exportId.toString()),actors.authenticationContextRef(request),correlation(h));}
  @PostMapping("/log-exports/{exportId}/download") ResponseEntity<byte[]> download(@PathVariable UUID exportId,@Valid @RequestBody PurposeRequest body,HttpServletRequest request,@RequestHeader HttpHeaders h){byte[] data=logs.download(exportId,body.purpose(),protectedActor(request,"ouf.ths.log.download",exportId.toString()),actors.authenticationContextRef(request),correlation(h));return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=protected-log-export-"+exportId+".json").cacheControl(CacheControl.noStore()).body(data);}
  private it.comune.trieste.ouf.onboarding.application.OnboardingService.Actor protectedActor(HttpServletRequest request,String capability,String id){
    try{
      if(logTenant==null||logTenant.isBlank())throw new SecurityException("PROTECTED_LOG_OWNER_SCOPE_REQUIRED");
      var owner=it.comune.trieste.ouf.authorization.OwnerAuthorization.bind(request);
      if(owner.principal().actorType()!=it.comune.trieste.ouf.authorization.PrincipalContext.ActorType.HUMAN)throw new SecurityException("HUMAN_REQUIRED");
      // This adapter owns the global Onboarding audit store. Its namespace is deployment-governed, never inferred from the caller.
      var resource=new it.comune.trieste.ouf.authorization.ResourceContext("protected-log",id,logTenant,null,Map.of("module","ONBOARDING","detailLevel","SECURITY_SENSITIVE"));
      owner.require(capability,resource);request.setAttribute(it.comune.trieste.ouf.authorization.ServletAuthorization.RESOURCE,resource);return actors.actor(request);
    }catch(SecurityException e){throw new it.comune.trieste.ouf.onboarding.domain.DomainFailure(HttpStatus.FORBIDDEN,"THS_LOG_ACCESS_DENIED",e.getMessage());}
  }
  private static ProtectedLogService.Search search(SearchRequest r){return new ProtectedLogService.Search(r.from(),r.to(),r.service(),r.severity(),r.correlationId(),r.eventType(),r.limit(),r.cursor(),r.purpose());}
  private static String correlation(HttpHeaders h){return Optional.ofNullable(h.getFirst("X-Correlation-ID")).filter(x->!x.isBlank()).orElseGet(()->UUID.randomUUID().toString());}
}
