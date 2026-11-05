package it.comune.trieste.ouf.onboarding.installation;

import it.comune.trieste.ouf.onboarding.authorization.StorageRecoveryAuthorityFence;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit internal bridge to platform-owned current authority. No bean or grant issuer.
 * Binding must come from protected worker admission outside the restored database.
 * Installation configuration revision is NOT the independent recovery generation.
 */
public final class PlatformStorageRecoveryAuthorityFence implements StorageRecoveryAuthorityFence {
  public record Binding(String installation,String tenant,long generation) {
    public Binding {
      if(installation==null||installation.isBlank()||tenant==null||tenant.isBlank()||generation<1)
        throw new IllegalArgumentException("Trusted platform admission binding required");
    }
  }
  /** Platform must exclude generation/revocation changes until close, including commit.
   * Nonblocking local acquisition only: remote verification happens before admission.
   * A cached allow without coordinated revocation exclusion does not implement this port.
   */
  public interface CurrentAuthority {
    Lease tryAcquire(Binding binding);
  }
  public interface Lease extends AutoCloseable {
    Proof proof();
    @Override void close();
    /** Unknown database completion must retain exclusion or a durable unresolved latch. */
    default void abandon(){throw new IllegalStateException("Unknown owner completion; platform reconciliation required");}
  }
  private record Held(Lease lease,Proof proof) {}
  private final Binding binding;
  private final CurrentAuthority current;
  public PlatformStorageRecoveryAuthorityFence(Binding binding,CurrentAuthority current){
    this.binding=Objects.requireNonNull(binding);this.current=Objects.requireNonNull(current);
  }
  @Override public Proof lockCurrent(){
    if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("Platform authority requires owner transaction");
    Held existing=(Held)TransactionSynchronizationManager.getResource(this);
    if(existing!=null)return existing.proof();
    Lease lease=current.tryAcquire(binding);
    if(lease==null)throw new IllegalStateException("Current platform authority unavailable or contended");
    boolean registered=false;
    try{
      Proof proof=lease.proof();
      if(proof==null||proof.revoked()||!binding.installation().equals(proof.installation())||!binding.tenant().equals(proof.tenant())||binding.generation()!=proof.generation())
        throw new IllegalStateException("Current platform admission generation revoked or differs");
      Held held=new Held(lease,proof);
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
        @Override public void suspend(){TransactionSynchronizationManager.unbindResource(PlatformStorageRecoveryAuthorityFence.this);}
        @Override public void resume(){TransactionSynchronizationManager.bindResource(PlatformStorageRecoveryAuthorityFence.this,held);}
        @Override public void afterCompletion(int status){
          try{TransactionSynchronizationManager.unbindResourceIfPossible(PlatformStorageRecoveryAuthorityFence.this);}
          finally{if(status==STATUS_COMMITTED||status==STATUS_ROLLED_BACK)lease.close();else lease.abandon();}
        }
      });
      registered=true;
      TransactionSynchronizationManager.bindResource(this,held);
      return proof;
    }finally{if(!registered)lease.close();}
  }
}
