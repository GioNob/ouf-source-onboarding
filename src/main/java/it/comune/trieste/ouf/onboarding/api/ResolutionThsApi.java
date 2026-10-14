package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Trusted browser surface; UDP remains the owner of every review decision. */
@RestController
@RequestMapping("/trusted-human/resolution")
@ConditionalOnProperty(name="ouf.resolution.ths.gateway-base-url")
public class ResolutionThsApi {
  private static final String OWNER_PATH="/api/udp/v1/governance/resolution/issues/package";
  private final OAuth2AuthorizedClientService clients;
  private final ObjectMapper json;
  private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
      .followRedirects(HttpClient.Redirect.NEVER).build();
  private final URI owner;
  public ResolutionThsApi(OAuth2AuthorizedClientService clients,ObjectMapper json,
      @Value("${ouf.resolution.ths.gateway-base-url}") String baseUrl){
    this.clients=clients;this.json=json;
    URI base=URI.create(baseUrl);
    if(base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null
        ||base.getFragment()!=null||!(base.getPath().isEmpty()||"/".equals(base.getPath()))
        ||!("https".equals(base.getScheme())
            ||("http".equals(base.getScheme())&&Set.of("localhost","127.0.0.1").contains(base.getHost()))))
      throw new IllegalArgumentException("THS_RESOLUTION_GATEWAY_INVALID");
    this.owner=URI.create(base.toString().replaceAll("/+$","")+OWNER_PATH);
  }
  @GetMapping("/") ResponseEntity<byte[]> page(HttpServletRequest request)throws IOException{
    token(request);return asset("resolution.html",MediaType.TEXT_HTML);
  }
  @GetMapping("/review.js") ResponseEntity<byte[]> script(HttpServletRequest request)throws IOException{
    token(request);return asset("resolution.js",MediaType.valueOf("text/javascript"));
  }
  @GetMapping("/review.css") ResponseEntity<byte[]> css(HttpServletRequest request)throws IOException{
    token(request);return asset("resolution.css",MediaType.valueOf("text/css"));
  }
  @GetMapping("/api/package") ResponseEntity<?> prepare(HttpServletRequest request)throws Exception{
    String access=token(request);
    var response=send(HttpRequest.newBuilder(owner).GET(),access);
    if(response.statusCode()!=200)return upstream(response);
    CsrfToken csrf=(CsrfToken)request.getAttribute(CsrfToken.class.getName());
    if(csrf==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"THS_CSRF_REQUIRED");
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
        "package",json.readTree(response.body()),"csrfHeader",csrf.getHeaderName(),
        "csrfToken",csrf.getToken()));
  }
  @PostMapping("/api/package/confirm") ResponseEntity<String> confirm(@RequestBody byte[] body,
      HttpServletRequest request)throws Exception{
    if(body.length>1048576)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
    String access=token(request);
    var builder=HttpRequest.newBuilder(URI.create(owner+"/confirm"))
        .header("Content-Type","application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    return upstream(send(builder,access));
  }
  private HttpResponse<String> send(HttpRequest.Builder builder,String access)throws Exception{
    HttpRequest request=builder.header("Authorization","Bearer "+access)
        .header("Accept","application/json").timeout(Duration.ofSeconds(15)).build();
    return http.send(request,HttpResponse.BodyHandlers.ofString());
  }
  private String token(HttpServletRequest request){
    if(!Boolean.TRUE.equals(request.getAttribute("ouf.permissionThsSession")))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"THS_SESSION_REQUIRED");
    if(!(SecurityContextHolder.getContext().getAuthentication() instanceof OAuth2AuthenticationToken authentication))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"THS_SESSION_REQUIRED");
    var authorized=clients.loadAuthorizedClient(authentication.getAuthorizedClientRegistrationId(),
        authentication.getName());
    if(authorized==null||!"ouf-ths".equals(authorized.getClientRegistration().getRegistrationId()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"THS_SESSION_REQUIRED");
    return authorized.getAccessToken().getTokenValue();
  }
  private static ResponseEntity<String> upstream(HttpResponse<String> response){
    int status=response.statusCode();
    if(status<200||status>=500||status>=300&&status<400)
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
        "THS_RESOLUTION_OWNER_UNAVAILABLE");
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
        .cacheControl(CacheControl.noStore()).body(response.body());
  }
  private static ResponseEntity<byte[]> asset(String name,MediaType type)throws IOException{
    return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options","nosniff").header("Referrer-Policy","no-referrer")
        .header("X-Frame-Options","DENY")
        .header("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        .body(new ClassPathResource("ths/"+name).getContentAsByteArray());
  }
}
