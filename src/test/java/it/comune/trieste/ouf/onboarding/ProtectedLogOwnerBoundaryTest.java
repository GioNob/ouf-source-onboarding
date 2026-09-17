package it.comune.trieste.ouf.onboarding;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import it.comune.trieste.ouf.onboarding.api.*;
import it.comune.trieste.ouf.onboarding.application.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
class ProtectedLogOwnerBoundaryTest {
 private RequestPostProcessor actor(boolean detail){return request->{
  TestAuthorization.bind(request,"operator","HUMAN",Set.of("ouf.ths.log.read.detail"));
  var engine=(LocalAuthorization)request.getServletContext().getAttribute(ServletAuthorization.RUNTIME);var old=engine.currentSnapshot().bundle();var g=old.grants().getFirst();
  var constraint=new GrantConstraints("ALLOW",null,"protected-log","log-1",Map.of("module","ONBOARDING"),Set.of(),detail?Set.of("SECURITY_SENSITIVE"):Set.of(),null,Set.of(),null);
  var grant=new Grant(g.grantId(),g.capabilityId(),g.tenantId(),g.subjectId(),g.servicePrincipalId(),g.organizationId(),g.validFrom(),g.validUntil(),constraint);
  try{TestAuthorization.install(engine,new PolicyBundle(old.bundleId(),2,old.publishedAt(),old.capabilities(),List.of(grant)));}catch(Exception e){throw new IllegalStateException(e);}return request;
 };}
 @Test void protectedLogRequiresGovernedNamespaceAndExplicitDetail()throws Exception{
  var logs=mock(ProtectedLogService.class);when(logs.read(anyString(),anyString(),any(),anyString(),anyString())).thenReturn(Map.of("safe",true));
  var api=new ProtectedLogApi(logs,new TrustedActorResolver());ReflectionTestUtils.setField(api,"logTenant","tenant-a");
  var http=MockMvcBuilders.standaloneSetup(api).setControllerAdvice(new ProblemHandler()).build();
  http.perform(get("/api/trusted-human/v1/logs/log-1").param("purpose","incident").with(actor(false))).andExpect(status().isForbidden());verifyNoInteractions(logs);
  http.perform(get("/api/trusted-human/v1/logs/log-1").param("purpose","incident").with(actor(true))).andExpect(status().isOk());
  verify(logs).read(eq("log-1"),eq("incident"),any(),eq("fixture:2:ouf.ths.log.read.detail"),anyString());
  ReflectionTestUtils.setField(api,"logTenant","other-tenant");http.perform(get("/api/trusted-human/v1/logs/log-1").param("purpose","incident").with(actor(true))).andExpect(status().isForbidden());
 }
}
