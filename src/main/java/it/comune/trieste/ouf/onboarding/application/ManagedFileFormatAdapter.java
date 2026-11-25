package it.comune.trieste.ouf.onboarding.application;

interface ManagedFileFormatAdapter {
  ManagedFileProfiler.Profile profile(byte[] bytes);
  default ManagedFileProfiler.Profile profile(byte[] bytes, ManagedFileProfiler.ReadOptions options) {
    if (options.declared()) throw new IllegalArgumentException("read options unsupported");
    return profile(bytes);
  }
}
