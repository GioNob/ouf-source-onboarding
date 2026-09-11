package it.comune.trieste.ouf.onboarding.domain;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public final class CanonicalHash {
  private final ObjectMapper mapper;
  public CanonicalHash(ObjectMapper source){mapper=source.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);}
  public String of(Object value){
    try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}
    catch(Exception e){throw new IllegalStateException("canonical hash failure",e);}
  }
}
