package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class ManagedFileOpenApiTest {
  @Test void exposesTheConversationalCapabilitiesWithoutRuntimeIngestion() throws Exception {String api=Files.readString(Path.of("openapi/onboarding-v1.yaml"));assertThat(api).contains("ouf.managed-source.file.upload","ouf.managed-source.file.profile","ouf.managed-source.preview","ouf.managed-source.onboarding.create","ouf.source-onboarding.status.read","ouf.source-onboarding.submit","ouf.source-onboarding.approval-handoff.create","trustedApprovalRef","ONE_ROW_ONE_SOURCE_OBJECT");assertThat(api).doesNotContain("ouf.managed-source.ingest","pull-dispatch","initial-ingestions","/confirm:","/activate:");}
  @Test void exposesIncrementalSchemaFieldAndRelationshipCapabilities() throws Exception {String api=Files.readString(Path.of("openapi/onboarding-v1.yaml"));assertThat(api).contains("source.discovery.type.list","source.discovery.schema.read","source.mapping.initialize","source.field.mapping.configure","source.relationship.mapping.list","extractionDecision","dataAccessLabel","vocabularyVersion","valueMapRef");}
}
