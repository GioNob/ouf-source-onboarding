package it.comune.trieste.ouf.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ManagedCsvRevisionTest {
  private final Map<String,String> mappings=Map.of("id","IDENTITY","price","DECIMAL_DOT_V1");
  private final Map<String,String> schema=Map.of("id","STRING","price","DECIMAL","notes","STRING");

  @Test void checksOnlyIncludedTypesAndKeepsTheApprovedNativeKey(){
    ManagedCsvRevision.checkColumns(schema,Map.of("notes","LONG","price","DECIMAL","id","STRING"),mappings,"id",List.of("id"),List.of());
    assertThatThrownBy(()->ManagedCsvRevision.checkColumns(schema,schema,mappings,"id",List.of("notes"),List.of()))
        .isInstanceOfSatisfying(ManagedCsvRevision.Incompatible.class,e->assertThat(e.diagnostics())
            .containsExactly(new ManagedCsvRevision.Finding("KEY_NOT_UNIQUE_OR_NULL","id",null,null)));
  }
  @Test void reportsAddedMissingAndDegradedColumnsTogether(){
    var next=Map.of("price","STRING","other","DATE");
    assertThatThrownBy(()->ManagedCsvRevision.checkColumns(schema,next,mappings,"id",List.of(),List.of()))
        .isInstanceOfSatisfying(ManagedCsvRevision.Incompatible.class,e->assertThat(e.diagnostics()).containsExactlyInAnyOrder(
            new ManagedCsvRevision.Finding("MISSING_MAPPED_COLUMN","id","STRING",null),
            new ManagedCsvRevision.Finding("MISSING_COLUMN","notes","STRING",null),
            new ManagedCsvRevision.Finding("TYPE_CHANGED","price","DECIMAL","STRING"),
            new ManagedCsvRevision.Finding("NEW_COLUMN_DECISION_REQUIRED","other",null,"DATE")));
  }
  @Test void matchingInferredTypesStillHaveToSupportTheActiveTransform(){
    var lexical=Map.of("id","STRING","price","STRING");
    assertThatThrownBy(()->ManagedCsvRevision.checkColumns(lexical,lexical,mappings,"id",List.of("id"),List.of()))
        .isInstanceOfSatisfying(ManagedCsvRevision.Incompatible.class,e->assertThat(e.diagnostics())
            .containsExactly(new ManagedCsvRevision.Finding("TRANSFORM_TYPE_MISMATCH","price","DECIMAL_DOT_V1","STRING")));
  }
  @Test void everyDecisionMustAddressExactlyOneNewColumn(){
    var next=new LinkedHashMap<>(schema);
    next.put("extra","STRING");
    var exclude=new ManagedCsvRevision.NewColumn("extra","EXCLUDE",null);
    var other=new ManagedCsvRevision.NewColumn("id","EXCLUDE",null);
    assertThatThrownBy(()->ManagedCsvRevision.checkColumns(schema,next,mappings,"id",List.of("id"),List.of(exclude,other,exclude)))
        .isInstanceOfSatisfying(ManagedCsvRevision.Incompatible.class,e->assertThat(e.diagnostics())
            .extracting(ManagedCsvRevision.Finding::code).containsExactly("COLUMN_NOT_NEW","DUPLICATE_NEW_COLUMN_DECISION"));
    var mapping=new ManagedCsvRevision.Mapping("urn:extra","DATE_ISO_V1",Map.of("cardinality","SINGLE"),null,null,null);
    for(var decision:List.of(new ManagedCsvRevision.NewColumn("extra","EXCLUDE",mapping),
        new ManagedCsvRevision.NewColumn("extra","MAP",null),new ManagedCsvRevision.NewColumn("extra","AUTO",null))){
      assertThatThrownBy(()->ManagedCsvRevision.checkColumns(schema,next,mappings,"id",List.of("id"),List.of(decision)))
          .isInstanceOfSatisfying(ManagedCsvRevision.Incompatible.class,e->assertThat(e.diagnostics())
              .extracting(ManagedCsvRevision.Finding::code).containsExactly("INVALID_NEW_COLUMN_DECISION"));
    }
    var map=new ManagedCsvRevision.NewColumn("extra","MAP",mapping);
    assertThatThrownBy(()->ManagedCsvRevision.checkColumns(schema,next,mappings,"id",List.of("id"),List.of(map)))
        .isInstanceOfSatisfying(ManagedCsvRevision.Incompatible.class,e->assertThat(e.diagnostics())
            .containsExactly(new ManagedCsvRevision.Finding("TRANSFORM_TYPE_MISMATCH","extra","DATE_ISO_V1","STRING")));
  }
}
