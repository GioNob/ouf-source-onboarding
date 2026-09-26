package it.comune.trieste.ouf.onboarding.application;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="ouf.onboarding.staging.bucket")
public class MinioManagedFileStagingStore implements ManagedFileStagingStore {
  private static final int MAX_BYTES=10*1024*1024;
  private static final String PREFIX="object://managed-files/";
  private final MinioClient minio;
  private final String bucket;

  @Autowired
  public MinioManagedFileStagingStore(
      @Value("${ouf.onboarding.staging.endpoint}") String endpoint,
      @Value("${ouf.onboarding.staging.bucket}") String bucket,
      @Value("${ouf.onboarding.staging.access-key-file}") Path accessKeyFile,
      @Value("${ouf.onboarding.staging.secret-key-file}") Path secretKeyFile) throws Exception {
    this.bucket=bucket;
    if(!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"))throw new IllegalArgumentException("invalid staging bucket");
    String access=Files.readString(accessKeyFile).strip();
    String secret=Files.readString(secretKeyFile).strip();
    if(access.isEmpty()||secret.isEmpty())throw new IllegalArgumentException("staging credentials must not be empty");
    this.minio=MinioClient.builder().endpoint(endpoint).credentials(access,secret).build();
  }

  // Visible for storage tests without reading any real credentials.
  MinioManagedFileStagingStore(MinioClient minio,String bucket){this.minio=minio;this.bucket=bucket;}

  @Override public String put(InputStream content,long size,String mediaType) throws IOException {
    if(content==null||size<=0||size>MAX_BYTES)throw new IllegalArgumentException("file exceeds staging limit");
    if(!"text/csv".equals(mediaType))throw new IllegalArgumentException("unsupported staging media type");
    String id=UUID.randomUUID().toString();
    try {
      minio.putObject(PutObjectArgs.builder().bucket(bucket).object("managed-files/"+id)
          .stream(content,size,-1).contentType(mediaType).build());
      return PREFIX+id;
    } catch(Exception e){throw new DomainFailure(HttpStatus.BAD_GATEWAY,"ONB_STAGING_WRITE_FAILED","Managed file staging write failed");}
  }

  @Override public byte[] get(String ref){
    String id=objectId(ref);
    try(var stream=minio.getObject(GetObjectArgs.builder().bucket(bucket).object("managed-files/"+id).build())){
      byte[] result=stream.readNBytes(MAX_BYTES+1);
      if(result.length==0||result.length>MAX_BYTES)throw new DomainFailure(HttpStatus.BAD_GATEWAY,"ONB_STAGING_SIZE_INVALID","Staged file size is outside limits");
      return result;
    } catch(DomainFailure e){throw e;}
      catch(Exception e){throw new DomainFailure(HttpStatus.BAD_GATEWAY,"ONB_STAGING_READ_FAILED","Managed file staging read failed");}
  }

  static String objectId(String ref){
    if(ref==null||!ref.matches("object://managed-files/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
      throw new IllegalArgumentException("invalid managed file reference");
    return ref.substring(PREFIX.length());
  }
}
