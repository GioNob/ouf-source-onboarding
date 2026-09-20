package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.authorization.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/internal/v1/authorization/permissions")
public class PermissionProposalApi {
 private final PermissionDelegationVerifier verifier;private final PermissionProposalService service;private final AuthorizationAdminService admin;private final ObjectMapper json;
 public PermissionProposalApi(PermissionDelegationVerifier verifier,PermissionProposalService service,AuthorizationAdminService admin,ObjectMapper json){this.verifier=verifier;this.service=service;this.admin=admin;this.json=json;}
 public record Query(String subjectId,String externalRoleRef,Integer limit,String after,String policyRef){}
 public record Status(UUID proposalId){}
 private <T>T body(byte[] raw,Class<T> type){try{return admin.parse(json.readTree(raw),type);}catch(java.io.IOException e){throw new IllegalArgumentException("invalid body",e);}}
 private PermissionDelegationVerifier.Delegated caller(HttpServletRequest r,byte[] raw,String cap){return verifier.verify(r.getHeader("X-OUF-Authorization-Receipt"),r.getRequestURI(),cap,raw);}
 @PostMapping("/read") Object read(@RequestBody byte[] raw,HttpServletRequest r){var p=caller(r,raw,PermissionProposalService.READ).principal();var q=body(raw,Query.class);return service.access(p,q.subjectId(),q.externalRoleRef(),q.limit()==null?100:q.limit(),q.after(),q.policyRef());}
 @PostMapping("/propose") Object propose(@RequestBody byte[] raw,HttpServletRequest r){var d=caller(r,raw,PermissionProposalService.PROPOSE);return service.propose(d.principal(),body(raw,PermissionProposalService.Change.class),d.idempotencyKey());}
 @PostMapping("/status") Object status(@RequestBody byte[] raw,HttpServletRequest r){var p=caller(r,raw,PermissionProposalService.STATUS).principal();var q=body(raw,Status.class);if(q.proposalId()==null)throw new IllegalArgumentException("proposalId required");return service.status(p,q.proposalId());}
 @ExceptionHandler(SecurityException.class) ResponseEntity<?> denied(){return ResponseEntity.status(403).body(java.util.Map.of("code","AUTH_OWNER_RECEIPT_INVALID"));}
}
