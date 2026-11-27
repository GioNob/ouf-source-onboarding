package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.application.xlsx.XlsxArchive;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.*;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Installation policy shared by the browser adapter and both owner intake paths. */
@Component
public final class ManagedFileContentInspector {
  public static final String CSV = "text/csv";
  public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  public static final String GPKG = "application/geopackage+sqlite3";
  public static final int MAX_BYTES = 10 << 20;
  private static final String PREFIX = "ouf.onboarding.files.";
  private static final byte[] SQLITE = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] OLE = HexFormat.of().parseHex("d0cf11e0a1b11ae1");
  private static final Map<String,String> EXTENSIONS = Map.of(CSV, "csv", XLSX, "xlsx", GPKG, "gpkg");
  public record Format(String mediaType, int maxBytes) {}
  private final Map<String,Format> formats;
  private final XlsxArchive.Limits xlsx;
  private final ZoneId zone;

  public ManagedFileContentInspector(Environment env) {
    var enabled = new LinkedHashMap<String,Format>();
    var sizes = new HashMap<String,Integer>();
    EXTENSIONS.forEach((type, ext) -> {
      int size = env.getProperty(PREFIX + ext + ".max-bytes", Integer.class, MAX_BYTES);
      if (size < 1 || size > MAX_BYTES) throw new IllegalArgumentException(ext + ".max-bytes");
      sizes.put(type, size);
    });
    var configured = Binder.get(env).bind(PREFIX + "media-types", Bindable.listOf(String.class)).orElse(List.of(CSV, XLSX));
    if (configured.isEmpty()) throw new IllegalArgumentException("Empty managed file media-types");
    for (String item : configured) {
      String type = item.strip();
      if (!EXTENSIONS.containsKey(type) || enabled.put(EXTENSIONS.get(type), new Format(type, sizes.get(type))) != null)
        throw new IllegalArgumentException("Invalid managed file media-types");
    }
    formats = Collections.unmodifiableMap(enabled);
    var defaults = XlsxArchive.Limits.defaults();
    String prefix = PREFIX + "xlsx.";
    xlsx = new XlsxArchive.Limits(
        env.getProperty(prefix + "max-uncompressed-bytes", Integer.class, defaults.maxUncompressedBytes()),
        env.getProperty(prefix + "max-entry-bytes", Integer.class, defaults.maxEntryBytes()),
        env.getProperty(prefix + "min-inflate-ratio", Double.class, defaults.minInflateRatio()),
        env.getProperty(prefix + "max-entries", Integer.class, defaults.maxEntries()),
        env.getProperty(prefix + "max-sheets", Integer.class, defaults.maxSheets()),
        env.getProperty(prefix + "max-rows", Integer.class, defaults.maxRows()),
        env.getProperty(prefix + "max-columns", Integer.class, defaults.maxColumns()));
    String tz = env.getProperty("ouf.onboarding.source-time-zone", "");
    if (!tz.isEmpty() && !ZoneId.getAvailableZoneIds().contains(tz)) throw new IllegalArgumentException("source-time-zone");
    zone = tz.isEmpty() ? null : ZoneId.of(tz);
    // POI settings are JVM-wide. Install them once during startup, never around individual reads.
    ZipSecureFile.setMaxEntrySize(xlsx.maxEntryBytes());
    ZipSecureFile.setMinInflateRatio(xlsx.minInflateRatio());
    ZipSecureFile.setMaxFileCount(xlsx.maxEntries());
  }

  public Map<String,Format> formats() { return formats; }
  public ZoneId sourceTimeZone() { return zone; }
  public XlsxArchive.Limits xlsxLimits() { return xlsx; }

  public Format requireFormat(String header) {
    if (header != null) {
      try {
        var mime = MediaType.parseMediaType(header);
        String type = mime.getType() + "/" + mime.getSubtype();
        var format = formats.get(EXTENSIONS.get(type.toLowerCase(Locale.ROOT)));
        if (format != null) return format;
      } catch (IllegalArgumentException ignored) { /* Invalid MIME has no binding. */ }
    }
    throw new DomainFailure(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "ONB_FILE_MEDIA_TYPE_UNSUPPORTED", "Unsupported managed file format");
  }

  public void checkSize(Format format, long size) {
    if (size > format.maxBytes()) throw XlsxArchive.limit(EXTENSIONS.get(format.mediaType()) + ".max-bytes");
  }

  public void verify(Format format, byte[] bytes) {
    checkSize(format, bytes.length);
    switch (format.mediaType()) {
      case CSV -> {
        boolean zip = bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
            && (bytes[2] == 3 && bytes[3] == 4 || bytes[2] == 5 && bytes[3] == 6 || bytes[2] == 7 && bytes[3] == 8);
        if (zip || startsWith(bytes, OLE) || startsWith(bytes, SQLITE)) throw XlsxArchive.mismatch();
      }
      case XLSX -> XlsxArchive.verify(bytes, xlsx);
      case GPKG -> {
        if (!startsWith(bytes, SQLITE) || bytes.length < 72 || ByteBuffer.wrap(bytes).getInt(68) != 0x47504b47)
          throw XlsxArchive.mismatch();
      }
      default -> throw new IllegalArgumentException("Unknown file format");
    }
  }

  private static boolean startsWith(byte[] bytes, byte[] prefix) {
    return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
  }
}
