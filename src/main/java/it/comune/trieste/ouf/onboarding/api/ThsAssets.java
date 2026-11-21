package it.comune.trieste.ouf.onboarding.api;

import java.io.IOException;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;

/**
 * THS static assets. Each page script carries the shared session code: the Gateway routes
 * only page-owned paths, so a shared session.js under another surface is not reachable.
 */
final class ThsAssets {
  static final Set<String> PAGE_SCRIPTS = Set.of("workflow-review.js", "permissions.js",
      "managed-file-picker.js", "profile-recovery.js", "resolution.js");

  static byte[] read(String name) throws IOException {
    byte[] body = new ClassPathResource("ths/" + name).getContentAsByteArray();
    if (!PAGE_SCRIPTS.contains(name)) return body;
    byte[] session = new ClassPathResource("ths/session.js").getContentAsByteArray();
    byte[] separator = {10, ';', 10}; // newline, statement terminator, newline
    byte[] out = new byte[session.length + separator.length + body.length];
    System.arraycopy(session, 0, out, 0, session.length);
    System.arraycopy(separator, 0, out, session.length, separator.length);
    System.arraycopy(body, 0, out, session.length + separator.length, body.length);
    return out;
  }

  private ThsAssets() {}
}
