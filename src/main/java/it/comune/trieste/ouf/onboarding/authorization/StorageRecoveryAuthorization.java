package it.comune.trieste.ouf.onboarding.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.CapabilityDescriptor;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.*;

/** Private, explicitly constructed same-database integration. No bean, endpoint or automatic grant.
 * Lock order: storage control, policy activation lock, projection, decision. Network-free.
 */
public final class StorageRecoveryAuthorization {
  public static final CapabilityDescriptor CAPABILITY=new CapabilityDescriptor("installation.storage.recovery","EXECUTE","installation.storage.recovery",Set.of(PrincipalContext.ActorType.HUMAN));
  public static final Set<String> ACTIONS=Set.of("DRAIN","RECONCILE","FREEZE");
  private final JdbcClient db; private final TransactionTemplate tx; private final ObjectMapper json;
  private final AuthorizationPolicyRegistry registry; private final CanonicalHash hashes;
  private final String installation,tenant;
  private final StorageRecoveryAuthorityFence fence;
  public record Request(String commandKey,UUID runtimeSessionId,long epoch,Set<String> actions,Instant expiresAt) {
    public Request { actions=actions==null?Set.of():Set.copyOf(actions); }
  }
  public record Decision(UUID id,String installation,String tenant,UUID runtimeSessionId,long epoch,Set<String> actions,
                         Instant issuedAt,Instant expiresAt,String state,String bundleId,long bundleVersion,PrincipalContext principal,String requestHash,long authorityGeneration) {}
  public StorageRecoveryAuthorization(JdbcClient db,TransactionTemplate tx,ObjectMapper json,AuthorizationPolicyRegistry registry,CanonicalHash hashes,String installation,String tenant){
    this(db,tx,json,registry,hashes,installation,tenant,null);
  }
  public StorageRecoveryAuthorization(JdbcClient db,TransactionTemplate tx,ObjectMapper json,AuthorizationPolicyRegistry registry,CanonicalHash hashes,String installation,String tenant,StorageRecoveryAuthorityFence fence){
    if(!reference(installation)||!reference(tenant))throw new IllegalArgumentException("Trusted installation and tenant required");
    this.db=db;this.tx=new TransactionTemplate(tx.getTransactionManager());this.tx.setTimeout(12);this.json=json;this.registry=registry;this.hashes=hashes;this.installation=installation;this.tenant=tenant;this.fence=fence;
  }
  public Decision prepare(PrincipalContext principal,Request request){
    outside();Objects.requireNonNull(request);
    if(request.commandKey()==null||!request.commandKey().matches("[A-Za-z0-9_.:-]{1,128}")||request.runtimeSessionId()==null||request.epoch()<1||request.actions().isEmpty()||!ACTIONS.containsAll(request.actions())||request.expiresAt()==null)throw denied("Invalid recovery request");
    return tx.execute(status->{
      control(request.epoch());policyLock();Instant now=now();human(principal,now);
      if(!request.expiresAt().isAfter(now)||request.expiresAt().isAfter(now.plusSeconds(900))||request.expiresAt().isAfter(principal.claims().authenticatedAt().plusSeconds(900)))throw denied("Finite fresh recovery authority required");
      var active=registry.active().orElseThrow(()->denied("No active policy"));
      var proof=currentFence(active.bundleId(),active.version());
      if(request.expiresAt().isAfter(proof.expiresAt()))throw denied("Decision exceeds current authority deadline");
      for(String action:new TreeSet<>(request.actions()))evaluate(principal,action,now);
      // One durable command even if the response is lost; namespace includes trusted installation.
      db.sql("select pg_advisory_xact_lock(hashtextextended(:key,741094))").param("key",installation+":"+request.commandKey()).query(Object.class).single();
      String hash=fingerprint(principal,request,proof.generation());
      var existing=db.sql("select decision_id from ouf_authorization.storage_recovery_decision where installation_ref=:i and command_key=:k").param("i",installation).param("k",request.commandKey()).query(UUID.class).optional();
      if(existing.isPresent()){
        var old=decision(existing.get(),false);if(!hash.equals(old.requestHash()))throw denied("Command key has different binding");
        current(old,null);return old;
      }
      UUID id=UUID.randomUUID();
      db.sql("insert into ouf_authorization.storage_recovery_decision(decision_id,installation_ref,tenant_id,command_key,request_hash,runtime_session_id,epoch,principal,actions,bundle_id,bundle_version,issued_at,expires_at,state,authority_generation) values(:id,:i,:t,:k,:h,:r,:e,cast(:p as jsonb),cast(:a as jsonb),:b,:v,:now,:until,'PREPARED',:generation)")
        .param("id",id).param("i",installation).param("t",tenant).param("k",request.commandKey()).param("h",hash).param("r",request.runtimeSessionId()).param("e",request.epoch())
        .param("p",encode(principal)).param("a",encode(new TreeSet<>(request.actions()))).param("b",active.bundleId()).param("v",active.version()).param("now",at(now)).param("until",at(request.expiresAt())).param("generation",proof.generation()).update();
      audit(id,principal.subjectId(),"PREPARED",null);var prepared=decision(id,false);current(prepared,null);return prepared;
    });
  }
  public long deliver(UUID id,long expectedProjectionVersion){
    outside();return tx.execute(status->{
      control(null);policyLock();var projection=projection(true);var value=decision(id,true);current(value,null);controlEpoch(value.epoch());
      long version=((Number)projection.get("version")).longValue();
      if(id.equals(projection.get("decision_id"))&&"DELIVERED".equals(value.state()))return version;
      if(version!=expectedProjectionVersion)throw denied("Stale projection version");
      if(!"PREPARED".equals(value.state()))throw denied("Decision cannot be redelivered");
      UUID previous=(UUID)projection.get("decision_id");
      if(previous!=null){var old=decision(previous,true);scope(old);db.sql("update ouf_authorization.storage_recovery_decision set state='REVOKED' where decision_id=:id").param("id",previous).update();audit(previous,old.principal().subjectId(),"SUPERSEDED",version+1);}
      db.sql("update ouf_authorization.storage_recovery_decision set state='DELIVERED' where decision_id=:id").param("id",id).update();
      db.sql("update ouf_onboarding.storage_recovery_authority_projection set decision_id=:id,version=version+1 where singleton=true").param("id",id).update();
      audit(id,value.principal().subjectId(),"DELIVERED",version+1);current(value,null);return version+1;
    });
  }
  public long revoke(PrincipalContext actor,UUID id,long expectedProjectionVersion){
    outside();return tx.execute(status->{
      control(null);policyLock();human(actor,now());var active=registry.active().orElseThrow(()->denied("No active policy"));currentFence(active.bundleId(),active.version());evaluate(actor,"REVOKE",now());
      var projection=projection(true);var value=decision(id,true);scope(value);
      long version=((Number)projection.get("version")).longValue();
      if("REVOKED".equals(value.state()))return version;
      if(version!=expectedProjectionVersion)throw denied("Stale projection version");
      db.sql("update ouf_authorization.storage_recovery_decision set state='REVOKED' where decision_id=:id").param("id",id).update();
      if(id.equals(projection.get("decision_id"))){db.sql("update ouf_onboarding.storage_recovery_authority_projection set decision_id=null,version=version+1 where singleton=true").update();version++;}
      audit(id,actor.subjectId(),"REVOKED",version);currentFence(active.bundleId(),active.version());human(actor,now());return version;
    });
  }
  /** Called only inside the owner transaction, after its control row lock. */
  public Decision lockCurrent(UUID runtime,long epoch,String action){
    inside();policyLock();var p=projection(false);UUID id=(UUID)p.get("decision_id");if(id==null)throw denied("No delivered recovery decision");
    var value=decision(id,false);current(value,action);
    if(!"DELIVERED".equals(value.state())||!value.runtimeSessionId().equals(runtime)||value.epoch()!=epoch)throw denied("Delivered recovery binding differs");
    return value;
  }
  private void current(Decision value,String action){
    scope(value);live(value);Instant now=now();
    var active=registry.active().orElseThrow(()->denied("No active policy"));
    if(!active.bundleId().equals(value.bundleId())||active.version()!=value.bundleVersion())throw denied("Recovery policy generation changed");
    var proof=currentFence(active.bundleId(),active.version());
    if(value.authorityGeneration()<1||value.authorityGeneration()!=proof.generation())throw denied("Current platform authority generation differs");
    if(action!=null&&!value.actions().contains(action))throw denied("Recovery action not delivered");
    for(String selected:action==null?new TreeSet<>(value.actions()):Set.of(action))evaluate(value.principal(),selected,now);
  }
  private void live(Decision value){Instant now=now();human(value.principal(),now);if("REVOKED".equals(value.state())||!now.isBefore(value.expiresAt()))throw denied("Recovery decision revoked or expired");}
  private StorageRecoveryAuthorityFence.Proof currentFence(String bundle,long version){
    if(fence==null)throw denied("Current platform authority fence unavailable");
    var proof=fence.lockCurrent();Instant now=now();
    if(proof==null||proof.revoked())throw denied("Current platform authority revoked or unavailable");
    if(!installation.equals(proof.installation())||!tenant.equals(proof.tenant())||!bundle.equals(proof.bundleId())||version!=proof.bundleVersion()||proof.generation()<1)
      throw denied("Current platform authority binding differs");
    if(proof.issuedAt()==null||proof.expiresAt()==null||proof.issuedAt().isAfter(now)||!now.isBefore(proof.expiresAt())||!proof.issuedAt().isBefore(proof.expiresAt())||Duration.between(proof.issuedAt(),proof.expiresAt()).compareTo(Duration.ofMinutes(15))>0)
      throw denied("Current platform authority window invalid or expired");
    return proof;
  }
  private String fingerprint(PrincipalContext p,Request r,long generation){
    var actor=new TreeMap<String,Object>();actor.put("subject",p.subjectId());actor.put("tenant",p.tenantId());actor.put("actorType",p.actorType().name());actor.put("servicePrincipal",p.servicePrincipalId());
    actor.put("authenticationContext",p.authenticationContextRef());actor.put("issuer",p.issuer());actor.put("audience",p.audience());actor.put("scopes",new TreeSet<>(p.scopes()));
    var claims=new TreeMap<String,Object>();claims.put("roles",new TreeSet<>(p.claims().externalRoleRefs()));claims.put("acr",p.claims().acr());claims.put("amr",new TreeSet<>(p.claims().amr()));claims.put("authenticatedAt",p.claims().authenticatedAt().toString());actor.put("claims",claims);
    return hashes.of(Map.of("installation",installation,"tenant",tenant,"actor",actor,"commandKey",r.commandKey(),"runtime",r.runtimeSessionId().toString(),"epoch",r.epoch(),"actions",new TreeSet<>(r.actions()),"expiresAt",r.expiresAt().toString(),"authorityGeneration",generation));
  }
  private void human(PrincipalContext p,Instant now){
    if(p==null||p.actorType()!=PrincipalContext.ActorType.HUMAN||!tenant.equals(p.tenantId())||p.claims()==null||p.claims().authenticatedAt()==null||p.claims().authenticatedAt().isAfter(now)||!now.isBefore(p.claims().authenticatedAt().plusSeconds(900)))throw denied("Fresh trusted HUMAN required");
  }
  private void evaluate(PrincipalContext p,String action,Instant now){
    var active=registry.active().orElseThrow(()->denied("No active policy"));
    if(registry.load(active.bundleId(),active.version()).capabilities().stream().noneMatch(CAPABILITY::equals))throw denied("Storage recovery capability semantics differ");
    var resource=new ResourceContext("installation-storage-recovery",installation,tenant,null,Map.of("module","installation","storageOwner","onboarding","recoveryAction",action,"detailLevel","SECURITY_SENSITIVE"));
    if(!registry.authorizeActive(p,resource,CAPABILITY.capabilityId(),"EXECUTE",now).allowed())throw denied("Storage recovery policy denied");
  }
  private void scope(Decision d){if(!installation.equals(d.installation())||!tenant.equals(d.tenant()))throw denied("Recovery installation differs");}
  private void policyLock(){db.sql("select pg_advisory_xact_lock_shared(741093)").query(Object.class).single();}
  private void control(Long epoch){
    db.sql("set local lock_timeout='2s'").update();db.sql("set local statement_timeout='5s'").update();
    db.sql("select epoch from ouf_onboarding.managed_file_storage_control where singleton=true for share").query(Long.class).single();
    if(epoch!=null)controlEpoch(epoch);
  }
  private void controlEpoch(long epoch){if(db.sql("select epoch from ouf_onboarding.managed_file_storage_control where singleton=true").query(Long.class).single()!=epoch)throw denied("Storage generation changed");}
  private Map<String,Object> projection(boolean exclusive){return db.sql("select * from ouf_onboarding.storage_recovery_authority_projection where singleton=true for "+(exclusive?"update":"share")).query().singleRow();}
  private Decision decision(UUID id,boolean exclusive){return db.sql("select * from ouf_authorization.storage_recovery_decision where decision_id=:id for "+(exclusive?"update":"share")).param("id",id).query((rs,n)->new Decision(rs.getObject("decision_id",UUID.class),rs.getString("installation_ref"),rs.getString("tenant_id"),rs.getObject("runtime_session_id",UUID.class),rs.getLong("epoch"),Set.of(decode(rs.getString("actions"),String[].class)),rs.getObject("issued_at",OffsetDateTime.class).toInstant(),rs.getObject("expires_at",OffsetDateTime.class).toInstant(),rs.getString("state"),rs.getString("bundle_id"),rs.getLong("bundle_version"),decode(rs.getString("principal"),PrincipalContext.class),rs.getString("request_hash"),rs.getLong("authority_generation"))).single();}
  private void audit(UUID id,String subject,String event,Long version){db.sql("insert into ouf_authorization.storage_recovery_delivery_audit(event_id,decision_id,subject_id,event,projection_version) values(:e,:d,:s,:v,:p)").param("e",UUID.randomUUID()).param("d",id).param("s",subject).param("v",event).param("p",version).update();}
  private Instant now(){return db.sql("select clock_timestamp()").query(OffsetDateTime.class).single().toInstant();}
  private static OffsetDateTime at(Instant value){return OffsetDateTime.ofInstant(value,ZoneOffset.UTC);}
  private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException(e);}}
  private <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException(e);}}
  private static boolean reference(String value){return value!=null&&value.matches("[A-Za-z0-9_.:/-]{1,256}");}
  private static IllegalStateException denied(String message){return new IllegalStateException(message);}
  private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw denied("Delivery requires no ambient transaction");}
  private static void inside(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw denied("Gate requires owner transaction");}
}
