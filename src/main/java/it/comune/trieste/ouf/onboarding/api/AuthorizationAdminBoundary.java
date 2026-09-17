package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.authorization.ServletAuthorization;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authentication adapters must establish principal and validated CSRF before this boundary. */
@Component @Order(Ordered.LOWEST_PRECEDENCE-10)
public class AuthorizationAdminBoundary extends OncePerRequestFilter {
 @Override protected boolean shouldNotFilter(HttpServletRequest r){return !r.getRequestURI().startsWith(r.getContextPath()+"/api/trusted-human/v1/authorization");}
 @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
  try {ServletAuthorization.require(r,"authorization.policy.admin",true);
   if(!"GET".equals(r.getMethod())&&!"HEAD".equals(r.getMethod())&&!Boolean.TRUE.equals(r.getAttribute("ouf.csrfValidated")))throw new SecurityException("AUTH_CSRF_REQUIRED");
  }catch(SecurityException e){response.sendError(403,"Authorization administration denied");return;}
  final int maximum=5*1024*1024;
  byte[] body=r.getInputStream().readNBytes(maximum+1);
  if(body.length>maximum){response.sendError(413,"Authorization request too large");return;}
  chain.doFilter(new HttpServletRequestWrapper(r){
   @Override public ServletInputStream getInputStream(){var input=new ByteArrayInputStream(body);return new ServletInputStream(){
    public int read(){return input.read();}public int read(byte[] b,int off,int len){return input.read(b,off,len);}
    public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}
    public void setReadListener(ReadListener listener){throw new IllegalStateException("Synchronous administrative endpoint");}
   };}
  },response);
 }
}
