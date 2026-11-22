package it.comune.trieste.ouf.onboarding.api;

import static it.comune.trieste.ouf.onboarding.application.ManagedMcpObservation.*;

import java.util.List;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Keep established owner error bodies and status codes while exposing a closed workflow result. */
@RestControllerAdvice
class ManagedMcpErrorObservation implements ResponseBodyAdvice<Object> {
  @Override public boolean supports(MethodParameter type,Class<? extends HttpMessageConverter<?>> converter){
    return true;
  }

  @Override public Object beforeBodyWrite(Object body,MethodParameter type,MediaType contentType,
      Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response){
    String path=request.getURI().getPath();
    if(!path.startsWith("/api/internal/v1/onboarding/managed-file-mcp/")
        &&!path.startsWith("/api/internal/v1/onboarding/source-revision-mcp/"))return body;
    if(body instanceof ProblemDetail problem){
      problem.setProperty("workflowState",State.ERROR);
      problem.setProperty("reasonCode",Reason.OWNER_ERROR);
      problem.setProperty("allowedActions",List.of(Action.NONE));
    }else if(body instanceof Map<?,?> map && response instanceof ServletServerHttpResponse servlet
        && servlet.getServletResponse().getStatus()>=400){
      @SuppressWarnings("unchecked") var error=(Map<String,Object>)map;
      return with(error,State.ERROR,Reason.OWNER_ERROR);
    }
    return body;
  }
}
