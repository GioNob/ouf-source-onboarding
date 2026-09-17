package it.comune.trieste.ouf.pairwise;

import it.comune.trieste.ouf.onboarding.OnboardingApplication;
import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import it.comune.trieste.ouf.authorization.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;

/** Laboratory identity/bootstrap only; excluded from the production JAR. */
public class ActivationPublisherFixture {
 public static void main(String[] args){SpringApplication.run(new Class<?>[]{OnboardingApplication.class,Fixture.class},args);}
 @Configuration(proxyBeanMethods=false) public static class Fixture {
  @Bean FilterRegistrationBean<Filter> fixtureIdentity(){
   String token=System.getenv("OUF_PAIRWISE_TOKEN");if(token==null||!token.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Ephemeral fixture token required");
   byte[] expected=("Bearer "+token).getBytes(StandardCharsets.UTF_8);Set<String> caps=Set.of("ouf.onboarding.configuration.read");var runtime=TestAuthorization.runtime("pairwise-ingestion","SERVICE",caps);
   Filter filter=(input,output,chain)->{var request=(HttpServletRequest)input;var response=(HttpServletResponse)output;String header=request.getHeader("Authorization");if(header==null||!MessageDigest.isEqual(expected,header.getBytes(StandardCharsets.UTF_8))){response.sendError(401);return;}if(!request.getMethod().equals("GET")||!request.getRequestURI().startsWith("/api/onboarding/v1/runtime/publications")){response.sendError(403);return;}request.getServletContext().setAttribute(ServletAuthorization.RUNTIME,runtime);chain.doFilter(new HttpServletRequestWrapper(request){@Override public Principal getUserPrincipal(){return TestAuthorization.principal("pairwise-ingestion","SERVICE",caps);}},response);};
   var registration=new FilterRegistrationBean<>(filter);registration.addUrlPatterns("/api/*");registration.setOrder(-100);return registration;
  }
  @Bean ApplicationRunner seed(OnboardingService onboarding,ManagedFileService files,CanonicalHash hashes){return args->{
   var human=new OnboardingService.Actor("fixture-human","HUMAN_USER");
   byte[] csv="id,name\n1,Alpha\n".getBytes(StandardCharsets.UTF_8);UUID asset=(UUID)files.register(null,"object://r2a/input.csv",hashes.ofBytes(csv),"text/csv",csv.length,"fixture-human","retention://30d").get("asset_id");UUID profile=(UUID)files.profile(asset,csv,"object://r2a/profile.json").get("profile_id");
   var draft=files.onboard(asset,profile,"r2a-file","R2a file","platform","https://example.org/Object",List.of("core@1"),List.of("id"),human,"r2a");
   @SuppressWarnings("unchecked")var config=new LinkedHashMap<>((Map<String,Object>)draft.get("configuration"));config.put("semanticReferenceBindings",bindings());UUID version=(UUID)draft.get("onboarding_version_id");onboarding.patchVersion("r2a-file",version,0,config,human,"r2a");activate(onboarding,"r2a-file",version,1,human);
   onboarding.createSource("r2a-pull","R2a pull","EXTERNAL_API","PULL","platform",Map.of(),human,"r2a");var pull=onboarding.createVersion("r2a-pull",pull(),human,"r2a");activate(onboarding,"r2a-pull",(UUID)pull.get("onboarding_version_id"),0,human);
  };}
 }
 private static void activate(OnboardingService service,String source,UUID version,long lock,OnboardingService.Actor human){service.submit(source,version,lock,human,"r2a");var challenge=service.createChallenge(source,version,human,"r2a");service.confirm(source,version,(UUID)challenge.get("challenge_id"),human,"r2a","fixture:mfa");service.attestIngestionCompatibility(source,version,true,"R2a fixture exact runtime profile",new OnboardingService.Actor("fixture-ingestion","SERVICE",Set.of("ouf.ingestion.configuration.attest")),"r2a");service.activate(source,version,human,"r2a");}
 private static List<Map<String,Object>> bindings(){return List.of(Map.of("semanticId","core","semanticVersion","1","revisionId","00000000-0000-0000-0000-000000000001","publicationSetId","00000000-0000-0000-0000-000000000002"));}
 private static Map<String,Object> pull(){var policy=Map.of("timeZone","Europe/Rome","misfireToleranceSeconds",60,"sourceTimeoutSeconds",30,"maxRetryAttempts",3,"maxRetryElapsedSeconds",300,"incidentDedupWindowSeconds",300,"operationalVisibilityClass","TENANT_OPERATIONAL","operationalRetentionDays",30);var sync=Map.of("bootstrap","FULL_SNAPSHOT","incremental","NONE","pollInterval","PT1M","retryBackoffSeconds",5,"operationalPolicy",policy);return Map.of("syncProfile",sync,"semanticReferenceBindings",bindings(),"extractionProfile",Map.of("profileId","r2a-pull","version","1","sourceId","r2a-pull","selection",Map.of(),"projection",Map.of("TYPE",List.of("id")),"sync",sync),"semanticMapping",Map.of("mappingId","r2a-mapping","sourceType",Map.of("sourceId","r2a-pull","typeCode","TYPE"),"targetClasses",List.of(Map.of("ontologyId","core","ontologyVersion","1","classIri","https://example.org/Object")),"semanticRefs",List.of("core@1")),"bundle",Map.of("bundleId","r2a-pull","bundleVersion","1","environment","test","source",Map.of("sourceId","r2a-pull","sourceKind","EXTERNAL_API","acquisitionMode","PULL"),"bindingRef","gateway://r2a/pull","createdFromOnboardingVersion","pending","effectiveFrom","2026-09-17T00:00:00Z","checksum","sha256:pending","status","APPROVED"));}
}
