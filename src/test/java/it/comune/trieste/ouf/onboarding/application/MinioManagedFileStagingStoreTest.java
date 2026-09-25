package it.comune.trieste.ouf.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class MinioManagedFileStagingStoreTest {
  @Test void rejectsNonManagedReferencesBeforeMinioAccess(){
    var minio=mock(MinioClient.class);
    var storage=new MinioManagedFileStagingStore(minio,"ouf-managed-files");
    assertThatThrownBy(()->storage.get("object://other-bucket/file.csv"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reference");
    assertThatThrownBy(()->storage.get("object://managed-files/../../secret"))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(minio);
  }

  @Test void rejectsOversizedInputBeforeMinioAccess(){
    var minio=mock(MinioClient.class);
    var storage=new MinioManagedFileStagingStore(minio,"ouf-managed-files");
    assertThatThrownBy(()->storage.put(new ByteArrayInputStream(new byte[1]),10*1024*1024+1L,"text/csv"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
    verifyNoInteractions(minio);
  }
}
