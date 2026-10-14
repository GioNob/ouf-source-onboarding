package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.api.PermissionDelegationVerifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedFileDelegationVerifierTest {
  @Test void acceptsOnlyBoundManagedReceiptAndRejectsPermissionDomain(@TempDir Path directory)throws Exception {
    byte[] key="a".repeat(64).getBytes(StandardCharsets.US_ASCII);
    Path file=directory.resolve("owner-key");Files.write(file,key);
    var json=new ObjectMapper();
    var verifier=new PermissionDelegationVerifier(json,file.toString(),"https://iam.example/realm","ouf-gateway","ouf-mcp-server");
    byte[] body="{\"Arguments\":{\"assetId\":\"00000000-0000-4000-8000-000000000001\"}}".getBytes(StandardCharsets.UTF_8);
    String path="/api/internal/v1/onboarding/managed-file-mcp/profile",cap="ouf.managed-source.file.profile";
    long now=Instant.now().getEpochSecond();
    var payload=new PermissionDelegationVerifier.Receipt(1,"managed-file-owner","POST",path,
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)),cap,now,now+25,
        "https://iam.example/realm","ouf-gateway","ouf-mcp-server","human:alice","tenant-a","1","",cap,"profile-once");
    String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(payload));
    Mac hmac=Mac.getInstance("HmacSHA256");hmac.init(new SecretKeySpec(key,"HmacSHA256"));
    String signature=Base64.getUrlEncoder().withoutPadding().encodeToString(hmac.doFinal(("ouf-managed-file-owner-v1."+encoded).getBytes(StandardCharsets.US_ASCII)));
    String receipt=encoded+"."+signature;
    assertThat(verifier.verifyManagedFile(receipt,path,cap,body).principal().subjectId()).isEqualTo("human:alice");
    assertThatThrownBy(()->verifier.verify(receipt,path,cap,body)).isInstanceOf(SecurityException.class);
    assertThatThrownBy(()->verifier.verifyManagedFile(receipt,path,"ouf.managed-source.preview",body)).isInstanceOf(SecurityException.class);
    assertThatThrownBy(()->verifier.verifyManagedFile(receipt,path,cap,"different".getBytes(StandardCharsets.UTF_8))).isInstanceOf(SecurityException.class);
  }
}
