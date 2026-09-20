package it.comune.trieste.ouf.onboarding;
import it.comune.trieste.ouf.authorization.*;
import java.util.Set;
final class SuperadminFixtures {
 static TrustedPrincipal principal(String subject,String actor,Set<String> scopes){
  var p=TestAuthorization.principal(subject,actor,scopes).context();
  return new TrustedPrincipal(new PrincipalContext(p.subjectId(),p.tenantId(),p.actorType(),p.servicePrincipalId(),p.authenticationContextRef(),p.issuer(),p.audience(),p.scopes(),new PrincipalContext.IdentityClaims(Set.of("ente:bootstrap"),"1",Set.of(),null)));
 }
}
