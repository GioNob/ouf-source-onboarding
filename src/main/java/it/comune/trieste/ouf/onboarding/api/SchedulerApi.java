package it.comune.trieste.ouf.onboarding.api;
import it.comune.trieste.ouf.onboarding.application.PullSchedulerService;
import java.time.Instant;import java.util.*;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/onboarding/v1/runtime") public class SchedulerApi {
  private final PullSchedulerService service; public SchedulerApi(PullSchedulerService service){this.service=service;}
  @GetMapping("/sources/{sourceId}/schedule") Map<String,Object> schedule(@PathVariable String sourceId){return service.schedule(sourceId);}
  @PostMapping("/pull-dispatches/emit-due") List<Map<String,Object>> emit(@RequestParam(defaultValue="100") int limit){return service.emitDue(Instant.now(),Math.min(Math.max(limit,1),500));}
}
