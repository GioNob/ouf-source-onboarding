package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.JsonNode;
import it.comune.trieste.ouf.authorization.ServletAuthorization;
import it.comune.trieste.ouf.onboarding.authorization.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/trusted-human/v1/authorization")
public class AuthorizationReviewApi {
 private final AuthorizationReviewService review;
 private final AuthorizationAdminService admin;
 public AuthorizationReviewApi(AuthorizationReviewService review,AuthorizationAdminService admin){this.review=review;this.admin=admin;}
 private long revision(String etag){
  if(etag==null)throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"AUTH_ETAG_REQUIRED");
  if(!etag.matches("\"[0-9]+\""))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"AUTH_ETAG_INVALID");
  try{return Long.parseLong(etag.substring(1,etag.length()-1));}catch(NumberFormatException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"AUTH_ETAG_INVALID");}
 }
 private <T> ResponseEntity<T> response(T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
 @GetMapping("/access") public ResponseEntity<?> access(@RequestParam(required=false) String subjectId,@RequestParam(required=false) String externalRoleRef,@RequestParam(defaultValue="100") int limit,@RequestParam(required=false) String after,@RequestParam(required=false) String policyRef,HttpServletRequest request){
  return response(review.grants(ServletAuthorization.principal(request).context(),subjectId,externalRoleRef,limit,after,policyRef));
 }
 @PostMapping("/policies/{id}:preview") public ResponseEntity<?> preview(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag,HttpServletRequest request){
  return response(review.preview(ServletAuthorization.principal(request).context(),id,revision(etag)));
 }
 @PostMapping("/policies/{id}:simulate") public ResponseEntity<?> simulate(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody JsonNode input,HttpServletRequest request){
  return response(review.simulate(ServletAuthorization.principal(request).context(),id,revision(etag),admin.parse(input,AuthorizationReviewService.Scenario.class)));
 }
}
