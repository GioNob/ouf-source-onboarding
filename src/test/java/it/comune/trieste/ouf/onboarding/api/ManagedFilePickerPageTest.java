package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import it.comune.trieste.ouf.onboarding.application.ManagedFileContentInspector;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ManagedFilePickerPageTest {
  final ManagedFileContentInspector inspector = new ManagedFileContentInspector(new org.springframework.mock.env.MockEnvironment());
  final ManagedFileContentInspector.Format csvFormat = inspector.requireFormat("text/csv");

  @Test void streamsChunkedRequestWhenContentLengthIsUnknown() throws Exception {
    byte[] csv = "id,label\n1,Trieste\n".getBytes(StandardCharsets.UTF_8);
    assertThat(ManagedFilePickerPage.verifiedBody(new ByteArrayInputStream(csv), -1, csvFormat, inspector)).isEqualTo(csv);
  }

  @Test void rejectsOversizeWithoutForwardingExcessBytes() {
    InputStream source = new InputStream() {
      int remaining = 10 * 1024 * 1024 + 1;
      @Override public int read() { return remaining-- > 0 ? 'x' : -1; }
    };
    assertThatThrownBy(() -> ManagedFilePickerPage.verifiedBody(source, -1, csvFormat, inspector))
        .isInstanceOfSatisfying(it.comune.trieste.ouf.onboarding.domain.DomainFailure.class,
            e -> assertThat(e.properties()).containsEntry("limit","csv.max-bytes"));
  }

  @Test void rejectsTruncatedDeclaredBody() {
    var source = new ByteArrayInputStream("id\n".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> ManagedFilePickerPage.verifiedBody(source, 10, csvFormat, inspector))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
  }
}
