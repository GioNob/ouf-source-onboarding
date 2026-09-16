package it.comune.trieste.ouf.authorization;

import java.nio.file.Path;
import java.time.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class AuthorizationAutoConfiguration {
  @Bean LocalAuthorization oufLocalAuthorization(Environment env){
    var runtime=new LocalAuthorization(Clock.systemUTC(),Duration.ofSeconds(env.getProperty("ouf.authorization.max-staleness-seconds",Long.class,300L)));
    String file=env.getProperty("ouf.authorization.bundle-file");
    if(file!=null){
      var ref=new LocalAuthorization.BundleReference(Path.of(file),env.getRequiredProperty("ouf.authorization.bundle-id"),Long.parseLong(env.getRequiredProperty("ouf.authorization.bundle-version")),env.getRequiredProperty("ouf.authorization.bundle-sha256"),1);
      var result=runtime.refresh(ref);if(!result.installed())throw new IllegalStateException(result.reason());
    }
    return runtime; // Missing bundle never authorizes a request; health reports not-ready.
  }
  @Bean ServletContextInitializer oufAuthorizationContext(LocalAuthorization runtime){return context->context.setAttribute(ServletAuthorization.RUNTIME,runtime);}
}
