package it.comune.trieste.ouf.onboarding.authorization;

import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AuthorizationAdminService {
 private final JdbcClient db;private final ObjectMapper json;private final AuthorizationPolicyRegistry registry;private final AuthorizationRuntimeSynchronizer runtime;private final BootstrapAdministrator bootstrapAdmin;private final SuperadminAuthority authority;
 public AuthorizationAdminService(JdbcClient db,ObjectMapper json,AuthorizationPolicyRegistry registry,AuthorizationRuntimeSynchronizer runtime,BootstrapAdministrator bootstrapAdmin,SuperadminAuthority authority){this.db=db;this.json=json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);this.registry=registry;this.runtime=runtime;this.bootstrapAdmin=bootstrapAdmin;this.authority=authority;}
 public record Draft(UUID id,long revision,String state,String baseActiveRef,PolicyBundle policy){}
 public record Actor(String subject,String tenant,String type,String policyRef,String correlation,PrincipalContext principal) {public Actor(String subject,String tenant,String type,String policyRef,String correlation){this(subject,tenant,type,policyRef,correlation,null);}public Actor {if(!"HUMAN".equals(type)||subject==null||subject.isBlank()||policyRef==null||policyRef.isBlank())throw new SecurityException("AUTH_ADMIN_HUMAN_REQUIRED");if(principal!=null&&(!subject.equals(principal.subjectId())||!Objects.equals(tenant,principal.tenantId())||!type.equals(principal.actorType().name())))throw new SecurityException("AUTH_ADMIN_PRINCIPAL_MISMATCH");}}
 private DomainFailure failure(HttpStatus status,String code){return new DomainFailure(status,code,code);}
 public <T> T parse(JsonNode node,Class<T> type){try{var value=json.treeToValue(node,type);if(value==null)throw new IllegalArgumentException("null policy");return value;}catch(Exception e){throw new IllegalArgumentException("AUTH_POLICY_INVALID",e);}}
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("AUTH_POLICY_INVALID",e);}}
 private PolicyBundle decode(String raw){try{return json.readValue(raw,PolicyBundle.class);}catch(Exception e){throw new IllegalArgumentException("AUTH_POLICY_INVALID",e);}}
 private String activeRef(){return registry.active().map(a->a.bundleId()+":"+a.version()).orElse("NONE");}
 private boolean bootstrapCompleted(){return db.sql("select completed from ouf_authorization.bootstrap_latch where singleton_key=true").query(Boolean.class).single();}
 public boolean bootstrapOpen(){return !bootstrapCompleted()&&registry.active().isEmpty();}
 public boolean isSuperadmin(PrincipalContext p){return authority.isSuperadmin(p);}
 private void requireWriteAuthority(Actor actor){
  authority.lock();
  if(authority.binding(actor.tenant()).isEmpty())return; // Existing installations retain their governed policy until explicit adoption.
  var p=actor.principal();
  if(p==null)throw failure(HttpStatus.FORBIDDEN,"TRUSTED_PRINCIPAL_REQUIRED");
  if(authority.isSuperadmin(p))return;
  if(registry.active().isEmpty()||!registry.authorizeActive(p,new ResourceContext("capability",null,p.tenantId(),null,Map.of()),"authorization.policy.admin","EXECUTE",Instant.now()).allowed())throw failure(HttpStatus.FORBIDDEN,"AUTH_ADMIN_REQUIRED");
 }
 private void ownDraft(UUID id,Actor actor){
  String tenant=db.sql("select tenant_id from ouf_authorization.policy_draft where draft_id=:id").param("id",id).query(String.class).optional().orElse("");
  if(!actor.tenant().equals(tenant))throw failure(HttpStatus.NOT_FOUND,"AUTH_DRAFT_NOT_FOUND");
 }
 public Draft get(UUID id,Actor actor){ownDraft(id,actor);return get(id);}
 private void preserveOtherTenants(PolicyBundle policy,Actor actor){
  var previous=registry.active().map(a->registry.load(a.bundleId(),a.version()));
  var before=previous.map(p->p.grants().stream().filter(g->!actor.tenant().equals(g.tenantId())).collect(java.util.stream.Collectors.toSet())).orElse(Set.of());
  var after=policy.grants().stream().filter(g->!actor.tenant().equals(g.tenantId())).collect(java.util.stream.Collectors.toSet());
  if(!before.equals(after))throw failure(HttpStatus.FORBIDDEN,"AUTH_OTHER_TENANT_GRANTS_PROTECTED");
 }
 public void requireBootstrapPrincipal(PrincipalContext principal){bootstrapAdmin.requirePrincipal(principal);}
 private void completeBootstrap(Actor actor){db.sql("update ouf_authorization.bootstrap_latch set completed=true,completed_at=transaction_timestamp(),completed_by=:subject where singleton_key=true and completed=false").param("subject",actor.subject()).update();}
 private void refreshRuntimeAfterCommit(){
  if(!TransactionSynchronizationManager.isSynchronizationActive())throw new IllegalStateException("AUTH_RUNTIME_REFRESH_REQUIRES_TRANSACTION");
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){runtime.refreshActive();}});
 }
 private void audit(String action,String target,Actor actor){db.sql("insert into ouf_authorization.admin_audit values(:id,:a,:t,:s,:tenant,'HUMAN',:p,:c,transaction_timestamp())").param("id",UUID.randomUUID()).param("a",action).param("t",target).param("s",actor.subject()).param("tenant",actor.tenant()).param("p",actor.policyRef()).param("c",actor.correlation()).update();}
 @Transactional public void register(String owner,CapabilityDescriptor descriptor,Actor actor){
  requireWriteAuthority(actor);
  if(owner==null||owner.isBlank())throw new IllegalArgumentException("ownerRef required");
  if(descriptor==null||descriptor.allowedActors().isEmpty())throw new IllegalArgumentException("allowedActors required");
  String raw=encode(descriptor);
  int n=db.sql("insert into ouf_authorization.capability_registration(capability_id,owner_ref,descriptor,registered_by) values(:id,:owner,cast(:raw as jsonb),:subject) on conflict do nothing").param("id",descriptor.capabilityId()).param("owner",owner).param("raw",raw).param("subject",actor.subject()).update();
  if(n==0)throw failure(HttpStatus.CONFLICT,"AUTH_CAPABILITY_ALREADY_REGISTERED");audit("REGISTER_CAPABILITY",descriptor.capabilityId(),actor);
 }
 public List<Map<String,Object>> capabilities(int limit){if(limit<1||limit>200)throw new IllegalArgumentException("limit 1..200");return db.sql("select capability_id,owner_ref,descriptor from ouf_authorization.capability_registration order by capability_id limit :n").param("n",limit).query().listOfRows();}
 @Transactional public Draft create(PolicyBundle policy,Actor actor){
  requireWriteAuthority(actor);preserveOtherTenants(policy,actor);validate(policy);UUID id=UUID.randomUUID();db.sql("insert into ouf_authorization.policy_draft(draft_id,state,base_active_ref,payload,created_by,tenant_id) values(:id,'DRAFT',:base,cast(:payload as jsonb),:subject,:tenant)").param("id",id).param("base",activeRef()).param("payload",encode(policy)).param("subject",actor.subject()).param("tenant",actor.tenant()).update();audit("CREATE_DRAFT",id.toString(),actor);return get(id);
 }
 public Draft get(UUID id){return db.sql("select draft_id,revision,state,base_active_ref,payload::text from ouf_authorization.policy_draft where draft_id=:id").param("id",id).query((rs,n)->new Draft(rs.getObject(1,UUID.class),rs.getLong(2),rs.getString(3),rs.getString(4),decode(rs.getString(5)))).optional().orElseThrow(()->failure(HttpStatus.NOT_FOUND,"AUTH_DRAFT_NOT_FOUND"));}
 private void lock(UUID id){db.sql("select draft_id from ouf_authorization.policy_draft where draft_id=:id for update").param("id",id).query(UUID.class).optional().orElseThrow(()->failure(HttpStatus.NOT_FOUND,"AUTH_DRAFT_NOT_FOUND"));}
 private Draft editable(UUID id,long revision){lock(id);var d=get(id);if(d.revision()!=revision)throw failure(HttpStatus.PRECONDITION_FAILED,"AUTH_STALE_ETAG");if(!d.state().equals("DRAFT"))throw failure(HttpStatus.CONFLICT,"AUTH_DRAFT_NOT_EDITABLE");return d;}
 @Transactional public Draft replace(UUID id,long revision,PolicyBundle policy,Actor actor){requireWriteAuthority(actor);ownDraft(id,actor);var d=editable(id,revision);preserveOtherTenants(policy,actor);validate(policy);if(!policy.bundleId().equals(d.policy().bundleId())||policy.version()!=d.policy().version())throw failure(HttpStatus.CONFLICT,"AUTH_DRAFT_IDENTITY_IMMUTABLE");db.sql("update ouf_authorization.policy_draft set payload=cast(:p as jsonb),revision=revision+1,updated_at=transaction_timestamp() where draft_id=:id").param("p",encode(policy)).param("id",id).update();audit("UPDATE_DRAFT",id.toString(),actor);return get(id);}
 @Transactional public Draft grant(UUID id,long revision,String grantId,Grant grant,Actor actor){requireWriteAuthority(actor);ownDraft(id,actor);var d=editable(id,revision);var grants=new ArrayList<>(d.policy().grants());grants.removeIf(g->g.grantId().equals(grantId));if(grant!=null){if(!grant.grantId().equals(grantId))throw new IllegalArgumentException("grant id mismatch");grants.add(grant);}var p=d.policy();var updated=replace(id,revision,new PolicyBundle(p.bundleId(),p.version(),p.publishedAt(),p.capabilities(),grants),actor);audit(grant==null?"REVOKE_GRANT_IN_DRAFT":"UPSERT_GRANT",id+":"+grantId,actor);return updated;}
 @Transactional public Draft abandon(UUID id,long revision,Actor actor){requireWriteAuthority(actor);ownDraft(id,actor);editable(id,revision);db.sql("update ouf_authorization.policy_draft set state='ABANDONED',revision=revision+1 where draft_id=:id").param("id",id).update();audit("ABANDON_DRAFT",id.toString(),actor);return get(id);}
 @Transactional public Draft publish(UUID id,long revision,Actor actor){
  db.sql("select pg_advisory_xact_lock(741093)").query(Object.class).single();requireWriteAuthority(actor);ownDraft(id,actor);var d=editable(id,revision);preserveOtherTenants(d.policy(),actor);
  if(!activeRef().equals(d.baseActiveRef()))throw failure(HttpStatus.CONFLICT,"AUTH_ACTIVE_CHANGED_REBASE_REQUIRED");validate(d.policy());
  var active=registry.active();if(active.isPresent()&&(!active.get().bundleId().equals(d.policy().bundleId())||d.policy().version()<=active.get().version()))throw failure(HttpStatus.CONFLICT,"AUTH_POLICY_VERSION_MUST_ADVANCE");
  var p=d.policy();
  if(bootstrapOpen()){
   if(actor.principal()==null)throw failure(HttpStatus.FORBIDDEN,"AUTH_BOOTSTRAP_TRUSTED_PRINCIPAL_REQUIRED");
   try{authority.installAtBootstrap(actor.principal());}
   catch(SecurityException e){throw failure(HttpStatus.FORBIDDEN,e.getMessage());}
  }
  registry.publishAndActivate(new PolicyBundle(p.bundleId(),p.version(),Instant.now(),p.capabilities(),p.grants()),actor.subject());
  completeBootstrap(actor);
  db.sql("update ouf_authorization.policy_draft set state='PUBLISHED',revision=revision+1 where draft_id=:id").param("id",id).update();audit("PUBLISH_ACTIVATE",id.toString(),actor);
  refreshRuntimeAfterCommit();
  return get(id);
 }
 private void validate(PolicyBundle policy){
  if(encode(policy).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>5*1024*1024)throw new IllegalArgumentException("AUTH_POLICY_TOO_LARGE");
  for(var c:policy.capabilities()){
   String raw=db.sql("select descriptor::text from ouf_authorization.capability_registration where capability_id=:id").param("id",c.capabilityId()).query(String.class).optional().orElseThrow(()->failure(HttpStatus.CONFLICT,"AUTH_CAPABILITY_UNREGISTERED"));
   try{if(!json.readValue(raw,CapabilityDescriptor.class).equals(c))throw failure(HttpStatus.CONFLICT,"AUTH_CAPABILITY_SEMANTICS_CHANGED");}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
  }
 }
}
