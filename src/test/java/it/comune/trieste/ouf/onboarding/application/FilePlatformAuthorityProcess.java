package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.authorization.StorageRecoveryAuthorityFence.Proof;
import it.comune.trieste.ouf.onboarding.installation.*;
import java.nio.file.*;
import java.time.Duration;

/** Disposable separate JVM, synthetic platform state only; no HTTP or credential input. */
public final class FilePlatformAuthorityProcess {
  public static void main(String[] args)throws Exception{
    var json=new ObjectMapper().findAndRegisterModules();var root=Path.of(args[0]);var store=new FilePlatformRecoveryAuthority(root);
    try{
      switch(args[1]){
        case "read" -> {
          try(var lease=store.tryAcquire(new PlatformStorageRecoveryAuthorityFence.Binding("lab-installation","platform-tenant",1))){
            if(lease==null)throw new IllegalStateException("contended");System.out.println("FILE_AUTHORITY_READ "+json.writeValueAsString(lease.proof()));
          }
        }
        case "publish","publish-halt" -> {
          var next=json.readValue(Path.of(args[2]).toFile(),Proof.class);long version=store.publish(Long.parseLong(args[3]),next);
          if(args[1].equals("publish-halt"))Runtime.getRuntime().halt(86);
          System.out.println("FILE_AUTHORITY_PUBLISHED "+version);
        }
        case "hold" -> {
          try(var lease=store.tryAcquire(new PlatformStorageRecoveryAuthorityFence.Binding("lab-installation","platform-tenant",1))){
            if(lease==null)throw new IllegalStateException("contended");Files.writeString(root.resolve("child-ready"),"locked");
            long until=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            while(!Files.exists(root.resolve("child-release"))&&System.nanoTime()<until)Thread.sleep(20);
          }
        }
        default -> throw new IllegalArgumentException("Unknown fixture phase");
      }
    }catch(IllegalStateException ex){System.out.println("FILE_AUTHORITY_DENIED "+ex.getMessage());System.exit(3);}
  }
}
