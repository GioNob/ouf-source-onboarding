package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationAdminService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/trusted-human/v1/authorization")
public class AuthorizationAdminApi {
 private final AuthorizationAdminService service;public AuthorizationAdminApi(AuthorizationAdminService service){this.service=service;}
 private AuthorizationAdminService.Actor actor(HttpServletRequest r,boolean write){
  try{
   if(service.bootstrapOpen()){
    var p=ServletAuthorization.principal(r).context();
    if(p.actorType()!=PrincipalContext.ActorType.HUMAN)throw new SecurityException("HUMAN_REQUIRED");
    if(!p.scopes().contains("authorization.bootstrap"))throw new SecurityException("AUTH_BOOTSTRAP_SCOPE_REQUIRED");
    service.requireBootstrapPrincipal(p);
    if(write)TrustedWriteProof.require(r);
    return new AuthorizationAdminService.Actor(p.subjectId(),p.tenantId(),p.actorType().name(),"bootstrap:iam",UUID.randomUUID().toString(),p);
   }
   var principal=ServletAuthorization.principal(r).context();
   if(service.isSuperadmin(principal)){
    if(write)TrustedWriteProof.require(r);
    return new AuthorizationAdminService.Actor(principal.subjectId(),principal.tenantId(),principal.actorType().name(),"superadmin:protected",UUID.randomUUID().toString(),principal);
   }
   var c=ServletAuthorization.require(r,"authorization.policy.admin",true);
   if(write)TrustedWriteProof.require(r);
   return new AuthorizationAdminService.Actor(c.principal().subjectId(),c.principal().tenantId(),c.principal().actorType().name(),c.decisionRef(),UUID.randomUUID().toString(),c.principal());
  }catch(SecurityException e){throw new ResponseStatusException(HttpStatus.FORBIDDEN,e.getMessage());}
 }
 private long revision(String etag){if(etag==null||!etag.matches("\"[0-9]+\""))throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"AUTH_ETAG_REQUIRED");try{return Long.parseLong(etag.substring(1,etag.length()-1));}catch(NumberFormatException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"AUTH_ETAG_INVALID");}}
 private ResponseEntity<AuthorizationAdminService.Draft> response(AuthorizationAdminService.Draft d){return ResponseEntity.ok().eTag(Long.toString(d.revision())).body(d);}
 public record Registration(String ownerRef,CapabilityDescriptor descriptor){}
 @PostMapping("/capabilities") @ResponseStatus(HttpStatus.CREATED) void register(@RequestBody com.fasterxml.jackson.databind.JsonNode input,HttpServletRequest r){var a=actor(r,true);var body=service.parse(input,Registration.class);service.register(body.ownerRef(),body.descriptor(),a);}
 @GetMapping("/capabilities") Object capabilities(@RequestParam(defaultValue="100") int limit,@RequestParam(defaultValue="0") int offset,HttpServletRequest r){actor(r,false);return service.capabilities(limit,offset);}
 @PostMapping("/policies") ResponseEntity<?> create(@RequestBody com.fasterxml.jackson.databind.JsonNode p,HttpServletRequest r){var a=actor(r,true);return response(service.create(service.parse(p,PolicyBundle.class),a));}
 @GetMapping("/policies/active") ResponseEntity<?> active(HttpServletRequest r){var a=actor(r,false);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.activePolicy(a));}
 @GetMapping("/policies/{id}") ResponseEntity<?> get(@PathVariable UUID id,HttpServletRequest r){var a=actor(r,false);return response(service.get(id,a));}
 @PutMapping("/policies/{id}") ResponseEntity<?> replace(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody com.fasterxml.jackson.databind.JsonNode p,HttpServletRequest r){var a=actor(r,true);return response(service.replace(id,revision(etag),service.parse(p,PolicyBundle.class),a));}
 @DeleteMapping("/policies/{id}") ResponseEntity<?> abandon(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag,HttpServletRequest r){var a=actor(r,true);return response(service.abandon(id,revision(etag),a));}
 @PutMapping("/policies/{id}/grants/{grantId}") ResponseEntity<?> grant(@PathVariable UUID id,@PathVariable String grantId,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody com.fasterxml.jackson.databind.JsonNode grant,HttpServletRequest r){var a=actor(r,true);return response(service.grant(id,revision(etag),grantId,service.parse(grant,Grant.class),a));}
 @DeleteMapping("/policies/{id}/grants/{grantId}") ResponseEntity<?> revoke(@PathVariable UUID id,@PathVariable String grantId,@RequestHeader(value="If-Match",required=false) String etag,HttpServletRequest r){var a=actor(r,true);return response(service.grant(id,revision(etag),grantId,null,a));}
 @PostMapping("/policies/{id}:publish") ResponseEntity<?> publish(@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag,HttpServletRequest r){var a=actor(r,true);return response(service.publish(id,revision(etag),a));}
}
