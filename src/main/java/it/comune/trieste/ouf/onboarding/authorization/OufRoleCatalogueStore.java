package it.comune.trieste.ouf.onboarding.authorization;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class OufRoleCatalogueStore {
 private final JdbcClient db;private final ObjectMapper json;private final String issuer;
 public OufRoleCatalogueStore(JdbcClient db,ObjectMapper json,@Value("${ouf.iam.issuer:}")String issuer){this.db=db;this.json=json;this.issuer=issuer;}
 private OufRoleCatalogue.Snapshot decode(String raw){try{return json.readValue(raw,OufRoleCatalogue.Snapshot.class);}catch(Exception e){throw new IllegalStateException("AUTH_ROLE_STORED_INVALID",e);}}
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("AUTH_ROLE_INVALID",e);}}
 public OufRoleCatalogue.Snapshot current(String tenant){return db.sql("select payload::text from ouf_authorization.role_catalogue where tenant_id=:tenant").param("tenant",tenant).query(String.class).optional().map(this::decode).orElse(new OufRoleCatalogue.Snapshot(issuer,List.of(),List.of()));}
 public List<Grant> compile(OufRoleCatalogue.Snapshot s,PrincipalContext p,List<CapabilityDescriptor> descriptors){
  if(!issuer.equals(p.issuer())||p.actorType()!=PrincipalContext.ActorType.HUMAN)throw new SecurityException("AUTH_ROLE_ISSUER_MISMATCH");
  return OufRoleCatalogue.compile(s,p.tenantId(),issuer,descriptors);
 }
 /** Must run inside the same locked publication transaction; failures roll back metadata and policy. */
 void stage(OufRoleCatalogue.Snapshot s,PrincipalContext p,String policyRef,List<CapabilityDescriptor> descriptors){
  compile(s,p,descriptors);
  db.sql("insert into ouf_authorization.role_catalogue(tenant_id,payload,policy_ref,changed_by) values(:tenant,cast(:payload as jsonb),:ref,:subject) on conflict(tenant_id) do update set payload=excluded.payload,policy_ref=excluded.policy_ref,changed_by=excluded.changed_by,changed_at=transaction_timestamp()")
   .param("tenant",p.tenantId()).param("payload",encode(s)).param("ref",policyRef).param("subject",p.subjectId()).update();
 }
 /** Generic grant APIs cannot alter grants owned by the role catalogue. */
 public void verifyManagedGrants(PolicyBundle policy){
  List<Grant> expected=new ArrayList<>();
  for(var row:db.sql("select tenant_id,payload::text as payload from ouf_authorization.role_catalogue").query().listOfRows())
   expected.addAll(OufRoleCatalogue.compile(decode((String)row.get("payload")),(String)row.get("tenant_id"),issuer,policy.capabilities()));
  var actual=policy.grants().stream().filter(g->g.grantId().startsWith(OufRoleCatalogue.PREFIX)).toList();
  if(!new HashSet<>(expected).equals(new HashSet<>(actual)))throw new IllegalArgumentException("AUTH_ROLE_MANAGED_GRANTS_PROTECTED");
 }
}
