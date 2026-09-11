package it.comune.trieste.ouf.onboarding.domain;

import org.springframework.http.HttpStatus;

public final class DomainFailure extends RuntimeException {
  private final HttpStatus status; private final String code;
  public DomainFailure(HttpStatus status,String code,String message){super(message);this.status=status;this.code=code;}
  public HttpStatus status(){return status;} public String code(){return code;}
}
