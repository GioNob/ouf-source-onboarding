package it.comune.trieste.ouf.onboarding.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** The browser surface resides in Onboarding; decisions remain UDP-owned through Gateway. */
@RestController
public class R2fReviewSurface {
  @GetMapping("/trusted-human/r2f") ResponseEntity<byte[]> page(HttpServletRequest request)throws IOException{
    it.comune.trieste.ouf.authorization.ServletAuthorization.require(request,"resolution.issue.read",true);
    return asset("r2f.html",MediaType.TEXT_HTML);
  }
  @GetMapping("/trusted-human/r2f/style.css") ResponseEntity<byte[]> css()throws IOException{return asset("r2f.css",MediaType.valueOf("text/css"));}
  @GetMapping("/trusted-human/r2f/review.js") ResponseEntity<byte[]> js()throws IOException{return asset("r2f.js",MediaType.valueOf("text/javascript"));}
  private ResponseEntity<byte[]> asset(String name,MediaType type)throws IOException{
    return ResponseEntity.ok().contentType(type).header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("Referrer-Policy","no-referrer").header("X-Frame-Options","DENY").header("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'").body(new ClassPathResource("ths/"+name).getContentAsByteArray());
  }
}
