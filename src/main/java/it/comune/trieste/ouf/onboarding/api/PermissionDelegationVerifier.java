package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.*;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.onboarding.authorization.BootstrapAdministrator;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A separate, purpose-bound owner receipt. The signing key is never distributed to MCP. */
@Component
public class PermissionDelegationVerifier {
 private final ObjectMapper json;private final String issuer,audience,workload;private final byte[] key;
 public record Delegated(PrincipalContext principal,String idempotencyKey){}
 public record Receipt(int v,String purpose,String method,String path,String bodyHash,String capability,long iat,long exp,String issuer,String audience,String workload,String subject,String tenant,String acr,String roles,String scope,String idempotencyKey){}
 /** The Gateway authenticates stream metadata; the owner must independently hash and count the bytes. */
 public record UploadReceipt(int v,String purpose,String method,String path,String capability,String expectedHash,long expectedLength,String fileId,long iat,long exp,String issuer,String audience,String workload,String subject,String tenant,String acr,String roles,String scope,String idempotencyKey){}
 public record DelegatedUpload(PrincipalContext principal,String idempotencyKey,String fileId,String expectedHash,long expectedLength){}
 public PermissionDelegationVerifier(ObjectMapper json,@Value("${ouf.authorization.delegation.key-file:}") String file,@Value("${ouf.iam.issuer:}") String issuer,@Value("${ouf.iam.audience:}") String audience,@Value("${ouf.authorization.delegation.workload:ouf-mcp-server}") String workload){
  this.json=json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);this.issuer=issuer;this.audience=audience;this.workload=workload;
  try{String value=file.isBlank()?"":Files.readString(Path.of(file)).trim();if(!value.isEmpty()&&!value.matches("[0-9a-fA-F]{64}"))throw new IllegalArgumentException("invalid authorization owner key");key=value.getBytes(StandardCharsets.US_ASCII);}catch(java.io.IOException e){throw new IllegalStateException("authorization owner key unavailable",e);}
 }
 private SecurityException denied(){return new SecurityException("AUTH_OWNER_RECEIPT_INVALID");}
 public Delegated verify(String proof,String path,String capability,byte[] body){
  return verifyBound(proof,path,capability,body,"authorization-proposal-owner","ouf-authorization-owner-v1.");
 }
 /** Managed-file receipts use a distinct purpose and MAC domain. */
 public Delegated verifyManagedFile(String proof,String path,String capability,byte[] body){
  return verifyBound(proof,path,capability,body,"managed-file-owner","ouf-managed-file-owner-v1.");
 }
 /** A distinct MAC domain prevents JSON-command receipts from authorizing a CSV stream. */
 public DelegatedUpload verifyManagedUpload(String proof,String path){
  try{
   if(key.length!=64||issuer.isBlank()||audience.isBlank()||proof==null||proof.length()>16384)throw denied();
   var parts=proof.split("\\.",-1);
   if(parts.length!=2||!parts[0].matches("[A-Za-z0-9_-]+")||!parts[1].matches("[A-Za-z0-9_-]+"))throw denied();
   var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
   if(!MessageDigest.isEqual(mac.doFinal(("ouf-managed-file-upload-v1."+parts[0]).getBytes(StandardCharsets.US_ASCII)),Base64.getUrlDecoder().decode(parts[1])))throw denied();
   var r=json.readValue(Base64.getUrlDecoder().decode(parts[0]),UploadReceipt.class);
   long now=Instant.now().getEpochSecond();String capability="ouf.managed-source.file.upload";
   if(r.v()!=1||!"managed-file-upload-owner".equals(r.purpose())||!"POST".equals(r.method())
       ||!path.equals(r.path())||!capability.equals(r.capability())
       ||r.iat()>now||r.exp()<=now||r.exp()<=r.iat()||r.exp()-r.iat()>30
       ||!issuer.equals(r.issuer())||!audience.equals(r.audience())||!workload.equals(r.workload())
       ||r.expectedHash()==null||!r.expectedHash().matches("sha256:[0-9a-f]{64}")
       ||r.expectedLength()<1||r.expectedLength()>10*1024*1024
       ||r.fileId()==null||!r.fileId().matches("file_[A-Za-z0-9_-]{1,128}")
       ||r.idempotencyKey()==null||r.idempotencyKey().isBlank()||r.idempotencyKey().length()>128
       ||r.subject()==null||r.subject().isBlank()||r.tenant()==null||r.tenant().isBlank()
       ||r.acr()==null||r.acr().isBlank())throw denied();
   var roles=new TreeSet<String>();
   if(r.roles()!=null&&!r.roles().isEmpty())for(String role:r.roles().split(" ",-1)){
    if(!BootstrapAdministrator.validRole(role)||!roles.add(role)||roles.size()>32)throw denied();
   }
   if(r.scope()==null||r.scope().length()>8192)throw denied();
   var scopes=new HashSet<>(Arrays.asList(r.scope().split(" +")));
   if(!scopes.contains(capability))throw denied();
   var principal=new PrincipalContext(r.subject(),r.tenant(),PrincipalContext.ActorType.HUMAN,r.workload(),r.acr(),r.issuer(),r.audience(),scopes,new PrincipalContext.IdentityClaims(roles,r.acr(),Set.of(),null));
   return new DelegatedUpload(principal,r.idempotencyKey(),r.fileId(),r.expectedHash(),r.expectedLength());
  }catch(Exception e){throw denied();}
 }
 private Delegated verifyBound(String proof,String path,String capability,byte[] body,String purpose,String macDomain){
  try{
   if(key.length!=64||issuer.isBlank()||audience.isBlank()||proof==null||proof.length()>16384||body.length>65536)throw denied();
   var parts=proof.split("\\.",-1);if(parts.length!=2||!parts[0].matches("[A-Za-z0-9_-]+")||!parts[1].matches("[A-Za-z0-9_-]+"))throw denied();
   var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
   if(!MessageDigest.isEqual(mac.doFinal((macDomain+parts[0]).getBytes(StandardCharsets.US_ASCII)),Base64.getUrlDecoder().decode(parts[1])))throw denied();
   var r=json.readValue(Base64.getUrlDecoder().decode(parts[0]),Receipt.class);var now=Instant.now().getEpochSecond();
   if(r.v()!=1||!purpose.equals(r.purpose())||!"POST".equals(r.method())||!path.equals(r.path())||!capability.equals(r.capability())||r.iat()>now||r.exp()<=now||r.exp()<=r.iat()||r.exp()-r.iat()>30||!issuer.equals(r.issuer())||!audience.equals(r.audience())||!workload.equals(r.workload())||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)).equals(r.bodyHash()))throw denied();
   var roles=new TreeSet<String>();if(r.roles()!=null&&!r.roles().isEmpty())for(String role:r.roles().split(" ",-1)){if(!BootstrapAdministrator.validRole(role)||!roles.add(role)||roles.size()>32)throw denied();}
   if(r.scope()==null||r.scope().length()>8192)throw denied();var scopes=new HashSet<>(Arrays.asList(r.scope().split(" +")));if(!scopes.contains(capability))throw denied();
   return new Delegated(new PrincipalContext(r.subject(),r.tenant(),PrincipalContext.ActorType.HUMAN,r.workload(),r.acr(),r.issuer(),r.audience(),scopes,new PrincipalContext.IdentityClaims(roles,r.acr(),Set.of(),null)),r.idempotencyKey());
  }catch(Exception e){throw denied();}
 }
}
