package it.comune.trieste.ouf.onboarding.application;

interface ManagedFileFormatAdapter {
  ManagedFileProfiler.Profile profile(byte[] bytes);
}
