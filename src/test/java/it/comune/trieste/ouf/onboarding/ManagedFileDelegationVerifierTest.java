package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.api.ManagedFileMcpApi;
import it.comune.trieste.ouf.onboarding.api.PermissionDelegationVerifier;
import it.comune.trieste.ouf.onboarding.api.ProblemHandler;
import it.comune.trieste.ouf.onboarding.application.ManagedFileChatHandoff;
import it.comune.trieste.ouf.onboarding.application.ManagedFileLimits;
import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ManagedFileDelegationVerifierTest {
  @Test void acceptsOnlyBoundManagedReceiptAndRejectsPermissionDomain(@TempDir Path directory)throws Exception {
    byte[] key="a".repeat(64).getBytes(StandardCharsets.US_ASCII);
    Path file=directory.resolve("owner-key");Files.write(file,key);
    var json=new ObjectMapper();
    var verifier=new PermissionDelegationVerifier(json,file.toString(),"https://iam.example/realm","ouf-gateway","ouf-mcp-server");
    byte[] body="{\"Arguments\":{\"assetId\":\"00000000-0000-4000-8000-000000000001\"}}".getBytes(StandardCharsets.UTF_8);
    String path="/api/internal/v1/onboarding/managed-file-mcp/profile",cap="ouf.managed-source.file.profile";
    String receipt=sign(json,key,path,cap,body);
    assertThat(verifier.verifyManagedFile(receipt,path,cap,body).principal().subjectId()).isEqualTo("human:alice");
    assertThatThrownBy(()->verifier.verify(receipt,path,cap,body)).isInstanceOf(SecurityException.class);
    assertThatThrownBy(()->verifier.verifyManagedFile(receipt,path,"ouf.managed-source.preview",body)).isInstanceOf(SecurityException.class);
    assertThatThrownBy(()->verifier.verifyManagedFile(receipt,path,cap,"different".getBytes(StandardCharsets.UTF_8))).isInstanceOf(SecurityException.class);
  }

  @Test void d084_23_signedCreateReachesTheOwnerAndKeepsTheThreeMiBLimit(@TempDir Path directory)throws Exception{
    byte[] key="b".repeat(64).getBytes(StandardCharsets.US_ASCII);
    Path file=directory.resolve("owner-key");Files.write(file,key);
    var json=new ObjectMapper();
    var verifier=new PermissionDelegationVerifier(json,file.toString(),"https://iam.example/realm","ouf-gateway","ouf-mcp-server");
    var files=mock(ManagedFileService.class);
    var mvc=MockMvcBuilders.standaloneSetup(new ManagedFileMcpApi(verifier,files,json,mock(ManagedFileChatHandoff.class)))
        .setControllerAdvice(new ProblemHandler()).build();
    var asset=UUID.randomUUID();var profile=UUID.randomUUID();
    var fields=IntStream.range(0,450).mapToObj(i->Map.of("fieldName","column_"+i,"extractionDecision","INCLUDE",
        "dataAccessLabel","OPEN","targetPropertyIri","https://example.org/property/column_"+i,"transform","IDENTITY")).toList();
    byte[] body=json.writeValueAsBytes(Map.of("CorrelationID","corr","Arguments",Map.of("assetId",asset,"profileId",profile,
        "sourceId","wide","name","Wide","owner","Comune","targetClassIri","urn:fixture:class","semanticRefs",List.of("urn:fixture:class@1"),
        "sourceObjectKeyFields",List.of("column_0"),"fields",fields)));
    assertThat(body.length).isGreaterThan(65_536).isLessThan(2*1024*1024);
    String base="/api/internal/v1/onboarding/managed-file-mcp";
    String path=base+"/create",cap="ouf.managed-source.onboarding.create";
    mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
        .header("X-OUF-Managed-File-Receipt",sign(json,key,path,cap,body))).andExpect(status().isOk());
    verify(files).onboardIdempotent(eq(asset),eq(profile),eq("wide"),eq("Wide"),eq("Comune"),eq("urn:fixture:class"),
        eq(List.of("urn:fixture:class@1")),eq(List.of("column_0")),argThat(values->values.size()==450),isNull(),any(),
        eq("corr"),eq("profile-once"),eq("tenant-a"),isNull(),isNull());

    byte[] oversized=new byte[ManagedFileLimits.MAX_MAPPING_ENVELOPE_BYTES+1];
    java.util.Arrays.fill(oversized,(byte)' ');
    System.arraycopy(body,0,oversized,0,body.length);
    byte[] exact=java.util.Arrays.copyOf(oversized,oversized.length-1);
    assertThat(verifier.verifyManagedFile(sign(json,key,path,cap,exact),path,cap,exact).idempotencyKey())
        .isEqualTo("profile-once");
    mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(oversized)
        .header("X-OUF-Managed-File-Receipt",sign(json,key,path,cap,oversized)))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ONB_BAD_REQUEST"));
    String preview=base+"/preview",previewCap="ouf.managed-source.preview";
    assertThatThrownBy(()->verifier.verifyManagedFile(sign(json,key,preview,previewCap,body),preview,previewCap,body))
        .hasMessage("AUTH_OWNER_RECEIPT_INVALID");
    assertThatThrownBy(()->verifier.verifyManagedFile(sign(json,key,preview,cap,body),preview,cap,body))
        .hasMessage("AUTH_OWNER_RECEIPT_INVALID");
    verifyNoMoreInteractions(files);
  }

  private static String sign(ObjectMapper json,byte[] key,String path,String cap,byte[] body)throws Exception{
    long now=Instant.now().getEpochSecond();
    var payload=new PermissionDelegationVerifier.Receipt(1,"managed-file-owner","POST",path,
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)),cap,now,now+25,
        "https://iam.example/realm","ouf-gateway","ouf-mcp-server","human:alice","tenant-a","1","",cap,"profile-once");
    String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(payload));
    Mac hmac=Mac.getInstance("HmacSHA256");hmac.init(new SecretKeySpec(key,"HmacSHA256"));
    String signature=Base64.getUrlEncoder().withoutPadding().encodeToString(hmac.doFinal(("ouf-managed-file-owner-v1."+encoded).getBytes(StandardCharsets.US_ASCII)));
    return encoded+"."+signature;
  }
}
