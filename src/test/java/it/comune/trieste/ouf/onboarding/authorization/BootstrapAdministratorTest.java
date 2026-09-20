package it.comune.trieste.ouf.onboarding.authorization;
import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import java.util.Set;
import org.junit.jupiter.api.Test;
class BootstrapAdministratorTest {
 private final BootstrapAdministrator bootstrap=new BootstrapAdministrator("issuer","ente:director","tenant");
 private PrincipalContext person(String issuer,String subject,String tenant,Set<String> roles,Set<String> scopes){
  return new PrincipalContext(subject,tenant,PrincipalContext.ActorType.HUMAN,null,"auth",issuer,"aud",scopes,new PrincipalContext.IdentityClaims(roles,"1",Set.of(),null));
 }
 @Test void organizationalRoleRatherThanNamedPersonDeterminesBootstrap(){
  for(String person:Set.of("installer","successor"))assertThatCode(()->bootstrap.requirePrincipal(person("issuer",person,"tenant",Set.of("ente:director"),Set.of("authorization.bootstrap")))).doesNotThrowAnyException();
 }
 @Test void wrongIssuerTenantOrMissingRoleFailsClosed(){
  for(var p:Set.of(person("other","p","tenant",Set.of("ente:director"),Set.of("authorization.bootstrap")),person("issuer","p","other",Set.of("ente:director"),Set.of("authorization.bootstrap")),person("issuer","p","tenant",Set.of("admin"),Set.of("authorization.bootstrap"))))assertThatThrownBy(()->bootstrap.requirePrincipal(p)).hasMessage("AUTH_BOOTSTRAP_ADMIN_MISMATCH");
 }
 @Test void missingConfigurationOrScopeIsRejected(){
  var p=person("issuer","p","tenant",Set.of("ente:director"),Set.of());
  assertThatThrownBy(()->bootstrap.requirePrincipal(p)).hasMessage("AUTH_BOOTSTRAP_SCOPE_REQUIRED");
  assertThatThrownBy(()->new BootstrapAdministrator("issuer","","tenant").requirePrincipal(p)).hasMessage("AUTH_BOOTSTRAP_ADMIN_NOT_CONFIGURED");
 }
 @Test void nominalBootstrapDoesNotRequireAnOrganizationalRole(){
  var nominal=new BootstrapAdministrator("issuer","","tenant","person-id");
  var p=person("issuer","person-id","tenant",Set.of(),Set.of("authorization.bootstrap"));
  assertThatCode(()->nominal.requirePrincipal(p)).doesNotThrowAnyException();
  for(var wrong:Set.of(person("other","person-id","tenant",Set.of(),p.scopes()),person("issuer","other","tenant",Set.of(),p.scopes()),person("issuer","person-id","other",Set.of(),p.scopes())))
   assertThatThrownBy(()->nominal.requirePrincipal(wrong)).hasMessage("AUTH_BOOTSTRAP_ADMIN_MISMATCH");
  assertThatThrownBy(()->new BootstrapAdministrator("issuer","ente:director","tenant","person-id").requirePrincipal(p)).hasMessage("AUTH_BOOTSTRAP_ADMIN_NOT_CONFIGURED");
  assertThatThrownBy(()->bootstrap.requirePrincipal(p)).hasMessage("AUTH_BOOTSTRAP_ADMIN_MISMATCH");
 }
}
