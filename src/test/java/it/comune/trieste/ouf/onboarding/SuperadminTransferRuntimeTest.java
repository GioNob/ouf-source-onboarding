package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import it.comune.trieste.ouf.onboarding.authorization.*;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties={"ouf.authorization.bootstrap.admin-issuer=fixture-issuer","ouf.authorization.bootstrap.superadmin-role=ente:bootstrap","ouf.authorization.bootstrap.admin-tenant=tenant-a"})
@AutoConfigureMockMvc
class SuperadminTransferRuntimeTest {
 @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->System.getenv("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->System.getenv("OUF_ONB_DB_PASSWORD"));}
 @Autowired SuperadminAuthority authority;@Autowired AuthorizationAdminService admin;@Autowired AuthorizationPolicyRegistry registry;
 @Autowired JdbcClient db;@Autowired MockMvc http;@Autowired ObjectMapper json;@Autowired PlatformTransactionManager transactions;
 @MockBean AuthorizationRuntimeSynchronizer runtime;
 private static final String ROOT="/api/trusted-human/v1/authorization";
 private PrincipalContext person(String subject,String role){return person(subject,role,"tenant-a","fixture-issuer");}
 private PrincipalContext person(String subject,String role,String tenant,String issuer){return new PrincipalContext(subject,tenant,PrincipalContext.ActorType.HUMAN,null,"auth",issuer,"aud",Set.of("authorization.bootstrap","authorization.policy.admin"),new PrincipalContext.IdentityClaims(role==null?Set.of():Set.of(role),"1",Set.of(),Instant.now()));}
 private PrincipalContext installer(){return person("installer","ente:bootstrap");}
 private PrincipalContext successor(){return person("director","ente:director");}
 private AuthorizationAdminService.Actor actor(PrincipalContext p){return new AuthorizationAdminService.Actor(p.subjectId(),p.tenantId(),"HUMAN","test:authority",UUID.randomUUID().toString(),p);}
 private RequestPostProcessor request(PrincipalContext p,boolean proof){return r->{TestAuthorization.bind(r,p.subjectId(),"HUMAN",Set.of("authorization.policy.admin"));r.setAttribute(ServletAuthorization.TRUSTED_PRINCIPAL,new TrustedPrincipal(p));r.setAttribute("ouf.statelessBearerWriteValidated",proof);return r;};}
 private PolicyBundle empty(long version){return new PolicyBundle("roles",version,Instant.now(),List.of(),List.of());}
 @BeforeEach void clean(){db.sql("truncate ouf_authorization.superadmin_history,ouf_authorization.superadmin_transfer,ouf_authorization.superadmin_binding,ouf_authorization.admin_audit,ouf_authorization.policy_draft,ouf_authorization.capability_registration,ouf_authorization.authorization_decision_audit,ouf_authorization.active_policy_bundle,ouf_authorization.policy_bundle,ouf_authorization.bootstrap_latch cascade").update();db.sql("insert into ouf_authorization.bootstrap_latch(singleton_key,completed) values(true,false)").update();}
 private void boot(){var d=admin.create(empty(1),actor(installer()));admin.publish(d.id(),0,actor(installer()));}
 @Test void handoverRequiresTargetRoleAndAtomicallyRemovesPreviousAuthority()throws Exception{
  boot();assertThat(authority.isSuperadmin(installer())).isTrue();assertThat(authority.isSuperadmin(successor())).isFalse();
  var t=authority.propose(installer(),0,"ente:director","handover to municipality");
  assertThat(authority.isSuperadmin(installer())).isTrue();
  http.perform(post(ROOT+"/superadmin/transfers/"+t.id()+":accept").with(request(installer(),true)).header("If-Match","\"0\"")).andExpect(status().isForbidden());
  http.perform(get(ROOT+"/superadmin/transfers/"+t.id()).with(request(successor(),false))).andExpect(status().isOk()).andExpect(jsonPath("$.targetRole").value("ente:director"));
  http.perform(post(ROOT+"/superadmin/transfers/"+t.id()+":accept").with(request(successor(),true)).header("If-Match","\"0\"")).andExpect(status().isOk()).andExpect(header().string("ETag","\"1\""));
  assertThat(authority.isSuperadmin(installer())).isFalse();assertThat(authority.isSuperadmin(successor())).isTrue();
  http.perform(post(ROOT+"/superadmin/transfers/"+t.id()+":accept").with(request(successor(),true)).header("If-Match","\"0\"")).andExpect(status().isPreconditionFailed());
  http.perform(post(ROOT+"/superadmin/transfers").with(request(installer(),true)).header("If-Match","\"1\"").contentType(MediaType.APPLICATION_JSON).content("{\"targetRoleRef\":\"ente:other\",\"reason\":\"unauthorized\"}")).andExpect(status().isForbidden());
  // Losing privileged role does not regain it from unchanged bootstrap configuration.
  assertThatThrownBy(()->authority.adopt(installer())).isInstanceOfAny(SecurityException.class,DomainFailure.class);
  assertThat(db.sql("select count(*) from ouf_authorization.superadmin_history").query(Long.class).single()).isEqualTo(2);
 }
 @Test void tenantIssuerProofAndUnknownPayloadCannotForgeSuccessor()throws Exception{
  boot();var t=authority.propose(installer(),0,"ente:director","handover");
  http.perform(post(ROOT+"/superadmin/transfers/"+t.id()+":accept").with(request(person("director","ente:director","tenant-b","fixture-issuer"),true)).header("If-Match","\"0\"")).andExpect(status().isNotFound());
  http.perform(post(ROOT+"/superadmin/transfers/"+t.id()+":accept").with(request(person("director","ente:director","tenant-a","wrong"),true)).header("If-Match","\"0\"")).andExpect(status().isForbidden());
  http.perform(post(ROOT+"/superadmin/transfers/"+t.id()+":accept").with(request(successor(),false)).header("If-Match","\"0\"")).andExpect(status().isForbidden());
  http.perform(post(ROOT+"/superadmin/transfers/"+t.id()+":accept").with(request(person("ordinary","ente:staff"),true)).header("X-OUF-External-Role-Refs","ente:director").header("If-Match","\"0\"")).andExpect(status().isForbidden());
  http.perform(post(ROOT+"/superadmin/transfers/"+t.id()+":accept").with(request(successor(),true))).andExpect(status().isPreconditionRequired());
  http.perform(post(ROOT+"/superadmin/transfers").with(request(installer(),true)).header("If-Match","\"0\"").contentType(MediaType.APPLICATION_JSON).content("{\"targetRoleRef\":\"ente:other\",\"reason\":\"handover\",\"issuer\":\"forged\"}")).andExpect(status().isBadRequest());
  assertThat(authority.isSuperadmin(installer())).isTrue();
 }
 @Test void cancelledExpiredAndCompetingProposalsDoNotRemoveCurrentSuperadmin(){
  boot();var t=authority.propose(installer(),0,"ente:director","handover");
  assertThatThrownBy(()->authority.propose(installer(),0,"ente:other","second")).hasMessage("AUTH_TRANSFER_PENDING");
  authority.cancel(t.id(),0,installer());assertThatThrownBy(()->authority.accept(t.id(),1,successor())).hasMessage("AUTH_TRANSFER_NOT_PENDING");
  var next=authority.propose(installer(),0,"ente:director","retry");
  db.sql("update ouf_authorization.superadmin_transfer set expires_at=clock_timestamp()-interval '1 second' where transfer_id=:id").param("id",next.id()).update();
  assertThatThrownBy(()->authority.accept(next.id(),0,successor())).hasMessage("AUTH_TRANSFER_EXPIRED");assertThat(authority.isSuperadmin(installer())).isTrue();
 }
 @Test void ordinaryAdminCanChangeGrantsButCannotNeutralizeProtectedAuthority()throws Exception{
  boot();var cap=new CapabilityDescriptor("authorization.policy.admin","EXECUTE","authorization.policy.admin",Set.of(PrincipalContext.ActorType.HUMAN));
  admin.register("authorization",cap,actor(installer()));var now=Instant.now();
  var grant=new Grant("ordinary",cap.capabilityId(),"tenant-a","ordinary",null,null,now.minusSeconds(5),now.plusSeconds(3600));
  var policy=new PolicyBundle("roles",2,now,List.of(cap),List.of(grant));
  var d=admin.create(policy,actor(installer()));admin.publish(d.id(),0,actor(installer()));
  var ordinary=person("ordinary","ente:staff");
  http.perform(post(ROOT+"/superadmin/transfers").with(request(ordinary,true)).header("If-Match","\"0\"").contentType(MediaType.APPLICATION_JSON).content("{\"targetRoleRef\":\"ente:staff\",\"reason\":\"escalate\"}")).andExpect(status().isForbidden());
  var deny=new Grant("deny-superadmin",cap.capabilityId(),"tenant-a",null,null,null,now.minusSeconds(5),now.plusSeconds(3600),new GrantConstraints("DENY","ente:bootstrap",null,null,Map.of(),Set.of(),Set.of(),null,Set.of(),null));
  var hostile=new PolicyBundle("roles",3,now,List.of(cap),List.of(grant,deny));
  var h=admin.create(hostile,actor(ordinary));admin.publish(h.id(),0,actor(ordinary));
  // Ordinary bundle DENY cannot change the separate governance association.
  http.perform(get(ROOT+"/capabilities").with(request(installer(),false))).andExpect(status().isOk());
  var repair=admin.create(empty(4),actor(installer()));admin.publish(repair.id(),0,actor(installer()));
  assertThat(authority.isSuperadmin(installer())).isTrue();
  assertThatThrownBy(()->admin.create(empty(5),actor(ordinary))).hasMessage("AUTH_ADMIN_REQUIRED");
  assertThatThrownBy(()->db.sql("delete from ouf_authorization.superadmin_binding").update()).hasStackTraceContaining("cannot be deleted");
  assertThatThrownBy(()->db.sql("delete from ouf_authorization.superadmin_history").update()).hasStackTraceContaining("append-only");
 }
 @Test void ordinaryAdminCannotReadAnotherTenantsDraftOrChangeItsGrants(){
  boot();var d=admin.create(empty(2),actor(installer()));
  assertThatThrownBy(()->admin.get(d.id(),actor(person("other","ente:bootstrap","tenant-b","fixture-issuer")))).hasMessage("AUTH_DRAFT_NOT_FOUND");
  var cap=new CapabilityDescriptor("x","READ","x",Set.of(PrincipalContext.ActorType.HUMAN));admin.register("test",cap,actor(installer()));
  var now=Instant.now();var foreign=new PolicyBundle("roles",2,now,List.of(cap),List.of(new Grant("g","x","tenant-b","someone",null,null,now.minusSeconds(5),now.plusSeconds(50))));
  assertThatThrownBy(()->admin.create(foreign,actor(installer()))).hasMessage("AUTH_OTHER_TENANT_GRANTS_PROTECTED");
 }
 @Test void concurrentAcceptanceHasOneWinner()throws Exception{
  boot();var t=authority.propose(installer(),0,"ente:director","handover");
  var start=new java.util.concurrent.CountDownLatch(1);
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
   var futures=new ArrayList<java.util.concurrent.Future<Boolean>>();
   for(int i=0;i<2;i++)futures.add(pool.submit(()->{start.await();try{authority.accept(t.id(),0,successor());return true;}catch(DomainFailure expected){return false;}}));
   start.countDown();int winners=0;for(var f:futures)if(f.get(10,java.util.concurrent.TimeUnit.SECONDS))winners++;
   assertThat(winners).isEqualTo(1);assertThat(authority.binding("tenant-a").orElseThrow().revision()).isEqualTo(1);
  }
 }
 @Test void rolledBackAcceptanceLeavesOldRoleAndProposalIntact(){
  boot();var t=authority.propose(installer(),0,"ente:director","handover");
  new TransactionTemplate(transactions).executeWithoutResult(tx->{authority.accept(t.id(),0,successor());tx.setRollbackOnly();});
  assertThat(authority.isSuperadmin(installer())).isTrue();assertThat(authority.read(t.id(),successor()).state()).isEqualTo("PENDING");
 }
 @Test void legacyAdoptionRequiresBothConfiguredRoleAndExistingPolicyPermission(){
  var now=Instant.now();var cap=new CapabilityDescriptor("authorization.policy.admin","EXECUTE","authorization.policy.admin",Set.of(PrincipalContext.ActorType.HUMAN));
  registry.publishAndActivate(new PolicyBundle("legacy",1,now,List.of(cap),List.of(new Grant("admin",cap.capabilityId(),"tenant-a","installer",null,null,now.minusSeconds(5),now.plusSeconds(3600)))),"migration");
  db.sql("update ouf_authorization.bootstrap_latch set completed=true,completed_at=transaction_timestamp(),completed_by='legacy'").update();
  assertThatThrownBy(()->authority.adopt(person("other","ente:bootstrap"))).hasMessage("AUTH_EXISTING_ADMIN_REQUIRED");
  assertThatThrownBy(()->authority.adopt(person("installer","ente:staff"))).hasMessage("AUTH_BOOTSTRAP_ADMIN_MISMATCH");
  assertThat(authority.adopt(installer()).roleRef()).isEqualTo("ente:bootstrap");
  assertThatThrownBy(()->authority.adopt(installer())).hasMessage("AUTH_SUPERADMIN_ALREADY_CONFIGURED");
  assertThat(admin.bootstrapOpen()).isFalse();
 }
 @Test void roleToPersonAndBackPreservesAuthorityUntilAcceptance() throws Exception {
  boot();var nominee=person("nominee",null);
  var result=http.perform(post(ROOT+"/superadmin/transfers").with(request(installer(),true)).header("If-Match","\"0\"").contentType(MediaType.APPLICATION_JSON)
   .content("{\"targetSubjectId\":\"nominee\",\"reason\":\"retire organization module\"}"))
   .andExpect(status().isOk()).andReturn();
  var id=UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
  assertThat(authority.isSuperadmin(installer())).isTrue();assertThat(authority.isSuperadmin(nominee)).isFalse();
  assertThatThrownBy(()->authority.accept(id,0,person("other",null))).hasMessage("AUTH_TARGET_SUBJECT_REQUIRED");
  assertThatThrownBy(()->authority.accept(id,0,person("nominee",null,"tenant-a","other-issuer"))).hasMessage("AUTH_TARGET_SUBJECT_REQUIRED");
  new TransactionTemplate(transactions).executeWithoutResult(tx->{authority.accept(id,0,nominee);tx.setRollbackOnly();});
  assertThat(authority.isSuperadmin(installer())).isTrue();
  var binding=authority.accept(id,0,nominee);
  assertThat(binding.subjectId()).isEqualTo("nominee");assertThat(binding.roleRef()).isNull();
  assertThat(authority.isSuperadmin(installer())).isFalse();assertThat(authority.isSuperadmin(nominee)).isTrue();
  var next=authority.propose(nominee,1,"ente:director",null,"restore role designation");
  authority.accept(next.id(),0,successor());
  assertThat(authority.isSuperadmin(nominee)).isFalse();assertThat(authority.isSuperadmin(successor())).isTrue();
  assertThat(authority.binding("tenant-a").orElseThrow().subjectId()).isNull();
 }
 @Test void ambiguousOrEmptySuccessorDoesNotCreateTransfer(){
  boot();
  assertThatThrownBy(()->authority.propose(installer(),0,"ente:director","nominee","both")).hasMessage("AUTH_TRANSFER_INVALID");
  assertThatThrownBy(()->authority.propose(installer(),0,null,null,"neither")).hasMessage("AUTH_TRANSFER_INVALID");
  assertThat(db.sql("select count(*) from ouf_authorization.superadmin_transfer").query(Long.class).single()).isZero();
 }
}
