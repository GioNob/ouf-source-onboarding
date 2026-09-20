package it.comune.trieste.ouf.onboarding.authorization;

import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Application permission groups, not an IAM user or organization directory. */
public final class OufRoleCatalogue {
 private OufRoleCatalogue() {}
 public static final String PREFIX="ouf-role:";
 public record Permission(String capabilityId,GrantConstraints constraints) {}
 public record Role(String roleId,String displayName,List<Permission> permissions) {
  public Role { permissions=permissions==null?List.of():List.copyOf(permissions); }
 }
 public record Assignment(String assignmentId,String roleId,String subjectId,String externalRoleRef,Instant validFrom,Instant validUntil) {}
 public record Snapshot(String issuer,List<Role> roles,List<Assignment> assignments) {
  public Snapshot { roles=roles==null?List.of():List.copyOf(roles);assignments=assignments==null?List.of():List.copyOf(assignments); }
 }
 private static void require(boolean valid,String code){if(!valid)throw new IllegalArgumentException(code);}
 private static boolean id(String value){return value!=null&&value.matches("[A-Za-z0-9_.-]{1,80}");}
 public static String tenantPrefix(String tenant){return PREFIX+digest(tenant).substring(0,16)+":";}
 private static String digest(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 public static List<Grant> compile(Snapshot snapshot,String tenant,String trustedIssuer,List<CapabilityDescriptor> capabilities){
  require(snapshot!=null&&trustedIssuer!=null&&!trustedIssuer.isBlank()&&trustedIssuer.equals(snapshot.issuer()),"AUTH_ROLE_ISSUER_MISMATCH");
  require(tenant!=null&&!tenant.isBlank(),"AUTH_ROLE_TENANT_REQUIRED");
  require(snapshot.roles().size()<=100&&snapshot.assignments().size()<=1000,"AUTH_ROLE_LIMIT");
  Map<String,Role> roles=new HashMap<>();Map<String,CapabilityDescriptor> descriptors=new HashMap<>();
  for(var c:capabilities)descriptors.put(c.capabilityId(),c);
  for(var r:snapshot.roles()){
   require(r!=null&&id(r.roleId())&&!"superadmin".equalsIgnoreCase(r.roleId())&&r.displayName()!=null&&!r.displayName().isBlank()&&r.displayName().length()<=160,"AUTH_ROLE_INVALID");
   require(roles.putIfAbsent(r.roleId(),r)==null,"AUTH_ROLE_DUPLICATE");
   require(r.permissions().size()<=100,"AUTH_ROLE_LIMIT");Set<String> seen=new HashSet<>();
   for(var p:r.permissions()){
    require(p!=null&&descriptors.containsKey(p.capabilityId())&&descriptors.get(p.capabilityId()).allowedActors().contains(PrincipalContext.ActorType.HUMAN),"AUTH_ROLE_CAPABILITY_INVALID");
    require(!p.capabilityId().startsWith("authorization.superadmin")&&!p.capabilityId().equals("authorization.bootstrap"),"AUTH_ROLE_PROTECTED_AUTHORITY");
    require(seen.add(p.capabilityId()),"AUTH_ROLE_PERMISSION_DUPLICATE");
    require(p.constraints()==null||p.constraints().externalRoleRef()==null,"AUTH_ROLE_SELECTOR_IN_PERMISSION");
   }
  }
  List<Grant> grants=new ArrayList<>();Set<String> assignments=new HashSet<>();
  for(var a:snapshot.assignments()){
   require(a!=null&&id(a.assignmentId())&&assignments.add(a.assignmentId())&&roles.containsKey(a.roleId()),"AUTH_ROLE_ASSIGNMENT_INVALID");
   require(BootstrapAdministrator.validDesignation(a.externalRoleRef(),a.subjectId()),"AUTH_ROLE_SELECTOR_INVALID");
   require(a.validFrom()!=null&&a.validUntil()!=null&&a.validUntil().isAfter(a.validFrom()),"AUTH_ROLE_VALIDITY_INVALID");
   for(var p:roles.get(a.roleId()).permissions()){
    var c=p.constraints();
    var constraints=new GrantConstraints(c==null?"ALLOW":c.effect(),a.externalRoleRef(),c==null?null:c.resourceType(),c==null?null:c.resourceId(),c==null?Map.of():c.resourceAttributes(),c==null?Set.of():c.allowedDataLabels(),c==null?Set.of():c.allowedDetailLevels(),c==null?null:c.requiredAcr(),c==null?Set.of():c.requiredAmr(),c==null?null:c.maxAuthenticationAgeSeconds());
    grants.add(new Grant(tenantPrefix(tenant)+digest(a.assignmentId()+"\n"+p.capabilityId()),p.capabilityId(),tenant,a.subjectId(),null,null,a.validFrom(),a.validUntil(),constraints));
    require(grants.size()<=200,"AUTH_ROLE_LIMIT");
   }
  }
  grants.sort(Comparator.comparing(Grant::grantId));return List.copyOf(grants);
 }
}
