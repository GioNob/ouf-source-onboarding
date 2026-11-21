package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationPolicyRegistry;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SchemaNoticeReader {
  public static final String READ="ouf.onboarding.schema-notices.read";
  public record Parent(UUID parentVersionId,Long expectedVersion,String configurationHash,UUID challengeId) {}
  public record Revision(String tool,Integer parentVersion,String observedSchemaRef,
      @com.fasterxml.jackson.annotation.JsonIgnore Parent parent) {}
  public record Notice(UUID noticeId,String sourceId,String sourceName,Instant since,Instant lastObservedAt,
      long observationPolls,List<SchemaObservation.Difference> differences,String nextAction,Revision revision) {}
  public record Page(List<Notice> notices,UUID next,boolean hasMore) {}

  private final JdbcClient db;
  private final ObjectMapper json;
  private final AuthorizationPolicyRegistry policy;
  public SchemaNoticeReader(JdbcClient db,ObjectMapper json,AuthorizationPolicyRegistry policy) {
    this.db=db; this.json=json; this.policy=policy;
  }
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Page read(PrincipalContext principal,String state,int limit,UUID after) {
    if(principal.actorType()!=PrincipalContext.ActorType.HUMAN)
      throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_HUMAN_REQUIRED","A human reviewer is required");
    if(limit<1 || limit>100 || !Set.of("OPEN","RESOLVED").contains(state))
      throw SchemaSurveillanceService.invalid("Invalid notice state or page size");
    if(!principal.scopes().contains(READ))
      throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_NOTICE_SCOPE_REQUIRED","Schema notice read scope required");
    var pointer=policy.active().orElseThrow(()->new DomainFailure(HttpStatus.SERVICE_UNAVAILABLE,
        "ONB_POLICY_UNAVAILABLE","Current authorization policy unavailable"));
    var active=policy.load(pointer.bundleId(),pointer.version());
    if(active.capabilities().stream().noneMatch(c->READ.equals(c.capabilityId()) && "READ".equals(c.operation())
        && READ.equals(c.requiredScope()) && c.allowedActors().contains(principal.actorType())))
      throw new DomainFailure(HttpStatus.FORBIDDEN,"ONB_NOTICE_READ_DISABLED","Schema notice read capability unavailable");
    // Test membership of the original COMMAND grants in a read-only evaluation. Keep all
    // grant constraints, DENY rules and principal claims, without minting a command scope.
    var descriptors=active.capabilities().stream()
        .filter(c->c.capabilityId().equals(SourceRevisionService.PREPARE) && c.operation().equals("COMMAND"))
        .map(c->new AuthorizationPolicy.CapabilityDescriptor(c.capabilityId(),"READ",READ,c.allowedActors())).toList();
    var bundle=new AuthorizationPolicy.PolicyBundle(active.bundleId(),active.version(),active.publishedAt(),descriptors,active.grants());
    var visible=new ArrayList<Notice>();
    var now=Instant.now();
    // Only an additional authorized row can produce a continuation token.
    while(true) {
      var rows=db.sql("""
          select i.issue_id,s.source_id,s.name,i.created_at,i.last_observed_at,i.observation_polls,
                 i.differences::text differences,i.observed_schema_ref,v.onboarding_version_id,v.version,
                 v.lock_version,v.configuration_hash,
                 (select m.challenge_id from ouf_onboarding.source_property_revision r
                   join ouf_onboarding.managed_onboarding_review m using(onboarding_version_id)
                   join ouf_onboarding.onboarding_version candidate using(onboarding_version_id)
                   where r.schema_notice_id=i.issue_id and r.tenant_id=i.tenant_id
                     and candidate.state in ('IN_REVIEW','APPROVED')) challenge_id
          from ouf_onboarding.schema_surveillance_issue i join ouf_onboarding.source s using(source_id)
          left join ouf_onboarding.onboarding_version v on v.source_id=s.source_id and v.state='ACTIVE'
          where i.tenant_id=:tenant and i.state=:state and i.observed_schema is not null
            and (cast(:after as uuid) is null or i.issue_id>cast(:after as uuid))
          order by i.issue_id limit 64
          """).param("tenant",principal.tenantId()).param("state",state).param("after",after).query().listOfRows();
      for(var row:rows) {
        after=(UUID)row.get("issue_id");
        String source=(String)row.get("source_id");
        var parent=(UUID)row.get("onboarding_version_id");
        var resource=new ResourceContext("source",source,principal.tenantId(),null,
            parent==null?Map.of():Map.of("onboardingVersionId",parent.toString()));
        if("EXPLICIT_DENY".equals(AuthorizationPolicy.evaluate(active,principal,resource,READ,"READ",now).decisionCode())) continue;
        if(!AuthorizationPolicy.evaluate(bundle,principal,resource,SourceRevisionService.PREPARE,"READ",now).allowed()) continue;
        if(visible.size()==limit) return new Page(visible,visible.getLast().noticeId(),true);
        List<SchemaObservation.Difference> differences;
        try { differences=List.of(json.readValue((String)row.get("differences"),SchemaObservation.Difference[].class)); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Invalid stored differences",e); }
        var revision=new Revision("source.onboarding.revision.prepare",(Integer)row.get("version"),
            (String)row.get("observed_schema_ref"),new Parent(parent,(Long)row.get("lock_version"),
                (String)row.get("configuration_hash"),(UUID)row.get("challenge_id")));
        visible.add(new Notice(after,source,(String)row.get("name"),((java.sql.Timestamp)row.get("created_at")).toInstant(),
            ((java.sql.Timestamp)row.get("last_observed_at")).toInstant(),(long)row.get("observation_polls"),
            differences,"REVISE_ONBOARDING",revision));
      }
      if(rows.size()<64) return new Page(visible,null,false);
    }
  }
}
