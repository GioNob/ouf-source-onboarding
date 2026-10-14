package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ManagedFilePickerPageTest {
  @Test void streamsChunkedRequestWhenContentLengthIsUnknown() throws Exception {
    byte[] csv = "id,label\n1,Trieste\n".getBytes(StandardCharsets.UTF_8);
    var output = new ByteArrayOutputStream();
    assertThat(ManagedFilePickerPage.copyBounded(new ByteArrayInputStream(csv), output, -1)).isEqualTo(csv.length);
    assertThat(output.toByteArray()).isEqualTo(csv);
  }

  @Test void rejectsOversizeWithoutForwardingExcessBytes() {
    InputStream source = new InputStream() {
      int remaining = 10 * 1024 * 1024 + 1;
      @Override public int read() { return remaining-- > 0 ? 'x' : -1; }
    };
    assertThatThrownBy(() -> ManagedFilePickerPage.copyBounded(source, OutputStream.nullOutputStream(), -1))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
  }

  @Test void rejectsTruncatedDeclaredBody() {
    var source = new ByteArrayInputStream("id\n".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> ManagedFilePickerPage.copyBounded(source, OutputStream.nullOutputStream(), 10))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
  }
}
