package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.authorization.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** Server-side OIDC session: no bearer is exposed to JavaScript or the chatbot. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="ouf.authorization.ths.enabled",havingValue="true")
public class PermissionThsSecurity {
 @Bean @Order(1) SecurityFilterChain permissionThsChain(HttpSecurity http,OAuth2AuthorizedClientService clients,JwtDecoder decoder,Converter<Jwt,? extends AbstractAuthenticationToken> converter)throws Exception{
  http.securityMatcher("/trusted-human/authorization/**","/oauth2/**","/login/oauth2/**");
  http.authorizeHttpRequests(a->a.requestMatchers("/oauth2/**","/login/oauth2/**").permitAll().anyRequest().authenticated());
  http.oauth2Login(o->o.loginPage("/oauth2/authorization/ouf-ths"));
  // Keep Spring CSRF enabled for all session-authenticated state changes.
  http.addFilterAfter(new OncePerRequestFilter(){@Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
   if(r.getRequestURI().startsWith("/trusted-human/authorization/")){
    try{
     if(!(SecurityContextHolder.getContext().getAuthentication() instanceof OAuth2AuthenticationToken a))throw new SecurityException();
     OAuth2AuthorizedClient c=clients.loadAuthorizedClient(a.getAuthorizedClientRegistrationId(),a.getName());
     if(c==null||!"ouf-ths".equals(c.getClientRegistration().getRegistrationId()))throw new SecurityException();
     var token=converter.convert(decoder.decode(c.getAccessToken().getTokenValue()));
     if(token==null||!(token.getPrincipal() instanceof TrustedPrincipal p)||p.context().actorType()!=PrincipalContext.ActorType.HUMAN)throw new SecurityException();
     r.setAttribute(ServletAuthorization.TRUSTED_PRINCIPAL,p);r.setAttribute("ouf.permissionThsSession",Boolean.TRUE);
    }catch(Exception e){response.sendError(403,"THS session unavailable or expired; sign in again");return;}
   }
   chain.doFilter(r,response);
  }},AuthorizationFilter.class);
  return http.build();
 }
}
