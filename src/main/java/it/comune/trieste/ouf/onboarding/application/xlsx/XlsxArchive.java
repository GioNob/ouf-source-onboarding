package it.comune.trieste.ouf.onboarding.application.xlsx;

import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.*;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.springframework.http.HttpStatus;

/** Bounds the local entry streams consumed by POI and checks them against the ZIP directory. */
public final class XlsxArchive {
  public record Limits(int maxUncompressedBytes, int maxEntryBytes, double minInflateRatio,
                       int maxEntries, int maxSheets, int maxRows, int maxColumns) {
    public Limits {
      bound(maxUncompressedBytes, 256 << 20);
      bound(maxEntryBytes, 256 << 20);
      if (!(minInflateRatio > 0 && minInflateRatio <= 1)) throw new IllegalArgumentException("min-inflate-ratio");
      bound(maxEntries, 10000);
      bound(maxSheets, 64);
      bound(maxRows, 10000);
      bound(maxColumns, 1024);
    }
    private static void bound(int value, int max) {
      if (value < 1 || value > max) throw new IllegalArgumentException("XLSX limit outside protocol range");
    }
    public static Limits defaults() { return new Limits(100 << 20, 64 << 20, .01, 10000, 64, 10000, 1024); }
  }

  private static final String TYPES = "[Content_Types].xml";
  private static final String TYPE_NS = "http://schemas.openxmlformats.org/package/2006/content-types";
  private static final String BOOK_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
  private static final String REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
  private static final String WORKBOOK = "xl/workbook.xml";
  private static final String MAIN = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";
  private XlsxArchive() {}

  public static DomainFailure limit(String name) {
    return new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY, "ONB_FILE_LIMIT_EXCEEDED",
        "Managed file exceeds " + name, Map.of("limit", name));
  }
  public static DomainFailure mismatch() {
    return new DomainFailure(HttpStatus.UNPROCESSABLE_ENTITY, "ONB_FILE_MEDIA_TYPE_MISMATCH",
        "File content differs from its declared format");
  }

  public static void verify(byte[] bytes, Limits limits) {
    try (var input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      var directory = new HashMap<String,ZipEntry>();
      byte[] buffer = new byte[16384];
      long total = 0;
      // Count entries before loading the central directory or building a workbook.
      for (ZipEntry e; (e = input.getNextEntry()) != null;) {
        String name = e.getName();
        if (directory.put(name, e) != null || "vbaProject.bin".equalsIgnoreCase(name.substring(name.lastIndexOf('/') + 1))) throw mismatch();
        if (directory.size() > limits.maxEntries()) throw limit("xlsx.max-entries");
        long size = 0;
        for (int n; (n = input.read(buffer)) != -1;) {
          size += n;
          total += n;
          if (size > limits.maxEntryBytes()) throw limit("xlsx.max-entry-bytes");
          if (total > limits.maxUncompressedBytes()) throw limit("xlsx.max-uncompressed-bytes");
        }
        if (size * limits.minInflateRatio() > e.getCompressedSize()) throw limit("xlsx.min-inflate-ratio");
      }
      if (!directory.containsKey(TYPES) || !directory.containsKey(WORKBOOK)) throw mismatch();
      try (var channel = new SeekableInMemoryByteChannel(bytes);
           var zip = ZipFile.builder().setSeekableByteChannel(channel).get()) {
        var entries = zip.getEntries();
        while (entries.hasMoreElements()) {
          var e = entries.nextElement();
          ZipEntry local = directory.remove(e.getName());
          if (local == null || local.getCrc() != e.getCrc() || local.getSize() != e.getSize()
              || local.getCompressedSize() != e.getCompressedSize() || local.getMethod() != e.getMethod()
              || e.getGeneralPurposeBit().usesEncryption()) throw mismatch();
        }
        if (!directory.isEmpty()) throw mismatch();
      }
      // Parse the very same local streams that passed the bounds above.
      inspectPackage(bytes, limits);
    } catch (IOException | XMLStreamException | IllegalArgumentException e) {
      throw mismatch();
    }
  }

  private static void inspectPackage(byte[] bytes, Limits limits) throws IOException, XMLStreamException {
    var xml = XMLInputFactory.newDefaultFactory();
    xml.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    xml.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    var worksheets = new HashSet<String>();
    var sheetRefs = new ArrayList<String>();
    boolean workbookType = false;
    try (var in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      for (ZipEntry entry; (entry = in.getNextEntry()) != null;) {
        String name = entry.getName();
        if (!Set.of(TYPES, WORKBOOK, "xl/_rels/workbook.xml.rels").contains(name)) continue;
        var reader = xml.createXMLStreamReader(new FilterInputStream(in) {
          @Override public void close() { /* The ZIP owns the stream. */ }
        });
        try {
          boolean root = true;
          while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.DTD) throw mismatch();
            if (event != XMLStreamConstants.START_ELEMENT) continue;
            if (root) {
              String element = TYPES.equals(name) ? "Types" : WORKBOOK.equals(name) ? "workbook" : "Relationships";
              String namespace = TYPES.equals(name) ? TYPE_NS : WORKBOOK.equals(name) ? BOOK_NS : REL_NS;
              if (!element.equals(reader.getLocalName()) || !namespace.equals(reader.getNamespaceURI())) throw mismatch();
              root = false;
            }
            if (TYPES.equals(name) && TYPE_NS.equals(reader.getNamespaceURI())) {
              String type = reader.getAttributeValue(null, "ContentType");
              if (type == null) continue;
              String lower = type.toLowerCase(Locale.ROOT);
              if (lower.contains("macro") || lower.contains("vba")) throw mismatch();
              if ("Override".equals(reader.getLocalName()) && ("/" + WORKBOOK).equals(reader.getAttributeValue(null,"PartName"))) {
                if (workbookType || !MAIN.equals(type)) throw mismatch();
                workbookType = true;
              }
            } else if (WORKBOOK.equals(name) && "sheet".equals(reader.getLocalName())) {
              sheetRefs.add(reader.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id"));
            } else if (name.endsWith(".rels") && "Relationship".equals(reader.getLocalName())
                && "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet".equals(reader.getAttributeValue(null,"Type"))) {
              worksheets.add(reader.getAttributeValue(null,"Id"));
            }
          }
        } finally { reader.close(); }
      }
    }
    if (!workbookType) throw mismatch();
    if (sheetRefs.stream().filter(worksheets::contains).count() > limits.maxSheets()) throw limit("xlsx.max-sheets");
  }
}
