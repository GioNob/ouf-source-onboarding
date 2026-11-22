package it.comune.trieste.ouf.onboarding.application;

import java.net.URI;
import java.util.*;
import java.util.function.Function;

/** Walks the mapping, and, at preparation, the matching published definition in lockstep. */
final class ValueCompositions {
  static final Set<String> LABELS=Set.of("OPEN","ANONYMOUS","PERSONAL","SENSITIVE","RESTRICTED");
  record Output(String type,boolean many){
    String datatype(){
      if(many)return "list";
      if(ValueReader.DATATYPES.containsKey(type))return ValueReader.DATATYPES.get(type);
      return type.substring(0,type.lastIndexOf('@'));
    }
  }
  final Map<String,String> datatypes=new LinkedHashMap<>();
  final Map<String,String> labels=new LinkedHashMap<>();
  final Set<String> typeRefs=new HashSet<>();
  final Set<String> used=new HashSet<>();
  private final Map<String,String> fields;
  private final Function<String,Map<?,?>> definition;
  private int leaves;

  ValueCompositions(Object raw,Map<String,String> fields,Function<String,Map<?,?>> definition){
    this.fields=fields;this.definition=definition;
    for(Object item:list(raw,0,ManagedFileLimits.MAX_COLUMNS)){
      Map<?,?> row=map(item);
      String target=iri(text(row,"targetPropertyIri")),label=text(row,"dataAccessLabel");
      require(LABELS.contains(label)&&labels.put(target,label)==null);
      var expression=new LinkedHashMap<>(row);
      expression.remove("targetPropertyIri");expression.remove("dataAccessLabel");expression.remove("kind");
      leaves=0;
      Output output=switch(text(row,"kind")){
        case "CONSTRUCTOR" -> constructor(expression);
        case "STRUCTURED" -> structured(expression,new HashSet<>());
        default -> throw invalid();
      };
      datatypes.put(target,output.datatype());
    }
  }
  private Output reader(Map<?,?> input){
    String field=text(input,"fieldName");
    ValueReader rule=ValueReader.named(text(input,"reader"));
    require(fields.containsKey(field));
    String profile=fields.get(field);
    require(profile==null||rule.supports(profile));
    rule.parameters(input.get("transformParams"),null);
    used.add(field);
    return new Output(rule.output,false);
  }
  private Output constructor(Map<?,?> expression){
    keys(expression,Set.of("constructorId","inputs"),Set.of("params"));
    String id=text(expression,"constructorId");
    Map<?,?> params=expression.containsKey("params")?map(expression.get("params")):Map.of();
    List<?> inputs=list(expression.get("inputs"),1,8);
    List<String> roles=new ArrayList<>();
    String expected=switch(id){
      case "POINT_FROM_LAT_LON_V1" -> "decimal";
      case "TEXT_JOIN_V1","LIST_SPLIT_V1" -> "string";
      default -> throw invalid();
    };
    for(Object raw:inputs){
      var input=map(raw);keys(input,Set.of("role","reader","fieldName"),Set.of("transformParams"));
      require(expected.equals(reader(input).type()));roles.add(text(input,"role"));
    }
    if(id.equals("POINT_FROM_LAT_LON_V1")){
      require(roles.size()==2 && roles.containsAll(List.of("lat","lon")) && params.isEmpty());
      return new Output("geometry",false);
    }
    String separator=textAllowSpace(params,"separator");
    int length=separator.codePointCount(0,separator.length());
    require(length>=1&&length<=8);
    if(id.equals("TEXT_JOIN_V1")){
      require(params.size()==1 && roles.size()>=2 && roles.stream().allMatch("part"::equals));
      return new Output("string",false);
    }
    require(params.size()==2 && roles.equals(List.of("value")));
    var itemReader=ValueReader.named(text(params,"itemReader"));
    itemReader.parameters(null,null);
    return new Output(itemReader.output,true);
  }
  private Output structured(Map<?,?> expression,Set<String> path){
    keys(expression,Set.of("typeRef","components"),Set.of());
    String ref=text(expression,"typeRef");
    int at=ref.lastIndexOf('@');
    require(at>0 && at==ref.indexOf('@') && ref.substring(at+1).matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"));
    iri(ref.substring(0,at));
    String id=ref.substring(0,at);
    require(path.size()<4 && path.add(id));
    typeRefs.add(ref);
    var expected=definition==null?Map.<String,Map<?,?>>of():componentSpecs(definition.apply(ref));
    var names=new HashSet<String>();
    for(Object raw:list(expression.get("components"),1,32)){
      var component=map(raw);String name=text(component,"component");
      require(name.matches("[a-z][A-Za-z0-9]{0,63}") && names.add(name));
      Output value;
      if(component.containsKey("structured")){
        keys(component,Set.of("component","structured"),Set.of());
        value=structured(map(component.get("structured")),path);
      }else{
        require(++leaves<=256);
        if(component.containsKey("constructor")){
          keys(component,Set.of("component","constructor"),Set.of());
          value=constructor(map(component.get("constructor")));
        }else{
          keys(component,Set.of("component","fieldName","reader"),Set.of("transformParams"));
          value=reader(component);
        }
      }
      if(definition!=null){
        var spec=expected.get(name);
        require(spec!=null && value.type().equals(spec.get("type"))
            && value.many()=="MANY".equals(spec.get("cardinality")));
      }
    }
    require(definition==null||expected.keySet().equals(names));
    path.remove(id);
    return new Output(ref,false);
  }
  static Map<String,Map<?,?>> componentSpecs(Map<?,?> definition){
    Map<String,Map<?,?>> expected=new HashMap<>();
    for(Object raw:list(definition.get("components"),1,32)){
      var component=map(raw);
      keys(component,Set.of("name","type","cardinality","required"),Set.of("labels","constraints"));
      String name=text(component,"name"),type=text(component,"type");
      require(name.matches("[a-z][A-Za-z0-9]{0,63}") && expected.put(name,component)==null
          && component.get("required") instanceof Boolean && Set.of("ONE","MANY").contains(text(component,"cardinality")));
      if(component.containsKey("constraints")){
        Set<String> allowed=switch(type){
          case "decimal","integer","date","datetime" -> Set.of("minInclusive","maxInclusive");
          case "string" -> Set.of("maxLength");case "concept" -> Set.of("vocabularyRef");default -> Set.of();
        };
        require(allowed.containsAll(map(component.get("constraints")).keySet()));
      }
    }
    return expected;
  }
  static String iri(String text){
    try{require(URI.create(text).isAbsolute());}catch(IllegalArgumentException e){throw invalid();}
    return text;
  }
  static Map<?,?> map(Object raw){if(raw instanceof Map<?,?> m)return m;throw invalid();}
  static List<?> list(Object raw,int min,int max){
    if(raw instanceof List<?> items && items.size()>=min && items.size()<=max)return items;throw invalid();
  }
  static String text(Map<?,?> map,String key){String s=textAllowSpace(map,key);require(!s.isBlank());return s;}
  private static String textAllowSpace(Map<?,?> map,String key){if(map.get(key) instanceof String s)return s;throw invalid();}
  private static void keys(Map<?,?> map,Set<String> required,Set<String> optional){
    require(map.keySet().containsAll(required)&&map.keySet().stream().allMatch(k->required.contains(k)||optional.contains(k)));
  }
  static void require(boolean ok){if(!ok)throw invalid();}
  private static IllegalArgumentException invalid(){return new IllegalArgumentException("ONB_COMPOSITION_INVALID");}
}
