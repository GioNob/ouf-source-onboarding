package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationPolicyRegistry;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationRuntimeSynchronizer;
import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
 "ouf.authorization.bootstrap.admin-issuer=fixture-issuer",
 "ouf.authorization.bootstrap.superadmin-role=ente:bootstrap",
 "ouf.authorization.bootstrap.admin-tenant=tenant-a"
}) @AutoConfigureMockMvc
class AuthorizationAdminRuntimeTest {
 @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->System.getenv("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->System.getenv("OUF_ONB_DB_PASSWORD"));}
 @Autowired it.comune.trieste.ouf.onboarding.authorization.AuthorizationAdminService admin;
 @Autowired MockMvc http;@Autowired ObjectMapper json;@Autowired JdbcClient db;@Autowired AuthorizationPolicyRegistry registry;
 @MockBean AuthorizationRuntimeSynchronizer runtimeSynchronizer;
 private final String root="/api/trusted-human/v1/authorization";
 @BeforeEach void clean(){db.sql("truncate ouf_authorization.superadmin_history,ouf_authorization.superadmin_transfer,ouf_authorization.superadmin_binding,ouf_authorization.admin_audit,ouf_authorization.policy_draft,ouf_authorization.capability_registration,ouf_authorization.authorization_decision_audit,ouf_authorization.active_policy_bundle,ouf_authorization.policy_bundle,ouf_authorization.bootstrap_latch cascade").update();db.sql("insert into ouf_authorization.bootstrap_latch(singleton_key,completed) values(true,false)").update();}
 private RequestPostProcessor actor(String type,boolean writeProof){return r->{var caps=registry.active().isEmpty()?Set.of("authorization.bootstrap"):Set.of("authorization.policy.admin");TestAuthorization.bind(r,"admin",type,caps);r.setAttribute(ServletAuthorization.TRUSTED_PRINCIPAL,SuperadminFixtures.principal("admin",type,caps));r.setAttribute("ouf.statelessBearerWriteValidated",writeProof);return r;};}
 private CapabilityDescriptor cap(){return new CapabilityDescriptor("data.read","READ","data.read",Set.of(PrincipalContext.ActorType.HUMAN));}
 private CapabilityDescriptor adminCap(){return new CapabilityDescriptor("authorization.policy.admin","EXECUTE","authorization.policy.admin",Set.of(PrincipalContext.ActorType.HUMAN));}
 private PolicyBundle policy(long version){var now=Instant.now();return new PolicyBundle("admin-test",version,now,List.of(cap(),adminCap()),List.of(new Grant("bootstrap-admin","authorization.policy.admin","tenant-a","admin",null,null,now.minusSeconds(60),now.plusSeconds(3600)),new Grant("g1","data.read","tenant-a","reader",null,null,now.minusSeconds(60),now.plusSeconds(3600))));}
 private void register()throws Exception{http.perform(post(root+"/capabilities").with(actor("HUMAN",true)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("ownerRef","authorization","descriptor",adminCap())))).andExpect(status().isCreated());http.perform(post(root+"/capabilities").with(actor("HUMAN",true)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("ownerRef","udp","descriptor",cap())))).andExpect(status().isCreated());}
 private JsonNode create(long version)throws Exception{return json.readTree(http.perform(post(root+"/policies").with(actor("HUMAN",true)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(policy(version)))).andExpect(status().isOk()).andExpect(header().string("ETag","\"0\"")).andReturn().getResponse().getContentAsByteArray());}
 @Test void bootstrapPersistsRoleWithoutRequiringNominalGrant()throws Exception{
  register();var p=policy(1);
  var withoutAdmin=new PolicyBundle(p.bundleId(),p.version(),p.publishedAt(),p.capabilities(),p.grants().stream().filter(g->!g.capabilityId().equals("authorization.policy.admin")).toList());
  var draft=json.readTree(http.perform(post(root+"/policies").with(actor("HUMAN",true)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(withoutAdmin))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
  http.perform(post(root+"/policies/"+draft.get("id").asText()+":publish").with(actor("HUMAN",true)).header("If-Match","\"0\"")).andExpect(status().isOk());
  assertThat(admin.bootstrapOpen()).isFalse();assertThat(registry.active()).isPresent();
  assertThat(db.sql("select role_ref from ouf_authorization.superadmin_binding").query(String.class).single()).isEqualTo("ente:bootstrap");
  assertThat(admin.get(java.util.UUID.fromString(draft.get("id").asText())).state()).isEqualTo("PUBLISHED");
 }
 @Test void adminPublishAndRevocationAreVersionedAuditedAndImmutable()throws Exception{
  register();var draft=create(1);String id=draft.get("id").asText();
  http.perform(post(root+"/policies/"+id+":publish").with(actor("HUMAN",true)).header("If-Match","\"0\"")).andExpect(status().isOk());
  var runtime=new LocalAuthorization(java.time.Clock.systemUTC(),java.time.Duration.ofMinutes(5));TestAuthorization.install(runtime,registry.load("admin-test",1));var pinned=runtime.currentSnapshot();
  var p=new PrincipalContext("reader","tenant-a",PrincipalContext.ActorType.HUMAN,null,"auth","issuer","aud",Set.of("data.read"));var resource=new ResourceContext("object","42","tenant-a",null,Map.of());assertThat(runtime.evaluate(p,resource,"data.read","READ").allowed()).isTrue();
  var next=create(2);String nextId=next.get("id").asText();
  http.perform(delete(root+"/policies/"+nextId+"/grants/g1").with(actor("HUMAN",true)).header("If-Match","\"0\"")).andExpect(status().isOk()).andExpect(header().string("ETag","\"1\""));
  http.perform(post(root+"/policies/"+nextId+":publish").with(actor("HUMAN",true)).header("If-Match","\"0\"")).andExpect(status().isPreconditionFailed());
  http.perform(post(root+"/policies/"+nextId+":publish").with(actor("HUMAN",true)).header("If-Match","\"1\"")).andExpect(status().isOk());
  TestAuthorization.install(runtime,registry.load("admin-test",2));assertThat(runtime.evaluate(p,resource,"data.read","READ").allowed()).isFalse();assertThat(runtime.evaluate(pinned,p,resource,"data.read","READ").allowed()).isTrue();
  assertThat(db.sql("select count(*) from ouf_authorization.admin_audit").query(Long.class).single()).isGreaterThanOrEqualTo(6);
  assertThatThrownBy(()->db.sql("delete from ouf_authorization.admin_audit").update()).hasStackTraceContaining("append-only");
 }
 @Test void bootstrapLatchDoesNotReopenWhenActivePointerDisappears()throws Exception{
  assertThat(admin.bootstrapOpen()).isTrue();
  register();String id=create(1).get("id").asText();
  http.perform(post(root+"/policies/"+id+":publish").with(actor("HUMAN",true)).header("If-Match","\"0\"")).andExpect(status().isOk());
  assertThat(admin.bootstrapOpen()).isFalse();
  db.sql("delete from ouf_authorization.active_policy_bundle").update();
  assertThat(registry.active()).isEmpty();
  assertThat(admin.bootstrapOpen()).isFalse();
  http.perform(post(root+"/policies").with(actor("HUMAN",true)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(policy(2)))).andExpect(status().isForbidden());
  assertThatThrownBy(()->db.sql("update ouf_authorization.bootstrap_latch set completed=false,completed_at=null,completed_by=null where singleton_key=true").update()).hasStackTraceContaining("cannot be reopened");
  assertThatThrownBy(()->db.sql("delete from ouf_authorization.bootstrap_latch").update()).hasStackTraceContaining("cannot be deleted");
 }
 @Test void machineAndMissingTrustedWriteProofCannotWrite()throws Exception{
  for(String type:List.of("SERVICE","AI_AGENT"))http.perform(post(root+"/policies").with(actor(type,true)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(policy(1)))).andExpect(status().isForbidden());
  http.perform(post(root+"/policies").with(actor("HUMAN",false)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(policy(1)))).andExpect(status().isForbidden());
  register();String id=create(1).get("id").asText();http.perform(delete(root+"/policies/"+id).with(actor("HUMAN",true))).andExpect(status().isPreconditionRequired());
 }
 @Test void capabilityRepurposeAndStaleActiveBaseAreRejected()throws Exception{
  register();http.perform(post(root+"/capabilities").with(actor("HUMAN",true)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("ownerRef","other","descriptor",cap())))).andExpect(status().isConflict());
  String first=create(1).get("id").asText(),second=create(2).get("id").asText();
  http.perform(post(root+"/policies/"+first+":publish").with(actor("HUMAN",true)).header("If-Match","\"0\"")).andExpect(status().isOk());
  http.perform(post(root+"/policies/"+second+":publish").with(actor("HUMAN",true)).header("If-Match","\"0\"")).andExpect(status().isConflict());
 }
 @Test void unknownPolicyConditionFailsClosed()throws Exception{
  register();var raw=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(policy(1));raw.put("unknownMandatoryCondition",true);
  http.perform(post(root+"/policies").with(actor("HUMAN",true)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(raw))).andExpect(status().isBadRequest());
 }
 @Test void concurrentPublishHasExactlyOneWinner()throws Exception{
  register();var a=new it.comune.trieste.ouf.onboarding.authorization.AuthorizationAdminService.Actor("admin","tenant-a","HUMAN","fixture:1","concurrency",SuperadminFixtures.principal("admin","HUMAN",Set.of("authorization.bootstrap")).context());
  var first=admin.create(policy(1),a);var second=admin.create(policy(2),a);
  var start=new java.util.concurrent.CountDownLatch(1);
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
   var futures=new ArrayList<java.util.concurrent.Future<Boolean>>();
   for(var draft:List.of(first,second))futures.add(pool.submit(()->{start.await();try{admin.publish(draft.id(),0,a);return true;}catch(it.comune.trieste.ouf.onboarding.domain.DomainFailure expected){return false;}}));
   start.countDown();int winners=0;for(var future:futures)if(future.get(10,java.util.concurrent.TimeUnit.SECONDS))winners++;
   assertThat(winners).isEqualTo(1);
   assertThat(db.sql("select count(*) from ouf_authorization.admin_audit where action='PUBLISH_ACTIVATE'").query(Long.class).single()).isEqualTo(1);
  }
 }
 @Test void activeTransportHashMatchesSerializedBundle()throws Exception{
  register();String id=create(1).get("id").asText();http.perform(post(root+"/policies/"+id+":publish").with(actor("HUMAN",true)).header("If-Match","\"0\"")).andExpect(status().isOk());
  byte[] raw=http.perform(get("/api/internal/v1/authorization/policy-bundle/active").with(r->{TestAuthorization.bind(r,"workload","SERVICE",Set.of("authorization.bundle.read"));return r;})).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
  var node=json.readTree(raw);String actual=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(node.get("bundle"))));
  assertThat(node.get("contentHash").asText()).isEqualTo(actual);
 }
}
