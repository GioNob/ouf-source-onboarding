package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import it.comune.trieste.ouf.onboarding.authorization.*;
import java.time.Instant;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties={"ouf.authorization.bootstrap.admin-issuer=https://fixture/realms/ouf","ouf.authorization.bootstrap.superadmin-role=ente:bootstrap","ouf.authorization.bootstrap.admin-tenant=tenant-a","ouf.iam.enabled=true","ouf.iam.issuer=https://fixture/realms/ouf","ouf.iam.audience=gateway","ouf.authorization.ths.enabled=true","spring.security.oauth2.client.registration.ouf-ths.provider=fixture","spring.security.oauth2.client.registration.ouf-ths.client-id=ouf-ths","spring.security.oauth2.client.registration.ouf-ths.client-secret=test-only","spring.security.oauth2.client.registration.ouf-ths.authorization-grant-type=authorization_code","spring.security.oauth2.client.registration.ouf-ths.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}","spring.security.oauth2.client.registration.ouf-ths.scope=openid,authorization.policy.admin","spring.security.oauth2.client.provider.fixture.authorization-uri=https://fixture/authorize","spring.security.oauth2.client.provider.fixture.token-uri=https://fixture/token","spring.security.oauth2.client.provider.fixture.jwk-set-uri=https://fixture/jwks","spring.security.oauth2.client.provider.fixture.user-info-uri=https://fixture/userinfo","spring.security.oauth2.client.provider.fixture.user-name-attribute=sub"})
@AutoConfigureMockMvc
class PermissionProposalRuntimeTest {
 static final String KEY="ab".repeat(32), ISSUER="https://fixture/realms/ouf", API="/api/internal/v1/authorization/permissions/", THS="/trusted-human/authorization/api/proposals/";
 static final Path KEYFILE;
 static {try{KEYFILE=Files.createTempFile("ouf-owner-test-",".key");Files.writeString(KEYFILE,KEY);KEYFILE.toFile().deleteOnExit();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->System.getenv("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->System.getenv("OUF_ONB_DB_PASSWORD"));r.add("ouf.authorization.delegation.key-file",()->KEYFILE.toString());}
 @Autowired JdbcClient db;@Autowired ObjectMapper json;@Autowired MockMvc http;@Autowired PermissionProposalService proposals;@Autowired AuthorizationAdminService admin;@Autowired AuthorizationPolicyRegistry registry;@Autowired ClientRegistrationRepository registrations;@Autowired PlatformTransactionManager transactions;
 @MockBean JwtDecoder decoder;@MockBean OAuth2AuthorizedClientService clients;@MockBean AuthorizationRuntimeSynchronizer runtime;
 PrincipalContext root,proposer;Instant now;List<CapabilityDescriptor> descriptors;ClientRegistration registration;
 AuthorizationAdminService.Actor actor(PrincipalContext p){return new AuthorizationAdminService.Actor(p.subjectId(),p.tenantId(),"HUMAN","fixture:admin",UUID.randomUUID().toString(),p);}
 PrincipalContext person(String subject,String tenant,Set<String> roles,Set<String> scopes){return new PrincipalContext(subject,tenant,PrincipalContext.ActorType.HUMAN,null,"1",ISSUER,"gateway",scopes,new PrincipalContext.IdentityClaims(roles,"1",Set.of(),Instant.now()));}
 @BeforeEach void setup(){
  db.sql("truncate ouf_authorization.permission_proposal,ouf_authorization.superadmin_history,ouf_authorization.superadmin_transfer,ouf_authorization.superadmin_binding,ouf_authorization.admin_audit,ouf_authorization.policy_draft,ouf_authorization.capability_registration,ouf_authorization.authorization_decision_audit,ouf_authorization.active_policy_bundle,ouf_authorization.policy_bundle,ouf_authorization.bootstrap_latch cascade").update();db.sql("insert into ouf_authorization.bootstrap_latch(singleton_key,completed) values(true,false)").update();
  now=Instant.now();root=person("admin","tenant-a",Set.of("ente:bootstrap"),Set.of("authorization.bootstrap","authorization.policy.admin"));proposer=person("giovanni","tenant-a",Set.of(),Set.of(PermissionProposalService.READ,PermissionProposalService.PROPOSE,PermissionProposalService.STATUS));
  descriptors=List.of(AuthorizationCapabilities.POLICY_ADMIN,new CapabilityDescriptor(PermissionProposalService.READ,"READ",PermissionProposalService.READ,Set.of(PrincipalContext.ActorType.HUMAN)),new CapabilityDescriptor(PermissionProposalService.PROPOSE,"COMMAND",PermissionProposalService.PROPOSE,Set.of(PrincipalContext.ActorType.HUMAN)),new CapabilityDescriptor(PermissionProposalService.STATUS,"READ",PermissionProposalService.STATUS,Set.of(PrincipalContext.ActorType.HUMAN)),new CapabilityDescriptor("ouf.system.status","READ","operations.status.read",Set.of(PrincipalContext.ActorType.HUMAN)));
  for(var c:descriptors)admin.register("authorization",c,actor(root));
  var grants=new ArrayList<Grant>();for(String cap:proposer.scopes())grants.add(new Grant(cap,cap,"tenant-a","giovanni",null,null,now.minusSeconds(60),now.plusSeconds(3600)));
  var d=admin.create(new PolicyBundle("permissions",1,now,descriptors,grants),actor(root));admin.publish(d.id(),0,actor(root));
  registration=registrations.findByRegistrationId("ouf-ths");
  when(clients.loadAuthorizedClient(eq("ouf-ths"),anyString())).thenReturn(new OAuth2AuthorizedClient(registration,"admin",new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,"human-session-only",now,now.plusSeconds(300))));
  when(decoder.decode("human-session-only")).thenReturn(Jwt.withTokenValue("human-session-only").header("alg","RS256").issuer(ISSUER).subject("admin").audience(List.of("gateway")).issuedAt(now).expiresAt(now.plusSeconds(300)).claim("tenant_id","tenant-a").claim("ouf_actor_type","HUMAN").claim("acr","1").claim("scope","authorization.policy.admin").claim("externalRoleRefs",List.of("ente:bootstrap")).build());
 }
 PermissionProposalService.Change change(){return new PermissionProposalService.Change("UPSERT","status-grant",new Grant("status-grant","ouf.system.status","tenant-a","giovanni",null,null,now.minusSeconds(5),now.plusSeconds(3600)),"Concedere lettura stato");}
 String receipt(String mode,String cap,byte[] body,long expiry)throws Exception{
  var map=new LinkedHashMap<String,Object>();map.put("v",1);map.put("purpose","authorization-proposal-owner");map.put("method","POST");map.put("path",API+mode);map.put("bodyHash",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));map.put("capability",cap);map.put("iat",now.getEpochSecond());map.put("exp",expiry);map.put("issuer",ISSUER);map.put("audience","gateway");map.put("workload","ouf-mcp-server");map.put("subject","giovanni");map.put("tenant","tenant-a");map.put("acr","1");map.put("roles","");map.put("scope",String.join(" ",proposer.scopes()));map.put("idempotencyKey","tool-attempt");
  String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(map));Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.US_ASCII),"HmacSHA256"));return payload+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("ouf-authorization-owner-v1."+payload).getBytes(StandardCharsets.US_ASCII)));
 }
 @Test void delegatedHttpProposalThenSessionThsConfirmationPublishesExactlyOnce()throws Exception{
  byte[] body=json.writeValueAsBytes(change());String proof=receipt("propose",PermissionProposalService.PROPOSE,body,now.plusSeconds(30).getEpochSecond());
  var response=http.perform(post(API+"propose").header("X-OUF-Authorization-Receipt",proof).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn();
  var result=json.readTree(response.getResponse().getContentAsString());UUID id=UUID.fromString(result.get("proposalId").asText());assertThat(registry.active().orElseThrow().version()).isEqualTo(1);
  http.perform(get(THS+id).with(oauth2Login().clientRegistration(registration))).andExpect(status().isOk()).andExpect(jsonPath("$.card.after.subjectId").value("giovanni")).andExpect(jsonPath("$.csrfToken").isNotEmpty());
  var card=proposals.card(root,id);var confirm=json.writeValueAsString(new PermissionProposalService.Confirmation(card.proposedHash()));
  http.perform(post(THS+id+"/confirm").with(oauth2Login().clientRegistration(registration)).header("If-Match","\"0\"").contentType(MediaType.APPLICATION_JSON).content(confirm)).andExpect(status().isForbidden());
  http.perform(post(THS+id+"/confirm").with(oauth2Login().clientRegistration(registration)).with(csrf()).header("If-Match","\"0\"").contentType(MediaType.APPLICATION_JSON).content(confirm)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PUBLISHED"));
  assertThat(registry.active().orElseThrow().version()).isEqualTo(2);assertThat(registry.load("permissions",2).grants()).anyMatch(g->g.grantId().equals("status-grant"));
  http.perform(post(THS+id+"/confirm").with(oauth2Login().clientRegistration(registration)).with(csrf()).header("If-Match","\"0\"").contentType(MediaType.APPLICATION_JSON).content(confirm)).andExpect(status().isPreconditionFailed());
  assertThat(proposals.status(proposer,id).finalPolicyRef()).isEqualTo("permissions:2");
 }
 @Test void receiptCannotBeForgedMovedOrReusedWithDifferentArguments()throws Exception{
  byte[] body=json.writeValueAsBytes(change());var proof=receipt("propose",PermissionProposalService.PROPOSE,body,now.plusSeconds(30).getEpochSecond());
  http.perform(post(API+"propose").contentType(MediaType.APPLICATION_JSON).content(body).header("X-OUF-Gateway-Verified","true")).andExpect(status().isForbidden());
  http.perform(post(API+"propose").header("X-OUF-Authorization-Receipt",proof).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
  http.perform(post(API+"read").header("X-OUF-Authorization-Receipt",proof).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
  http.perform(post(API+"propose").header("X-OUF-Authorization-Receipt",receipt("propose",PermissionProposalService.PROPOSE,body,now.minusSeconds(1).getEpochSecond())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
  http.perform(post(API+"confirm").header("X-OUF-Authorization-Receipt",proof).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
  http.perform(post(THS+UUID.randomUUID()+"/confirm").with(csrf()).header("X-OUF-Authorization-Receipt",proof).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().is3xxRedirection());
  assertThat(registry.active().orElseThrow().version()).isEqualTo(1);
 }
 @Test void idempotencyHashAndActivePinProtectTheProposal(){
  var r=proposals.propose(proposer,change(),"same");assertThat(proposals.propose(proposer,change(),"same").proposalId()).isEqualTo(r.proposalId());
  assertThatThrownBy(()->proposals.propose(proposer,new PermissionProposalService.Change("REVOKE",PermissionProposalService.READ,null,"different"),"same")).hasMessage("AUTH_IDEMPOTENCY_CONFLICT");
  var c=proposals.card(root,r.proposalId());assertThatThrownBy(()->proposals.decide(root,r.proposalId(),0,new PermissionProposalService.Confirmation("wrong"),true)).hasMessage("AUTH_PROPOSAL_HASH_MISMATCH");
  var active=registry.load("permissions",1);registry.publishAndActivate(new PolicyBundle("permissions",2,now,active.capabilities(),active.grants()),"fixture");
  assertThatThrownBy(()->proposals.decide(root,r.proposalId(),0,new PermissionProposalService.Confirmation(c.proposedHash()),true)).hasMessage("AUTH_ACTIVE_CHANGED_NEW_PROPOSAL_REQUIRED");
  assertThat(proposals.decide(root,r.proposalId(),0,new PermissionProposalService.Confirmation(c.proposedHash()),false).state()).isEqualTo("REJECTED");
 }
 @Test void tenantAndAdminChecksRemainIndependentOfProposeGrant(){
  var r=proposals.propose(proposer,change(),"tenant");
  assertThatThrownBy(()->proposals.card(proposer,r.proposalId())).hasMessage("AUTH_ADMIN_REQUIRED");
  assertThatThrownBy(()->proposals.access(person("giovanni","tenant-b",Set.of(),proposer.scopes()),"giovanni",null,10,null,null)).hasMessage("AUTH_PROPOSAL_DENIED");
  var bad=change().grant();var foreign=new Grant(bad.grantId(),bad.capabilityId(),"tenant-b",bad.subjectId(),null,null,bad.validFrom(),bad.validUntil());
  assertThatThrownBy(()->proposals.propose(proposer,new PermissionProposalService.Change("UPSERT",bad.grantId(),foreign,"foreign"),"foreign")).hasMessage("AUTH_PROPOSAL_GRANT_INVALID");
  assertThatThrownBy(()->db.sql("delete from ouf_authorization.permission_proposal").update()).hasStackTraceContaining("append-only");
 }
 @Test void rollbackAndConcurrentConfirmationNeverPartiallyPublish()throws Exception{
  var r=proposals.propose(proposer,change(),"concurrency");var c=proposals.card(root,r.proposalId());var confirmation=new PermissionProposalService.Confirmation(c.proposedHash());
  new TransactionTemplate(transactions).executeWithoutResult(tx->{proposals.decide(root,r.proposalId(),0,confirmation,true);tx.setRollbackOnly();});
  assertThat(registry.active().orElseThrow().version()).isEqualTo(1);assertThat(proposals.status(proposer,r.proposalId()).state()).isEqualTo("PENDING");
  var start=new java.util.concurrent.CountDownLatch(1);try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
   var futures=new ArrayList<java.util.concurrent.Future<Boolean>>();for(int i=0;i<2;i++)futures.add(pool.submit(()->{start.await();try{proposals.decide(root,r.proposalId(),0,confirmation,true);return true;}catch(it.comune.trieste.ouf.onboarding.domain.DomainFailure e){return false;}}));start.countDown();int wins=0;for(var f:futures)if(f.get(10,java.util.concurrent.TimeUnit.SECONDS))wins++;assertThat(wins).isEqualTo(1);
  }
  assertThat(registry.active().orElseThrow().version()).isEqualTo(2);
 }
 @Test void revokedAdministratorCannotConfirm() {
  var r=proposals.propose(proposer,change(),"revocation");var c=proposals.card(root,r.proposalId());
  var former=person("admin","tenant-a",Set.of(),Set.of("authorization.policy.admin"));
  assertThatThrownBy(()->proposals.decide(former,r.proposalId(),0,new PermissionProposalService.Confirmation(c.proposedHash()),true)).hasMessage("AUTH_ADMIN_REQUIRED");
 }
}
