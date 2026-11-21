package it.comune.trieste.ouf.onboarding.application;

interface ManagedFileFormatAdapter {
  String mediaType();
  ManagedFileProfiler.Profile profile(byte[] bytes);
}
