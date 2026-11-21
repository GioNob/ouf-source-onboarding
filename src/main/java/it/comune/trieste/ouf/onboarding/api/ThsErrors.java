package it.comune.trieste.ouf.onboarding.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.http.ResponseEntity;

/** Scoped rendering for container/MVC errors, including missing THS routes. */
@Configuration(proxyBeanMethods=false)
class ThsErrors {
  @Bean FilterRegistrationBean<Filter> thsErrorPages() {
    Filter filter = (request, response, chain) -> {
      var r = (HttpServletRequest) request;
      var uri = (String) r.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
      var status = (Integer) r.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
      if (uri != null && uri.startsWith(r.getContextPath() + "/trusted-human/") &&
          status != null && isPage(r, uri)) {
        page((HttpServletResponse) response, status);
      } else {
        chain.doFilter(request, response);
      }
    };
    var bean = new FilterRegistrationBean<>(filter);
    bean.setDispatcherTypes(DispatcherType.ERROR);
    bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return bean;
  }

  static boolean isPage(HttpServletRequest r, String uri) {
    String accept = r.getHeader("Accept");
    String path = uri.substring(r.getContextPath().length());
    return path.startsWith("/trusted-human/") && "GET".equals(r.getMethod()) && !path.contains("/api/") && !path.endsWith("/api") &&
        !path.equals("/trusted-human/managed-files/session") && !path.endsWith(".js") && !path.endsWith(".css") &&
        (accept == null || accept.contains("text/html") || accept.contains("application/xhtml+xml") || accept.contains("*/*"));
  }

  static void respond(HttpServletRequest r, HttpServletResponse s, int status) throws IOException {
    if (isPage(r, r.getRequestURI())) {
      page(s, status);
      return;
    }
    String code = switch (status) {
      case 401 -> "THS_SESSION_EXPIRED";
      case 403 -> "THS_ACCESS_DENIED";
      default -> "THS_UNAVAILABLE";
    };
    s.setStatus(status);
    s.setHeader("Cache-Control", "no-store");
    s.setContentType("application/json");
    s.getWriter().write("{\"code\":\"" + code + "\"}");
  }

  static void page(HttpServletResponse response, int status) throws IOException {
    var page = html(status);
    response.setStatus(status);
    page.getHeaders().forEach((name, values) -> response.setHeader(name, values.getFirst()));
    response.setContentType("text/html;charset=UTF-8");
    response.getWriter().write(page.getBody());
  }

  static ResponseEntity<String> html(int status) {
    String guidance = switch (status) {
      case 403 -> "Accesso negato. Verifica con l’amministratore che il tuo account possa esaminare questa richiesta.";
      case 404 -> "Pagina non trovata. Riapri il collegamento alla richiesta dalla conversazione OUF.";
      default -> "La richiesta non è disponibile. Riapri la scheda e verifica lo stato prima di confermare una decisione.";
    };
    return ResponseEntity.status(status).header("Content-Type", "text/html;charset=UTF-8")
        .header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer").header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        .body("""
        <!doctype html><html lang="it"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>OUF · Errore %d</title><main><h1>Errore %d</h1><p>%s</p></main></html>
        """.formatted(status, status, guidance));
  }
}
