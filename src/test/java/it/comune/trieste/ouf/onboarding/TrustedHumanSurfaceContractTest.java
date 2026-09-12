package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class TrustedHumanSurfaceContractTest {
  @Test void protectedOperationsAreExplicitlyOutsideMcp() throws Exception {String ths=Files.readString(Path.of("openapi/ths-v1.yaml"));assertThat(ths).contains("x-ouf-mcp-exposed: false","/api/trusted-human/v1/","getTrustedApprovalCard","confirmTrustedApproval","rejectTrustedApproval","activateTrustedApproval","searchProtectedLogs","readProtectedLog","aggregateProtectedLogs","correlateProtectedLogs","createProtectedLogExport","downloadProtectedLogExport","searchManagedFileQuarantine","releaseManagedFileQuarantine","rejectManagedFileQuarantine").doesNotContain("x-ouf-mcp-capability","/api/ths/v1");String mcp=Files.readString(Path.of("openapi/onboarding-v1.yaml"));assertThat(mcp).doesNotContain("confirmTrustedApproval","activateTrustedApproval","searchProtectedLogs","downloadProtectedLogExport","releaseManagedFileQuarantine");}
}
