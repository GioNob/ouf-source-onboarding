package it.comune.trieste.ouf.onboarding.authorization;

import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.ResourceContext;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Protected governance authority. Ordinary policy grants cannot create, deny or transfer it. */
@Service
public class SuperadminAuthority {
  public record Binding(String tenant, String issuer, String roleRef, String subjectId, long revision) {}
  public record Transfer(UUID id, String tenant, String issuer, String sourceRole, String targetRole, String sourceSubjectId, String targetSubjectId,
      long bindingRevision, long revision, String state, String proposedBy, String acceptedBy,
      String reason, Instant expiresAt) {}
  private final JdbcClient db;
  private final BootstrapAdministrator bootstrap;
  private final AuthorizationPolicyRegistry registry;
  public SuperadminAuthority(JdbcClient db, BootstrapAdministrator bootstrap, AuthorizationPolicyRegistry registry) {
    this.db=db; this.bootstrap=bootstrap; this.registry=registry;
  }
  private DomainFailure fail(HttpStatus status, String code) { return new DomainFailure(status,code,code); }
  public void lock() { db.sql("select pg_advisory_xact_lock(741093)").query(Object.class).single(); }
  private Instant now() { return db.sql("select clock_timestamp()").query((r,n)->r.getTimestamp(1).toInstant()).single(); }
  public Optional<Binding> binding(String tenant) {
    return db.sql("select tenant_id,issuer,role_ref,subject_id,revision from ouf_authorization.superadmin_binding where tenant_id=:tenant")
        .param("tenant",tenant).query((r,n)->new Binding(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5))).optional();
  }
  public boolean isSuperadmin(PrincipalContext p) {
    return p!=null && p.scopes().contains(BootstrapAdministrator.CAPABILITY)
        && binding(p.tenantId()).map(b->BootstrapAdministrator.matches(p,b.issuer(),b.tenant(),b.roleRef(),b.subjectId())).orElse(false);
  }
  public void requireHumanScope(PrincipalContext p) {
    if(p==null || p.actorType()!=PrincipalContext.ActorType.HUMAN || !p.scopes().contains(BootstrapAdministrator.CAPABILITY))
      throw fail(HttpStatus.FORBIDDEN,"AUTH_SUPERADMIN_HUMAN_SCOPE_REQUIRED");
  }
  private Binding requireCurrent(PrincipalContext p) {
    requireHumanScope(p);
    var b=binding(p.tenantId()).orElseThrow(()->fail(HttpStatus.FORBIDDEN,"AUTH_SUPERADMIN_REQUIRED"));
    if(!BootstrapAdministrator.matches(p,b.issuer(),b.tenant(),b.roleRef(),b.subjectId())) throw fail(HttpStatus.FORBIDDEN,"AUTH_SUPERADMIN_REQUIRED");
    return b;
  }
  public Binding current(PrincipalContext p) { return requireCurrent(p); }
  private void audit(String action,String target,PrincipalContext p,String ref) {
    db.sql("insert into ouf_authorization.admin_audit values(:id,:action,:target,:subject,:tenant,'HUMAN',:ref,:correlation,transaction_timestamp())")
      .param("id",UUID.randomUUID()).param("action",action).param("target",target).param("subject",p.subjectId())
      .param("tenant",p.tenantId()).param("ref",ref).param("correlation",UUID.randomUUID().toString()).update();
  }
  private void history(Binding b,PrincipalContext p,UUID transfer) {
    db.sql("insert into ouf_authorization.superadmin_history(tenant_id,revision,issuer,role_ref,subject_id,changed_by,transfer_id) values(:tenant,:revision,:issuer,:role,:nominee,:subject,:transfer)")
      .param("tenant",b.tenant()).param("revision",b.revision()).param("issuer",b.issuer()).param("role",b.roleRef()).param("nominee",b.subjectId())
      .param("subject",p.subjectId()).param("transfer",transfer).update();
  }
  private Binding install(PrincipalContext p) {
    if(binding(p.tenantId()).isPresent()) throw fail(HttpStatus.CONFLICT,"AUTH_SUPERADMIN_ALREADY_CONFIGURED");
    var b=new Binding(bootstrap.tenant(),bootstrap.issuer(),bootstrap.role(),bootstrap.subject(),0);
    db.sql("insert into ouf_authorization.superadmin_binding(tenant_id,issuer,role_ref,subject_id,revision,changed_by) values(:tenant,:issuer,:role,:nominee,0,:subject)")
      .param("tenant",b.tenant()).param("issuer",b.issuer()).param("role",b.roleRef()).param("nominee",b.subjectId()).param("subject",p.subjectId()).update();
    history(b,p,null);audit("INSTALL_SUPERADMIN",b.subjectId()!=null?"subject:"+b.subjectId():"role:"+b.roleRef(),p,"superadmin:0");return b;
  }
  @Transactional public Binding installAtBootstrap(PrincipalContext p) {
    lock();bootstrap.requirePrincipal(p);
    boolean closed=db.sql("select completed from ouf_authorization.bootstrap_latch where singleton_key=true").query(Boolean.class).single();
    if(closed || registry.active().isPresent()) throw fail(HttpStatus.CONFLICT,"AUTH_BOOTSTRAP_CLOSED");
    return install(p);
  }
  /** One-time adoption on an existing installation: BOTH current policy authority and configured IAM designation. */
  @Transactional public Binding adopt(PrincipalContext p) {
    lock();requireHumanScope(p);bootstrap.requireIdentity(p);
    boolean closed=db.sql("select completed from ouf_authorization.bootstrap_latch where singleton_key=true").query(Boolean.class).single();
    if(!closed || registry.active().isEmpty()) throw fail(HttpStatus.CONFLICT,"AUTH_EXISTING_INSTALLATION_REQUIRED");
    var decision=registry.authorizeActive(p,new ResourceContext("capability",null,p.tenantId(),null,Map.of()),
        BootstrapAdministrator.CAPABILITY,"EXECUTE",now());
    if(!decision.allowed()) throw fail(HttpStatus.FORBIDDEN,"AUTH_EXISTING_ADMIN_REQUIRED");
    return install(p);
  }
  @Transactional public Transfer propose(PrincipalContext p,long revision,String targetRole,String reason) {
    return propose(p,revision,targetRole,null,reason);
  }
  @Transactional public Transfer propose(PrincipalContext p,long revision,String targetRole,String targetSubject,String reason) {
    lock();var b=requireCurrent(p);
    if(b.revision()!=revision) throw fail(HttpStatus.PRECONDITION_FAILED,"AUTH_STALE_ETAG");
    if(!BootstrapAdministrator.validDesignation(targetRole,targetSubject) || (Objects.equals(b.roleRef(),targetRole) && Objects.equals(b.subjectId(),targetSubject)) || reason==null || reason.isBlank() || reason.length()>1000)
      throw new IllegalArgumentException("AUTH_TRANSFER_INVALID");
    var time=now();
    db.sql("update ouf_authorization.superadmin_transfer set state='CANCELLED',revision=revision+1 where tenant_id=:tenant and state='PENDING' and expires_at<=:now")
      .param("tenant",b.tenant()).param("now",Timestamp.from(time)).update();
    if(db.sql("select count(*) from ouf_authorization.superadmin_transfer where tenant_id=:tenant and state='PENDING'").param("tenant",b.tenant()).query(Long.class).single()>0)
      throw fail(HttpStatus.CONFLICT,"AUTH_TRANSFER_PENDING");
    var id=UUID.randomUUID();
    db.sql("insert into ouf_authorization.superadmin_transfer(transfer_id,tenant_id,issuer,source_role,target_role,source_subject_id,target_subject_id,binding_revision,state,proposed_by,reason,expires_at) values(:id,:tenant,:issuer,:source,:target,:sourceSubject,:targetSubject,:version,'PENDING',:subject,:reason,:expires)")
      .param("id",id).param("tenant",b.tenant()).param("issuer",b.issuer()).param("source",b.roleRef()).param("target",targetRole).param("sourceSubject",b.subjectId()).param("targetSubject",targetSubject)
      .param("version",b.revision()).param("subject",p.subjectId()).param("reason",reason).param("expires",Timestamp.from(time.plusSeconds(900))).update();
    audit("PROPOSE_SUPERADMIN_TRANSFER",id.toString(),p,"superadmin:"+b.revision());return read(id,p);
  }
  private Transfer load(UUID id,String tenant) {
    return db.sql("select transfer_id,tenant_id,issuer,source_role,target_role,source_subject_id,target_subject_id,binding_revision,revision,state,proposed_by,accepted_by,reason,expires_at from ouf_authorization.superadmin_transfer where transfer_id=:id and tenant_id=:tenant")
      .param("id",id).param("tenant",tenant).query((r,n)->new Transfer(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getLong(8),r.getLong(9),r.getString(10),r.getString(11),r.getString(12),r.getString(13),r.getTimestamp(14).toInstant()))
      .optional().orElseThrow(()->fail(HttpStatus.NOT_FOUND,"AUTH_TRANSFER_NOT_FOUND"));
  }
  public Transfer read(UUID id,PrincipalContext p) {
    requireHumanScope(p);var t=load(id,p.tenantId());
    if(!isSuperadmin(p) && !BootstrapAdministrator.matches(p,t.issuer(),t.tenant(),t.targetRole(),t.targetSubjectId())) throw fail(HttpStatus.FORBIDDEN,"AUTH_TRANSFER_PARTICIPANT_REQUIRED");
    return t;
  }
  private Transfer pending(UUID id,PrincipalContext p,long revision) {
    var t=load(id,p.tenantId());
    if(t.revision()!=revision) throw fail(HttpStatus.PRECONDITION_FAILED,"AUTH_STALE_ETAG");
    if(!t.state().equals("PENDING")) throw fail(HttpStatus.CONFLICT,"AUTH_TRANSFER_NOT_PENDING");
    if(!now().isBefore(t.expiresAt())) throw fail(HttpStatus.GONE,"AUTH_TRANSFER_EXPIRED");
    var b=binding(p.tenantId()).orElseThrow(()->fail(HttpStatus.CONFLICT,"AUTH_BINDING_CHANGED"));
    if(b.revision()!=t.bindingRevision() || !Objects.equals(b.roleRef(),t.sourceRole()) || !Objects.equals(b.subjectId(),t.sourceSubjectId()) || !b.issuer().equals(t.issuer())) throw fail(HttpStatus.CONFLICT,"AUTH_BINDING_CHANGED");
    return t;
  }
  @Transactional public Binding accept(UUID id,long revision,PrincipalContext p) {
    lock();requireHumanScope(p);var t=pending(id,p,revision);
    if(!BootstrapAdministrator.matches(p,t.issuer(),t.tenant(),t.targetRole(),t.targetSubjectId())) throw fail(HttpStatus.FORBIDDEN,t.targetSubjectId()!=null?"AUTH_TARGET_SUBJECT_REQUIRED":"AUTH_TARGET_ROLE_REQUIRED");
    db.sql("update ouf_authorization.superadmin_binding set role_ref=:role,subject_id=:nominee,revision=revision+1,changed_by=:subject,changed_at=transaction_timestamp() where tenant_id=:tenant")
      .param("role",t.targetRole()).param("nominee",t.targetSubjectId()).param("subject",p.subjectId()).param("tenant",p.tenantId()).update();
    db.sql("update ouf_authorization.superadmin_transfer set state='ACCEPTED',revision=revision+1,accepted_by=:subject where transfer_id=:id")
      .param("subject",p.subjectId()).param("id",id).update();
    var b=binding(p.tenantId()).orElseThrow();history(b,p,id);audit("ACCEPT_SUPERADMIN_TRANSFER",id.toString(),p,"superadmin:"+b.revision());return b;
  }
  @Transactional public Transfer cancel(UUID id,long revision,PrincipalContext p) {
    lock();requireCurrent(p);pending(id,p,revision);
    db.sql("update ouf_authorization.superadmin_transfer set state='CANCELLED',revision=revision+1 where transfer_id=:id").param("id",id).update();
    audit("CANCEL_SUPERADMIN_TRANSFER",id.toString(),p,"superadmin:"+binding(p.tenantId()).orElseThrow().revision());return read(id,p);
  }
}
