package it.comune.trieste.ouf.onboarding.authorization;
import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
class OufRoleCatalogueTest {
 final Instant now=Instant.parse("2026-09-20T00:00:00Z");
 final List<CapabilityDescriptor> caps=List.of(new CapabilityDescriptor("status","READ","status.read",Set.of(PrincipalContext.ActorType.HUMAN)));
 final OufRoleCatalogue.Role role=new OufRoleCatalogue.Role("operator","Operatore",List.of(new OufRoleCatalogue.Permission("status",null)));
 OufRoleCatalogue.Assignment assignment(String id,String subject,String external){return new OufRoleCatalogue.Assignment(id,"operator",subject,external,now.minusSeconds(60),now.plusSeconds(600));}
 PrincipalContext person(String subject,String tenant,Set<String> roles){return new PrincipalContext(subject,tenant,PrincipalContext.ActorType.HUMAN,null,"1","issuer","aud",Set.of("status.read"),new PrincipalContext.IdentityClaims(roles,"1",Set.of(),now));}
 boolean allowed(PolicyBundle b,PrincipalContext p){return AuthorizationPolicy.evaluate(b,p,new ResourceContext("capability",null,p.tenantId(),null,Map.of()),"status","READ",now).allowed();}
 @Test void sameOufRoleCanBeAssignedToAnIamRoleAndToAnIndividual(){
  var grants=OufRoleCatalogue.compile(new OufRoleCatalogue.Snapshot("issuer",List.of(role),List.of(assignment("staff",null,"ente:it"),assignment("person","giovanni",null))),"tenant","issuer",caps);
  var b=new PolicyBundle("roles",1,now,caps,grants);
  assertThat(allowed(b,person("other","tenant",Set.of("ente:it")))).isTrue();
  assertThat(allowed(b,person("giovanni","tenant",Set.of()))).isTrue();
  assertThat(allowed(b,person("other","tenant",Set.of()))).isFalse();
  assertThat(allowed(b,person("giovanni","other-tenant",Set.of()))).isFalse();
  assertThat(grants).allMatch(g->g.grantId().length()<=128);
 }
 @Test void revocationAndExpiryDoNotLeaveGeneratedPermissions(){
  var s=new OufRoleCatalogue.Snapshot("issuer",List.of(role),List.of());
  assertThat(OufRoleCatalogue.compile(s,"tenant","issuer",caps)).isEmpty();
  var expired=new OufRoleCatalogue.Assignment("expired","operator","giovanni",null,now.minusSeconds(600),now);
  var b=new PolicyBundle("roles",1,now,caps,OufRoleCatalogue.compile(new OufRoleCatalogue.Snapshot("issuer",List.of(role),List.of(expired)),"tenant","issuer",caps));
  assertThat(allowed(b,person("giovanni","tenant",Set.of()))).isFalse();
 }
 @Test void issuerAmbiguityDanglingRolesAndSuperadminAreRejected(){
  var s=new OufRoleCatalogue.Snapshot("issuer",List.of(role),List.of(assignment("both","person","ente:it")));
  assertThatThrownBy(()->OufRoleCatalogue.compile(s,"tenant","issuer",caps)).hasMessage("AUTH_ROLE_SELECTOR_INVALID");
  assertThatThrownBy(()->OufRoleCatalogue.compile(s,"tenant","other",caps)).hasMessage("AUTH_ROLE_ISSUER_MISMATCH");
  assertThatThrownBy(()->OufRoleCatalogue.compile(new OufRoleCatalogue.Snapshot("issuer",List.of(),List.of(assignment("dangling","person",null))),"tenant","issuer",caps)).hasMessage("AUTH_ROLE_ASSIGNMENT_INVALID");
  assertThatThrownBy(()->OufRoleCatalogue.compile(new OufRoleCatalogue.Snapshot("issuer",List.of(new OufRoleCatalogue.Role("superadmin","Superadmin",List.of())),List.of()),"tenant","issuer",caps)).hasMessage("AUTH_ROLE_INVALID");
 }
 @Test void denyAndResourceConstraintsArePreserved(){
  var c=new GrantConstraints("DENY",null,"capability",null,Map.of(),Set.of(),Set.of(),null,Set.of(),null);
  var deny=new OufRoleCatalogue.Role("operator","Operatore",List.of(new OufRoleCatalogue.Permission("status",c)));
  var generated=OufRoleCatalogue.compile(new OufRoleCatalogue.Snapshot("issuer",List.of(deny),List.of(assignment("person","giovanni",null))),"tenant","issuer",caps);
  var grants=new ArrayList<>(generated);grants.add(new Grant("direct","status","tenant","giovanni",null,null,now.minusSeconds(60),now.plusSeconds(600)));
  assertThat(allowed(new PolicyBundle("roles",1,now,caps,grants),person("giovanni","tenant",Set.of()))).isFalse();
 }
}
