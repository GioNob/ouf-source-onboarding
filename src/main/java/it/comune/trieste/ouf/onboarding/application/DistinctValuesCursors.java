package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
final class DistinctValuesCursors {
  private static final byte VERSION = 1;
  private static final int PAYLOAD_BYTES = 1 + 3 * Integer.BYTES + Long.BYTES;
  private static final int TOKEN_BYTES = PAYLOAD_BYTES + 32;
  private static final int TOKEN_CHARS = (TOKEN_BYTES * 8 + 5) / 6;
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  record Scope(String tenant, String subject, UUID asset, UUID profile, String schemaHash) {}
  record Position(int layer, int column, int offset, long expiresAt) {}

  private final ObjectMapper json;
  private final SecretKeySpec key;
  private final Clock clock;
  private final Duration ttl;

  @Autowired
  DistinctValuesCursors(ObjectMapper json,
      @Value("${ouf.onboarding.preview.cursor-key-file:}") String keyFile,
      @Value("${ouf.onboarding.preview.cursor-ttl:PT15M}") Duration ttl) {
    this(json, loadKey(keyFile), Clock.systemUTC(), ttl);
  }

  DistinctValuesCursors(ObjectMapper json, byte[] key, Clock clock, Duration ttl) {
    if (ttl.compareTo(Duration.ofSeconds(1)) < 0) throw new IllegalArgumentException("Preview cursor TTL must be at least one second");
    this.json = json;
    this.key = new SecretKeySpec(key, "HmacSHA256");
    this.clock = clock;
    this.ttl = ttl;
  }

  long deadline() {
    return clock.instant().plus(ttl).getEpochSecond();
  }

  String issue(Scope scope, Position position) {
    ByteBuffer token = ByteBuffer.allocate(TOKEN_BYTES);
    token.put(VERSION).putInt(position.layer()).putInt(position.column())
        .putInt(position.offset()).putLong(position.expiresAt());
    token.put(signature(token.array(), scope));
    return ENCODER.encodeToString(token.array());
  }

  Position verify(String cursor, Scope scope) {
    if (cursor == null || cursor.length() != TOKEN_CHARS) throw invalid();
    byte[] token;
    try {
      token = DECODER.decode(cursor);
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
    if (token.length != TOKEN_BYTES || !ENCODER.encodeToString(token).equals(cursor)
        || token[0] != VERSION || !MessageDigest.isEqual(signature(token, scope),
            Arrays.copyOfRange(token, PAYLOAD_BYTES, TOKEN_BYTES))) throw invalid();
    ByteBuffer payload = ByteBuffer.wrap(token, 1, PAYLOAD_BYTES - 1);
    var position = new Position(payload.getInt(), payload.getInt(), payload.getInt(), payload.getLong());
    if (position.expiresAt() <= clock.instant().getEpochSecond()) throw invalid();
    return position;
  }

  static DomainFailure invalid() {
    return new DomainFailure(HttpStatus.BAD_REQUEST, "ONB_DISTINCT_VALUES_CURSOR_INVALID",
        "Invalid or expired distinct values cursor");
  }

  private byte[] signature(byte[] token, Scope scope) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      mac.update(token, 0, PAYLOAD_BYTES);
      return mac.doFinal(json.writeValueAsBytes(scope));
    } catch (GeneralSecurityException | JsonProcessingException failure) {
      throw new IllegalStateException("Distinct values cursor verification unavailable", failure);
    }
  }

  private static byte[] loadKey(String keyFile) {
    if (keyFile.isBlank()) {
      byte[] key = new byte[32];
      new SecureRandom().nextBytes(key);
      return key;
    }
    try {
      String hex = Files.readString(Path.of(keyFile)).strip();
      if (!hex.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException();
      return HexFormat.of().parseHex(hex);
    } catch (IOException | IllegalArgumentException failure) {
      throw new IllegalStateException("Invalid or unavailable distinct values cursor key file");
    }
  }
}
