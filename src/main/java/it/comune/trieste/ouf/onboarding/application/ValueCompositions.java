package it.comune.trieste.ouf.onboarding.application;

import java.net.URI;
import java.util.*;
import java.util.function.Function;

/** Walks the mapping, and, at preparation, the matching published definition in lockstep. */
final class ValueCompositions {
  static final Set<String> LABELS=Set.of("OPEN","ANONYMOUS","PERSONAL","SENSITIVE","RESTRICTED");
  record Output(String type,boolean many,String valueMapRef){
    Output(String type,boolean many){this(type,many,null);}
    String datatype(){
      if(many)return "list";
      if(ValueReader.DATATYPES.containsKey(type))return ValueReader.DATATYPES.get(type);
      return type.substring(0,type.lastIndexOf('@'));
    }
  }
  final Map<String,String> datatypes=new LinkedHashMap<>();
  final Map<String,String> labels=new LinkedHashMap<>();
  final Set<String> typeRefs=new HashSet<>();
  final Set<String> valueMapRefs=new LinkedHashSet<>();
  final Set<String> used=new HashSet<>();
  private final Map<String,String> fields;
  private final Function<String,Map<?,?>> definition;
  private final Function<String,String> valueMapVocabulary;
  private int leaves;

  ValueCompositions(Object raw,Map<String,String> fields,Function<String,Map<?,?>> definition){
    this(raw,fields,definition,null);
  }

  ValueCompositions(Object raw,Map<String,String> fields,Function<String,Map<?,?>> definition,
      Function<String,String> valueMapVocabulary){
    this.fields=fields;this.definition=definition;this.valueMapVocabulary=valueMapVocabulary;
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
    used.add(field);
    return read(rule,input.get("transformParams"),false);
  }
  private Output read(ValueReader reader,Object params,boolean many){
    var checked=reader.parameters(params,null);
    String ref=(String)checked.get("valueMapRef");
    if(ref!=null)valueMapRefs.add(ref);
    return new Output(reader.output,many,ref);
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
    keys(params,Set.of("separator","itemReader"),Set.of("itemReaderParams"));
    require(roles.equals(List.of("value")));
    var itemReader=ValueReader.named(text(params,"itemReader"));
    return read(itemReader,params.containsKey("itemReaderParams")?map(params.get("itemReaderParams")):null,true);
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
      var spec=expected.get(name);
      require(definition==null || spec!=null);
      var builder=new LinkedHashMap<>(component);
      builder.remove("component");
      if(builder.containsKey("items")){
        keys(builder,Set.of("items"),Set.of());
        require(spec==null || "MANY".equals(spec.get("cardinality")));
        // Count leaves in the type, independently of its number of values.
        int before=leaves,expanded=before;
        for(Object item:list(builder.get("items"),0,Integer.MAX_VALUE)){
          leaves=before;
          Output value=singleBuilder(map(item),path);
          require(!value.many());
          checkComponent(value,spec,false);
          expanded=Math.max(expanded,leaves);
        }
        leaves=expanded;
      }else{
        Output value=singleBuilder(builder,path);
        checkComponent(value,spec,spec!=null && "MANY".equals(spec.get("cardinality")));
      }
    }
    require(definition==null||expected.keySet().equals(names));
    path.remove(id);
    return new Output(ref,false);
  }
  private void checkComponent(Output value,Map<?,?> spec,boolean many){
    if(spec==null)return;
    require(value.type().equals(spec.get("type")) && value.many()==many);
    Object vocabulary=ManagedReviewSnapshot.child(spec,"constraints").get("vocabularyRef");
    if(vocabulary!=null && value.valueMapRef()!=null && valueMapVocabulary!=null)
      require(vocabulary.equals(valueMapVocabulary.apply(value.valueMapRef())));
  }
  private Output singleBuilder(Map<?,?> builder,Set<String> path){
    if(builder.containsKey("structured")){
      keys(builder,Set.of("structured"),Set.of());
      return structured(map(builder.get("structured")),path);
    }
    require(++leaves<=256);
    if(builder.containsKey("constructor")){
      keys(builder,Set.of("constructor"),Set.of());
      return constructor(map(builder.get("constructor")));
    }
    keys(builder,Set.of("fieldName","reader"),Set.of("transformParams"));
    return reader(builder);
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
        var constraints=map(component.get("constraints"));
        require(allowed.containsAll(constraints.keySet()));
        for(var entry:constraints.entrySet()){
          Object bound=entry.getValue();
          switch(entry.getKey().toString()){
            case "maxLength" -> require(bound instanceof Number && bound.toString().matches("0|[1-9][0-9]*"));
            case "vocabularyRef" -> iri(text(constraints,"vocabularyRef"));
            default -> require(bound instanceof String value && canonicalBound(type,value));
          }
        }
      }
    }
    return expected;
  }
  private static boolean canonicalBound(String type,String value){
    try{
      return switch(type){
        case "integer" -> value.matches("0|-?[1-9][0-9]*");
        case "decimal" -> !value.equals("-0") && value.matches("-?(0|[1-9][0-9]*)([.][0-9]*[1-9])?");
        case "date" -> value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") && java.time.LocalDate.parse(value).toString().equals(value);
        case "datetime" -> {
          java.time.Instant.parse(value);
          yield value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]([.][0-9]{1,9})?Z");
        }
        default -> false;
      };
    }catch(java.time.DateTimeException invalid){return false;}
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
  static void keys(Map<?,?> map,Set<String> required,Set<String> optional){
    require(map.keySet().containsAll(required)&&map.keySet().stream().allMatch(k->required.contains(k)||optional.contains(k)));
  }
  static void require(boolean ok){if(!ok)throw invalid();}
  private static ReviewValidationFailure invalid(){return new ReviewValidationFailure("ONB_COMPOSITION_INVALID");}
}
