package it.comune.trieste.ouf.onboarding.application;

/** Versioned shape and transport bounds for one managed tabular source object. */
public final class ManagedFileLimits {
  private ManagedFileLimits() {}
  public static final String PROFILE = "managed-tabular-1024.v1";
  public static final int MAX_COLUMNS = 1024;
  public static final int MAX_COLUMN_NAME_CHARS = 256;
  public static final int COLUMN_PAGE_SIZE = 128;
  public static final int MAX_MAPPING_ENVELOPE_BYTES = 3 * 1024 * 1024;
}
