package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataAccessException;
import org.springframework.web.client.RestClientException;

@RestControllerAdvice
public class ProblemHandler {
  @ExceptionHandler(DomainFailure.class) ResponseEntity<ProblemDetail> domain(DomainFailure e,HttpServletRequest request){ProblemDetail p=ProblemDetail.forStatusAndDetail(e.status(),e.getMessage());p.setType(URI.create("urn:ouf:onboarding:error:"+e.code().toLowerCase().replace('_','-')));p.setTitle(e.code());p.setProperty("code",e.code());p.setProperty("correlationId",correlation(request));return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);}
  @ExceptionHandler({IllegalArgumentException.class,MethodArgumentNotValidException.class}) ResponseEntity<ProblemDetail> bad(Exception e,HttpServletRequest request){ProblemDetail p=ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,e.getMessage());p.setTitle("ONB_BAD_REQUEST");p.setProperty("code","ONB_BAD_REQUEST");p.setProperty("correlationId",correlation(request));return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);}
  @ExceptionHandler(DataAccessException.class) ResponseEntity<ProblemDetail> database(DataAccessException e,HttpServletRequest request){return infrastructure("ONB_PERSISTENCE_FAILURE","The onboarding persistence operation failed",e,request);}
  @ExceptionHandler(RestClientException.class) ResponseEntity<ProblemDetail> downstream(RestClientException e,HttpServletRequest request){return infrastructure("ONB_DEPENDENCY_FAILURE","A required governed dependency failed",e,request);}
  private static ResponseEntity<ProblemDetail> infrastructure(String code,String detail,Exception ignored,HttpServletRequest request){ProblemDetail p=ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,detail);p.setTitle(code);p.setProperty("code",code);p.setProperty("correlationId",correlation(request));return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);}
  private static String correlation(HttpServletRequest r){return CorrelationFilter.get(r);}
}
