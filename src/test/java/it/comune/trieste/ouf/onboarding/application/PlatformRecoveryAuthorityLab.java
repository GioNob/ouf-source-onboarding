package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.authorization.StorageRecoveryAuthorityFence.Proof;
import it.comune.trieste.ouf.onboarding.installation.PlatformStorageRecoveryAuthorityFence;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Test-only current platform state: separate from every PostgreSQL table/rollback. */
final class PlatformRecoveryAuthorityLab implements PlatformStorageRecoveryAuthorityFence.CurrentAuthority {
  private final ReentrantReadWriteLock lock=new ReentrantReadWriteLock();
  private Proof proof;
  final AtomicInteger acquired=new AtomicInteger(),closed=new AtomicInteger();
  PlatformRecoveryAuthorityLab(Proof proof){this.proof=proof;}
  public PlatformStorageRecoveryAuthorityFence.Lease tryAcquire(PlatformStorageRecoveryAuthorityFence.Binding binding){
    if(!lock.readLock().tryLock())return null;
    acquired.incrementAndGet();Proof snapshot=proof;
    return new PlatformStorageRecoveryAuthorityFence.Lease(){
      public Proof proof(){return snapshot;}
      public void close(){closed.incrementAndGet();lock.readLock().unlock();}
    };
  }
  boolean advance(Proof next){
    if(!lock.writeLock().tryLock())return false;
    try{proof=next;return true;}finally{lock.writeLock().unlock();}
  }
  boolean held(){return lock.getReadLockCount()>0;}
}
