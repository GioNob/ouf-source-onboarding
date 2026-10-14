package it.comune.trieste.ouf.onboarding.application;

import java.io.IOException;
import java.io.InputStream;

public interface ManagedFileStagingStore {
  String put(InputStream content, long size, String mediaType) throws IOException;
  byte[] get(String ref);
}
