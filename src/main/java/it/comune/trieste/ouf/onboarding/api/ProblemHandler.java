package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ProblemHandler {
  @ExceptionHandler(DomainFailure.class) ResponseEntity<ProblemDetail> domain(DomainFailure e,HttpServletRequest request){ProblemDetail p=ProblemDetail.forStatusAndDetail(e.status(),e.getMessage());p.setType(URI.create("urn:ouf:onboarding:error:"+e.code().toLowerCase().replace('_','-')));p.setTitle(e.code());p.setProperty("code",e.code());p.setProperty("correlationId",correlation(request));return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);}
  @ExceptionHandler({IllegalArgumentException.class,MethodArgumentNotValidException.class}) ResponseEntity<ProblemDetail> bad(Exception e,HttpServletRequest request){ProblemDetail p=ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,e.getMessage());p.setTitle("ONB_BAD_REQUEST");p.setProperty("code","ONB_BAD_REQUEST");p.setProperty("correlationId",correlation(request));return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);}
  private static String correlation(HttpServletRequest r){String value=r.getHeader("X-Correlation-ID");return value==null?"unavailable":value;}
}
