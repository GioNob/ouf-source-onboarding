package it.comune.trieste.ouf.onboarding.application;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ProtectedLogRedactionService {
  private static final Set<String> ALWAYS_REMOVE=Set.of("password","passwd","secret","secretref","authorization","bearertoken","accesstoken","refreshtoken","cookie","setcookie","credential","credentialref");
  private static final Set<String> PERSONAL=Set.of("actorsubject","subject","subjectref","firstname","lastname","name","surname","fiscalcode","codicefiscale","ip","ipaddress","organizationalunit");
  public Map<String,Object> redact(Map<String,Object> input,boolean allowPersonal){return object(input,allowPersonal);}
  private Map<String,Object> object(Map<?,?> input,boolean allowPersonal){Map<String,Object> out=new LinkedHashMap<>();input.forEach((key,value)->{String name=String.valueOf(key);String normalized=name.replaceAll("[^A-Za-z0-9]","").toLowerCase(Locale.ROOT);if(ALWAYS_REMOVE.contains(normalized))return;if(!allowPersonal&&PERSONAL.contains(normalized)){out.put(name,"[REDACTED]");return;}out.put(name,value(value,allowPersonal));});return out;}
  private Object value(Object value,boolean allowPersonal){if(value instanceof Map<?,?> map)return object(map,allowPersonal);if(value instanceof Collection<?> list)return list.stream().map(v->value(v,allowPersonal)).toList();return value;}
}
