package it.comune.trieste.ouf.onboarding.domain;

import org.springframework.http.HttpStatus;

public class DomainFailure extends RuntimeException {
  private final HttpStatus status; private final String code;
  private final java.util.Map<String,Object> properties;
  public DomainFailure(HttpStatus status,String code,String message){this(status,code,message,java.util.Map.of());}
  public DomainFailure(HttpStatus status,String code,String message,java.util.Map<String,Object> properties){
    super(message);this.status=status;this.code=code;this.properties=java.util.Map.copyOf(properties);
  }
  public java.util.Map<String,Object> properties(){return properties;}
  public HttpStatus status(){return status;} public String code(){return code;}
}
