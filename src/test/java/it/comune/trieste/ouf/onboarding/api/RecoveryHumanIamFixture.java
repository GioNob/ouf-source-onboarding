package it.comune.trieste.ouf.onboarding.api;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;

/** Disposable RSA key, real Nimbus signature/issuer/audience validation and actual IAM
 * converter/bridge. No mocked decoder; not a remote IdP or deployed HTTP endpoint. */
public final class RecoveryHumanIamFixture {
  public static final String ISSUER="https://iam.fixture.invalid/realm",AUDIENCE="ouf-recovery";
  private final KeyPair keys;private final NimbusJwtDecoder decoder;
  public final LocalAuthorization policy=new LocalAuthorization(Clock.systemUTC(),Duration.ofMinutes(5));
  private final StorageRecoveryHumanApproval adapter=new StorageRecoveryHumanApproval("lab-installation","platform-tenant",ISSUER,AUDIENCE,policy);
  public RecoveryHumanIamFixture()throws Exception{
    var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);keys=generator.generateKeyPair();
    decoder=NimbusJwtDecoder.withPublicKey((RSAPublicKey)keys.getPublic()).build();
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER),IamSecurityConfiguration.requiredAudienceValidator(AUDIENCE)));
    publish(1,true);
  }
  public void publish(long version,boolean allow)throws Exception{
    var c=StorageRecoveryHumanApproval.CAPABILITY;var now=Instant.now();
    var constraints=new GrantConstraints("ALLOW",null,"installation-storage-authority","lab-installation",Map.of("recoveryAction","RECONCILE_AUTHORITY"),Set.of(),Set.of("SECURITY_SENSITIVE"),"fixture-mfa",Set.of("mfa"),300L);
    TestAuthorization.install(policy,new PolicyBundle("human-recovery",version,now,List.of(c),allow?List.of(new Grant("human-only",c.capabilityId(),"platform-tenant","operator",null,null,now.minusSeconds(60),now.plusSeconds(600),constraints)):List.of()));
  }
  public StorageRecoveryHumanApproval.Approved approve(StorageRecoveryHumanApproval.Command command)throws Exception{return approve(command,Map.of(),true,false);}
  public StorageRecoveryHumanApproval.Approved approve(StorageRecoveryHumanApproval.Command command,Map<String,Object> overrides,boolean bridge,boolean badSignature)throws Exception{
    Instant now=Instant.now();var claims=new HashMap<String,Object>();
    claims.put("sub","operator");claims.put("iss",ISSUER);claims.put("aud",List.of(AUDIENCE));claims.put("iat",Date.from(now.minusSeconds(1)));claims.put("exp",Date.from(now.plusSeconds(120)));claims.put("auth_time",now.minusSeconds(1).getEpochSecond());
    claims.put("tenant_id","platform-tenant");claims.put("ouf_actor_type","HUMAN");claims.put("acr","fixture-mfa");claims.put("amr",List.of("mfa"));claims.put("scope",StorageRecoveryHumanApproval.CAPABILITY.requiredScope());claims.putAll(overrides);
    var builder=new JWTClaimsSet.Builder();claims.forEach(builder::claim);var signed=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),builder.build());
    PrivateKey signing=keys.getPrivate();if(badSignature){var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);signing=generator.generateKeyPair().getPrivate();}
    signed.sign(new RSASSASigner(signing));String bearer=signed.serialize();
    var jwt=decoder.decode(bearer);var auth=new IamSecurityConfiguration().trustedJwtAuthenticationConverter(AUDIENCE,"ouf_actor_type").convert(jwt);
    var http=new MockHttpServletRequest("POST","/fixture/reconcile");http.addHeader("Authorization","Bearer "+bearer);http.addHeader("X-OUF-Actor-Type","HUMAN");http.setAttribute("ouf.capabilities",Set.of(StorageRecoveryHumanApproval.CAPABILITY.capabilityId()));
    final StorageRecoveryHumanApproval.Approved[] approved={null};SecurityContextHolder.getContext().setAuthentication(auth);
    try{
      if(bridge)new IamSecurityConfiguration.TrustedBearerPrincipalBridgeFilter().doFilter(http,new MockHttpServletResponse(),(request,response)->approved[0]=adapter.approve((jakarta.servlet.http.HttpServletRequest)request,command));
      else approved[0]=adapter.approve(http,command);
      return approved[0];
    }finally{SecurityContextHolder.clearContext();}
  }
}
