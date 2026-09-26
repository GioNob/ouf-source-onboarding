package it.comune.trieste.ouf.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class MinioManagedFileStagingStoreTest {
  @Test void startsFromTheDeploymentEnvironment(@TempDir Path directory) throws Exception {
    Path access=directory.resolve("access");
    Path secret=directory.resolve("secret");
    Files.writeString(access,"ouf-onboarding-staging\n");
    Files.writeString(secret,"fake-test-secret-only\n");
    var variables=Map.of(
        "OUF_ONBOARDING_STAGING_ENDPOINT","http://127.0.0.1:9000",
        "OUF_ONBOARDING_STAGING_BUCKET","ouf-managed-files",
        "OUF_ONBOARDING_STAGING_ACCESS_KEY_FILE",access.toString(),
        "OUF_ONBOARDING_STAGING_SECRET_KEY_FILE",secret.toString());
    new ApplicationContextRunner()
        .withInitializer(ctx->ctx.getEnvironment().getPropertySources().addFirst(
            new SystemEnvironmentPropertySource("deployment",variables)))
        .withUserConfiguration(MinioManagedFileStagingStore.class)
        .run(ctx->{assertThat(ctx).hasNotFailed();assertThat(ctx).hasSingleBean(MinioManagedFileStagingStore.class);});
  }

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
