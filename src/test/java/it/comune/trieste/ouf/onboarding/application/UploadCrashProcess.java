package it.comune.trieste.ouf.onboarding.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Separate JVM: halt skips shutdown hooks/finally/transaction cleanup in the owner. */
public class UploadCrashProcess {
  static DriverManagerDataSource datasource(){return new DriverManagerDataSource(System.getenv("OUF_ONB_DB_URL"),System.getenv("OUF_ONB_DB_USER"),System.getenv("OUF_ONB_DB_PASSWORD"));}
  static MinioClient client(){return MinioClient.builder().endpoint(System.getenv("OUF_UPLOAD_S3_URL")).region("us-east-1")
      .credentials("upload-fixture","upload-fixture-only-secret").httpClient(new okhttp3.OkHttpClient.Builder().retryOnConnectionFailure(false).build()).build();}
  public static void main(String[] args)throws Exception{
    var ds=datasource();var db=JdbcClient.create(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    var json=new ObjectMapper();var hashes=new CanonicalHash(json);
    var files=new ManagedFileService(db,json,hashes,null,tx,null,null);
    var real=new MinioManagedFileStagingStore(client(),"upload-fixture");
    var store=new ManagedFileStagingStore(){
      public void putReserved(String ref,InputStream in,long size,String type)throws IOException{
        System.out.println("DURABLE_INTENT "+ref);System.out.flush();
        if("BEFORE_PUT".equals(args[0]))Runtime.getRuntime().halt(86);
        real.putReserved(ref,in,size,type);
        System.out.println("PUT_RETURNED_BEFORE_ACK");System.out.flush();Runtime.getRuntime().halt(86);
      }
      public byte[] get(String ref){return real.get(ref);}
    };
    var spool=Path.of(args[2]);byte[] bytes=java.nio.file.Files.readAllBytes(spool);
    files.registerDelegatedUpload("tenant-a","alice","file_fixture",args[1],hashes.ofBytes(bytes),bytes.length,store,spool);
    throw new AssertionError("Crash hook not reached");
  }
}
