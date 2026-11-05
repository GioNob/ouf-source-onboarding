package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import it.comune.trieste.ouf.onboarding.authorization.StorageRecoveryAuthorityFence.Proof;
import it.comune.trieste.ouf.onboarding.installation.*;
import static it.comune.trieste.ouf.onboarding.installation.PlatformAuthorityReconciliation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;

/** Explicit server adapter; no controller, bean, new grant or durable approval issuer.
 * Fresh authenticated request on every attempt. Local policy distribution is a separate gate.
 */
public final class StorageRecoveryHumanApproval {
  public static final CapabilityDescriptor CAPABILITY=new CapabilityDescriptor("installation.storage.authority.reconcile","EXECUTE","installation.storage.authority.reconcile",Set.of(PrincipalContext.ActorType.HUMAN));
  public record Command(UUID commandId,UUID leaseId,String leaseSha256,long expectedGeneration,String reasonCode,Proof revokedNext) {}
  public record Approved(Request request,PostgresRecoveryOutcomeResolver.ApprovalLease lease) implements AutoCloseable {
    @Override public void close(){lease.close();}
  }
  private final String installation,tenant,issuer,audience;private final LocalAuthorization policy;
  public StorageRecoveryHumanApproval(String installation,String tenant,String issuer,String audience,LocalAuthorization policy){
    this.installation=Objects.requireNonNull(installation);this.tenant=Objects.requireNonNull(tenant);this.issuer=Objects.requireNonNull(issuer);this.audience=Objects.requireNonNull(audience);this.policy=Objects.requireNonNull(policy);
  }
  public Approved approve(HttpServletRequest http,Command command){
    if(!"POST".equals(http.getMethod()))throw denied("POST required");
    TrustedWriteProof.require(http);
    var authentication=SecurityContextHolder.getContext().getAuthentication();
    if(!(authentication instanceof IamSecurityConfiguration.TrustedJwtAuthenticationToken token)||!token.isAuthenticated())throw denied("Authenticated IAM bearer required");
    var principal=ServletAuthorization.principal(http).context();
    if(!token.getPrincipal().context().equals(principal)||!issuer.equals(principal.issuer())||!audience.equals(principal.audience()))throw denied("IAM binding differs");
    Instant now=Instant.now(),tokenExpiry=token.jwt().getExpiresAt();
    if(token.jwt().getIssuedAt()==null||token.jwt().getIssuedAt().isAfter(now)||tokenExpiry==null||!now.isBefore(tokenExpiry))throw denied("Bearer expired or future");
    human(principal,now);
    var snapshot=policy.currentSnapshot();
    if(!snapshot.bundle().capabilities().contains(CAPABILITY))throw denied("Recovery capability semantics differ");
    String decision=snapshot.bundle().bundleId()+":"+snapshot.bundle().version()+":"+CAPABILITY.capabilityId();
    var request=new Request(command.commandId(),command.leaseId(),command.leaseSha256(),command.expectedGeneration(),principal.subjectId(),command.reasonCode(),decision,command.revokedNext());
    if(command.revokedNext()==null||!installation.equals(command.revokedNext().installation())||!tenant.equals(command.revokedNext().tenant()))throw denied("Recovery installation differs");
    String fingerprint=FilePlatformRecoveryAuthority.reconciliationFingerprint(request);
    var resource=new ResourceContext("installation-storage-authority",installation,tenant,null,Map.of("detailLevel","SECURITY_SENSITIVE","recoveryAction","RECONCILE_AUTHORITY","requestSha256",fingerprint,"reasonCode",command.reasonCode()));
    Instant until=now.plusSeconds(30);if(tokenExpiry.isBefore(until))until=tokenExpiry;
    Instant freshness=principal.claims().authenticatedAt().plusSeconds(300);if(freshness.isBefore(until))until=freshness;
    var approval=new PostgresRecoveryOutcomeResolver.Approval(fingerprint,now,until);
    var lease=new HumanLease(principal,snapshot,resource,approval);
    lease.current();return new Approved(request,lease);
  }
  private final class HumanLease implements PostgresRecoveryOutcomeResolver.ApprovalLease {
    private final PrincipalContext principal;private final LocalAuthorization.PolicySnapshot snapshot;
    private final ResourceContext resource;private final PostgresRecoveryOutcomeResolver.Approval approval;
    private LocalAuthorization.SnapshotLease sealed;private boolean closed;
    private HumanLease(PrincipalContext p,LocalAuthorization.PolicySnapshot s,ResourceContext r,PostgresRecoveryOutcomeResolver.Approval a){principal=p;snapshot=s;resource=r;approval=a;}
    @Override public synchronized PostgresRecoveryOutcomeResolver.Approval current(){
      Instant now=Instant.now();if(closed||now.isBefore(approval.issuedAt())||!now.isBefore(approval.expiresAt()))throw denied("Human approval expired or closed");
      human(principal,now);
      if((sealed==null?policy.currentSnapshot():sealed.current())!=snapshot)throw denied("Human approval policy changed");
      if(!policy.evaluate(snapshot,principal,resource,CAPABILITY.capabilityId(),"EXECUTE").allowed())throw denied("Human recovery policy denied");
      return approval;
    }
    @Override public synchronized void seal(){current();if(sealed!=null)throw denied("Human approval already sealed");sealed=policy.tryLease(snapshot);current();}
    @Override public synchronized void close(){if(!closed){closed=true;if(sealed!=null)sealed.close();}}
  }
  private void human(PrincipalContext p,Instant now){
    if(p.actorType()!=PrincipalContext.ActorType.HUMAN||!tenant.equals(p.tenantId())||p.servicePrincipalId()!=null||p.claims()==null||p.claims().authenticatedAt()==null||p.claims().authenticatedAt().isAfter(now)||!now.isBefore(p.claims().authenticatedAt().plusSeconds(300)))throw denied("Fresh tenant HUMAN required");
  }
  private static SecurityException denied(String message){return new SecurityException(message);}
}
