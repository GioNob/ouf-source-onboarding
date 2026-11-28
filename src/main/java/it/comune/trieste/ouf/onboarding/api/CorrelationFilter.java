package it.comune.trieste.ouf.onboarding.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {
  public static final String ATTRIBUTE="ouf.correlationId";
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
    String supplied=request.getHeader("X-Correlation-ID");
    // Protected routes only echo a UUID in the request completion log.
    boolean protectedRoute=request.getRequestURI().endsWith("/managed-file-mcp/review-prepare")
        || request.getRequestURI().endsWith("/managed-file-mcp/review-read")
        || request.getRequestURI().startsWith(request.getContextPath()+AnalysisMandateApi.PATH+"/");
    String id=supplied==null || supplied.isBlank() || protectedRoute && !supplied.matches("(?i)[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")
        ?UUID.randomUUID().toString():supplied;
    request.setAttribute(ATTRIBUTE,id);
    response.setHeader("X-Correlation-ID",id);
    chain.doFilter(request,response);
  }
  public static String get(HttpServletRequest request){Object value=request.getAttribute(ATTRIBUTE);return value instanceof String s&&!s.isBlank()?s:UUID.randomUUID().toString();}
}
