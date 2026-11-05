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
  private final Path root,guard,state,pending;
  private final Object guardKey;
  private record Stored(String schema,Proof proof,String sha256) {}

  public FilePlatformRecoveryAuthority(Path directory){
    try{
      root=directory.toAbsolutePath().normalize();
      if(!root.equals(root.toRealPath())||!Files.isDirectory(root,NOFOLLOW_LINKS)||!Files.getPosixFilePermissions(root,NOFOLLOW_LINKS).equals(DIRECTORY))throw denied("Protected authority directory required");
      if(!Set.of("ext4","xfs","tmpfs","overlay").contains(Files.getFileStore(root).type()))throw denied("Unqualified authority filesystem");
      guard=root.resolve("authority.lock");state=root.resolve("current.json");pending=root.resolve("current.pending");
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
      return new PlatformStorageRecoveryAuthorityFence.Lease(){
        public Proof proof(){return proof;}
        public void close(){held.close();}
      };
    }catch(RuntimeException ex){held.close();throw ex;}
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
  private Held acquire(){
    FileChannel channel=null;
    try{
      if(!guardKey.equals(regular(guard).fileKey()))throw denied("Authority guard replaced");
      channel=FileChannel.open(guard,READ,WRITE,NOFOLLOW_LINKS);
      FileLock lock;
      try{lock=channel.tryLock();}catch(OverlappingFileLockException ex){channel.close();return null;}
      if(lock==null){channel.close();return null;}
      Held held=new Held(channel,lock);
      if(!guardKey.equals(regular(guard).fileKey())){held.close();throw denied("Authority guard replaced");}
      return held;
    }catch(IOException|RuntimeException ex){if(channel!=null)try{channel.close();}catch(IOException ignored){}throw denied("Authority lock unavailable",ex);}
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
      try(var directory=FileChannel.open(root,READ)){directory.force(true);}
    }catch(IOException ex){throw denied("Authority publication outcome uncertain; reconcile exact proof",ex);}
  }
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
  private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw denied("Platform publication requires no owner transaction");}
  private static IllegalStateException denied(String message){return new IllegalStateException(message);}
  private static IllegalStateException denied(String message,Throwable cause){return new IllegalStateException(message,cause);}
  private static final class Held implements AutoCloseable{
    final FileChannel channel;final FileLock lock;final AtomicBoolean closed=new AtomicBoolean();
    Held(FileChannel channel,FileLock lock){this.channel=channel;this.lock=lock;}
    public void close(){if(closed.compareAndSet(false,true))try{try{lock.release();}finally{channel.close();}}catch(IOException ex){throw denied("Authority lease release failed",ex);}}
  }
}
