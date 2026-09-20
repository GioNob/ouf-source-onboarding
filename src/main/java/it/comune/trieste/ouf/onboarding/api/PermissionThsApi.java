package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.onboarding.authorization.PermissionProposalService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/trusted-human/authorization")
public class PermissionThsApi {
 private final PermissionProposalService service;
 public PermissionThsApi(PermissionProposalService service){this.service=service;}
 private PrincipalContext principal(HttpServletRequest r){if(!Boolean.TRUE.equals(r.getAttribute("ouf.permissionThsSession")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"THS_SESSION_REQUIRED");return ServletAuthorization.principal(r).context();}
 @GetMapping("/") ResponseEntity<byte[]> page(HttpServletRequest r)throws IOException{principal(r);return asset("permissions.html",MediaType.TEXT_HTML);}
 @GetMapping("/permissions.js") ResponseEntity<byte[]> script(HttpServletRequest r)throws IOException{principal(r);return asset("permissions.js",MediaType.valueOf("text/javascript"));}
 @GetMapping("/permissions.css") ResponseEntity<byte[]> css(HttpServletRequest r)throws IOException{principal(r);return asset("permissions.css",MediaType.valueOf("text/css"));}
 private ResponseEntity<byte[]> asset(String name,MediaType type)throws IOException{return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").header("Referrer-Policy","no-referrer").header("X-Frame-Options","DENY").header("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'").body(new ClassPathResource("ths/"+name).getContentAsByteArray());}
 @GetMapping("/api/proposals/{id}") ResponseEntity<?> card(@PathVariable UUID id,HttpServletRequest r){var p=principal(r);var csrf=(CsrfToken)r.getAttribute(CsrfToken.class.getName());if(csrf==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"THS_CSRF_REQUIRED");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("card",service.card(p,id),"csrfToken",csrf.getToken(),"csrfHeader",csrf.getHeaderName()));}
 @PostMapping("/api/proposals/{id}/{action}") ResponseEntity<?> decide(@PathVariable UUID id,@PathVariable String action,@RequestHeader(value="If-Match",required=false)String etag,@RequestBody PermissionProposalService.Confirmation body,HttpServletRequest r){
  var p=principal(r);if(!Set.of("confirm","reject").contains(action))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  if(etag==null)throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED);long revision;try{if(!etag.matches("\"[0-9]+\""))throw new NumberFormatException();revision=Long.parseLong(etag.substring(1,etag.length()-1));}catch(NumberFormatException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST);}
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.decide(p,id,revision,body,action.equals("confirm")));
 }
}
