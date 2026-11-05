package it.comune.trieste.ouf.onboarding.installation;

import static it.comune.trieste.ouf.onboarding.installation.PlatformAuthorityReconciliation.*;
import java.sql.SQLException;
import java.time.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Resolves the persisted xid8 against the admitted live PG17 server, before the file guard.
 * No business-row heuristic or caller-supplied transaction outcome. Approval is still owned
 * by the platform; this class does not authenticate a HUMAN or install a runtime grant.
 */
public final class PostgresRecoveryOutcomeResolver {
  public record Approval(String requestSha256,Instant issuedAt,Instant expiresAt) {}
  public interface ApprovalLease extends AutoCloseable {
    /** Local, current authenticated approval and revocation exclusion for this exact command. */
    Approval current();
    @Override void close();
  }
  private final DataSource dataSource;private final PostgresRecoveryTransactions.Database admitted;
  public PostgresRecoveryOutcomeResolver(DataSource ds,PostgresRecoveryTransactions.Database admitted){this.dataSource=Objects.requireNonNull(ds);this.admitted=Objects.requireNonNull(admitted);}
  /** Caller must close the returned preflight even if it never calls store.reconcile.
   * SQL and connection acquisition happen outside both file guard and owner transaction.
   * The datasource must have finite pool/login timeouts; no retry is performed here.
   */
  public Prepared prepare(FilePlatformRecoveryAuthority store,Request request,ApprovalLease approval){
    if(TransactionSynchronizationManager.isActualTransactionActive())throw denied("Outcome resolution requires no owner transaction");
    Objects.requireNonNull(approval);boolean transferred=false;
    try{
      String fingerprint=FilePlatformRecoveryAuthority.reconciliationFingerprint(request);
      Approval approved=approval.current();validate(approved,fingerprint);
      var transaction=store.pendingTransaction(request);
      if(!admitted.equals(transaction.database()))throw denied("Recorded PostgreSQL admission differs");
      String status;
      try(var c=dataSource.getConnection()){status=PostgresRecoveryTransactions.status(c,transaction);}
      catch(SQLException ex){throw new IllegalStateException("PostgreSQL outcome unavailable",ex);}
      Outcome outcome;
      if("committed".equals(status))outcome=Outcome.COMMITTED;
      else if("aborted".equals(status))outcome=Outcome.ROLLED_BACK;
      else throw denied("PostgreSQL outcome not terminal or no longer retained");
      if(!approved.equals(approval.current()))throw denied("Resolution approval changed during preflight");validate(approved,fingerprint);
      Instant now=Instant.now(),until=now.plusSeconds(30);if(approved.expiresAt().isBefore(until))until=approved.expiresAt();
      Evidence evidence=new Evidence(fingerprint,"postgres/"+FilePlatformRecoveryAuthority.identityFingerprint(admitted),"postgres/xid8/"+transaction.xid8(),outcome,now,until);
      var result=new Prepared(request,transaction,approved,approval,evidence);transferred=true;return result;
    }finally{if(!transferred)approval.close();}
  }
  /** Opaque terminal-outcome proof. Only this resolver can construct it. */
  public static final class Prepared implements Gate,Lease {
    private final Request request;private final PostgresRecoveryTransactions.Transaction transaction;
    private final Approval approved;private final ApprovalLease approval;private final Evidence evidence;
    private final AtomicBoolean acquired=new AtomicBoolean(),closed=new AtomicBoolean();
    private Prepared(Request request,PostgresRecoveryTransactions.Transaction transaction,Approval approved,ApprovalLease approval,Evidence evidence){this.request=request;this.transaction=transaction;this.approved=approved;this.approval=approval;this.evidence=evidence;}
    @Override public Lease tryAcquire(Request r){throw denied("Recorded PostgreSQL transaction binding required");}
    Lease tryAcquire(Request r,PostgresRecoveryTransactions.Transaction recorded){
      if(!request.equals(r)||!transaction.equals(recorded)||!acquired.compareAndSet(false,true))throw denied("Prepared PostgreSQL resolution binding differs or consumed");
      current();return this;
    }
    @Override public Evidence current(){
      if(closed.get()||!approved.equals(approval.current()))throw denied("Resolution approval unavailable or changed");
      validate(approved,evidence.requestSha256());
      if(!Instant.now().isBefore(evidence.validUntil()))throw denied("Prepared PostgreSQL resolution expired");
      return evidence;
    }
    @Override public void close(){if(closed.compareAndSet(false,true))approval.close();}
  }
  private static void validate(Approval a,String fingerprint){
    Instant now=Instant.now();
    if(a==null||!fingerprint.equals(a.requestSha256())||a.issuedAt()==null||a.expiresAt()==null||a.issuedAt().isAfter(now)||!now.isBefore(a.expiresAt())||!a.expiresAt().isAfter(a.issuedAt())||Duration.between(a.issuedAt(),a.expiresAt()).compareTo(Duration.ofMinutes(5))>0)throw denied("Fresh exact platform resolution approval required");
  }
  private static IllegalStateException denied(String message){return new IllegalStateException(message);}
}
