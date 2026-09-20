package it.comune.trieste.ouf.onboarding.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import it.comune.trieste.ouf.onboarding.domain.*;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Delegated preparation and direct human confirmation are distinct entry points. */
@Service
public class PermissionProposalService {
 public static final String READ="authorization.permissions.read", PROPOSE="authorization.permissions.propose", STATUS="authorization.proposal.read";
 @org.springframework.beans.factory.annotation.Value("${ouf.authorization.ths.public-origin:}") private String publicOrigin;
 private final JdbcClient db;private final ObjectMapper json;private final CanonicalHash hash;
 private final AuthorizationPolicyRegistry registry;private final AuthorizationAdminService admin;private final AuthorizationReviewService review;private final SuperadminAuthority authority;
 public PermissionProposalService(JdbcClient db,ObjectMapper json,CanonicalHash hash,AuthorizationPolicyRegistry registry,AuthorizationAdminService admin,AuthorizationReviewService review,SuperadminAuthority authority){this.db=db;this.json=json;this.hash=hash;this.registry=registry;this.admin=admin;this.review=review;this.authority=authority;}
 public record Change(String operation,String grantId,Grant grant,String reason){}
 public record Receipt(UUID proposalId,long revision,String state,Instant expiresAt,String approvalPath,String finalPolicyRef){}
 public record Card(UUID proposalId,long revision,String state,String tenant,String proposedBy,String reason,String baseActiveRef,String proposedHash,Instant expiresAt,Grant before,Grant after,String finalPolicyRef){}
 public record Confirmation(String expectedHash){}
 private record Proposal(UUID id,String tenant,String subject,String requestHash,String baseRef,String baseHash,String proposedHash,PolicyBundle policy,Change change,String state,long revision,Instant expires,String finalRef){}
 private DomainFailure fail(HttpStatus s,String c){return new DomainFailure(s,c,c);}
 private Instant now(){return db.sql("select clock_timestamp()").query((r,n)->r.getTimestamp(1).toInstant()).single();}
 private String ref(PolicyBundle p){return p.bundleId()+":"+p.version();}
 private String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalArgumentException("AUTH_PROPOSAL_INVALID",e);}}
 private <T>T decode(String v,Class<T> t){try{return json.readValue(v,t);}catch(Exception e){throw new IllegalStateException("stored proposal invalid",e);}}
 private PolicyBundle delegated(PrincipalContext p,String capability,String operation){
  authority.lock();
  if(p==null||p.actorType()!=PrincipalContext.ActorType.HUMAN)throw fail(HttpStatus.FORBIDDEN,"AUTH_DELEGATED_HUMAN_REQUIRED");
  var active=registry.active().orElseThrow(()->fail(HttpStatus.SERVICE_UNAVAILABLE,"AUTH_ACTIVE_REQUIRED"));
  if(!registry.authorizeActive(p,new ResourceContext("capability",null,p.tenantId(),null,Map.of()),capability,operation,now()).allowed())throw fail(HttpStatus.FORBIDDEN,"AUTH_PROPOSAL_DENIED");
  return registry.load(active.bundleId(),active.version());
 }
 private void audit(String action,String target,PrincipalContext p,String policy){db.sql("insert into ouf_authorization.admin_audit values(:id,:action,:target,:subject,:tenant,'HUMAN',:policy,:correlation,transaction_timestamp())").param("id",UUID.randomUUID()).param("action",action).param("target",target).param("subject",p.subjectId()).param("tenant",p.tenantId()).param("policy",policy).param("correlation",UUID.randomUUID().toString()).update();}
 @Transactional public AuthorizationReviewService.GrantPage access(PrincipalContext p,String subject,String role,int limit,String after,String policyRef){return review.projectGrants(p,delegated(p,READ,"READ"),subject,role,limit,after,policyRef);}
 private void validate(Change c,PrincipalContext p){
  if(c==null||!Set.of("UPSERT","REVOKE").contains(c.operation()==null?"":c.operation())||c.grantId()==null||!c.grantId().matches("[A-Za-z0-9_:.-]{1,128}")||c.reason()==null||c.reason().isBlank()||c.reason().length()>1000||encode(c).length()>16384)throw new IllegalArgumentException("AUTH_PROPOSAL_INVALID");
  if("UPSERT".equals(c.operation())){if(c.grant()==null||!c.grantId().equals(c.grant().grantId())||!p.tenantId().equals(c.grant().tenantId()))throw new IllegalArgumentException("AUTH_PROPOSAL_GRANT_INVALID");}
  else if(c.grant()!=null)throw new IllegalArgumentException("AUTH_REVOKE_MUST_NOT_HAVE_GRANT");
 }
 private Proposal load(UUID id,String tenant){return db.sql("select * from ouf_authorization.permission_proposal where proposal_id=:id and tenant_id=:tenant").param("id",id).param("tenant",tenant).query((r,n)->new Proposal(r.getObject("proposal_id",UUID.class),r.getString("tenant_id"),r.getString("proposed_by"),r.getString("request_hash"),r.getString("base_active_ref"),r.getString("base_hash"),r.getString("proposed_hash"),decode(r.getString("proposed_policy"),PolicyBundle.class),decode(r.getString("change_payload"),Change.class),r.getString("state"),r.getLong("revision"),r.getTimestamp("expires_at").toInstant(),r.getString("final_policy_ref"))).optional().orElseThrow(()->fail(HttpStatus.NOT_FOUND,"AUTH_PROPOSAL_NOT_FOUND"));}
 private String state(Proposal q){return q.state().equals("PENDING")&&!now().isBefore(q.expires())?"EXPIRED":q.state();}
 private String approvalPath(UUID id){
  if(publicOrigin==null||publicOrigin.isBlank())throw fail(HttpStatus.SERVICE_UNAVAILABLE,"AUTH_THS_ORIGIN_REQUIRED");
  var uri=java.net.URI.create(publicOrigin);
  if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||(uri.getPath()!=null&&!uri.getPath().isEmpty()&&!uri.getPath().equals("/")))throw new IllegalStateException("invalid THS HTTPS origin");
  return publicOrigin.replaceAll("/+$","")+"/trusted-human/authorization/?proposal="+id;
 }
 private Receipt receipt(Proposal q){return new Receipt(q.id(),q.revision(),state(q),q.expires(),approvalPath(q.id()),q.finalRef());}
 @Transactional public Receipt propose(PrincipalContext p,Change change,String idempotency){
  var active=delegated(p,PROPOSE,"COMMAND");validate(change,p);
  if(idempotency==null||!idempotency.matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException("AUTH_IDEMPOTENCY_REQUIRED");
  var requestHash=hash.of(change);
  var existing=db.sql("select proposal_id from ouf_authorization.permission_proposal where tenant_id=:tenant and proposed_by=:subject and idempotency_key=:key").param("tenant",p.tenantId()).param("subject",p.subjectId()).param("key",idempotency).query(UUID.class).optional();
  if(existing.isPresent()){var q=load(existing.get(),p.tenantId());if(!q.requestHash().equals(requestHash))throw fail(HttpStatus.CONFLICT,"AUTH_IDEMPOTENCY_CONFLICT");return receipt(q);}
  if(db.sql("select count(*) from ouf_authorization.permission_proposal where tenant_id=:tenant and state='PENDING' and expires_at>clock_timestamp()").param("tenant",p.tenantId()).query(Long.class).single()>=1000)throw fail(HttpStatus.TOO_MANY_REQUESTS,"AUTH_PROPOSAL_LIMIT");
  var previous=active.grants().stream().filter(g->g.grantId().equals(change.grantId())).findFirst();
  if(previous.isPresent()&&!p.tenantId().equals(previous.get().tenantId()))throw fail(HttpStatus.FORBIDDEN,"AUTH_OTHER_TENANT_GRANTS_PROTECTED");
  if(change.operation().equals("REVOKE")&&previous.isEmpty())throw fail(HttpStatus.NOT_FOUND,"AUTH_GRANT_NOT_FOUND");
  if(previous.isPresent()&&previous.get().equals(change.grant()))throw fail(HttpStatus.CONFLICT,"AUTH_PROPOSAL_NO_CHANGE");
  var grants=new ArrayList<>(active.grants());grants.removeIf(g->g.grantId().equals(change.grantId()));if(change.grant()!=null)grants.add(change.grant());
  var time=now();var proposed=new PolicyBundle(active.bundleId(),Math.addExact(active.version(),1),time,active.capabilities(),grants);
  var id=UUID.randomUUID();db.sql("insert into ouf_authorization.permission_proposal(proposal_id,tenant_id,proposed_by,idempotency_key,request_hash,base_active_ref,base_hash,proposed_hash,proposed_policy,change_payload,expires_at) values(:id,:tenant,:subject,:key,:request,:base,:basehash,:hash,cast(:policy as jsonb),cast(:change as jsonb),:expires)")
   .param("id",id).param("tenant",p.tenantId()).param("subject",p.subjectId()).param("key",idempotency).param("request",requestHash).param("base",ref(active)).param("basehash",hash.of(active)).param("hash",hash.of(proposed)).param("policy",encode(proposed)).param("change",encode(change)).param("expires",Timestamp.from(time.plusSeconds(900))).update();
  audit("PROPOSE_PERMISSION",id.toString(),p,ref(active));return receipt(load(id,p.tenantId()));
 }
 @Transactional public Receipt status(PrincipalContext p,UUID id){delegated(p,STATUS,"READ");var q=load(id,p.tenantId());if(!q.subject().equals(p.subjectId()))throw fail(HttpStatus.NOT_FOUND,"AUTH_PROPOSAL_NOT_FOUND");return receipt(q);}
 @Transactional public Card card(PrincipalContext p,UUID id){review.authorized(p);var q=load(id,p.tenantId());var active=registry.load(q.policy().bundleId(),Long.parseLong(q.baseRef().substring(q.baseRef().lastIndexOf(':')+1)));var before=active.grants().stream().filter(g->g.grantId().equals(q.change().grantId())).findFirst().orElse(null);audit("READ_PERMISSION_CARD",id.toString(),p,q.baseRef());return new Card(q.id(),q.revision(),state(q),q.tenant(),q.subject(),q.change().reason(),q.baseRef(),q.proposedHash(),q.expires(),before,q.change().grant(),q.finalRef());}
 @Transactional public Receipt decide(PrincipalContext p,UUID id,long revision,Confirmation confirmation,boolean accept){
  var active=review.authorized(p);var q=load(id,p.tenantId());
  if(q.revision()!=revision)throw fail(HttpStatus.PRECONDITION_FAILED,"AUTH_STALE_ETAG");
  if(!q.state().equals("PENDING"))throw fail(HttpStatus.CONFLICT,"AUTH_PROPOSAL_ALREADY_DECIDED");
  if(!now().isBefore(q.expires()))throw fail(HttpStatus.GONE,"AUTH_PROPOSAL_EXPIRED");
  if(confirmation==null||!q.proposedHash().equals(confirmation.expectedHash())||!q.proposedHash().equals(hash.of(q.policy())))throw fail(HttpStatus.CONFLICT,"AUTH_PROPOSAL_HASH_MISMATCH");
  String finalRef=null;
  if(accept){
   if(!q.baseRef().equals(ref(active))||!q.baseHash().equals(hash.of(active)))throw fail(HttpStatus.CONFLICT,"AUTH_ACTIVE_CHANGED_NEW_PROPOSAL_REQUIRED");
   var actor=new AuthorizationAdminService.Actor(p.subjectId(),p.tenantId(),"HUMAN",ref(active),UUID.randomUUID().toString(),p);
   var draft=admin.create(q.policy(),actor);var published=admin.publish(draft.id(),draft.revision(),actor);finalRef=ref(published.policy());
  }
  db.sql("update ouf_authorization.permission_proposal set state=:state,revision=revision+1,decided_by=:subject,final_policy_ref=:final where proposal_id=:id").param("state",accept?"PUBLISHED":"REJECTED").param("subject",p.subjectId()).param("final",finalRef).param("id",id).update();
  audit(accept?"CONFIRM_PERMISSION":"REJECT_PERMISSION",id.toString(),p,ref(active));return receipt(load(id,p.tenantId()));
 }
}
