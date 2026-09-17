package it.comune.trieste.ouf.pairwise;

import com.healthmarketscience.jackcess.*;
import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.nio.file.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Test-only identities and source data; all publications use the production approval lifecycle. */
@RestController
public class ManagedFormatsPublisherFixture {
  private final OnboardingService service;
  private final ManagedFileService files;
  private final CanonicalHash hashes;
  private final Map<String, Map<String,Object>> configurations = new HashMap<>();
  public ManagedFormatsPublisherFixture(OnboardingService service, ManagedFileService files, CanonicalHash hashes) {
    this.service=service; this.files=files; this.hashes=hashes;
  }
  public static void main(String[] args) throws Exception {
    Path directory=Path.of(args[0]); Files.createDirectories(directory);
    for (var format:List.of(Database.FileFormat.V2000, Database.FileFormat.V2010)) {
      Path path=directory.resolve(format==Database.FileFormat.V2000?"assets.mdb":"assets.accdb");
      try(var db=DatabaseBuilder.create(format,path.toFile())) {
        var assets=new TableBuilder("Assets").addColumn(new ColumnBuilder("ID",DataType.TEXT))
            .addColumn(new ColumnBuilder("CODE",DataType.TEXT)).addColumn(new ColumnBuilder("NAME",DataType.TEXT))
            .addColumn(new ColumnBuilder("DISTRICT",DataType.TEXT))
            .addIndex(new IndexBuilder("pk").addColumns("DISTRICT","CODE").setPrimaryKey()).toTable(db);
        var children=new TableBuilder("Children").addColumn(new ColumnBuilder("ID",DataType.TEXT))
            .addColumn(new ColumnBuilder("ASSET_CODE",DataType.TEXT)).addColumn(new ColumnBuilder("DISTRICT",DataType.TEXT))
            .addIndex(new IndexBuilder("pk").addColumns("ID").setPrimaryKey()).toTable(db);
        new RelationshipBuilder("Assets","Children").addColumns("DISTRICT","DISTRICT")
            .addColumns("CODE","ASSET_CODE").setReferentialIntegrity().setName("asset_children").toRelationship(db);
        assets.addRow("A-001","001","Asset Romo","Centro");
        for(String id:format==Database.FileFormat.V2000?List.of("C-1","C-2"):List.of("C-2","C-1"))
          children.addRow(id,"001","Centro");
      }
    }
  }
  public void seed(){publish("shape");}
  @PostMapping("/fixture/r2f/{kind}") public Map<String,Object> publishSelected(@PathVariable String kind,@RequestHeader("Authorization") String auth) {
    human(auth);
    if(!Set.of("assets","children","reload").contains(kind))throw new IllegalArgumentException("R2F_FIXTURE_KIND");
    return publish(kind);
  }
  @SuppressWarnings("unchecked") private Map<String,Object> publish(String kind) {
    try {
      boolean shape=kind.equals("shape"), reload=kind.equals("reload"), child=kind.equals("children")||reload;
      String source="r2f-"+(reload?"children":kind), filename=shape?"assets.zip":reload?"assets.accdb":"assets.mdb";
      String media=shape?"application/zip":"application/x-msaccess";
      byte[] bytes=Files.readAllBytes(Path.of(System.getenv("OUF_R2F_INPUT_DIR"),filename));
      String ref="object://r2f/"+filename;
      UUID asset=(UUID)files.register(reload?source:null,ref,hashes.ofBytes(bytes),media,bytes.length,"fixture-human","retention://30d").get("asset_id");
      UUID profile=(UUID)files.profile(asset,bytes,"object://r2f/"+kind+"-profile.json").get("profile_id");
      Map<String,Object> config;
      UUID version;
      if(reload) {
        config=new LinkedHashMap<>(configurations.get(source));
        var ex=copy(config,"extractionProfile");var runtime=copy(ex,"runtime");var execution=copy(runtime,"execution");
        runtime.put("stagingRef",ref);runtime.put("contentHash",hashes.ofBytes(bytes));runtime.put("assetId",asset.toString());runtime.put("fileProfileId",profile.toString());
        execution.put("expectedSize",bytes.length);runtime.put("execution",execution);ex.put("runtime",runtime);
        ex.put("selection",Map.of("assetId",asset.toString(),"fileProfileId",profile.toString()));config.put("extractionProfile",ex);
        var bundle=copy(config,"bundle");bundle.put("bundleVersion","2");config.put("bundle",bundle);
        var schema=copy(config,"sourceSchemaEvidence");schema.put("contentHash",hashes.ofBytes(bytes));schema.put("fileProfileRef","profile://managed-files/"+asset+"/profiles/"+profile);config.put("sourceSchemaEvidence",schema);
        version=(UUID)service.createVersion(source,config,actor(),"r2f").get("onboarding_version_id");
        activate(source,version,0);
      } else {
        String geometry=null;
        if(shape)try(var reader=new it.comune.trieste.ouf.managed.ShapefileReader(bytes)){geometry=reader.layers().getFirst().geometryColumn();}
        var names=new ArrayList<>(child?List.of("ID","ASSET_CODE","DISTRICT"):List.of("ID","CODE","NAME","DISTRICT"));
        if(shape)names.add(geometry);
        String geometryField=geometry;
        var fields=names.stream().map(name->new ManagedFileService.FieldDecision(name,"INCLUDE","OPEN",iri(name.equals(geometryField)?"geom":name),"IDENTITY",null,null,null)).toList();
        var binding=binding();String type=iri(child?"Child":"Asset");
        var draft=files.onboard(asset,profile,source,source,"platform",type,List.of("core@"+binding.get("semanticVersion")),
            shape||child?List.of("ID"):List.of("DISTRICT","CODE"),fields,shape?"assets":child?"Children":"Assets",actor(),"r2f");
        config=copy(draft,"configuration");config.put("semanticReferenceBindings",List.of(binding));
        var ex=copy(config,"extractionProfile");var runtime=copy(ex,"runtime");
        var execution=new LinkedHashMap<String,Object>();
        execution.put("acquisitionMode",shape?"INTERNAL_MANAGED_SHAPEFILE":"INTERNAL_MANAGED_ACCESS");
        execution.put("adapterId",shape?"managed-shapefile-v1":"managed-access-v1");execution.put("adapterRuntimeVersion","1.0.0");
        execution.put("sourceSchemaRef",copy(config,"bundle").get("schemaRef"));execution.put("sourceSchemaId",source);execution.put("sourceSchemaVersion","1");
        execution.put("semanticPublicationSetRef",binding.get("publicationSetId"));execution.put("adapterProfileRef","adapter://r2f/"+kind);
        execution.put("observationPolicy","ACQUISITION_TIME");execution.put("expectedSize",bytes.length);execution.put("maxRows",100);execution.put("maxCellChars",10000);
        execution.put("layer",runtime.get("layer"));
        if(shape)for(String key:List.of("sourceCrs","geometryColumn","encoding"))execution.put(key,runtime.get(key));
        var resolution=new LinkedHashMap<String,Object>(Map.of("strategyId",child?"CANONICAL_KEY":"COMPOSITE","strategyVersion","1","policyRef","policy://r2f-resolution/1","canonicalType",type,"canonicalKeyProperty",iri("ID"),"matchProperty",iri("ID")));
        if(!child)resolution.put("weighted",Map.of("signals",List.of(Map.of("property",iri("NAME"),"comparator","TEXT","weight",1)),"blockingProperties",List.of(iri("DISTRICT")),"maxCandidates",10,"highThreshold",.85,"reviewThreshold",.5,"minimumMargin",.1,"allowSpatialIdentity",false));
        var udp=new LinkedHashMap<String,Object>();udp.put("resolution",resolution);
        udp.put("materialization",Map.of("policyRef","policy://r2f-authority/1","checkpointInterval",1,"bitemporalProperties",List.of(),"properties",fields.stream().map(f->Map.of("sourceField",f.targetPropertyIri(),"propertyIri",f.targetPropertyIri(),"datatype","http://www.w3.org/2001/XMLSchema#string","accessLabel","OPEN","authorityOrder",child?List.of(source):List.of("r2f-shape","r2f-assets"))).toList()));
        if(shape)udp.put("spatial",Map.of("policyRef","policy://r2f-geometry/1","geometry",Map.of("sourceField",iri("geom"),"expectedSourceCrs","EPSG:4326","canonicalSrid",4326,"normalizationVersion","r2f-1","accessLabel","OPEN","crsPolicy",Map.of("sourceAxisOrder","XY","mismatchAction","REJECT")),"relationships",List.of()));
        if(child) {
          String relation=iri("belongsTo"),mapping="relationship://r2f-child-asset/1";
          config.put("relationshipMappings",List.of(Map.of("mappingId",mapping,"sourceField","ASSET_CODE","relationIri",relation,"targetClassIri",iri("Asset"),"resolution",Map.of("strategy","CANONICAL_KEY","targetKeyProperty",iri("CODE"),"onNoMatch","QUARANTINE_RELATION","onMultipleMatches","REVIEW_REQUIRED"),"provenancePolicy","SOURCE_DECLARED")));
          var access=new ArrayList<>((List<Map<String,Object>>)config.get("dataAccessPolicies"));access.add(Map.of("target",relation,"label","OPEN","scope","RELATIONSHIP"));config.put("dataAccessPolicies",access);
          execution.put("relationshipResolutionStrategyRefs",List.of(mapping));
          udp.put("relationships",Map.of("policyRef","policy://r2f-relations/1","relationships",List.of(Map.of("sourceField",iri("ASSET_CODE"),"relationIri",relation,"targetCanonicalType",iri("Asset"),"targetPropertyIri",iri("CODE"),"resolutionStrategy","CANONICAL_KEY","onNoMatch","QUARANTINE_RELATION","accessLabel","OPEN","selfLoopAllowed",false))));
        }
        runtime.put("execution",execution);runtime.put("udp",udp);ex.put("runtime",runtime);config.put("extractionProfile",ex);
        version=(UUID)draft.get("onboarding_version_id");service.patchVersion(source,version,0,config,actor(),"r2f");activate(source,version,1);
        configurations.put(source,config);
      }
      return Map.of("status","ACTIVE","version",version,"schemaEvidence",config.getOrDefault("sourceSchemaEvidence",Map.of()));
    } catch(Exception failure){throw new IllegalStateException("R2F_PUBLICATION_FAILED",failure);}
  }
  private void activate(String source,UUID version,long lock){service.submit(source,version,lock,actor(),"r2f");var c=service.createChallenge(source,version,actor(),"r2f");service.confirm(source,version,(UUID)c.get("challenge_id"),actor(),"r2f","fixture:mfa");service.attestIngestionCompatibility(source,version,true,"R2f approved fixture profile",new OnboardingService.Actor("fixture-ingestion","SERVICE",Set.of("ouf.ingestion.configuration.attest")),"r2f");service.activate(source,version,actor(),"r2f");}
  private static String iri(String name){return "https://example.org/"+name;}
  private static OnboardingService.Actor actor(){return new OnboardingService.Actor("fixture-human","HUMAN_USER");}
  private static void human(String auth){String expected=System.getenv("OUF_PAIRWISE_HUMAN_TOKEN");if(expected==null||!java.security.MessageDigest.isEqual(("Bearer "+expected).getBytes(java.nio.charset.StandardCharsets.UTF_8),auth.getBytes(java.nio.charset.StandardCharsets.UTF_8)))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);}
  @SuppressWarnings("unchecked") private static Map<String,Object> binding()throws Exception{return (Map<String,Object>)new com.fasterxml.jackson.databind.ObjectMapper().readValue(Files.readString(Path.of(System.getenv("OUF_R2C_SEMANTIC_RESULT"))),Map.class).get("binding");}
  @SuppressWarnings("unchecked") private static Map<String,Object> copy(Map<String,Object> parent,String key){return new LinkedHashMap<>((Map<String,Object>)parent.get(key));}
}
