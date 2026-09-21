package it.comune.trieste.ouf.onboarding.authorization;

import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owner-side review only. Hypothetical identities never enter the trusted principal or publish path. */
@Service
public class AuthorizationReviewService {
 private final AuthorizationAdminService admin;
 private final AuthorizationPolicyRegistry registry;
 private final SuperadminAuthority authority;
 private final CanonicalHash hash;
 private final JdbcClient db;
 public AuthorizationReviewService(AuthorizationAdminService admin,AuthorizationPolicyRegistry registry,SuperadminAuthority authority,CanonicalHash hash,JdbcClient db){this.admin=admin;this.registry=registry;this.authority=authority;this.hash=hash;this.db=db;}
 public record GrantPage(String policyRef,String policyHash,String meaning,List<Grant> grants,String nextAfter,SuperadminAuthority.Binding protectedRole){}
 public record GrantChange(String grantId,Grant before,Grant after){}
 public record Preview(UUID draftId,long revision,String baseActiveRef,String activeHash,String draftHash,List<CapabilityDescriptor> addedCapabilities,List<CapabilityDescriptor> removedCapabilities,List<GrantChange> grantChanges,boolean authoritative){}
 public record Scenario(PrincipalContext hypotheticalPrincipal,ResourceContext resource,String capabilityId,String operation){}
 public record Result(boolean allowed,String code,String permittedDetailLevel){}
 public record Simulation(UUID draftId,long revision,String baseActiveRef,String activeHash,String draftHash,Instant evaluatedAt,String contextSource,boolean authoritative,Result before,Result after){}
 private record Snapshot(AuthorizationAdminService.Draft draft,PolicyBundle active){}
 private DomainFailure fail(HttpStatus status,String code){return new DomainFailure(status,code,code);}
 private String ref(PolicyBundle p){return p.bundleId()+":"+p.version();}
 PolicyBundle authorized(PrincipalContext caller){
  authority.lock(); // Serialize permission checks and snapshots with policy publication and role handover.
  if(caller==null||caller.actorType()!=PrincipalContext.ActorType.HUMAN||!caller.scopes().contains("authorization.policy.admin"))throw fail(HttpStatus.FORBIDDEN,"AUTH_ADMIN_REQUIRED");
  var active=registry.active().orElseThrow(()->fail(HttpStatus.CONFLICT,"AUTH_ACTIVE_REQUIRED"));
  if(!authority.isSuperadmin(caller)&&!registry.authorizeActive(caller,new ResourceContext("capability",null,caller.tenantId(),null,Map.of()),"authorization.policy.admin","EXECUTE",Instant.now()).allowed())throw fail(HttpStatus.FORBIDDEN,"AUTH_ADMIN_REQUIRED");
  return registry.load(active.bundleId(),active.version());
 }
 private AuthorizationAdminService.Actor actor(PrincipalContext p){return new AuthorizationAdminService.Actor(p.subjectId(),p.tenantId(),"HUMAN","review:owner",UUID.randomUUID().toString(),p);}
 private void audit(String action,String target,PrincipalContext caller,PolicyBundle active){
  db.sql("insert into ouf_authorization.admin_audit values(:id,:action,:target,:subject,:tenant,'HUMAN',:policy,:correlation,transaction_timestamp())")
    .param("id",UUID.randomUUID()).param("action",action).param("target",target).param("subject",caller.subjectId()).param("tenant",caller.tenantId()).param("policy",ref(active)).param("correlation",UUID.randomUUID().toString()).update();
 }
 private static void text(String value,int maximum){if(value==null||value.isBlank()||value.length()>maximum||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("AUTH_REVIEW_INVALID_INPUT");}
 @Transactional public GrantPage grants(PrincipalContext caller,String subject,String role,int limit,String after,String expectedPolicyRef){
  return projectGrants(caller,authorized(caller),subject,role,limit,after,expectedPolicyRef);
 }
 GrantPage projectGrants(PrincipalContext caller,PolicyBundle active,String subject,String role,int limit,String after,String expectedPolicyRef){
  if((subject==null)==(role==null)||limit<1||limit>200)throw new IllegalArgumentException("AUTH_REVIEW_SELECTOR_OR_LIMIT_INVALID");
  text(subject==null?role:subject,256);
  if(role!=null&&!BootstrapAdministrator.validRole(role))throw new IllegalArgumentException("AUTH_REVIEW_ROLE_INVALID");
  if(after!=null){text(after,256);if(expectedPolicyRef==null)throw fail(HttpStatus.PRECONDITION_REQUIRED,"AUTH_REVIEW_POLICY_REF_REQUIRED");}
  if(expectedPolicyRef!=null&&!ref(active).equals(expectedPolicyRef))throw fail(HttpStatus.CONFLICT,"AUTH_ACTIVE_CHANGED_RESTART_REVIEW");
  var found=active.grants().stream().filter(g->g.tenantId().equals(caller.tenantId()))
    .filter(g->subject!=null?subject.equals(g.subjectId()):g.constraints()!=null&&role.equals(g.constraints().externalRoleRef()))
    .filter(g->after==null||g.grantId().compareTo(after)>0).sorted(Comparator.comparing(Grant::grantId)).limit(limit+1L).toList();
  boolean more=found.size()>limit;var page=more?found.subList(0,limit):found;
  var binding=role==null?null:authority.binding(caller.tenantId()).filter(b->Objects.equals(b.roleRef(),role)).orElse(null);
  // The selector is not an IAM lookup; expired and DENY grants are intentionally visible for review.
  audit("REVIEW_GRANTS",ref(active),caller,active);
  return new GrantPage(ref(active),hash.of(active),"CONFIGURED_GRANTS_NOT_EFFECTIVE_PERMISSIONS",page,more?page.getLast().grantId():null,binding);
 }
 private Snapshot snapshot(PrincipalContext caller,UUID id,long revision){
  var active=authorized(caller);var draft=admin.get(id,actor(caller));
  if(draft.revision()!=revision)throw fail(HttpStatus.PRECONDITION_FAILED,"AUTH_STALE_ETAG");
  if(!"DRAFT".equals(draft.state()))throw fail(HttpStatus.CONFLICT,"AUTH_DRAFT_NOT_EDITABLE");
  if(!ref(active).equals(draft.baseActiveRef()))throw fail(HttpStatus.CONFLICT,"AUTH_ACTIVE_CHANGED_REBASE_REQUIRED");
  return new Snapshot(draft,active);
 }
 @Transactional public Preview preview(PrincipalContext caller,UUID id,long revision){
  var s=snapshot(caller,id,revision);var proposed=s.draft().policy();
  var before=new TreeMap<String,Grant>();var after=new TreeMap<String,Grant>();
  s.active().grants().stream().filter(g->caller.tenantId().equals(g.tenantId())).forEach(g->before.put(g.grantId(),g));
  proposed.grants().stream().filter(g->caller.tenantId().equals(g.tenantId())).forEach(g->after.put(g.grantId(),g));
  var ids=new TreeSet<>(before.keySet());ids.addAll(after.keySet());
  var changes=ids.stream().filter(k->!Objects.equals(before.get(k),after.get(k))).map(k->new GrantChange(k,before.get(k),after.get(k))).toList();
  var added=proposed.capabilities().stream().filter(c->!s.active().capabilities().contains(c)).sorted(Comparator.comparing(CapabilityDescriptor::capabilityId)).toList();
  var removed=s.active().capabilities().stream().filter(c->!proposed.capabilities().contains(c)).sorted(Comparator.comparing(CapabilityDescriptor::capabilityId)).toList();
  if(changes.size()+added.size()+removed.size()>200)throw fail(HttpStatus.PAYLOAD_TOO_LARGE,"AUTH_REVIEW_DIFF_TOO_LARGE");
  audit("PREVIEW_POLICY",id+":"+revision,caller,s.active());
  return new Preview(id,revision,ref(s.active()),hash.of(s.active()),hash.of(proposed),added,removed,changes,false);
 }
 @Transactional public Simulation simulate(PrincipalContext caller,UUID id,long revision,Scenario scenario){
  var s=snapshot(caller,id,revision);
  if(scenario==null||scenario.hypotheticalPrincipal()==null||scenario.resource()==null)throw new IllegalArgumentException("AUTH_SIMULATION_CONTEXT_REQUIRED");
  var p=scenario.hypotheticalPrincipal();var r=scenario.resource();
  if(!caller.tenantId().equals(p.tenantId())||!caller.tenantId().equals(r.tenantId()))throw fail(HttpStatus.FORBIDDEN,"AUTH_SIMULATION_TENANT_MISMATCH");
  text(scenario.capabilityId(),256);text(scenario.operation(),32);text(p.subjectId(),256);text(p.issuer(),2048);text(p.audience(),2048);text(p.authenticationContextRef(),256);text(r.resourceType(),128);
  if(p.servicePrincipalId()!=null)text(p.servicePrincipalId(),256);if(r.resourceId()!=null)text(r.resourceId(),256);if(r.organizationId()!=null)text(r.organizationId(),256);
  if(p.scopes().size()>128||r.attributes().size()>64)throw new IllegalArgumentException("AUTH_SIMULATION_CONTEXT_LIMIT");
  p.scopes().forEach(v->text(v,256));r.attributes().forEach((k,v)->{text(k,128);text(v,1024);});
  if(p.claims()!=null){if(p.claims().externalRoleRefs().size()>32)throw new IllegalArgumentException("AUTH_SIMULATION_ROLES_LIMIT");p.claims().externalRoleRefs().forEach(v->{if(!BootstrapAdministrator.validRole(v))throw new IllegalArgumentException("AUTH_SIMULATION_ROLE_INVALID");});p.claims().amr().forEach(v->text(v,128));if(p.claims().acr()!=null)text(p.claims().acr(),128);}
  var now=Instant.now();
  var before=AuthorizationPolicy.evaluate(s.active(),p,r,scenario.capabilityId(),scenario.operation(),now);
  var after=AuthorizationPolicy.evaluate(s.draft().policy(),p,r,scenario.capabilityId(),scenario.operation(),now);
  audit("SIMULATE_POLICY",id+":"+revision,caller,s.active());
  // No enforceable decision reference, principal claims or synthetic enforcement audit is returned/stored.
  return new Simulation(id,revision,ref(s.active()),hash.of(s.active()),hash.of(s.draft().policy()),now,"HYPOTHETICAL_NOT_IAM_VERIFIED",false,result(before),result(after));
 }
 private Result result(AuthorizationDecision d){return new Result(d.allowed(),d.decisionCode(),d.permittedDetailLevel());}
}
