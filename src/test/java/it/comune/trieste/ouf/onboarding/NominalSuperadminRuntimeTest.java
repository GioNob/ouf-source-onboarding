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

@SpringBootTest(properties={"ouf.authorization.bootstrap.admin-issuer=fixture-issuer","ouf.authorization.bootstrap.superadmin-role=","ouf.authorization.bootstrap.superadmin-subject=installer","ouf.authorization.bootstrap.admin-tenant=tenant-a"})
@AutoConfigureMockMvc
class NominalSuperadminRuntimeTest {
 @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->System.getenv("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->System.getenv("OUF_ONB_DB_PASSWORD"));}
 @Autowired SuperadminAuthority authority;@Autowired AuthorizationAdminService admin;@Autowired AuthorizationPolicyRegistry registry;
 @Autowired JdbcClient db;@Autowired MockMvc http;@Autowired ObjectMapper json;@Autowired PlatformTransactionManager transactions;
 @MockBean AuthorizationRuntimeSynchronizer runtime;
 private static final String ROOT="/api/trusted-human/v1/authorization";
 private PrincipalContext person(String subject,String role){return person(subject,role,"tenant-a","fixture-issuer");}
 private PrincipalContext person(String subject,String role,String tenant,String issuer){return new PrincipalContext(subject,tenant,PrincipalContext.ActorType.HUMAN,null,"auth",issuer,"aud",Set.of("authorization.bootstrap","authorization.policy.admin"),new PrincipalContext.IdentityClaims(role==null?Set.of():Set.of(role),"1",Set.of(),Instant.now()));}
 private PrincipalContext installer(){return person("installer",null);}
 private PrincipalContext successor(){return person("director","ente:director");}
 private AuthorizationAdminService.Actor actor(PrincipalContext p){return new AuthorizationAdminService.Actor(p.subjectId(),p.tenantId(),"HUMAN","test:authority",UUID.randomUUID().toString(),p);}
 private RequestPostProcessor request(PrincipalContext p,boolean proof){return r->{TestAuthorization.bind(r,p.subjectId(),"HUMAN",Set.of("authorization.policy.admin"));r.setAttribute(ServletAuthorization.TRUSTED_PRINCIPAL,new TrustedPrincipal(p));r.setAttribute("ouf.statelessBearerWriteValidated",proof);return r;};}
 private PolicyBundle empty(long version){return new PolicyBundle("roles",version,Instant.now(),List.of(),List.of());}
 @BeforeEach void clean(){db.sql("truncate ouf_authorization.role_catalogue,ouf_authorization.superadmin_history,ouf_authorization.superadmin_transfer,ouf_authorization.superadmin_binding,ouf_authorization.admin_audit,ouf_authorization.policy_draft,ouf_authorization.capability_registration,ouf_authorization.authorization_decision_audit,ouf_authorization.active_policy_bundle,ouf_authorization.policy_bundle,ouf_authorization.bootstrap_latch cascade").update();db.sql("insert into ouf_authorization.bootstrap_latch(singleton_key,completed) values(true,false)").update();}
 private void boot(){var d=admin.create(empty(1),actor(installer()));admin.publish(d.id(),0,actor(installer()));}
 @Test void legacyAdoptionRequiresBothConfiguredRoleAndExistingPolicyPermission(){
  var now=Instant.now();var cap=new CapabilityDescriptor("authorization.policy.admin","EXECUTE","authorization.policy.admin",Set.of(PrincipalContext.ActorType.HUMAN));
  registry.publishAndActivate(new PolicyBundle("legacy",1,now,List.of(cap),List.of(new Grant("admin",cap.capabilityId(),"tenant-a","installer",null,null,now.minusSeconds(5),now.plusSeconds(3600)))),"migration");
  db.sql("update ouf_authorization.bootstrap_latch set completed=true,completed_at=transaction_timestamp(),completed_by='legacy'").update();
  assertThatThrownBy(()->authority.adopt(person("other","ente:bootstrap"))).hasMessage("AUTH_BOOTSTRAP_ADMIN_MISMATCH");
  assertThat(authority.adopt(installer()).subjectId()).isEqualTo("installer");
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
