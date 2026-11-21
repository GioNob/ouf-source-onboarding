package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.TestAuthorization;
import it.comune.trieste.ouf.onboarding.application.SchemaSurveillanceService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SchemaSurveillanceApiTest {
  private final ObjectMapper json=new ObjectMapper();
  private final SchemaSurveillanceService service=mock(SchemaSurveillanceService.class);
  private final SchemaSurveillanceApi api=new SchemaSurveillanceApi(service,new TrustedActorResolver(),json);

  @Test void humanCannotReportOrUseTheServiceReconciliationList() {
    var request=new MockHttpServletRequest();
    TestAuthorization.bind(request,"user","HUMAN",Set.of("ouf.onboarding.revision.prepare"));
    assertThatThrownBy(()->api.report("s",json.createObjectNode(),request)).hasMessageContaining("Invalid actor");
    assertThatThrownBy(()->api.issues("s",request)).hasMessageContaining("Invalid actor");
    verifyNoInteractions(service);
  }

  @Test void historicalProducerCanReconcileItsExactReceipt() {
    var request=new MockHttpServletRequest();
    TestAuthorization.bind(request,"ingestion","SERVICE",Set.of("ouf.onboarding.configuration.read"));
    String tenant=new TrustedActorResolver().principal(request).tenantId();
    var detail=Map.<String,Object>of("tenant",tenant,"sourceId","s","idempotencyKey","ingestion-schema:fixture");
    var body=Map.of("observedSchemaRef","ingestion:schema-incident:fixture","previousFingerprint","previous",
        "observedFingerprint","observed","classification","BREAKING","detail",detail);
    var row=Map.<String,Object>of("issue_id",UUID.randomUUID(),"detail",detail);
    when(service.legacy(tenant,"s","ingestion:schema-incident:fixture","previous","observed","BREAKING",detail)).thenReturn(row);
    var receipt=api.report("s",json.valueToTree(body),request);
    assertThat(receipt.getStatusCode().value()).isEqualTo(201);
    assertThat(receipt.getBody()).isEqualTo(row);
    when(service.issues(tenant,"s")).thenReturn(List.of(row));
    assertThat(api.issues("s",request)).containsExactly(row);
  }
}
