package it.comune.trieste.ouf.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ManagedCsvRevisionTest {
  private final Map<String,String> mappings=Map.of("id","IDENTITY","price","DECIMAL_DOT_V1");
  private final Map<String,String> schema=Map.of("id","STRING","price","DECIMAL","notes","STRING");

  @Test void checksOnlyIncludedTypesAndKeepsTheApprovedNativeKey(){
    ManagedCsvRevision.checkColumns(schema,Map.of("notes","LONG","price","DECIMAL","id","STRING"),mappings,"id",List.of("id"));
    assertThatThrownBy(()->ManagedCsvRevision.checkColumns(schema,schema,mappings,"id",List.of("notes")))
        .isInstanceOfSatisfying(ManagedCsvRevision.Incompatible.class,e->assertThat(e.diagnostics())
            .containsExactly(new ManagedCsvRevision.Finding("KEY_NOT_UNIQUE_OR_NULL","id",null,null)));
  }
  @Test void reportsAddedMissingAndDegradedColumnsTogether(){
    var next=Map.of("price","STRING","other","DATE");
    assertThatThrownBy(()->ManagedCsvRevision.checkColumns(schema,next,mappings,"id",List.of()))
        .isInstanceOfSatisfying(ManagedCsvRevision.Incompatible.class,e->assertThat(e.diagnostics()).containsExactlyInAnyOrder(
            new ManagedCsvRevision.Finding("MISSING_MAPPED_COLUMN","id","STRING",null),
            new ManagedCsvRevision.Finding("MISSING_COLUMN","notes","STRING",null),
            new ManagedCsvRevision.Finding("TYPE_CHANGED","price","DECIMAL","STRING"),
            new ManagedCsvRevision.Finding("NEW_COLUMN","other",null,"DATE")));
  }
  @Test void matchingInferredTypesStillHaveToSupportTheActiveTransform(){
    var lexical=Map.of("id","STRING","price","STRING");
    assertThatThrownBy(()->ManagedCsvRevision.checkColumns(lexical,lexical,mappings,"id",List.of("id")))
        .isInstanceOfSatisfying(ManagedCsvRevision.Incompatible.class,e->assertThat(e.diagnostics())
            .containsExactly(new ManagedCsvRevision.Finding("TRANSFORM_TYPE_MISMATCH","price","DECIMAL_DOT_V1","STRING")));
  }
}
