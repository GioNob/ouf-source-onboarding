package it.comune.trieste.ouf.onboarding.application;

public interface ManagedFileObjectStore {
  byte[] read(String stagingRef,long expectedSize);
}
