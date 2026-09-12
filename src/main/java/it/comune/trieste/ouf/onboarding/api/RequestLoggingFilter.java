package it.comune.trieste.ouf.onboarding.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.slf4j.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @Order(Ordered.HIGHEST_PRECEDENCE+1)
public class RequestLoggingFilter extends OncePerRequestFilter {
  private static final Logger LOG=LoggerFactory.getLogger(RequestLoggingFilter.class);
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {long start=System.nanoTime();try{chain.doFilter(request,response);}finally{LOG.atInfo().addKeyValue("event","HTTP_REQUEST_COMPLETED").addKeyValue("method",request.getMethod()).addKeyValue("route",request.getRequestURI()).addKeyValue("status",response.getStatus()).addKeyValue("durationMs",(System.nanoTime()-start)/1_000_000).addKeyValue("correlationId",CorrelationFilter.get(request)).log("onboarding request completed");}}
}
