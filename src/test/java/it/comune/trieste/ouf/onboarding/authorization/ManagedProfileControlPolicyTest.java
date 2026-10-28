package it.comune.trieste.ouf.onboarding.authorization;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ManagedProfileControlPolicyTest {
  final Instant now=Instant.parse("2026-10-27T00:00:00Z");
  final String profile="ouf.managed-source.file.profile", release="ouf.onboarding.quarantine.release";
  OufRoleCatalogue.Role role()throws Exception{
    return new ObjectMapper().readValue(new ClassPathResource("authorization/managed-profile-recovery-role-v1.json").getInputStream(),OufRoleCatalogue.Role.class);
  }
  List<CapabilityDescriptor> descriptors()throws Exception{return role().permissions().stream().map(p->new CapabilityDescriptor(p.capabilityId(),"EXECUTE",p.capabilityId(),Set.of(PrincipalContext.ActorType.HUMAN))).toList();}
  PrincipalContext person(String subject,String tenant,Set<String> scopes){return new PrincipalContext(subject,tenant,PrincipalContext.ActorType.HUMAN,null,"1","issuer","aud",scopes);}
  ResourceContext job(String id){return new ResourceContext("managed-profile-job",id,"tenant-a",null,Map.of("module","ONBOARDING","jobRef",id,"assetRef","asset-a","detailLevel","TENANT_OPERATIONAL"));}
  @Test void deployedLabelConstrainedRoleDoesNotAuthorizeAnUnlabelledCapabilityContext()throws Exception{
    var c=new GrantConstraints("ALLOW",null,null,null,Map.of(),Set.of("OPEN","RESTRICTED"),Set.of("TENANT_OPERATIONAL","PUBLIC_OPERATIONAL"),null,Set.of(),null);
    var g=new Grant("data-role",profile,"tenant-a","alice",null,null,now.minusSeconds(1),now.plusSeconds(3600),c);
    var p=new PolicyBundle("b",1,now,descriptors(),List.of(g));
    assertThat(AuthorizationPolicy.evaluate(p,person("alice","tenant-a",Set.of(profile)),new ResourceContext("capability",null,"tenant-a",null,Map.of()),profile,"EXECUTE",now).decisionCode()).isEqualTo("NO_APPLICABLE_GRANT");
  }
  @Test void productionControlRoleNeedsExplicitAssignmentAndDoesNotGrantGenericOrDataAccess()throws Exception{
    var r=role();var caps=descriptors();
    assertThat(OufRoleCatalogue.compile(new OufRoleCatalogue.Snapshot("issuer",List.of(r),List.of()),"tenant-a","issuer",caps)).isEmpty();
    var assignment=new OufRoleCatalogue.Assignment("explicit-human",r.roleId(),"alice",null,now.minusSeconds(1),now.plusSeconds(3600));
    var grants=OufRoleCatalogue.compile(new OufRoleCatalogue.Snapshot("issuer",List.of(r),List.of(assignment)),"tenant-a","issuer",caps);
    assertThat(grants).hasSize(3);
    var b=new PolicyBundle("b",1,now,caps,grants);
    for(var cap:caps){
      var person=person("alice","tenant-a",Set.of(cap.requiredScope()));
      assertThat(AuthorizationPolicy.evaluate(b,person,job("job-a"),cap.capabilityId(),"EXECUTE",now).allowed()).isTrue();
      assertThat(AuthorizationPolicy.evaluate(b,person,new ResourceContext("capability",null,"tenant-a",null,Map.of()),cap.capabilityId(),"EXECUTE",now).allowed()).isFalse();
      assertThat(AuthorizationPolicy.evaluate(b,person,new ResourceContext("materialization-job","job-a","tenant-a",null,job("job-a").attributes()),cap.capabilityId(),"EXECUTE",now).allowed()).isFalse();
      assertThat(AuthorizationPolicy.evaluate(b,person,new ResourceContext("managed-profile-job","job-a","tenant-a",null,Map.of("module","ONBOARDING","detailLevel","TENANT_OPERATIONAL","dataAccessLabel","SENSITIVE")),cap.capabilityId(),"EXECUTE",now).allowed()).isFalse();
      assertThat(AuthorizationPolicy.evaluate(b,person("bob","tenant-a",person.scopes()),job("job-a"),cap.capabilityId(),"EXECUTE",now).allowed()).isFalse();
      assertThat(AuthorizationPolicy.evaluate(b,person("alice","other",person.scopes()),job("job-a"),cap.capabilityId(),"EXECUTE",now).allowed()).isFalse();
      assertThat(AuthorizationPolicy.evaluate(b,person("alice","tenant-a",Set.of()),job("job-a"),cap.capabilityId(),"EXECUTE",now).decisionCode()).isEqualTo("SCOPE_MISSING");
      assertThat(AuthorizationPolicy.evaluate(b,person,job("job-a"),cap.capabilityId(),"EXECUTE",now.plusSeconds(3600)).allowed()).isFalse();
    }
  }
  @Test void jobScopedPermissionCannotAuthorizeAnotherJobAndDenyStillWins()throws Exception{
    var c=new GrantConstraints("ALLOW",null,"managed-profile-job","job-a",Map.of("module","ONBOARDING","assetRef","asset-a","jobRef","job-a"),Set.of(),Set.of("TENANT_OPERATIONAL"),null,Set.of(),null);
    var g=new Grant("one-job",profile,"tenant-a","alice",null,null,now.minusSeconds(1),now.plusSeconds(3600),c);
    var b=new PolicyBundle("b",1,now,descriptors(),List.of(g));var p=person("alice","tenant-a",Set.of(profile));
    assertThat(AuthorizationPolicy.evaluate(b,p,job("job-a"),profile,"EXECUTE",now).allowed()).isTrue();
    assertThat(AuthorizationPolicy.evaluate(b,p,job("job-b"),profile,"EXECUTE",now).allowed()).isFalse();
    var deny=new Grant("deny",profile,"tenant-a","alice",null,null,now.minusSeconds(1),now.plusSeconds(3600),new GrantConstraints("DENY",null,"managed-profile-job",null,Map.of(),Set.of(),Set.of(),null,Set.of(),null));
    assertThat(AuthorizationPolicy.evaluate(new PolicyBundle("b",1,now,descriptors(),List.of(g,deny)),p,job("job-a"),profile,"EXECUTE",now).decisionCode()).isEqualTo("EXPLICIT_DENY");
  }
  @Test void missingNativeCapabilityDescriptorBlocksRoleCompilationRatherThanInventingIt()throws Exception{
    var r=role();var assignment=new OufRoleCatalogue.Assignment("explicit-human",r.roleId(),"alice",null,now.minusSeconds(1),now.plusSeconds(3600));
    assertThatThrownBy(()->OufRoleCatalogue.compile(new OufRoleCatalogue.Snapshot("issuer",List.of(r),List.of(assignment)),"tenant-a","issuer",descriptors().stream().filter(c->!c.capabilityId().equals(release)).toList())).hasMessage("AUTH_ROLE_CAPABILITY_INVALID");
  }
}
