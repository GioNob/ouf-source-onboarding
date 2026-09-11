package it.comune.trieste.ouf.onboarding.api;
import it.comune.trieste.ouf.onboarding.application.PullSchedulerService;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;import java.time.*;import java.util.*;import org.springframework.http.ResponseEntity;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/onboarding/v1/runtime") public class SchedulerApi {
  private final PullSchedulerService service; public SchedulerApi(PullSchedulerService service){this.service=service;}
  public record ClaimRequest(@NotBlank String worker,@Min(10) @Max(3600) int leaseSeconds){}
  public record HeartbeatRequest(@NotBlank String worker,@Min(10) @Max(3600) int leaseSeconds){}
  public record CompleteRequest(@NotBlank String worker,@NotBlank String outputRef){}
  public record FailRequest(@NotBlank String worker,@NotBlank String errorCode,@NotBlank String detail,@PositiveOrZero long retryAfterSeconds){}
  @GetMapping("/sources/{sourceId}/schedule") Map<String,Object> schedule(@PathVariable String sourceId){return service.schedule(sourceId);}
  @PostMapping("/pull-dispatches/emit-due") List<Map<String,Object>> emit(@RequestParam(defaultValue="100") int limit){return service.emitDue(Instant.now(),Math.min(Math.max(limit,1),500));}
  @PostMapping("/pull-dispatches/claim") ResponseEntity<Map<String,Object>> claim(@Valid @RequestBody ClaimRequest request){return service.claim(request.worker(),Duration.ofSeconds(request.leaseSeconds()),Instant.now()).map(ResponseEntity::ok).orElseGet(()->ResponseEntity.noContent().build());}
  @PostMapping("/pull-dispatches/{dispatchId}/heartbeat") Map<String,Object> heartbeat(@PathVariable UUID dispatchId,@Valid @RequestBody HeartbeatRequest request){return service.heartbeat(dispatchId,request.worker(),Duration.ofSeconds(request.leaseSeconds()),Instant.now());}
  @PostMapping("/pull-dispatches/{dispatchId}/complete") Map<String,Object> complete(@PathVariable UUID dispatchId,@Valid @RequestBody CompleteRequest request){return service.complete(dispatchId,request.worker(),request.outputRef());}
  @PostMapping("/pull-dispatches/{dispatchId}/fail") Map<String,Object> fail(@PathVariable UUID dispatchId,@Valid @RequestBody FailRequest request){return service.fail(dispatchId,request.worker(),request.errorCode(),request.detail(),Duration.ofSeconds(request.retryAfterSeconds()));}
  @GetMapping("/pull-dispatches/{dispatchId}") Map<String,Object> dispatch(@PathVariable UUID dispatchId){return service.dispatch(dispatchId);}
}
