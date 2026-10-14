package it.comune.trieste.ouf.onboarding.application;

import java.net.URI;
import java.util.*;

/** Pure preparation of a lexical CSV execution profile. It issues no approval or runtime authority. */
public final class ManagedFileExecutionProposal {
  private static final String STRING="http://www.w3.org/2001/XMLSchema#string";
  public record SemanticBinding(String semanticId,String semanticVersion,String revisionId,String publicationSetId) {}
  public record Limits(int maxRows,int maxColumns,int maxCellChars) {}
  public record Proposal(List<SemanticBinding> semanticBindings,String semanticPublicationSetRef,
                         Map<String,Object> udp,Limits limits) {}
  private ManagedFileExecutionProposal() {}

  public static Map<String,Object> compile(Map<String,Object> existing,Map<String,Object> asset,
                                           String tenant,Proposal proposal) {
    require(existing!=null && asset!=null && proposal!=null && tenant!=null && !tenant.isBlank());
    Map<String,Object> config=copy(existing),extraction=object(config,"extractionProfile"),runtime=object(extraction,"runtime");
    Map<String,Object> semantic=object(config,"semanticMapping"),source=object(semantic,"sourceType"),bundle=object(config,"bundle");
    String sourceId=text(source,"sourceId"),assetId=text(runtime,"assetId"),profileId=text(runtime,"fileProfileId");
    require("FILE".equals(source.get("typeCode")) && sourceId.equals(extraction.get("sourceId"))
        && sourceId.equals(object(bundle,"source").get("sourceId"))
        && "INTERNAL_MANAGED".equals(object(bundle,"source").get("sourceKind"))
        && "MANAGED".equals(object(bundle,"source").get("acquisitionMode"))
        && "MANAGED".equals(runtime.get("mode")) && "ONE_ROW_ONE_SOURCE_OBJECT".equals(runtime.get("recordModel")));
    require(!runtime.containsKey("format") && !runtime.containsKey("layer")
        && Set.of(",",";").contains(text(runtime,"csvDelimiter")));
    require(assetId.equals(String.valueOf(asset.get("asset_id")))
        && sourceId.equals(asset.get("source_id"))
        && text(runtime,"stagingRef").equals(asset.get("staging_ref"))
        && text(runtime,"contentHash").equals(asset.get("content_hash"))
        && assetId.equals(object(extraction,"selection").get("assetId"))
        && profileId.equals(object(extraction,"selection").get("fileProfileId")));
    require(text(bundle,"schemaRef").equals("profile://managed-files/"+assetId+"/profiles/"+profileId));
    long size=integer(asset.get("size_bytes"),1,10_485_760);
    Limits limits=proposal.limits();require(limits!=null);
    integer(limits.maxRows(),1,1_000_000);integer(limits.maxColumns(),1,10_000);integer(limits.maxCellChars(),1,1_000_000);
    List<?> fields=list(object(extraction,"projection").get("FILE"),1,32);
    require(fields.size()<=limits.maxColumns() && new HashSet<>(fields).size()==fields.size());
    Set<String> mapped=new LinkedHashSet<>(),mappedFields=new LinkedHashSet<>(),classes=new LinkedHashSet<>();
    for(Object raw:list(semantic.get("targetClasses"),1,32))classes.add(iri(text(map(raw),"classIri")));
    for(Object raw:list(semantic.get("propertyMappings"),1,32)){
      Map<String,Object> mapping=map(raw);String field=text(mapping,"sourceField");
      require(fields.contains(field) && mappedFields.add(field) && "IDENTITY".equals(mapping.get("transform"))
          && mapped.add(iri(text(mapping,"targetPropertyIri"))));
    }
    require(mappedFields.equals(new HashSet<>(fields)));
    require(!semantic.containsKey("vocabularyMappings") || list(semantic.get("vocabularyMappings"),0,0).isEmpty());
    require(!config.containsKey("relationshipMappings") || list(config.get("relationshipMappings"),0,0).isEmpty());
    String publication=uuid(proposal.semanticPublicationSetRef());
    require(proposal.semanticBindings()!=null && !proposal.semanticBindings().isEmpty() && proposal.semanticBindings().size()<=32);
    Set<String> refs=new LinkedHashSet<>();List<Map<String,Object>> bindings=new ArrayList<>();
    for(SemanticBinding binding:proposal.semanticBindings()){
      require(binding!=null);String id=atom(binding.semanticId()),version=atom(binding.semanticVersion());
      require(refs.add(id+"@"+version) && publication.equals(uuid(binding.publicationSetId())));
      bindings.add(Map.of("semanticId",id,"semanticVersion",version,"revisionId",uuid(binding.revisionId()),"publicationSetId",publication));
    }
    List<?> expectedRefs=list(semantic.get("semanticRefs"),1,32);
    require(new HashSet<>(expectedRefs).size()==expectedRefs.size() && refs.equals(new HashSet<>(expectedRefs)));
    for(Object raw:list(semantic.get("targetClasses"),1,32)){
      Map<String,Object> target=map(raw);require(refs.contains(text(target,"ontologyId")+"@"+text(target,"ontologyVersion")));
    }
    Map<String,Object> udp=copy(proposal.udp());keys(udp,"resolution","materialization");
    Map<String,Object> resolution=object(udp,"resolution"),policy=object(resolution,"governedIdentity");
    GovernedIdentityProposal.validate(resolution,policy,sourceId,classes,mapped);
    require(tenant.equals(policy.get("tenantId")));
    for(Object raw:list(policy.get("signals"),1,32)){
      Map<String,Object> signal=map(raw);require((text(signal,"id")+"@"+publication).equals(signal.get("semanticRef")));
    }
    Map<String,Object> materialization=object(udp,"materialization");
    keys(materialization,"policyRef","properties","bitemporalProperties","checkpointInterval");
    text(materialization,"policyRef");integer(materialization.get("checkpointInterval"),1,1_000_000);
    list(materialization.get("bitemporalProperties"),0,0);
    Map<String,String> labels=new HashMap<>();
    for(Object raw:list(config.get("dataAccessPolicies"),1,32)){
      Map<String,Object> label=map(raw);require("PROPERTY".equals(label.get("scope")));
      require(labels.put(text(label,"target"),text(label,"label"))==null);
    }
    require(labels.keySet().equals(mapped));Set<String> properties=new HashSet<>();
    for(Object raw:list(materialization.get("properties"),1,32)){
      Map<String,Object> property=map(raw);keys(property,"sourceField","propertyIri","datatype","accessLabel","authorityOrder");
      String iri=text(property,"propertyIri");
      require(mapped.contains(iri) && properties.add(iri) && iri.equals(property.get("sourceField"))
          && STRING.equals(property.get("datatype")) && labels.get(iri).equals(property.get("accessLabel")));
      Set<String> authorities=new HashSet<>();
      for(Object authority:list(property.get("authorityOrder"),0,32))require(authorities.add(string(authority)));
    }
    require(properties.equals(mapped));
    Map<String,Object> execution=new LinkedHashMap<>();
    execution.put("acquisitionMode","INTERNAL_MANAGED_CSV");execution.put("adapterId","managed-tabular-v1");
    execution.put("adapterRuntimeVersion","1.0.0");execution.put("sourceSchemaRef",text(bundle,"schemaRef"));
    execution.put("sourceSchemaId",sourceId);execution.put("sourceSchemaVersion",text(extraction,"version"));
    execution.put("semanticPublicationSetRef",publication);execution.put("adapterProfileRef","adapter://managed-tabular/1");
    execution.put("observationPolicy","ACQUISITION_TIME");execution.put("expectedSize",size);
    execution.put("maxRows",limits.maxRows());execution.put("maxColumns",limits.maxColumns());execution.put("maxCellChars",limits.maxCellChars());
    execution.put("mappingRefs",List.of(text(semantic,"mappingId")));execution.put("authorityPolicyRef",text(materialization,"policyRef"));
    runtime.put("execution",execution);runtime.put("udp",udp);config.put("semanticReferenceBindings",bindings);
    return config;
  }
  private static String atom(String value){require(value!=null && !value.isBlank() && value.length()<=2048 && value.chars().noneMatch(c->Character.isWhitespace(c)||c=='@'));return value;}
  private static String uuid(String value){try{require(value!=null && UUID.fromString(value).toString().equals(value));return value;}catch(RuntimeException e){throw invalid();}}
  private static String iri(String value){try{require(URI.create(value).isAbsolute());return value;}catch(RuntimeException e){throw invalid();}}
  private static String string(Object value){require(value instanceof String && !((String)value).isBlank() && ((String)value).length()<=2048);return (String)value;}
  private static String text(Map<String,Object> value,String key){return string(value.get(key));}
  private static long integer(Object value,long minimum,long maximum){require(value instanceof Number);Number number=(Number)value;long n=number.longValue();require(number.doubleValue()==n && n>=minimum && n<=maximum);return n;}
  private static List<?> list(Object value,int minimum,int maximum){require(value instanceof List<?>);List<?> result=(List<?>)value;require(result.size()>=minimum && result.size()<=maximum);return result;}
  @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){require(value instanceof Map<?,?>);return (Map<String,Object>)value;}
  private static Map<String,Object> object(Map<String,Object> value,String key){return map(value.get(key));}
  private static void keys(Map<String,Object> value,String... keys){require(value.keySet().equals(Set.of(keys)));}
  private static Map<String,Object> copy(Map<String,Object> value){require(value!=null);Map<String,Object> out=new LinkedHashMap<>();value.forEach((key,item)->out.put(key,copyValue(item)));return out;}
  private static Object copyValue(Object value){if(value instanceof Map<?,?>)return copy(map(value));if(value instanceof List<?> items)return new ArrayList<>(items.stream().map(ManagedFileExecutionProposal::copyValue).toList());return value;}
  private static void require(boolean condition){if(!condition)throw invalid();}
  private static IllegalArgumentException invalid(){return new IllegalArgumentException("ONB_MANAGED_EXECUTION_PROPOSAL_INVALID");}
}
