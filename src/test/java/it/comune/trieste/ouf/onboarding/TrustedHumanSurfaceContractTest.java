package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class TrustedHumanSurfaceContractTest {
  @Test void protectedOperationsAreExplicitlyOutsideMcp() throws Exception {String ths=Files.readString(Path.of("openapi/ths-v1.yaml"));assertThat(ths).contains("x-ouf-mcp-exposed: false","getTrustedApprovalCard","confirmTrustedApproval","rejectTrustedApproval","activateTrustedApproval").doesNotContain("x-ouf-mcp-capability");String mcp=Files.readString(Path.of("openapi/onboarding-v1.yaml"));assertThat(mcp).doesNotContain("confirmTrustedApproval","activateTrustedApproval");}
}
