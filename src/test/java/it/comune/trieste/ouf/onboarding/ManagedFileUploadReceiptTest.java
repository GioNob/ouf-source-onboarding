package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.api.PermissionDelegationVerifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedFileUploadReceiptTest {
  private static final String PATH="/api/internal/v1/onboarding/managed-file-mcp/upload";
  private static final String CAP="ouf.managed-source.file.upload";
  private static final String HASH="sha256:"+"a".repeat(64);
  private static final byte[] KEY="b".repeat(64).getBytes(StandardCharsets.US_ASCII);

  private String signed(ObjectMapper json,PermissionDelegationVerifier.UploadReceipt receipt,String domain)throws Exception {
    String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(receipt));
    Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(KEY,"HmacSHA256"));
    return encoded+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((domain+encoded).getBytes(StandardCharsets.US_ASCII)));
  }
  private PermissionDelegationVerifier.UploadReceipt receipt(long now,String path,String hash,long length,String scope){
    return new PermissionDelegationVerifier.UploadReceipt(1,"managed-file-upload-owner","POST",path,CAP,
        hash,length,"file_123",now,now+25,"https://iam.example/realm","ouf-gateway","ouf-mcp-server",
        "human:alice","tenant-a","1","",scope,"upload-once");
  }

  @Test void verifiesExactStreamingMetadataAndRejectsCrossDomainReplay(@TempDir Path directory)throws Exception {
    Path key=directory.resolve("owner-key");Files.write(key,KEY);
    var json=new ObjectMapper();
    var verifier=new PermissionDelegationVerifier(json,key.toString(),"https://iam.example/realm","ouf-gateway","ouf-mcp-server");
    long now=Instant.now().getEpochSecond();
    String valid=signed(json,receipt(now,PATH,HASH,42,CAP),"ouf-managed-file-upload-v1.");
    var delegated=verifier.verifyManagedUpload(valid,PATH);
    assertThat(delegated.principal().subjectId()).isEqualTo("human:alice");
    assertThat(delegated.expectedHash()).isEqualTo(HASH);
    assertThat(delegated.expectedLength()).isEqualTo(42);
    assertThat(delegated.fileId()).isEqualTo("file_123");
    assertThatThrownBy(()->verifier.verifyManagedUpload(valid,PATH+"/other")).isInstanceOf(SecurityException.class);
    assertThatThrownBy(()->verifier.verifyManagedFile(valid,PATH,CAP,new byte[0])).isInstanceOf(SecurityException.class);
    for(var bad:new PermissionDelegationVerifier.UploadReceipt[]{
        receipt(now,PATH,"sha256:"+"B".repeat(64),42,CAP),
        receipt(now,PATH,HASH,10*1024*1024+1L,CAP),
        receipt(now,PATH,HASH,42,"mcp.connect"),
        receipt(now-60,PATH,HASH,42,CAP),
        receipt(now,PATH+"/other",HASH,42,CAP)
    }){
      String proof=signed(json,bad,"ouf-managed-file-upload-v1.");
      assertThatThrownBy(()->verifier.verifyManagedUpload(proof,PATH)).isInstanceOf(SecurityException.class);
    }
    String otherDomain=signed(json,receipt(now,PATH,HASH,42,CAP),"ouf-managed-file-owner-v1.");
    assertThatThrownBy(()->verifier.verifyManagedUpload(otherDomain,PATH)).isInstanceOf(SecurityException.class);
  }
}
