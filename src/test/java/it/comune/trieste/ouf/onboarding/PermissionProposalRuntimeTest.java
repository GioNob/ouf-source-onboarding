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
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties={"ouf.authorization.ths.public-origin=https://ouf.test","ouf.authorization.bootstrap.admin-issuer=https://fixture/realms/ouf","ouf.authorization.bootstrap.superadmin-role=ente:bootstrap","ouf.authorization.bootstrap.admin-tenant=tenant-a","ouf.iam.enabled=true","ouf.iam.issuer=https://fixture/realms/ouf","ouf.iam.audience=gateway","ouf.authorization.ths.enabled=true","spring.security.oauth2.client.registration.ouf-ths.provider=fixture","spring.security.oauth2.client.registration.ouf-ths.client-id=ouf-ths","spring.security.oauth2.client.registration.ouf-ths.client-secret=test-only","spring.security.oauth2.client.registration.ouf-ths.authorization-grant-type=authorization_code","spring.security.oauth2.client.registration.ouf-ths.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}","spring.security.oauth2.client.registration.ouf-ths.scope=openid,authorization.policy.admin","spring.security.oauth2.client.provider.fixture.authorization-uri=https://fixture/authorize","spring.security.oauth2.client.provider.fixture.token-uri=https://fixture/token","spring.security.oauth2.client.provider.fixture.jwk-set-uri=https://fixture/jwks","spring.security.oauth2.client.provider.fixture.user-info-uri=https://fixture/userinfo","spring.security.oauth2.client.provider.fixture.user-name-attribute=sub"})
@AutoConfigureMockMvc
class PermissionProposalRuntimeTest {
 static final String KEY="ab".repeat(32), ISSUER="https://fixture/realms/ouf", API="/api/internal/v1/authorization/permissions/", THS="/trusted-human/authorization/api/proposals/";
 static final Path KEYFILE;
 static {try{KEYFILE=Files.createTempFile("ouf-owner-test-",".key");Files.writeString(KEYFILE,KEY);KEYFILE.toFile().deleteOnExit();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->System.getenv("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->System.getenv("OUF_ONB_DB_PASSWORD"));r.add("ouf.authorization.delegation.key-file",()->KEYFILE.toString());}
 @Autowired JdbcClient db;@Autowired ObjectMapper json;@Autowired MockMvc http;@Autowired PermissionProposalService proposals;@Autowired AuthorizationAdminService admin;@Autowired AuthorizationPolicyRegistry registry;@Autowired ClientRegistrationRepository registrations;@Autowired PlatformTransactionManager transactions;
 @Autowired ReleaseCapabilityRegistration release;
 static final String DISCOVERY="ouf.semantic.discovery";
 static final String SEARCH="ouf.semantic.search";
 static final CapabilityDescriptor BATCH_SEARCH=new CapabilityDescriptor(
     SEARCH+".batch","READ",SEARCH,Set.of(PrincipalContext.ActorType.HUMAN));
 static final List<CapabilityDescriptor> DISCOVERY_DESCRIPTORS=List.of(
     new CapabilityDescriptor(DISCOVERY,"COMMAND",DISCOVERY,Set.of(PrincipalContext.ActorType.HUMAN)),
     new CapabilityDescriptor(DISCOVERY+".adopt","COMMAND",DISCOVERY,Set.of(PrincipalContext.ActorType.HUMAN)),
     new CapabilityDescriptor(DISCOVERY+".candidates","READ",DISCOVERY,Set.of(PrincipalContext.ActorType.HUMAN)),
     new CapabilityDescriptor(DISCOVERY+".status","READ",DISCOVERY,Set.of(PrincipalContext.ActorType.HUMAN)));
 @MockitoBean JwtDecoder decoder;@MockitoBean OAuth2AuthorizedClientService clients;@MockitoBean AuthorizationRuntimeSynchronizer runtime;
 @MockitoBean(name="thsRefreshClient") OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshClient;
 @Autowired org.springframework.security.web.authentication.AuthenticationSuccessHandler thsLoginSuccess;
 PrincipalContext root,proposer;Instant now;List<CapabilityDescriptor> descriptors;ClientRegistration registration;
 AuthorizationAdminService.Actor actor(PrincipalContext p){return new AuthorizationAdminService.Actor(p.subjectId(),p.tenantId(),"HUMAN","fixture:admin",UUID.randomUUID().toString(),p);}
 PrincipalContext person(String subject,String tenant,Set<String> roles,Set<String> scopes){return new PrincipalContext(subject,tenant,PrincipalContext.ActorType.HUMAN,null,"1",ISSUER,"gateway",scopes,new PrincipalContext.IdentityClaims(roles,"1",Set.of(),Instant.now()));}
 @BeforeEach void setup(){
  db.sql("truncate ouf_authorization.role_catalogue,ouf_authorization.permission_proposal,ouf_authorization.superadmin_history,ouf_authorization.superadmin_transfer,ouf_authorization.superadmin_binding,ouf_authorization.admin_audit,ouf_authorization.policy_draft,ouf_authorization.capability_registration,ouf_authorization.authorization_decision_audit,ouf_authorization.active_policy_bundle,ouf_authorization.policy_bundle,ouf_authorization.bootstrap_latch cascade").update();db.sql("insert into ouf_authorization.bootstrap_latch(singleton_key,completed) values(true,false)").update();
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
 @Test void capabilityViewEnumeratesTheActiveHumanDescriptorSet()throws Exception{
  byte[] body=json.writeValueAsBytes(Map.of("Arguments",Map.of("view","CAPABILITIES")));
  http.perform(post(API+"read").header("X-OUF-Authorization-Receipt",receipt("read",PermissionProposalService.READ,body,now.plusSeconds(30).getEpochSecond()))
      .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
      .andExpect(jsonPath("$.length()").value(descriptors.size()))
      .andExpect(jsonPath("$[0].capabilityId").value("authorization.permissions.propose"));
  assertThat(proposals.humanCapabilities(proposer)).extracting(CapabilityDescriptor::capabilityId)
      .containsExactlyElementsOf(descriptors.stream().map(CapabilityDescriptor::capabilityId).sorted().toList());
 }
 String receipt(String mode,String cap,byte[] body,long expiry)throws Exception{
  var map=new LinkedHashMap<String,Object>();map.put("v",1);map.put("purpose","authorization-proposal-owner");map.put("method","POST");map.put("path",API+mode);map.put("bodyHash",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));map.put("capability",cap);map.put("iat",now.getEpochSecond());map.put("exp",expiry);map.put("issuer",ISSUER);map.put("audience","gateway");map.put("workload","ouf-mcp-server");map.put("subject","giovanni");map.put("tenant","tenant-a");map.put("acr","1");map.put("roles","");map.put("scope",String.join(" ",proposer.scopes()));map.put("idempotencyKey","tool-attempt");
  String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(map));Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.US_ASCII),"HmacSHA256"));return payload+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("ouf-authorization-owner-v1."+payload).getBytes(StandardCharsets.US_ASCII)));
 }
 private void expiredClient(){
  var access=new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,"fixture-expired",now.minusSeconds(300),now.minusSeconds(120));
  var refresh=new OAuth2RefreshToken("fixture-refresh",now.minusSeconds(300));
  when(clients.loadAuthorizedClient(eq("ouf-ths"),anyString())).thenReturn(new OAuth2AuthorizedClient(registration,"admin",access,refresh));
 }
 @Test void expiredAccessIsRenewedPersistedAndValidatedBeforeServingThePage()throws Exception{
  expiredClient();
  when(refreshClient.getTokenResponse(any())).thenReturn(OAuth2AccessTokenResponse.withToken("human-session-only")
      .tokenType(OAuth2AccessToken.TokenType.BEARER).expiresIn(300).refreshToken("fixture-rotated").build());
  http.perform(get("/trusted-human/authorization/").with(oauth2Login().clientRegistration(registration)).accept(MediaType.TEXT_HTML))
      .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
      .andExpect(result->assertThat(result.getResponse().getContentAsString()).doesNotContain("human-session-only","fixture-refresh","fixture-rotated"));
  verify(decoder).decode("human-session-only");verify(decoder,never()).decode("fixture-expired");
  verify(clients).saveAuthorizedClient(argThat(c->c.getAccessToken().getTokenValue().equals("human-session-only")
      && c.getRefreshToken().getTokenValue().equals("fixture-rotated")),any());
 }
 @Test void failedRefreshSavesOnlyThePageIncludingItsChallengeForLogin()throws Exception{
  expiredClient();
  when(refreshClient.getTokenResponse(any())).thenThrow(new OAuth2AuthorizationException(new OAuth2Error("invalid_grant")));
  String page="/trusted-human/semantic/?challenge="+UUID.randomUUID();
  var request=http.perform(get(page).with(oauth2Login().clientRegistration(registration)).accept(MediaType.TEXT_HTML))
      .andExpect(redirectedUrl("/oauth2/authorization/ouf-ths")).andReturn().getRequest();
  var session=request.getSession(false);
  http.perform(get("/trusted-human/semantic/review.js").session((org.springframework.mock.web.MockHttpSession)session))
      .andExpect(status().isUnauthorized());
  var afterLogin=new org.springframework.mock.web.MockHttpServletResponse();
  thsLoginSuccess.onAuthenticationSuccess(request,afterLogin,null);
  assertThat(afterLogin.getRedirectedUrl()).isEqualTo(page);
  var nextLogin=new org.springframework.mock.web.MockHttpServletResponse();
  thsLoginSuccess.onAuthenticationSuccess(request,nextLogin,null);
  assertThat(nextLogin.getRedirectedUrl()).isEqualTo("/trusted-human/onboarding/");
  verify(decoder,never()).decode("fixture-expired");
 }
  @Test void onb55d03RenewalLoginReturnsToLinkAndExpiredWritesAreNeverRedirected()throws Exception{
    String page = "/trusted-human/analysis-renewal/Primo_link_rinnovo-001";
    var result = http.perform(get(page).accept(MediaType.TEXT_HTML))
        .andExpect(status().is3xxRedirection()).andReturn();
    var afterLogin = new org.springframework.mock.web.MockHttpServletResponse();
    thsLoginSuccess.onAuthenticationSuccess(result.getRequest(), afterLogin, null);
    assertThat(afterLogin.getRedirectedUrl()).isEqualTo(page);
    http.perform(get(page).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("THS_SESSION_EXPIRED"));
    http.perform(post(page + "/decision").header("X-CSRF-TOKEN", "stale").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("THS_SESSION_EXPIRED"));
  }
 @Test void expiredSessionOnApiReadOrWriteIsJsonEvenWithStaleCsrf()throws Exception{
  expiredClient();
  when(refreshClient.getTokenResponse(any())).thenThrow(new OAuth2AuthorizationException(new OAuth2Error("invalid_grant")));
  String api="/trusted-human/semantic/api/"+UUID.randomUUID();
  http.perform(get(api).with(oauth2Login().clientRegistration(registration)).accept(MediaType.TEXT_HTML))
      .andExpect(status().isUnauthorized()).andExpect(content().json("{\"code\":\"THS_SESSION_EXPIRED\"}"));
  http.perform(post(api+"/decision").header("X-CSRF-TOKEN","stale").accept(MediaType.TEXT_HTML))
      .andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
      .andExpect(content().json("{\"code\":\"THS_SESSION_EXPIRED\"}"));
 }
 @Test void authenticatedNonHumanSeesItalianDenialWithoutAnotherLogin()throws Exception{
  when(decoder.decode("human-session-only")).thenReturn(Jwt.withTokenValue("human-session-only")
      .header("alg","RS256").issuer(ISSUER).subject("fixture-service").audience(List.of("gateway"))
      .claim("tenant_id","tenant-a").claim("ouf_actor_type","SERVICE").claim("client_id","fixture-service").claim("acr","1").build());
  http.perform(get("/trusted-human/authorization/").with(oauth2Login().clientRegistration(registration)).accept(MediaType.TEXT_HTML))
      .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Location"))
      .andExpect(result -> assertThat(result.getResponse().getContentAsString()).contains("Accesso negato","403")
          .doesNotContain("human-session-only","fixture-service","Whitelabel"));
 }
 @Test void workflowHumanActionsRequireSessionCsrfBeforeReachingAnOwner()throws Exception{
  for(String flow:List.of("onboarding","semantic"))
   http.perform(post("/trusted-human/"+flow+"/api/"+UUID.randomUUID()+"/confirm")
       .with(oauth2Login().clientRegistration(registration)).contentType(MediaType.APPLICATION_JSON).content("{}"))
       .andExpect(status().isForbidden());
 }
 @Test void confidentialThsLoginUsesS256AndKeepsVerifierForTokenExchange()throws Exception{
  assertThat(registration.getClientSecret()).isNotBlank();
  assertThat(registration.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
  var response=http.perform(get("/oauth2/authorization/ouf-ths")).andExpect(status().is3xxRedirection()).andReturn();
  String location=response.getResponse().getRedirectedUrl();
  var query=org.springframework.web.util.UriComponentsBuilder.fromUriString(location).build().getQueryParams();
  assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
  assertThat(query.getFirst("state")).isNotBlank();
  assertThat(query).doesNotContainKey("code_verifier").doesNotContainKey("client_secret");
  var callback=new org.springframework.mock.web.MockHttpServletRequest();
  callback.setSession(response.getRequest().getSession(false));
  callback.setParameter("state",java.net.URLDecoder.decode(query.getFirst("state"),StandardCharsets.UTF_8));
  var saved=new org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository().loadAuthorizationRequest(callback);
  assertThat(saved).isNotNull();
  String verifier=saved.getAttribute("code_verifier");
  assertThat(verifier).matches("[A-Za-z0-9._~-]{43,128}");
  String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
  assertThat(query.getFirst("code_challenge")).isEqualTo(challenge);
  assertThat(location).doesNotContain(verifier).doesNotContain(registration.getClientSecret());
  var authorizationResponse=org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse.success("fixture-code")
    .redirectUri(saved.getRedirectUri()).state(saved.getState()).build();
  var exchange=new org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange(saved,authorizationResponse);
  var grant=new org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest(registration,exchange);
  var form=new org.springframework.security.oauth2.client.endpoint.DefaultOAuth2TokenRequestParametersConverter<org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest>().convert(grant);
  assertThat(form).isNotNull();
  assertThat(form.get("code_verifier")).isEqualTo(List.of(verifier));
  var second=http.perform(get("/oauth2/authorization/ouf-ths")).andExpect(status().is3xxRedirection()).andReturn();
  var nextQuery=org.springframework.web.util.UriComponentsBuilder.fromUriString(second.getResponse().getRedirectedUrl()).build().getQueryParams();
  assertThat(nextQuery.getFirst("code_challenge")).isNotEqualTo(challenge);
 }
 @Test void delegatedHttpProposalThenSessionThsConfirmationPublishesExactlyOnce()throws Exception{
  byte[] body=json.writeValueAsBytes(Map.of("Arguments",change()));String proof=receipt("propose",PermissionProposalService.PROPOSE,body,now.plusSeconds(30).getEpochSecond());
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
  byte[] body=json.writeValueAsBytes(Map.of("Arguments",change()));var proof=receipt("propose",PermissionProposalService.PROPOSE,body,now.plusSeconds(30).getEpochSecond());
  http.perform(post(API+"propose").contentType(MediaType.APPLICATION_JSON).content(body).header("X-OUF-Gateway-Verified","true")).andExpect(status().isForbidden());
  http.perform(post(API+"propose").header("X-OUF-Authorization-Receipt",proof).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
  http.perform(post(API+"read").header("X-OUF-Authorization-Receipt",proof).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
  http.perform(post(API+"propose").header("X-OUF-Authorization-Receipt",receipt("propose",PermissionProposalService.PROPOSE,body,now.minusSeconds(1).getEpochSecond())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
  http.perform(post(API+"confirm").header("X-OUF-Authorization-Receipt",proof).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
  http.perform(post(THS+UUID.randomUUID()+"/confirm").with(csrf()).header("X-OUF-Authorization-Receipt",proof).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("THS_SESSION_EXPIRED"));
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
 @Test void gatewayLuaReceiptIsAcceptedByJvmOwner() throws Exception {
  String repo=System.getenv("OUF_GATEWAY_PAIRWISE_ROOT");
  org.junit.jupiter.api.Assumptions.assumeTrue(repo!=null,"Gateway pairwise fixture runs in CI");
  var builder=new ProcessBuilder("python3",repo+"/tests/permission_receipt_fixture.py");builder.environment().put("PYTHONPATH",repo);builder.redirectError(ProcessBuilder.Redirect.INHERIT);
  var process=builder.start();try(var input=process.getOutputStream()){input.write(json.writeValueAsBytes(Map.of("issuer",ISSUER,"change",change())));}
  var output=process.getInputStream().readAllBytes();assertThat(process.waitFor()).isZero();var fixture=json.readTree(output);
  http.perform(post(API+"propose").header("X-OUF-Authorization-Receipt",fixture.get("receipt").asText()).contentType(MediaType.APPLICATION_JSON).content(fixture.get("body").asText())).andExpect(status().isOk()).andExpect(jsonPath("$.approvalPath").value(org.hamcrest.Matchers.startsWith("https://ouf.test/trusted-human/authorization/")));
  assertThat(registry.active().orElseThrow().version()).isEqualTo(1);
 }
 @Test void expiredProposalCannotPublish() {
  var original=proposals.propose(proposer,change(),"expiry");UUID expired=UUID.randomUUID();
  db.sql("insert into ouf_authorization.permission_proposal select (jsonb_populate_record(null::ouf_authorization.permission_proposal,to_jsonb(p)||jsonb_build_object('proposal_id',cast(:id as text),'idempotency_key','expired-copy','expires_at',clock_timestamp()-interval '1 second'))).* from ouf_authorization.permission_proposal p where proposal_id=:old").param("id",expired).param("old",original.proposalId()).update();
  var card=proposals.card(root,expired);assertThat(card.state()).isEqualTo("EXPIRED");
  assertThatThrownBy(()->proposals.decide(root,expired,0,new PermissionProposalService.Confirmation(card.proposedHash()),true)).hasMessage("AUTH_PROPOSAL_EXPIRED");assertThat(registry.active().orElseThrow().version()).isEqualTo(1);
 }


 OufRoleCatalogue.Snapshot catalogue(boolean assigned){
  var role=new OufRoleCatalogue.Role("operator","Operatore",List.of(new OufRoleCatalogue.Permission("ouf.system.status",null)));
  return new OufRoleCatalogue.Snapshot(ISSUER,List.of(role),assigned?List.of(new OufRoleCatalogue.Assignment("giovanni","operator","giovanni",null,now.minusSeconds(5),now.plusSeconds(3600)),new OufRoleCatalogue.Assignment("it-staff","operator",null,"ente:it",now.minusSeconds(5),now.plusSeconds(3600))):List.of());
 }
 PermissionProposalService.Change adminRole(List<String> ids){
  var permissions=ids.stream().map(id->new OufRoleCatalogue.Permission(id,null)).toList();
  var role=new OufRoleCatalogue.Role("admin","Amministratore",permissions);
  var assignment=new OufRoleCatalogue.Assignment("admin-person","admin","giovanni",null,now.minusSeconds(5),now.plusSeconds(3600));
  return new PermissionProposalService.Change("REPLACE_ROLES",null,null,"Aggiornare i permessi del ruolo admin",
      new OufRoleCatalogue.Snapshot(ISSUER,List.of(role),List.of(assignment)));
 }
 PermissionProposalService.Change discoveryRole(){
  return adminRole(DISCOVERY_DESCRIPTORS.stream().map(CapabilityDescriptor::capabilityId).toList());
 }
 @Test void releaseRegistersOnlyNewDescriptorsAndSecondStartupIsReadOnly()throws Exception{
  for(String manifest:List.of("chatbot-source-review-capabilities.json","r4a-semantic-human-capabilities.json")){
   try(var input=new org.springframework.core.io.ClassPathResource("catalogue/"+manifest).getInputStream()){
    for(var entry:json.readTree(input)){
     var descriptor=json.treeToValue(entry.get("descriptor"),CapabilityDescriptor.class);
     if(!DISCOVERY_DESCRIPTORS.contains(descriptor)&&!BATCH_SEARCH.capabilityId().equals(descriptor.capabilityId()))
      admin.register(entry.get("ownerRef").asText(),descriptor,actor(root));
    }
   }
  }
  var active=registry.load("permissions",1);
  release.run(null);
  assertThat(db.sql("""
      select capability_id from ouf_authorization.capability_registration
      where registered_by='release:catalogue/r4a-semantic-human-capabilities.json'
        and owner_ref='semantic' and registered_at is not null order by capability_id
      """).query(String.class).list()).containsExactly(DISCOVERY,DISCOVERY+".adopt",DISCOVERY+".candidates",DISCOVERY+".status",BATCH_SEARCH.capabilityId());
  var readOnly=new TransactionTemplate(transactions);
  readOnly.setReadOnly(true);
  readOnly.executeWithoutResult(tx->release.run(null));
  assertThat(registry.active().orElseThrow().version()).isEqualTo(1);
  assertThat(registry.load("permissions",1)).isEqualTo(active);
 }
 @Test @Transactional void releaseCollisionPreservesExistingDescriptorAndRollsBackWholeRegistration(){
  var other=new CapabilityDescriptor(DISCOVERY,"READ",DISCOVERY,Set.of(PrincipalContext.ActorType.HUMAN));
  admin.register("semantic",other,actor(root));
  // Roll back the release independently of the pre-existing registration in this test transaction.
  var attempt=new TransactionTemplate(transactions);
  attempt.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
  assertThatThrownBy(()->attempt.executeWithoutResult(tx->release.run(null))).hasMessage("AUTH_RELEASE_CAPABILITY_COLLISION "+DISCOVERY);
  assertThat(db.sql("select descriptor->>'operation' from ouf_authorization.capability_registration where capability_id=:id")
      .param("id",DISCOVERY).query(String.class).single()).isEqualTo("READ");
  assertThat(db.sql("select count(*) from ouf_authorization.capability_registration where registered_by like 'release:%'")
      .query(Long.class).single()).isZero();
 }
 @Test void releaseDescriptorsAndCompiledGrantsEnterActiveInOneThsPublication()throws Exception{
  verifyReleaseProposal(List.of("ouf.system.status"),DISCOVERY_DESCRIPTORS);
 }
 @Test void batchSearchEntersExistingAdminRoleOnlyAfterThsConfirmation()throws Exception{
  admin.register("semantic",new CapabilityDescriptor(SEARCH,"READ",SEARCH,Set.of(PrincipalContext.ActorType.HUMAN)),actor(root));
  verifyReleaseProposal(List.of(SEARCH),List.of(BATCH_SEARCH));
 }
 void verifyReleaseProposal(List<String> existingPermissions,List<CapabilityDescriptor> additions)throws Exception{
  var existingRole=adminRole(existingPermissions);
  var original=proposals.propose(proposer,existingRole,"existing-admin");
  var originalCard=proposals.card(root,original.proposalId());
  proposals.decide(root,original.proposalId(),original.revision(),new PermissionProposalService.Confirmation(originalCard.proposedHash()),true);
  var base=registry.load("permissions",2);
  var pointer=registry.active().orElseThrow();
  release.run(null);
  var ids=new ArrayList<>(existingPermissions);
  additions.forEach(c->ids.add(c.capabilityId()));
  var change=adminRole(ids);
  byte[] body=json.writeValueAsBytes(Map.of("Arguments",change));
  var response=http.perform(post(API+"propose")
      .header("X-OUF-Authorization-Receipt",receipt("propose",PermissionProposalService.PROPOSE,body,now.plusSeconds(30).getEpochSecond()))
      .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn();
  UUID id=UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("proposalId").asText());
  var proposed=json.readValue(db.sql("select proposed_policy::text from ouf_authorization.permission_proposal where proposal_id=:id")
      .param("id",id).query(String.class).single(),PolicyBundle.class);
  var expectedCapabilities=new ArrayList<>(base.capabilities());
  expectedCapabilities.addAll(additions);
  var expectedGrants=new ArrayList<>(base.grants());
  expectedGrants.removeIf(g->g.grantId().startsWith(OufRoleCatalogue.PREFIX));
  expectedGrants.addAll(OufRoleCatalogue.compile(change.roleCatalogue(),proposer.tenantId(),ISSUER,expectedCapabilities));
  assertThat(expectedGrants).hasSize(base.grants().size()+additions.size());
  assertThat(proposed.grants()).containsAll(base.grants());
  assertThat(proposed.capabilities()).containsExactlyElementsOf(expectedCapabilities);
  assertThat(proposed.grants()).containsExactlyElementsOf(expectedGrants);
  assertThat(registry.active().orElseThrow()).isEqualTo(pointer);
  assertThat(registry.load("permissions",2)).isEqualTo(base);
  assertThat(proposals.roleCatalogue(proposer)).isEqualTo(existingRole.roleCatalogue());
  var card=proposals.card(root,id);
  assertThat(card.addedCapabilities()).containsExactlyElementsOf(additions);
  var canonical=new CanonicalHash(json);
  assertThat(card.proposedHash()).isEqualTo(canonical.of(Map.of("policy",proposed,"roleCatalogue",change.roleCatalogue())));
  http.perform(post(THS+id+"/confirm").with(oauth2Login().clientRegistration(registration)).with(csrf())
      .header("If-Match","\"0\"").contentType(MediaType.APPLICATION_JSON)
      .content(json.writeValueAsString(new PermissionProposalService.Confirmation(card.proposedHash()))))
      .andExpect(status().isOk()).andExpect(jsonPath("$.finalPolicyRef").value("permissions:3"));
  assertThat(registry.active().orElseThrow().version()).isEqualTo(3);
  var published=registry.load("permissions",3);
  assertThat(published.capabilities()).isEqualTo(proposed.capabilities());
  assertThat(published.grants()).isEqualTo(proposed.grants());
  assertThat(proposals.roleCatalogue(proposer)).isEqualTo(change.roleCatalogue());
  assertThat(db.sql("select count(*) from ouf_authorization.policy_bundle").query(Long.class).single()).isEqualTo(3);
 }
 @Test void unknownProtectedAndServiceOnlyPermissionsCannotBeProposed(){
  assertThatThrownBy(()->proposals.propose(proposer,adminRole(List.of("unknown")),"unknown"))
      .hasMessage("AUTH_ROLE_CAPABILITY_INVALID");
  for(String id:List.of("authorization.bootstrap","authorization.superadmin.transfer")){
   admin.register("authorization",new CapabilityDescriptor(id,"COMMAND",id,Set.of(PrincipalContext.ActorType.HUMAN)),actor(root));
   assertThatThrownBy(()->proposals.propose(proposer,adminRole(List.of(id)),id)).hasMessage("AUTH_ROLE_PROTECTED_AUTHORITY");
  }
  admin.register("owner",new CapabilityDescriptor("workload","READ","workload",Set.of(PrincipalContext.ActorType.SERVICE)),actor(root));
  assertThatThrownBy(()->proposals.propose(proposer,adminRole(List.of("workload")),"workload")).hasMessage("AUTH_ROLE_CAPABILITY_INVALID");
 }
 @Test void roleCatalogueIsPublishedWithItsGrantsOnlyAfterThsConfirmation()throws Exception{
  var change=new PermissionProposalService.Change("REPLACE_ROLES",null,null,"Assegnare operatore a persona e ruolo IAM",catalogue(true));
  byte[] body=json.writeValueAsBytes(Map.of("Arguments",change));
  var response=http.perform(post(API+"propose").header("X-OUF-Authorization-Receipt",receipt("propose",PermissionProposalService.PROPOSE,body,now.plusSeconds(30).getEpochSecond())).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn();
  UUID id=UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("proposalId").asText());
  assertThat(proposals.roleCatalogue(proposer).assignments()).isEmpty();
  http.perform(get(THS+id).with(oauth2Login().clientRegistration(registration))).andExpect(status().isOk()).andExpect(jsonPath("$.card.afterRoles.assignments.length()").value(2));
  var card=proposals.card(root,id);var confirm=json.writeValueAsString(new PermissionProposalService.Confirmation(card.proposedHash()));
  http.perform(post(THS+id+"/confirm").with(oauth2Login().clientRegistration(registration)).with(csrf()).header("If-Match","\"0\"").contentType(MediaType.APPLICATION_JSON).content(confirm)).andExpect(status().isOk());
  assertThat(proposals.roleCatalogue(proposer)).isEqualTo(catalogue(true));
  var active=registry.load("permissions",2);assertThat(active.grants().stream().filter(g->g.grantId().startsWith(OufRoleCatalogue.PREFIX))).hasSize(2);
  var tampered=new PolicyBundle("permissions",3,now,active.capabilities(),active.grants().stream().filter(g->!g.grantId().startsWith(OufRoleCatalogue.PREFIX)).toList());
  assertThatThrownBy(()->admin.create(tampered,actor(root))).hasMessage("AUTH_ROLE_MANAGED_GRANTS_PROTECTED");
  var revoke=proposals.propose(proposer,new PermissionProposalService.Change("REPLACE_ROLES",null,null,"Revoca assegnazioni",catalogue(false)),"revoke-roles");
  var revCard=proposals.card(root,revoke.proposalId());proposals.decide(root,revoke.proposalId(),0,new PermissionProposalService.Confirmation(revCard.proposedHash()),true);
  assertThat(proposals.roleCatalogue(proposer).assignments()).isEmpty();assertThat(registry.load("permissions",3).grants()).noneMatch(g->g.grantId().startsWith(OufRoleCatalogue.PREFIX));
 }
 @Test void rolePublicationRollbackAndStaleProposalsCannotChangeCatalogue(){
  release.run(null);
  var change=discoveryRole();
  var proposal=proposals.propose(proposer,change,"roles-rollback");var card=proposals.card(root,proposal.proposalId());
  new TransactionTemplate(transactions).executeWithoutResult(tx->{proposals.decide(root,proposal.proposalId(),0,new PermissionProposalService.Confirmation(card.proposedHash()),true);tx.setRollbackOnly();});
  assertThat(proposals.roleCatalogue(proposer).assignments()).isEmpty();assertThat(registry.active().orElseThrow().version()).isEqualTo(1);
  var other=proposals.propose(proposer,change(),"independent");var otherCard=proposals.card(root,other.proposalId());proposals.decide(root,other.proposalId(),0,new PermissionProposalService.Confirmation(otherCard.proposedHash()),true);
  assertThatThrownBy(()->proposals.decide(root,proposal.proposalId(),0,new PermissionProposalService.Confirmation(card.proposedHash()),true)).hasMessage("AUTH_ACTIVE_CHANGED_NEW_PROPOSAL_REQUIRED");
  assertThat(proposals.roleCatalogue(proposer).assignments()).isEmpty();
  assertThat(registry.active().orElseThrow().version()).isEqualTo(2);
  assertThat(registry.load("permissions",2).capabilities()).isEqualTo(descriptors);
  assertThat(db.sql("select count(*) from ouf_authorization.policy_bundle").query(Long.class).single()).isEqualTo(2);
 }
}
