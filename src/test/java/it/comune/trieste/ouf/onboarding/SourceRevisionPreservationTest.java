package it.comune.trieste.ouf.onboarding;
import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.onboarding.application.SourceRevisionService;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceRevisionPreservationTest {
  @Test @SuppressWarnings("unchecked") void preservesAllUnselectedFieldsAndNeverMutatesItsInput(){
    var config=SourceRevisionRuntimeTest.fixture();var rule=SourceRevisionRuntimeTest.rule();
    var changed=SourceRevisionService.revise(config,List.of(new SourceRevisionService.Change("urn:name","urn:name",rule)));
    var ex=(Map<String,Object>)changed.get("extractionProfile");var udp=(Map<String,Object>)((Map<String,Object>)ex.get("runtime")).get("udp");
    var property=(Map<String,Object>)((List<?>)((Map<?,?>)udp.get("materialization")).get("properties")).getFirst();
    assertThat(property).containsEntry("reconciliation",rule).containsEntry("datatype","http://www.w3.org/2001/XMLSchema#string").containsEntry("authorityOrder",List.of("s")).containsEntry("accessLabel","RESTRICTED");
    property.put("reconciliation",null);assertThat(changed).isEqualTo(config);
  }
  @Test void absentDuplicateAndNoOpTargetsCannotCreateImplicitRules(){
    var config=SourceRevisionRuntimeTest.fixture();var change=new SourceRevisionService.Change("urn:name","urn:name",SourceRevisionRuntimeTest.rule());
    assertThatThrownBy(()->SourceRevisionService.revise(config,List.of(new SourceRevisionService.Change("urn:unknown","urn:name",SourceRevisionRuntimeTest.rule())))).hasMessage("ONB_REVISION_PROPERTY_NOT_UNIQUE");
    assertThatThrownBy(()->SourceRevisionService.revise(config,List.of(change,change))).hasMessage("ONB_REVISION_PROPERTY_INVALID");
    var once=SourceRevisionService.revise(config,List.of(change));assertThatThrownBy(()->SourceRevisionService.revise(once,List.of(change))).hasMessage("ONB_REVISION_NO_CHANGE");
  }
}
