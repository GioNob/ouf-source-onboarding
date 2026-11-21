package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SchemaNoticeApi {
  private final TrustedActorResolver actors;
  private final SchemaNoticeReader notices;
  private final SourceRevisionService revisions;
  public SchemaNoticeApi(TrustedActorResolver actors,SchemaNoticeReader notices,SourceRevisionService revisions) {
    this.actors=actors; this.notices=notices; this.revisions=revisions;
  }
  @GetMapping("/api/onboarding/v1/schema-notices")
  ResponseEntity<?> read(@RequestParam(defaultValue="OPEN") String state,@RequestParam(defaultValue="20") int limit,
      @RequestParam(required=false) UUID after,HttpServletRequest request) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notices.read(actors.principal(request),state,limit,after));
  }
  @GetMapping("/trusted-human/onboarding/api/schema-notices")
  ResponseEntity<?> home(@RequestParam(required=false) UUID after,HttpServletRequest request) {
    requireSession(request);
    var csrf=(CsrfToken)request.getAttribute(CsrfToken.class.getName());
    if(csrf==null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    var principal=actors.principal(request);
    var page=principal.scopes().contains(SchemaNoticeReader.READ)?notices.read(principal,"OPEN",20,after)
        :new SchemaNoticeReader.Page(List.of(),null,false);
    var parents=new HashMap<UUID,SchemaNoticeReader.Parent>();
    page.notices().forEach(n->parents.put(n.noticeId(),n.revision().parent()));
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("page",page,"parents",parents,
        "csrfHeader",csrf.getHeaderName(),"csrfToken",csrf.getToken()));
  }
  public record Prepare(String sourceId,UUID parentVersionId,long expectedVersion,String configurationHash,
      String commandKey,String observedSchemaRef) {}
  @PostMapping("/trusted-human/onboarding/api/schema-notices/prepare")
  ResponseEntity<?> prepare(@RequestBody Prepare body,HttpServletRequest request) {
    requireSession(request);
    var command=new SourceRevisionService.Prepare(body.sourceId(),body.parentVersionId(),body.expectedVersion(),
        body.configurationHash(),body.commandKey(),SourceRevisionService.EFFECT,null,null,null,body.observedSchemaRef());
    var result=revisions.prepare(actors.principal(request),command,UUID.randomUUID().toString());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("challengeId",result.get("challengeId")));
  }
  private static void requireSession(HttpServletRequest request) {
    if(!Boolean.TRUE.equals(request.getAttribute("ouf.permissionThsSession"))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }
}
