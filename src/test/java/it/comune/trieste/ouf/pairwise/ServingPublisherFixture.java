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
public class ServingPublisherFixture {
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
   byte[] csv="id,name\n1,Alpha\n".getBytes(StandardCharsets.UTF_8);UUID asset=(UUID)files.register(null,"object://r2b/input.csv",hashes.ofBytes(csv),"text/csv",csv.length,"fixture-human","retention://30d").get("asset_id");UUID profile=(UUID)files.profile(asset,csv,"object://r2b/profile.json").get("profile_id");
   var draft=files.onboard(asset,profile,"r2b-file","R2b file","platform","https://example.org/Object",List.of("core@1"),List.of("id"),List.of(field("id"),field("name")),human,"r2b");
   @SuppressWarnings("unchecked")var config=new LinkedHashMap<>((Map<String,Object>)draft.get("configuration"));config.put("semanticReferenceBindings",bindings());enrich(config,true,csv.length);UUID version=(UUID)draft.get("onboarding_version_id");onboarding.patchVersion("r2b-file",version,0,config,human,"r2b");activate(onboarding,"r2b-file",version,1,human);
   onboarding.createSource("r2b-pull","R2b pull","EXTERNAL_API","PULL","platform",Map.of(),human,"r2b");var pull=onboarding.createVersion("r2b-pull",pullExecution(),human,"r2b");activate(onboarding,"r2b-pull",(UUID)pull.get("onboarding_version_id"),0,human);
  };}
 }
 private static ManagedFileService.FieldDecision field(String name){return new ManagedFileService.FieldDecision(name,"INCLUDE","OPEN","https://example.org/"+name,"IDENTITY",null,null,null);}
 @SuppressWarnings("unchecked") private static void enrich(Map<String,Object> config,boolean managed,int size){
  var extraction=new LinkedHashMap<>((Map<String,Object>)config.get("extractionProfile"));
  var runtime=new LinkedHashMap<>((Map<String,Object>)extraction.getOrDefault("runtime",Map.of()));
  var execution=new LinkedHashMap<String,Object>();
  execution.put("acquisitionMode",managed?"INTERNAL_MANAGED_CSV":"REST_JSON");execution.put("adapterId",managed?"managed-tabular-v1":"gateway-rest-json-v1");execution.put("adapterRuntimeVersion","1.0.0");
  execution.put("sourceSchemaRef",managed?((Map<String,Object>)config.get("bundle")).get("schemaRef"):"schema://r2b-pull/1");execution.put("sourceSchemaId",managed?"r2b-file":"r2b-pull");execution.put("sourceSchemaVersion","1");
  execution.put("semanticPublicationSetRef","00000000-0000-0000-0000-000000000002");execution.put("adapterProfileRef",managed?"adapter://managed-tabular/1":"adapter://rest-json/1");execution.put("observationPolicy","ACQUISITION_TIME");
  if(managed){execution.put("expectedSize",size);execution.put("maxRows",1000);execution.put("maxColumns",20);execution.put("maxCellChars",1024);}else{execution.put("itemsPointer","/items");execution.put("pageSize",100);execution.put("maxPages",2);execution.put("maxRecords",100);execution.put("maxPageBytes",10000);}
  runtime.put("execution",execution);
  var properties=List.of("id","name").stream().map(name->Map.<String,Object>of("sourceField","https://example.org/"+name,"propertyIri","https://example.org/"+name,"datatype","http://www.w3.org/2001/XMLSchema#string","accessLabel","OPEN","authorityOrder",List.of(managed?"r2b-file":"r2b-pull"))).toList();
  runtime.put("udp",Map.of("resolution",Map.of("strategyId","r2b-approved-key","strategyVersion","1","policyRef","policy://r2b-resolution/1","canonicalType",managed?"R2bFile":"R2bPull","canonicalKeyProperty","https://example.org/id","matchProperty","https://example.org/id"),"materialization",Map.of("policyRef","policy://r2b-authority/1","properties",properties,"checkpointInterval",1,"bitemporalProperties",List.of())));
  extraction.put("runtime",runtime);config.put("extractionProfile",extraction);
 }
 @SuppressWarnings("unchecked") private static Map<String,Object> pullExecution(){
  var config=new LinkedHashMap<>(pull());var semantic=new LinkedHashMap<>((Map<String,Object>)config.get("semanticMapping"));
  semantic.put("propertyMappings",List.of("id","name").stream().map(name->Map.of("sourceField",name,"targetPropertyIri","https://example.org/"+name,"transform","IDENTITY")).toList());config.put("semanticMapping",semantic);
  config.put("sourceObjectIdentityPolicy",Map.of("strategy","NATIVE_KEY","sourceFields",List.of("id"),"normalizationRuleRef","normalization://native-key/1"));config.put("changeRepresentationProfile",Map.of("mode","FULL_SNAPSHOT"));
  config.put("dataAccessPolicies",List.of("id","name").stream().map(name->Map.of("target","https://example.org/"+name,"label","OPEN","scope","PROPERTY")).toList());
  var extraction=new LinkedHashMap<>((Map<String,Object>)config.get("extractionProfile"));extraction.put("projection",Map.of("TYPE",List.of("id","name")));config.put("extractionProfile",extraction);enrich(config,false,0);return config;
 }
 private static void activate(OnboardingService service,String source,UUID version,long lock,OnboardingService.Actor human){service.submit(source,version,lock,human,"r2b");var challenge=service.createChallenge(source,version,human,"r2b");service.confirm(source,version,(UUID)challenge.get("challenge_id"),human,"r2b","fixture:mfa");service.attestIngestionCompatibility(source,version,true,"R2b fixture exact runtime profile",new OnboardingService.Actor("fixture-ingestion","SERVICE",Set.of("ouf.ingestion.configuration.attest")),"r2b");service.activate(source,version,human,"r2b");}
 private static List<Map<String,Object>> bindings(){return List.of(Map.of("semanticId","core","semanticVersion","1","revisionId","00000000-0000-0000-0000-000000000001","publicationSetId","00000000-0000-0000-0000-000000000002"));}
 private static Map<String,Object> pull(){var policy=Map.of("timeZone","Europe/Rome","misfireToleranceSeconds",60,"sourceTimeoutSeconds",30,"maxRetryAttempts",3,"maxRetryElapsedSeconds",300,"incidentDedupWindowSeconds",300,"operationalVisibilityClass","TENANT_OPERATIONAL","operationalRetentionDays",30);var sync=Map.of("bootstrap","FULL_SNAPSHOT","incremental","NONE","pollInterval","PT1M","retryBackoffSeconds",5,"operationalPolicy",policy);return Map.of("syncProfile",sync,"semanticReferenceBindings",bindings(),"extractionProfile",Map.of("profileId","r2b-pull","version","1","sourceId","r2b-pull","selection",Map.of(),"projection",Map.of("TYPE",List.of("id")),"sync",sync),"semanticMapping",Map.of("mappingId","r2b-mapping","sourceType",Map.of("sourceId","r2b-pull","typeCode","TYPE"),"targetClasses",List.of(Map.of("ontologyId","core","ontologyVersion","1","classIri","https://example.org/Object")),"semanticRefs",List.of("core@1")),"bundle",Map.of("bundleId","r2b-pull","bundleVersion","1","environment","test","source",Map.of("sourceId","r2b-pull","sourceKind","EXTERNAL_API","acquisitionMode","PULL"),"bindingRef","gateway://r2b/pull","createdFromOnboardingVersion","pending","effectiveFrom","2026-09-17T00:00:00Z","checksum","sha256:pending","status","APPROVED"));}
}
