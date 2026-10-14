package it.comune.trieste.ouf.pairwise;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.OnboardingApplication;
import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationPolicyRegistry;
import it.comune.trieste.ouf.authorization.*;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;

/** Disposable integration identity and CSV transport seam. Excluded from the production JAR. */
public class ChatbotVerticalPublisherFixture {
  public static void main(String[] args){SpringApplication.run(new Class<?>[]{OnboardingApplication.class,Fixture.class},args);}
  @Configuration(proxyBeanMethods=false) public static class Fixture {
    private static final Set<String> SERVICE=Set.of("ouf.onboarding.configuration.read","ouf.ingestion.configuration.attest");
    private static final Set<String> HUMAN=Set.of("ouf.onboarding.configuration.write","ouf.managed-source.onboarding.create","ouf.managed-source.onboarding.review.prepare","ouf.managed-source.onboarding.read","ouf.managed-source.file.profile","ouf.managed-source.preview");
    @Bean FilterRegistrationBean<Filter> verticalIdentity()throws Exception{
      String serviceToken=required("OUF_PAIRWISE_TOKEN"),humanToken=required("OUF_PAIRWISE_HUMAN_TOKEN");
      var service=TestAuthorization.runtime("fixture-ingestion","SERVICE",SERVICE);var human=TestAuthorization.runtime("fixture-human","HUMAN",HUMAN);
      var caps=new ArrayList<CapabilityDescriptor>(service.currentSnapshot().bundle().capabilities());caps.addAll(human.currentSnapshot().bundle().capabilities());
      var grants=new ArrayList<Grant>(service.currentSnapshot().bundle().grants());grants.addAll(human.currentSnapshot().bundle().grants());
      TestAuthorization.install(service,new PolicyBundle("vertical-fixture",1,Instant.now(),caps,grants));
      Filter filter=(input,output,chain)->{
        var request=(HttpServletRequest)input;var response=(HttpServletResponse)output;
        boolean workload=matches(request.getHeader("Authorization"),serviceToken),person=matches(request.getHeader("Authorization"),humanToken);
        if(!workload&&!person){response.sendError(401);return;}
        request.getServletContext().setAttribute(ServletAuthorization.RUNTIME,service);
        chain.doFilter(new HttpServletRequestWrapper(request){@Override public Principal getUserPrincipal(){return TestAuthorization.principal(workload?"fixture-ingestion":"fixture-human",workload?"SERVICE":"HUMAN",workload?SERVICE:HUMAN);}},response);
      };
      var registration=new FilterRegistrationBean<>(filter);registration.addUrlPatterns("/api/*");registration.setOrder(-100);return registration;
    }
    @Bean ApplicationRunner verticalAssets(ManagedFileService files,CanonicalHash hashes,ObjectMapper json,AuthorizationPolicyRegistry registry){return args->{
      String directory=System.getenv("OUF_R4A_INPUT_DIR"),output=System.getenv("OUF_R4A_ASSETS_RESULT");
      if(directory==null||output==null)throw new IllegalStateException("R4A_EXPLICIT_FIXTURE_INPUT_REQUIRED");
      Instant now=Instant.now();var capabilities=new ArrayList<CapabilityDescriptor>();var grants=new ArrayList<Grant>();
      for(String cap:List.of("ouf.managed-source.onboarding.review.prepare","ouf.managed-source.onboarding.read","ouf.onboarding.configuration.write")){
        capabilities.add(new CapabilityDescriptor(cap,cap.endsWith(".read")?"READ":"COMMAND",cap,Set.of(PrincipalContext.ActorType.HUMAN)));
        grants.add(new Grant("fixture:"+cap,cap,"tenant-a","fixture-human",null,null,now.minusSeconds(60),now.plusSeconds(3600)));
      }
      registry.publishAndActivate(new PolicyBundle("vertical-owner-fixture",1,now,capabilities,grants),"fixture-bootstrap");
      var assets=new LinkedHashMap<String,Object>();
      for(String name:List.of("baseline","incoming")){
        byte[] bytes=Files.readAllBytes(Path.of(directory,name+".csv"));
        UUID asset=(UUID)files.register(null,"object://r4a/"+name+".csv",hashes.ofBytes(bytes),"text/csv",bytes.length,"fixture-human","retention://fixture-30d").get("asset_id");
        var profile=files.profile(asset,bytes,"object://r4a/"+name+"-profile.json");
        assets.put(name,Map.of("assetId",asset,"profileId",profile.get("profile_id"),"profileVersion",profile.get("version"),"contentHash",hashes.ofBytes(bytes),"sizeBytes",bytes.length));
      }
      Files.writeString(Path.of(output),json.writeValueAsString(Map.of("assets",assets,"identitySeam","EXPLICIT_DISPOSABLE_TEST_HUMAN_AND_SERVICE","approvalIssued",false,"runtimeActivated",false)));
    };}
    private static String required(String key){String value=System.getenv(key);if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Ephemeral fixture token required");return value;}
    private static boolean matches(String value,String expected){return value!=null&&MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8),("Bearer "+expected).getBytes(StandardCharsets.UTF_8));}
  }
}
