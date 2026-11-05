package it.comune.trieste.ouf.onboarding.installation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import it.comune.trieste.ouf.onboarding.authorization.StorageRecoveryAuthorityFence.Proof;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static java.nio.file.StandardOpenOption.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static it.comune.trieste.ouf.onboarding.installation.PlatformAuthorityReconciliation.*;

/** Explicit same-host POSIX provider for already-authorized platform proofs.
 * Protected directory/guard must remain outside owner restore and must never be replaced.
 * No bean, endpoint, identity validation or grant issuer. Not a distributed filesystem lock.
 */
public final class FilePlatformRecoveryAuthority implements PlatformStorageRecoveryAuthorityFence.CurrentAuthority {
  private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules()
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final String SCHEMA="ouf.platform-storage-authority.v1";
  private static final Set<PosixFilePermission> PRIVATE=PosixFilePermissions.fromString("rw-------");
  private static final Set<PosixFilePermission> DIRECTORY=PosixFilePermissions.fromString("rwx------");
  private final Path root,guard,state,pending,activeLease;
  private final Object guardKey;
  private record Stored(String schema,Proof proof,String sha256) {}
  private record Marker(UUID id,long generation,String sha256) {}
  private record Recorded(Receipt receipt,String sha256) {}

  public FilePlatformRecoveryAuthority(Path directory){
    try{
      root=directory.toAbsolutePath().normalize();
      if(!root.equals(root.toRealPath())||!Files.isDirectory(root,NOFOLLOW_LINKS)||!Files.getPosixFilePermissions(root,NOFOLLOW_LINKS).equals(DIRECTORY))throw denied("Protected authority directory required");
      if(!Set.of("ext4","xfs","tmpfs","overlay").contains(Files.getFileStore(root).type()))throw denied("Unqualified authority filesystem");
      guard=root.resolve("authority.lock");state=root.resolve("current.json");pending=root.resolve("current.pending");activeLease=root.resolve("lease.active");
      guardKey=regular(guard).fileKey();if(guardKey==null)throw denied("Stable authority guard identity required");
    }catch(IOException ex){throw denied("Authority store unavailable",ex);}
  }
  /** One-time trusted provisioning only. Existing guard, including incomplete provisioning,
   * is never overwritten. No runtime method recreates missing current state.
   */
  public static void provision(Path directory,Proof first){
    outside();validate(first,true);if(first.generation()!=1)throw denied("Initial authority generation must be one");
    Path root=directory.toAbsolutePath().normalize();
    try{
      if(!root.equals(root.toRealPath())||!Files.getPosixFilePermissions(root,NOFOLLOW_LINKS).equals(DIRECTORY))throw denied("Protected authority directory required");
      try(var entries=Files.list(root)){if(entries.findAny().isPresent())throw denied("Authority provisioning requires empty directory");}
      try(var channel=FileChannel.open(root.resolve("authority.lock"),Set.of(CREATE_NEW,READ,WRITE,NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(PRIVATE))){channel.force(true);}
      var store=new FilePlatformRecoveryAuthority(root);
      try(var held=store.acquire()){if(held==null)throw denied("Authority provision contended");store.write(first);}
    }catch(IOException ex){throw denied("Authority provisioning incomplete; explicit reconciliation required",ex);}
  }
  @Override public PlatformStorageRecoveryAuthorityFence.Lease tryAcquire(PlatformStorageRecoveryAuthorityFence.Binding binding){
    Held held=acquire();if(held==null)return null;
    try{
      Proof proof=read();validate(proof,true);
      if(!binding.installation().equals(proof.installation())||!binding.tenant().equals(proof.tenant()))throw denied("Authority installation binding differs");
      // A killed JVM may release its OS lock before PostgreSQL resolves a sent COMMIT.
      // Force this latch BEFORE granting authority; only known completion clears it.
      try(var marker=FileChannel.open(activeLease,Set.of(CREATE_NEW,WRITE,NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(PRIVATE))){
        ByteBuffer bytes=ByteBuffer.wrap((UUID.randomUUID()+"\n"+proof.generation()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        while(bytes.hasRemaining())marker.write(bytes);marker.force(true);
      }
      forceDirectory();var finished=new AtomicBoolean();
      return new PlatformStorageRecoveryAuthorityFence.Lease(){
        public Proof proof(){return proof;}
        public void close(){if(finished.compareAndSet(false,true))try{Files.delete(activeLease);forceDirectory();}catch(IOException ex){throw denied("Authority completion latch unresolved",ex);}finally{held.close();}}
        public void abandon(){if(finished.compareAndSet(false,true))held.close();}
      };
    }catch(IOException ex){held.close();throw denied("Authority lease unavailable",ex);}
    catch(RuntimeException ex){held.close();throw ex;}
  }
  /** Persist an already-approved proof with expected generation; no policy/identity issuance.
   * Lost result reconciles by identical proof, never by extending its deadline.
   */
  public long publish(long expectedGeneration,Proof next){
    outside();validate(next,true);
    if(expectedGeneration<1||expectedGeneration==Long.MAX_VALUE||next.generation()!=expectedGeneration+1)throw denied("Authority generation must advance exactly once");
    try(var held=acquire()){
      if(held==null)throw denied("Authority publication contended");
      Proof current=read();
      if(current.equals(next))return current.generation();
      if(current.generation()!=expectedGeneration)throw denied("Stale authority generation");
      if(!current.installation().equals(next.installation())||!current.tenant().equals(next.tenant()))throw denied("Authority installation binding differs");
      write(next);return next.generation();
    }
  }
  /** Read-only projection: never creates a lease, clears a latch or authorizes work.
   * Contention/unavailability deliberately hide unverified fields and never report CURRENT.
   */
  public Observation inspect(PlatformStorageRecoveryAuthorityFence.Binding binding){
    try(var held=acquire(true)){
      if(held==null)return new Observation(State.CONTENDED,0,null,null);
      Proof p=read();
      if(!binding.installation().equals(p.installation())||!binding.tenant().equals(p.tenant()))
        return new Observation(State.FENCED,0,null,null);
      if(Files.exists(activeLease,NOFOLLOW_LINKS)){
        Marker m=marker();return new Observation(State.RECONCILE_REQUIRED,p.generation(),m.id(),m.sha256());
      }
      State status=p.revoked()?State.REVOKED:binding.generation()!=p.generation()?State.FENCED:
          p.issuedAt().isAfter(Instant.now())||!Instant.now().isBefore(p.expiresAt())?State.EXPIRED:State.CURRENT;
      return new Observation(status,p.generation(),null,null);
    }catch(RuntimeException ex){return new Observation(State.UNAVAILABLE,0,null,null);}
  }
  /** Clears only the exact unresolved lease, under a trusted owner-outcome exclusion.
   * Persist a receipt and a REVOKED next generation before removing the latch. A fresh
   * separate admission/publication is required to resume work. Never resets owner budgets.
   */
  public Receipt reconcile(Request request,Gate gate){
    outside();validateRequest(request);
    try(var held=acquire(true)){
      if(held==null)throw denied("Authority reconciliation contended");
      Proof current=read();Proof next=request.revokedNext();
      if(!current.installation().equals(next.installation())||!current.tenant().equals(next.tenant()))throw denied("Authority installation binding differs");
      Path path=root.resolve("resolution-"+request.commandId()+".json");
      Receipt prior=Files.exists(path,NOFOLLOW_LINKS)?readReceipt(path):null;
      if(prior!=null&&!prior.request().equals(request))throw denied("Reconciliation command reused with different intent");
      if(!Files.exists(activeLease,NOFOLLOW_LINKS)){
        if(prior!=null&&current.equals(next)){forceDirectory();return prior;}
        throw denied("Exact unresolved authority lease required");
      }
      Marker m=marker();
      if(!m.id().equals(request.leaseId())||!m.sha256().equals(request.leaseSha256())||m.generation()!=request.expectedGeneration())throw denied("Unresolved authority lease differs");
      if(current.generation()!=request.expectedGeneration()&&!current.equals(next))throw denied("Stale authority reconciliation generation");
      if(gate==null)throw denied("Owner reconciliation gate unavailable");
      try(var owner=gate.tryAcquire(request)){
        if(owner==null)throw denied("Owner outcome unresolved or contended");
        Evidence proof=owner.current();validateEvidence(request,proof);
        if(prior!=null&&(!prior.evidence().databaseIdentity().equals(proof.databaseIdentity())||!prior.evidence().ownerEvidenceRef().equals(proof.ownerEvidenceRef())||prior.evidence().outcome()!=proof.outcome()))throw denied("Owner reconciliation evidence differs");
        Receipt receipt=prior==null?new Receipt("ouf.platform-storage-resolution.v1",request,proof,Instant.now()):prior;
        if(prior==null)writeReceipt(path,receipt);
        // A previously uncertain fsync is retried before either state transition.
        forceFile(path);forceDirectory();
        if(!current.equals(next))write(next);else{forceFile(state);forceDirectory();}
        Evidence finalProof=owner.current();validateEvidence(request,finalProof);
        if(!proof.equals(finalProof))throw denied("Owner reconciliation evidence changed before completion");
        Files.delete(activeLease);forceDirectory();return receipt;
      }
    }catch(IOException ex){throw denied("Authority reconciliation outcome uncertain; reconcile exact command",ex);}
  }
  public static String reconciliationFingerprint(Request request){validateRequest(request);return hashValue(request);}
  private static void validateRequest(Request r){
    if(r==null||r.commandId()==null||r.leaseId()==null||r.leaseSha256()==null||!r.leaseSha256().matches("[a-f0-9]{64}")||r.expectedGeneration()<1||r.expectedGeneration()==Long.MAX_VALUE||!reference(r.actorRef())||!reference(r.reasonCode())||!reference(r.decisionRef()))throw denied("Invalid reconciliation request");
    validate(r.revokedNext(),false);
    if(!r.revokedNext().revoked()||r.revokedNext().generation()!=r.expectedGeneration()+1)throw denied("Reconciliation requires revoked next generation");
  }
  private static void validateEvidence(Request r,Evidence e){
    Instant now=Instant.now();
    if(e==null||!reconciliationFingerprint(r).equals(e.requestSha256())||!reference(e.databaseIdentity())||!reference(e.ownerEvidenceRef())||e.outcome()==null||e.verifiedAt()==null||e.validUntil()==null||e.verifiedAt().isAfter(now)||!now.isBefore(e.validUntil())||!e.validUntil().isAfter(e.verifiedAt())||Duration.between(e.verifiedAt(),e.validUntil()).compareTo(Duration.ofMinutes(5))>0)throw denied("Fresh exact owner reconciliation evidence required");
  }
  private Marker marker(){
    try{
      String text=new String(bounded(activeLease,128),java.nio.charset.StandardCharsets.UTF_8);
      String[] parts=text.split("\n",-1);
      if(parts.length!=3||!parts[2].isEmpty()||!parts[1].matches("[1-9][0-9]{0,18}"))throw denied("Invalid unresolved authority marker");
      UUID id=UUID.fromString(parts[0]);if(!id.toString().equals(parts[0]))throw denied("Invalid unresolved authority marker");
      return new Marker(id,Long.parseLong(parts[1]),hashBytes(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }catch(IOException|IllegalArgumentException ex){throw denied("Unresolved authority marker unavailable or malformed",ex);}
  }
  private Receipt readReceipt(Path path){
    try{
      Recorded stored=JSON.readValue(bounded(path,8192),Recorded.class);Receipt r=stored.receipt();
      if(r==null||!"ouf.platform-storage-resolution.v1".equals(r.schema())||r.recordedAt()==null||r.evidence()==null||!hashValue(r).equals(stored.sha256()))throw denied("Reconciliation receipt integrity differs");
      validateRequest(r.request());return r;
    }catch(IOException ex){throw denied("Reconciliation receipt unavailable or malformed",ex);}
  }
  private void writeReceipt(Path path,Receipt receipt)throws IOException{
    // Preserve audit history; bounded capacity blocks instead of deleting old evidence.
    try(var entries=Files.list(root)){if(entries.filter(p->p.getFileName().toString().startsWith("resolution-")).limit(128).count()>=128)throw denied("Reconciliation receipt capacity exhausted");}
    byte[] bytes=JSON.writeValueAsBytes(new Recorded(receipt,hashValue(receipt)));
    if(bytes.length>8192)throw denied("Reconciliation receipt too large");
    Path staged=root.resolve("resolution.pending");if(Files.exists(staged,NOFOLLOW_LINKS))regular(staged);
    try(var channel=FileChannel.open(staged,Set.of(CREATE,TRUNCATE_EXISTING,WRITE,NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(PRIVATE))){
      ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
    }
    Files.move(staged,path,StandardCopyOption.ATOMIC_MOVE);
    forceDirectory();
  }
  private byte[] bounded(Path path,int limit)throws IOException{
    regular(path);
    try(var channel=FileChannel.open(path,READ,NOFOLLOW_LINKS)){
      ByteBuffer buffer=ByteBuffer.allocate(limit+1);while(buffer.hasRemaining()&&channel.read(buffer)!=-1){}
      if(buffer.position()==0||buffer.position()>limit)throw denied("Authority file size invalid");return Arrays.copyOf(buffer.array(),buffer.position());
    }
  }
  private void forceFile(Path path)throws IOException{regular(path);try(var channel=FileChannel.open(path,WRITE,NOFOLLOW_LINKS)){channel.force(true);}}
  private Held acquire(){return acquire(false);}
  private Held acquire(boolean allowUnresolved){
    FileChannel channel=null;
    try{
      if(!guardKey.equals(regular(guard).fileKey()))throw denied("Authority guard replaced");
      channel=FileChannel.open(guard,READ,WRITE,NOFOLLOW_LINKS);
      FileLock lock;
      try{lock=channel.tryLock();}catch(OverlappingFileLockException ex){channel.close();return null;}
      if(lock==null){channel.close();return null;}
      Held held=new Held(channel,lock);
      if(!guardKey.equals(regular(guard).fileKey())){held.close();throw denied("Authority guard replaced");}
      if(!allowUnresolved&&Files.exists(activeLease,NOFOLLOW_LINKS)){held.close();throw denied("Unresolved prior authority lease; platform reconciliation required");}
      return held;
    }catch(IOException ex){if(channel!=null)try{channel.close();}catch(IOException ignored){}throw denied("Authority lock unavailable",ex);}
    catch(RuntimeException ex){if(channel!=null)try{channel.close();}catch(IOException ignored){}throw ex;}
  }
  private Proof read(){
    try{
      regular(state);byte[] bytes;
      try(var channel=FileChannel.open(state,READ,NOFOLLOW_LINKS)){
        ByteBuffer buffer=ByteBuffer.allocate(4097);while(buffer.hasRemaining()&&channel.read(buffer)!=-1){}
        if(buffer.position()==0||buffer.position()>4096)throw denied("Authority state size invalid");bytes=Arrays.copyOf(buffer.array(),buffer.position());
      }
      var tree=JSON.readTree(bytes);
      if(!tree.isObject()||tree.size()!=3||!tree.path("proof").isObject()||tree.path("proof").size()!=8||!tree.path("proof").path("revoked").isBoolean())throw denied("Authority state shape invalid");
      var node=tree.path("proof");
      for(String field:List.of("installation","tenant","bundleId","issuedAt","expiresAt"))if(!node.path(field).isTextual())throw denied("Authority state shape invalid");
      for(String field:List.of("bundleVersion","generation"))if(!node.path(field).isIntegralNumber()||!node.path(field).canConvertToLong())throw denied("Authority state shape invalid");
      Stored stored=JSON.treeToValue(tree,Stored.class);validate(stored.proof(),false);
      if(!SCHEMA.equals(stored.schema())||!hash(stored.proof()).equals(stored.sha256()))throw denied("Authority state integrity differs");
      return stored.proof();
    }catch(IOException ex){throw denied("Authority state unavailable or malformed",ex);}
  }
  private void write(Proof proof){
    try{
      byte[] bytes=JSON.writeValueAsBytes(new Stored(SCHEMA,proof,hash(proof)));if(bytes.length>4096)throw denied("Authority state size invalid");
      if(Files.exists(pending,NOFOLLOW_LINKS))regular(pending);
      try(var channel=FileChannel.open(pending,Set.of(CREATE,TRUNCATE_EXISTING,WRITE,NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(PRIVATE))){
        ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
      }
      Files.move(pending,state,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
      forceDirectory();
    }catch(IOException ex){throw denied("Authority publication outcome uncertain; reconcile exact proof",ex);}
  }
  private void forceDirectory()throws IOException{try(var directory=FileChannel.open(root,READ)){directory.force(true);}}
  private BasicFileAttributes regular(Path path)throws IOException{
    var a=Files.readAttributes(path,BasicFileAttributes.class,NOFOLLOW_LINKS);
    if(!a.isRegularFile()||!Files.getPosixFilePermissions(path,NOFOLLOW_LINKS).equals(PRIVATE)||!Files.getOwner(path,NOFOLLOW_LINKS).equals(Files.getOwner(root,NOFOLLOW_LINKS)))throw denied("Private regular authority file required");
    return a;
  }
  private static void validate(Proof p,boolean current){
    if(p==null||!reference(p.installation())||!reference(p.tenant())||!reference(p.bundleId())||p.bundleVersion()<1||p.generation()<1||p.issuedAt()==null||p.expiresAt()==null||!p.expiresAt().isAfter(p.issuedAt())||Duration.between(p.issuedAt(),p.expiresAt()).compareTo(Duration.ofMinutes(15))>0)throw denied("Invalid platform authority proof");
    Instant now=Instant.now();if(current&&(p.issuedAt().isAfter(now)||!now.isBefore(p.expiresAt())))throw denied("Platform authority proof expired or future");
  }
  private static boolean reference(String value){return value!=null&&value.matches("[A-Za-z0-9_.:/-]{1,256}");}
  private static String hash(Proof p){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(p)));}catch(Exception ex){throw denied("Authority encoding unavailable",ex);}}
  private static String hashValue(Object value){try{return hashBytes(JSON.writeValueAsBytes(value));}catch(IOException ex){throw denied("Authority encoding unavailable",ex);}}
  private static String hashBytes(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception ex){throw denied("Authority digest unavailable",ex);}}
  private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw denied("Platform publication requires no owner transaction");}
  private static IllegalStateException denied(String message){return new IllegalStateException(message);}
  private static IllegalStateException denied(String message,Throwable cause){return new IllegalStateException(message,cause);}
  private static final class Held implements AutoCloseable{
    final FileChannel channel;final FileLock lock;final AtomicBoolean closed=new AtomicBoolean();
    Held(FileChannel channel,FileLock lock){this.channel=channel;this.lock=lock;}
    public void close(){if(closed.compareAndSet(false,true))try{try{lock.release();}finally{channel.close();}}catch(IOException ex){throw denied("Authority lease release failed",ex);}}
  }
}
