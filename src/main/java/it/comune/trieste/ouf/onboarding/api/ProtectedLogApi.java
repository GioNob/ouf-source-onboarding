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
  public ProtectedLogApi(ProtectedLogService logs,TrustedActorResolver actors){this.logs=logs;this.actors=actors;}
  public record SearchRequest(@NotNull OffsetDateTime from,@NotNull OffsetDateTime to,String service,String severity,String correlationId,String eventType,@Min(1) @Max(200) Integer limit,String cursor,@NotBlank @Size(max=500) String purpose){}
  public record AggregateRequest(@NotNull OffsetDateTime from,@NotNull OffsetDateTime to,String service,String severity,String correlationId,String eventType,@Min(1) @Max(200) Integer limit,@NotBlank String dimension,@NotBlank @Size(max=500) String purpose){}
  public record CorrelateRequest(@NotBlank String key,@NotBlank String value,@NotNull OffsetDateTime from,@NotNull OffsetDateTime to,@Min(1) @Max(200) Integer limit,@NotBlank @Size(max=500) String purpose){}
  public record PurposeRequest(@NotBlank @Size(max=500) String purpose){}
  @PostMapping("/logs/search") Map<String,Object> search(@Valid @RequestBody SearchRequest r,HttpServletRequest request,@RequestHeader HttpHeaders h){return logs.search(search(r),actors.actor(request),actors.authenticationContextRef(request),correlation(h));}
  @GetMapping("/logs/{logRef}") Map<String,Object> read(@PathVariable String logRef,@RequestParam @NotBlank String purpose,HttpServletRequest request,@RequestHeader HttpHeaders h){return logs.read(logRef,purpose,actors.actor(request),actors.authenticationContextRef(request),correlation(h));}
  @PostMapping("/logs/aggregate") Map<String,Object> aggregate(@Valid @RequestBody AggregateRequest r,HttpServletRequest request,@RequestHeader HttpHeaders h){var q=new ProtectedLogService.Search(r.from(),r.to(),r.service(),r.severity(),r.correlationId(),r.eventType(),r.limit(),null,r.purpose());return logs.aggregate(q,r.dimension(),actors.actor(request),actors.authenticationContextRef(request),correlation(h));}
  @PostMapping("/logs/correlate") Map<String,Object> correlate(@Valid @RequestBody CorrelateRequest r,HttpServletRequest request,@RequestHeader HttpHeaders h){return logs.correlate(new ProtectedLogService.Correlation(r.key(),r.value(),r.from(),r.to(),r.limit(),r.purpose()),actors.actor(request),actors.authenticationContextRef(request),correlation(h));}
  @PostMapping("/log-exports") ResponseEntity<Map<String,Object>> export(@Valid @RequestBody SearchRequest r,HttpServletRequest request,@RequestHeader HttpHeaders h){return ResponseEntity.accepted().body(logs.createExport(search(r),actors.actor(request),actors.authenticationContextRef(request),correlation(h)));}
  @GetMapping("/log-exports/{exportId}") Map<String,Object> exportStatus(@PathVariable UUID exportId,@RequestParam @NotBlank String purpose,HttpServletRequest request,@RequestHeader HttpHeaders h){return logs.export(exportId,purpose,actors.actor(request),actors.authenticationContextRef(request),correlation(h));}
  @PostMapping("/log-exports/{exportId}/download") ResponseEntity<byte[]> download(@PathVariable UUID exportId,@Valid @RequestBody PurposeRequest body,HttpServletRequest request,@RequestHeader HttpHeaders h){byte[] data=logs.download(exportId,body.purpose(),actors.actor(request),actors.authenticationContextRef(request),correlation(h));return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=protected-log-export-"+exportId+".json").cacheControl(CacheControl.noStore()).body(data);}
  private static ProtectedLogService.Search search(SearchRequest r){return new ProtectedLogService.Search(r.from(),r.to(),r.service(),r.severity(),r.correlationId(),r.eventType(),r.limit(),r.cursor(),r.purpose());}
  private static String correlation(HttpHeaders h){return Optional.ofNullable(h.getFirst("X-Correlation-ID")).filter(x->!x.isBlank()).orElseGet(()->UUID.randomUUID().toString());}
}
