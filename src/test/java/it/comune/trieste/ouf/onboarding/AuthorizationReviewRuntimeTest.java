package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import it.comune.trieste.ouf.onboarding.authorization.*;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties={"ouf.authorization.bootstrap.admin-issuer=fixture-issuer","ouf.authorization.bootstrap.superadmin-role=ente:bootstrap","ouf.authorization.bootstrap.admin-tenant=tenant-a"})
@AutoConfigureMockMvc
class AuthorizationReviewRuntimeTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->System.getenv("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->System.getenv("OUF_ONB_DB_PASSWORD"));}
 @Autowired AuthorizationReviewService review;@Autowired AuthorizationAdminService admin;@Autowired AuthorizationPolicyRegistry registry;
 @Autowired JdbcClient db;@Autowired MockMvc http;@Autowired ObjectMapper json;
 @MockBean AuthorizationRuntimeSynchronizer runtime;
 static final String ROOT="/api/trusted-human/v1/authorization";
 final CapabilityDescriptor status=new CapabilityDescriptor("ouf.system.status","READ","operations.status.read",Set.of(PrincipalContext.ActorType.HUMAN));
 final CapabilityDescriptor management=new CapabilityDescriptor("authorization.policy.admin","EXECUTE","authorization.policy.admin",Set.of(PrincipalContext.ActorType.HUMAN));
 Instant now;PrincipalContext root;
 PrincipalContext person(String subject,String tenant,Set<String> roles,Set<String> scopes){return new PrincipalContext(subject,tenant,PrincipalContext.ActorType.HUMAN,null,"auth","fixture-issuer","aud",scopes,new PrincipalContext.IdentityClaims(roles,"1",Set.of(),Instant.now()));}
 AuthorizationAdminService.Actor actor(PrincipalContext p){return new AuthorizationAdminService.Actor(p.subjectId(),p.tenantId(),p.actorType().name(),"fixture:review",UUID.randomUUID().toString(),p);}
 RequestPostProcessor request(PrincipalContext p,boolean proof){return r->{TestAuthorization.bind(r,p.subjectId(),p.actorType().name(),Set.of("authorization.policy.admin"));r.setAttribute(ServletAuthorization.TRUSTED_PRINCIPAL,new TrustedPrincipal(p));r.setAttribute("ouf.statelessBearerWriteValidated",proof);return r;};}
 PolicyBundle bundle(long version,List<Grant> grants){return new PolicyBundle("review",version,now,List.of(management,status),grants);}
 Grant nominal(String id,String subject,String tenant){return new Grant(id,status.capabilityId(),tenant,subject,null,null,now.minusSeconds(60),now.plusSeconds(3600));}
 Grant role(String id,String effect){return new Grant(id,status.capabilityId(),"tenant-a",null,null,null,now.minusSeconds(60),now.plusSeconds(3600),new GrantConstraints(effect,"ente:staff","capability",null,Map.of(),Set.of(),Set.of("PUBLIC_OPERATIONAL"),null,Set.of(),null));}
 AuthorizationReviewService.Scenario scenario(Set<String> scopes){return new AuthorizationReviewService.Scenario(person("hypothetical-target","tenant-a",Set.of("ente:staff"),scopes),new ResourceContext("capability",null,"tenant-a",null,Map.of("detailLevel","PUBLIC_OPERATIONAL")),status.capabilityId(),"READ");}
 @BeforeEach void setup(){
  db.sql("truncate ouf_authorization.superadmin_history,ouf_authorization.superadmin_transfer,ouf_authorization.superadmin_binding,ouf_authorization.admin_audit,ouf_authorization.policy_draft,ouf_authorization.capability_registration,ouf_authorization.authorization_decision_audit,ouf_authorization.active_policy_bundle,ouf_authorization.policy_bundle,ouf_authorization.bootstrap_latch cascade").update();
  db.sql("insert into ouf_authorization.bootstrap_latch(singleton_key,completed) values(true,false)").update();
  now=Instant.now();root=person("installer","tenant-a",Set.of("ente:bootstrap"),Set.of("authorization.bootstrap","authorization.policy.admin"));
  admin.register("authorization",management,actor(root));admin.register("mcp",status,actor(root));
  var d=admin.create(bundle(1,List.of(nominal("a","giovanni","tenant-a"),nominal("b","giovanni","tenant-a"))),actor(root));admin.publish(d.id(),0,actor(root));
 }
 @Test void grantLookupIsTenantBoundPaginatedAndDoesNotInventIamMembership()throws Exception{
  registry.publishAndActivate(bundle(2,List.of(nominal("a","giovanni","tenant-a"),nominal("b","giovanni","tenant-a"),nominal("foreign","giovanni","tenant-b"),role("role","ALLOW"))),"fixture");
  var first=review.grants(root,"giovanni",null,1,null,null);
  assertThat(first.meaning()).isEqualTo("CONFIGURED_GRANTS_NOT_EFFECTIVE_PERMISSIONS");assertThat(first.grants()).extracting(Grant::grantId).containsExactly("a");
  assertThat(review.grants(root,"giovanni",null,1,first.nextAfter(),first.policyRef()).grants()).extracting(Grant::grantId).containsExactly("b");
  assertThat(review.grants(root,"unknown",null,100,null,null).grants()).isEmpty();
  assertThat(review.grants(root,null,"ente:staff",100,null,null).grants()).extracting(Grant::grantId).containsExactly("role");
  assertThat(review.grants(root,null,"ente:bootstrap",100,null,null).protectedRole().roleRef()).isEqualTo("ente:bootstrap");
  http.perform(get(ROOT+"/access").with(request(root,false)).param("subjectId","giovanni")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
  http.perform(get(ROOT+"/access").with(request(root,false)).param("subjectId","giovanni").param("externalRoleRef","ente:staff")).andExpect(status().isBadRequest());
  assertThatThrownBy(()->review.grants(root,"giovanni",null,1,"a",null)).hasMessage("AUTH_REVIEW_POLICY_REF_REQUIRED");
  registry.publishAndActivate(bundle(3,List.of()),"fixture");
  assertThatThrownBy(()->review.grants(root,"giovanni",null,1,"a",first.policyRef())).hasMessage("AUTH_ACTIVE_CHANGED_RESTART_REVIEW");
 }
 @Test void previewAndSimulationShowRevocationAndRoleGrantWithoutChangingActiveOrDraft()throws Exception{
  var d=admin.create(bundle(2,List.of(role("staff-status","ALLOW"))),actor(root));
  var p=review.preview(root,d.id(),0);assertThat(p.authoritative()).isFalse();assertThat(p.grantChanges()).hasSize(3);
  assertThat(p.grantChanges().getFirst().after()).isNull();assertThat(p.activeHash()).isNotEqualTo(p.draftHash());
  var result=review.simulate(root,d.id(),0,scenario(Set.of("operations.status.read")));
  assertThat(result.before().allowed()).isFalse();assertThat(result.after().allowed()).isTrue();assertThat(result.contextSource()).isEqualTo("HYPOTHETICAL_NOT_IAM_VERIFIED");assertThat(result.authoritative()).isFalse();
  assertThat(review.simulate(root,d.id(),0,scenario(Set.of())).after().code()).isEqualTo("SCOPE_MISSING");
  assertThat(admin.get(d.id())).isEqualTo(d);assertThat(registry.active().orElseThrow().version()).isEqualTo(1);
  assertThat(db.sql("select count(*) from ouf_authorization.policy_bundle").query(Long.class).single()).isEqualTo(1);
  assertThat(db.sql("select count(*) from ouf_authorization.authorization_decision_audit where subject_id='hypothetical-target'").query(Long.class).single()).isZero();
  assertThat(db.sql("select count(*) from ouf_authorization.admin_audit where action='SIMULATE_POLICY' and subject_id='installer'").query(Long.class).single()).isEqualTo(2);
  http.perform(post(ROOT+"/policies/"+d.id()+":simulate").with(request(root,true)).header("If-Match","\"0\"").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(scenario(Set.of("operations.status.read"))))).andExpect(status().isOk()).andExpect(jsonPath("$.after.allowed").value(true)).andExpect(jsonPath("$.decisionRef").doesNotExist()).andExpect(jsonPath("$.after.decisionRef").doesNotExist());
 }
 @Test void sdkDenyAndExpiryRemainEffectiveInSimulation(){
  var expired=new Grant("expired",status.capabilityId(),"tenant-a","hypothetical-target",null,null,now.minusSeconds(100),now.minusSeconds(1));
  var d=admin.create(bundle(2,List.of(expired)),actor(root));
  assertThat(review.simulate(root,d.id(),0,scenario(Set.of("operations.status.read"))).after().code()).isEqualTo("NO_APPLICABLE_GRANT");
  admin.replace(d.id(),0,bundle(2,List.of(role("allow","ALLOW"),role("deny","DENY"))),actor(root));
  assertThat(review.simulate(root,d.id(),1,scenario(Set.of("operations.status.read"))).after().code()).isEqualTo("EXPLICIT_DENY");
 }
 @Test void staleRevisionAndActiveAndOtherTenantDraftAreRejected()throws Exception{
  var d=admin.create(bundle(2,List.of()),actor(root));
  http.perform(post(ROOT+"/policies/"+d.id()+":preview").with(request(root,true))).andExpect(status().isPreconditionRequired());
  http.perform(post(ROOT+"/policies/"+d.id()+":preview").with(request(root,true)).header("If-Match","\"9999999999999999999999\"")).andExpect(status().isBadRequest());
  admin.replace(d.id(),0,bundle(2,List.of(role("new","ALLOW"))),actor(root));
  assertThatThrownBy(()->review.preview(root,d.id(),0)).hasMessage("AUTH_STALE_ETAG");
  db.sql("update ouf_authorization.policy_draft set tenant_id='tenant-b' where draft_id=:id").param("id",d.id()).update();
  assertThatThrownBy(()->review.preview(root,d.id(),1)).hasMessage("AUTH_DRAFT_NOT_FOUND");
  db.sql("update ouf_authorization.policy_draft set tenant_id='tenant-a' where draft_id=:id").param("id",d.id()).update();
  registry.publishAndActivate(bundle(3,List.of()),"fixture");
  assertThatThrownBy(()->review.preview(root,d.id(),1)).hasMessage("AUTH_ACTIVE_CHANGED_REBASE_REQUIRED");
 }
 @Test void forgedCallerHypothesisAndBodyCannotAuthorizeTheRequest()throws Exception{
  var d=admin.create(bundle(2,List.of()),actor(root));var ordinary=person("ordinary","tenant-a",Set.of("ente:staff"),Set.of("authorization.policy.admin"));
  // Test adapter admits a coarse permission; owner still evaluates the actual caller against ACTIVE.
  http.perform(post(ROOT+"/policies/"+d.id()+":simulate").with(request(ordinary,true)).header("If-Match","\"0\"").header("X-OUF-External-Role-Refs","ente:bootstrap").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new AuthorizationReviewService.Scenario(root,scenario(Set.of()).resource(),management.capabilityId(),"EXECUTE")))).andExpect(status().isForbidden());
  http.perform(post(ROOT+"/policies/"+d.id()+":preview").with(request(root,false)).header("If-Match","\"0\"")).andExpect(status().isForbidden());
  http.perform(post(ROOT+"/policies/"+d.id()+":simulate").with(request(root,true)).header("If-Match","\"0\"").contentType(MediaType.APPLICATION_JSON).content("{\"trustedPrincipal\":{\"subjectId\":\"installer\"}}")).andExpect(status().isBadRequest());
  var foreign=new AuthorizationReviewService.Scenario(person("x","tenant-b",Set.of(),Set.of()),scenario(Set.of()).resource(),status.capabilityId(),"READ");
  assertThatThrownBy(()->review.simulate(root,d.id(),0,foreign)).hasMessage("AUTH_SIMULATION_TENANT_MISMATCH");
  var machine=new PrincipalContext("mcp","tenant-a",PrincipalContext.ActorType.SERVICE,"mcp","auth","fixture-issuer","aud",Set.of("authorization.policy.admin"));
  assertThatThrownBy(()->review.grants(machine,"x",null,1,null,null)).hasMessage("AUTH_ADMIN_REQUIRED");
 }
 @Test void revocationOfOrdinaryAdminIsRecheckedAndLargeDiffIsNeverSilentlyTruncated(){
  var grant=new Grant("admin",management.capabilityId(),"tenant-a","ordinary",null,null,now.minusSeconds(60),now.plusSeconds(3600));
  registry.publishAndActivate(bundle(2,List.of(grant)),"fixture");var ordinary=person("ordinary","tenant-a",Set.of(),Set.of("authorization.policy.admin"));
  assertThat(review.grants(ordinary,"someone",null,10,null,null).grants()).isEmpty();
  registry.publishAndActivate(bundle(3,List.of()),"fixture");assertThatThrownBy(()->review.grants(ordinary,"someone",null,10,null,null)).hasMessage("AUTH_ADMIN_REQUIRED");
  var many=new ArrayList<Grant>();for(int i=0;i<201;i++)many.add(nominal("g"+i,"x","tenant-a"));
  var d=admin.create(bundle(4,many),actor(root));assertThatThrownBy(()->review.preview(root,d.id(),0)).hasMessage("AUTH_REVIEW_DIFF_TOO_LARGE");
 }
}
