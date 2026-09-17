package it.comune.trieste.ouf.pairwise;

import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.util.*;
import java.nio.file.*;
import org.springframework.web.bind.annotation.*;

/** Laboratory controls; every publication goes through production human approval and activation. */
@RestController
public class GeoPackagePublisherFixture {
  private final OnboardingService service;private final ManagedFileService files;private final CanonicalHash hashes;
  private Map<String,Object> cameraConfiguration;
  public GeoPackagePublisherFixture(OnboardingService service,ManagedFileService files,CanonicalHash hashes){this.service=service;this.files=files;this.hashes=hashes;}
  public void seed(){publish("cameras");}
  @PostMapping("/fixture/r2e/cabinets") public Map<String,Object> cabinets(@RequestHeader("Authorization") String auth){human(auth);publish("cabinets");return Map.of("status","ACTIVE");}
  @PostMapping("/fixture/r2e/reload") public Map<String,Object> reload(@RequestHeader("Authorization") String auth)throws Exception{
    human(auth);var config=new LinkedHashMap<>(cameraConfiguration);var ex=copy(config,"extractionProfile");var runtime=copy(ex,"runtime");var execution=copy(runtime,"execution");
    byte[] bytes=Files.readAllBytes(Path.of(System.getenv("OUF_R2E_INPUT_RELOADED")));String ref="object://r2e/cameras-reloaded.gpkg";
    UUID asset=(UUID)files.register("r2e-cameras",ref,hashes.ofBytes(bytes),"application/geopackage+sqlite3",bytes.length,"fixture-human","retention://30d").get("asset_id");
    UUID profile=(UUID)files.profile(asset,bytes,"object://r2e/reloaded-profile.json").get("profile_id");
    runtime.put("stagingRef",ref);runtime.put("contentHash",hashes.ofBytes(bytes));runtime.put("assetId",asset.toString());runtime.put("fileProfileId",profile.toString());execution.put("expectedSize",bytes.length);runtime.put("execution",execution);ex.put("runtime",runtime);ex.put("selection",Map.of("assetId",asset.toString(),"fileProfileId",profile.toString()));config.put("extractionProfile",ex);
    var bundle=copy(config,"bundle");bundle.put("bundleVersion","2");config.put("bundle",bundle);
    var draft=service.createVersion("r2e-cameras",config,actor(),"r2e-reload");activate("r2e-cameras",(UUID)draft.get("onboarding_version_id"),0);return Map.of("status","ACTIVE");
  }
  @SuppressWarnings("unchecked") private void publish(String layer){try{
    byte[] bytes=Files.readAllBytes(Path.of(System.getenv("OUF_R2E_INPUT")));String source="r2e-"+layer;
    UUID asset=(UUID)files.register(null,"object://r2e/"+layer+".gpkg",hashes.ofBytes(bytes),"application/geopackage+sqlite3",bytes.length,"fixture-human","retention://30d").get("asset_id");
    UUID profile=(UUID)files.profile(asset,bytes,"object://r2e/"+layer+"-profile.json").get("profile_id");
    var fields=new ArrayList<ManagedFileService.FieldDecision>();for(String name:layer.equals("cameras")?List.of("fid","code","cabinet","geom"):List.of("fid","code","geom"))fields.add(new ManagedFileService.FieldDecision(name,"INCLUDE","OPEN","https://example.org/"+name,"IDENTITY",null,null,null));
    var binding=binding();String type="https://example.org/"+(layer.equals("cameras")?"Camera":"Cabinet");
    var draft=files.onboard(asset,profile,source,layer,"platform",type,List.of("core@"+binding.get("semanticVersion")),List.of("code"),fields,layer,actor(),"r2e");
    var config=copy(draft,"configuration");config.put("semanticReferenceBindings",List.of(binding));var ex=copy(config,"extractionProfile");var runtime=copy(ex,"runtime");
    var execution=new LinkedHashMap<String,Object>();execution.put("acquisitionMode","INTERNAL_MANAGED_GEOPACKAGE");execution.put("adapterId","managed-geopackage-v1");execution.put("adapterRuntimeVersion","1.0.0");execution.put("sourceSchemaRef",copy(config,"bundle").get("schemaRef"));execution.put("sourceSchemaId",source);execution.put("sourceSchemaVersion","1");execution.put("semanticPublicationSetRef",binding.get("publicationSetId"));execution.put("adapterProfileRef","adapter://managed-geopackage/1");execution.put("observationPolicy","ACQUISITION_TIME");execution.put("expectedSize",bytes.length);execution.put("maxRows",100);execution.put("maxCellChars",10000);for(String key:List.of("layer","sourceCrs","geometryColumn"))execution.put(key,runtime.get(key));
    var udp=new LinkedHashMap<String,Object>();udp.put("resolution",Map.of("strategyId","r2e-approved-key","strategyVersion","1","policyRef","policy://r2e-resolution/1","canonicalType",type,"canonicalKeyProperty","https://example.org/code","matchProperty","https://example.org/code"));
    udp.put("materialization",Map.of("policyRef","policy://r2e-authority/1","checkpointInterval",1,"bitemporalProperties",List.of(),"properties",fields.stream().map(f->Map.of("sourceField",f.targetPropertyIri(),"propertyIri",f.targetPropertyIri(),"datatype","http://www.w3.org/2001/XMLSchema#string","accessLabel","OPEN","authorityOrder",List.of(source))).toList()));
    udp.put("spatial",Map.of("policyRef","policy://r2e-geometry/1","geometry",Map.of("sourceField","https://example.org/geom","expectedSourceCrs","EPSG:4326","canonicalSrid",4326,"normalizationVersion","r2e-1","accessLabel","OPEN","crsPolicy",Map.of("sourceAxisOrder","XY","mismatchAction","REJECT")),"relationships",List.of()));
    if(layer.equals("cameras")){
      String relation="https://example.org/connectedTo",mapping="relationship://camera-cabinet/1";
      config.put("relationshipMappings",List.of(Map.of("mappingId",mapping,"sourceField","cabinet","relationIri",relation,"targetClassIri","https://example.org/Cabinet","resolution",Map.of("strategy","CANONICAL_KEY","targetKeyProperty","https://example.org/code","onNoMatch","QUARANTINE_RELATION","onMultipleMatches","REVIEW_REQUIRED"),"provenancePolicy","SOURCE_DECLARED")));
      var access=new ArrayList<>((List<Map<String,Object>>)config.get("dataAccessPolicies"));access.add(Map.of("target",relation,"label","OPEN","scope","RELATIONSHIP"));config.put("dataAccessPolicies",access);execution.put("relationshipResolutionStrategyRefs",List.of(mapping));
      udp.put("relationships",Map.of("policyRef","policy://camera-cabinet/1","relationships",List.of(Map.of("sourceField","https://example.org/cabinet","relationIri",relation,"targetCanonicalType","https://example.org/Cabinet","targetPropertyIri","https://example.org/code","resolutionStrategy","CANONICAL_KEY","onNoMatch","QUARANTINE_RELATION","accessLabel","OPEN","selfLoopAllowed",false))));
    }
    runtime.put("udp",udp);runtime.put("execution",execution);ex.put("runtime",runtime);config.put("extractionProfile",ex);UUID version=(UUID)draft.get("onboarding_version_id");service.patchVersion(source,version,0,config,actor(),"r2e");activate(source,version,1);if(layer.equals("cameras"))cameraConfiguration=config;
  }catch(Exception e){throw new IllegalStateException("R2E_PUBLICATION_FAILED",e);}}
  private void activate(String source,UUID version,long lock){service.submit(source,version,lock,actor(),"r2e");var c=service.createChallenge(source,version,actor(),"r2e");service.confirm(source,version,(UUID)c.get("challenge_id"),actor(),"r2e","fixture:mfa");service.attestIngestionCompatibility(source,version,true,"R2e approved fixture profile",new OnboardingService.Actor("fixture-ingestion","SERVICE",Set.of("ouf.ingestion.configuration.attest")),"r2e");service.activate(source,version,actor(),"r2e");}
  private static OnboardingService.Actor actor(){return new OnboardingService.Actor("fixture-human","HUMAN_USER");}
  private static void human(String auth){String expected=System.getenv("OUF_PAIRWISE_HUMAN_TOKEN");if(expected==null||!java.security.MessageDigest.isEqual(("Bearer "+expected).getBytes(java.nio.charset.StandardCharsets.UTF_8),auth.getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);}
  @SuppressWarnings("unchecked") private static Map<String,Object> binding()throws Exception{return (Map<String,Object>)new com.fasterxml.jackson.databind.ObjectMapper().readValue(Files.readString(Path.of(System.getenv("OUF_R2C_SEMANTIC_RESULT"))),Map.class).get("binding");}
  @SuppressWarnings("unchecked") private static Map<String,Object> copy(Map<String,Object> parent,String key){return new LinkedHashMap<>((Map<String,Object>)parent.get(key));}
}
