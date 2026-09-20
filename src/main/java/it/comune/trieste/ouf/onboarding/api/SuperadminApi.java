package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.authorization.ServletAuthorization;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationAdminService;
import it.comune.trieste.ouf.onboarding.authorization.SuperadminAuthority;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/trusted-human/v1/authorization")
public class SuperadminApi {
  private final SuperadminAuthority authority;
  private final AuthorizationAdminService policies;
  public SuperadminApi(SuperadminAuthority authority,AuthorizationAdminService policies){this.authority=authority;this.policies=policies;}
  private PrincipalContext principal(HttpServletRequest r,boolean write){
    if(write)TrustedWriteProof.require(r);
    var p=ServletAuthorization.principal(r).context();authority.requireHumanScope(p);return p;
  }
  private long revision(String etag){
    if(etag==null)throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"AUTH_ETAG_REQUIRED");
    if(!etag.matches("\"[0-9]+\""))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"AUTH_ETAG_INVALID");
    try{return Long.parseLong(etag.substring(1,etag.length()-1));}catch(NumberFormatException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"AUTH_ETAG_INVALID");}
  }
  private ResponseEntity<?> binding(SuperadminAuthority.Binding b){return ResponseEntity.ok().eTag(Long.toString(b.revision())).body(b);}
  private ResponseEntity<?> transfer(SuperadminAuthority.Transfer t){return ResponseEntity.ok().eTag(Long.toString(t.revision())).body(t);}
  @ExceptionHandler(SecurityException.class) ResponseEntity<?> denied(SecurityException e){
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code",e.getMessage()));
  }
  public record Proposal(String targetRoleRef,String reason){}
  @GetMapping("/superadmin") ResponseEntity<?> current(HttpServletRequest r){return binding(authority.current(principal(r,false)));}
  @PostMapping("/superadmin:adopt") ResponseEntity<?> adopt(HttpServletRequest r){return binding(authority.adopt(principal(r,true)));}
  @PostMapping("/superadmin/transfers") ResponseEntity<?> propose(@RequestHeader(value="If-Match",required=false)String etag,@RequestBody JsonNode raw,HttpServletRequest r){
    var p=principal(r,true);var body=policies.parse(raw,Proposal.class);
    return transfer(authority.propose(p,revision(etag),body.targetRoleRef(),body.reason()));
  }
  @GetMapping("/superadmin/transfers/{id}") ResponseEntity<?> read(@PathVariable UUID id,HttpServletRequest r){return transfer(authority.read(id,principal(r,false)));}
  @PostMapping("/superadmin/transfers/{id}:accept") ResponseEntity<?> accept(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false)String etag,HttpServletRequest r){return binding(authority.accept(id,revision(etag),principal(r,true)));}
  @PostMapping("/superadmin/transfers/{id}:cancel") ResponseEntity<?> cancel(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false)String etag,HttpServletRequest r){return transfer(authority.cancel(id,revision(etag),principal(r,true)));}
}
