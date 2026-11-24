package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.onboarding.application.RuntimePublicationEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Read-only publication feed. Scheduling remains in Ingestion. */
@RestController
@RequestMapping("/api/onboarding/v1/runtime/publications")
public class RuntimePublicationApi {
  private final JdbcClient db;private final RuntimePublicationEnvelope envelopes;private final String tenant;
  public RuntimePublicationApi(JdbcClient db,RuntimePublicationEnvelope envelopes){this.db=db;this.envelopes=envelopes;this.tenant=envelopes.tenant();}
  @GetMapping(produces=MediaType.APPLICATION_JSON_VALUE) public byte[] page(@RequestParam(defaultValue="") String after,@RequestParam(defaultValue="20") int limit,HttpServletRequest request){
    OwnerAuthorization owner=authorize(request,null);if(limit<1||limit>20||after.length()>160)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ONB_PUBLICATION_PAGE_INVALID");
    var rows=db.sql("select p.publication_id,p.source_id,p.onboarding_version_id,p.bundle_version,p.checksum,p.bundle::text bundle,s.status source_status from ouf_onboarding.published_configuration p join ouf_onboarding.source s using(source_id) where p.active=true and p.source_id>:after order by p.source_id limit :limit").param("after",after).param("limit",limit).query().listOfRows();
    var items=new ArrayList<Map<String,Object>>();for(var row:rows){require(owner,String.valueOf(row.get("source_id")));items.add(envelope(row));}
    return envelopes.encodePage(items,rows.size()==limit?String.valueOf(rows.getLast().get("source_id")):"");
  }
  @GetMapping(value="/{sourceId}/active",produces=MediaType.APPLICATION_JSON_VALUE) public byte[] active(@PathVariable String sourceId,HttpServletRequest request){
    authorize(request,sourceId);var row=db.sql("select p.publication_id,p.source_id,p.onboarding_version_id,p.bundle_version,p.checksum,p.bundle::text bundle,s.status source_status from ouf_onboarding.published_configuration p join ouf_onboarding.source s using(source_id) where p.active=true and p.source_id=:source").param("source",sourceId).query().listOfRows().stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"ONB_ACTIVE_PUBLICATION_NOT_FOUND"));return envelopes.encode(envelope(row));
  }
  @GetMapping(value="/resolve",produces=MediaType.APPLICATION_JSON_VALUE) public byte[] resolve(@RequestParam String bundleRef,HttpServletRequest request){
    var owner=authorize(request,null);
    if(bundleRef.isBlank()||bundleRef.length()>2048)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ONB_PUBLICATION_REF_INVALID");
    var rows=db.sql("select p.publication_id,p.source_id,p.onboarding_version_id,p.bundle_version,p.checksum,p.bundle::text bundle,s.status source_status from ouf_onboarding.published_configuration p join ouf_onboarding.source s using(source_id) where (p.bundle->>'bundleId')||':'||(p.bundle->>'bundleVersion')||':'||p.checksum=:ref limit 2").param("ref",bundleRef).query().listOfRows();
    if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ONB_EXACT_PUBLICATION_NOT_FOUND");
    require(owner,String.valueOf(rows.getFirst().get("source_id")));return envelopes.encode(envelope(rows.getFirst()));
  }
  private OwnerAuthorization authorize(HttpServletRequest request,String source){try{var owner=OwnerAuthorization.bind(request);if(tenant.isBlank()||owner.principal().actorType()!=PrincipalContext.ActorType.SERVICE)throw new SecurityException("ONB_RUNTIME_SERVICE_REQUIRED");require(owner,source);return owner;}catch(SecurityException e){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"ONB_RUNTIME_PUBLICATION_DENIED");}}
  private void require(OwnerAuthorization owner,String source){try{owner.require("ouf.onboarding.configuration.read",new ResourceContext("published-configuration",source,tenant,null,source==null?Map.of("module","ONBOARDING"):Map.of("module","ONBOARDING","sourceRef",source)));}catch(SecurityException e){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"ONB_RUNTIME_PUBLICATION_DENIED");}}
  private Map<String,Object> envelope(Map<String,Object> row){
    return envelopes.body(row.get("publication_id"),(String)row.get("source_id"),row.get("onboarding_version_id"),
        row.get("bundle_version"),row.get("checksum"),row.get("source_status"),(String)row.get("bundle"));
  }
}
