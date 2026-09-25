package it.comune.trieste.ouf.onboarding.application;

public interface ManagedFileStagingStore {
  String put(byte[] content, String mediaType);
  byte[] get(String ref);
}
