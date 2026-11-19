package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.onboarding.application.ManagedFileProfiler;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ManagedWideProfileTest {
  static byte[] csv(int columns,int rows,String value) {
    String header=IntStream.range(0,columns).mapToObj(i->"campo_"+i).collect(java.util.stream.Collectors.joining(";"));
    String row=IntStream.range(0,columns).mapToObj(i->value).collect(java.util.stream.Collectors.joining(";"));
    return (header+"\n"+(row+"\n").repeat(rows)).getBytes(StandardCharsets.UTF_8);
  }
  @ParameterizedTest @ValueSource(ints={20,50,100,200,256,300,400,512,1024})
  void allColumnsIncludingLastSurviveProfiling(int count) {
    var p=new ManagedFileProfiler().profile(csv(count,3,"value"),"text/csv");
    assertThat(p.columns()).hasSize(count);
    assertThat(p.columns().getLast().name()).isEqualTo("campo_"+(count-1));
    assertThat(p.metadata()).containsEntry("columns",count).containsEntry("rows",3);
    assertThat(p.sample()).hasSize(3);
    assertThat(p.sample().getFirst()).hasSize(count).containsEntry("campo_"+(count-1),"value");
  }
  @Test void overflowAndSilentExtraColumnsAreRejected() {
    var p=new ManagedFileProfiler();
    assertThatThrownBy(()->p.profile(csv(1025,1,"x"),"text/csv")).hasMessage("column limit exceeded");
    assertThatThrownBy(()->p.profile("a;b\n1;2;lost\n".getBytes(StandardCharsets.UTF_8),"text/csv")).hasMessage("CSV row exceeds declared headers");
    assertThatThrownBy(()->p.profile("a;a\n1;2\n".getBytes(StandardCharsets.UTF_8),"text/csv")).hasMessage("headers must be non-empty and unique");
  }
  @Test void rowAndByteBoundsAreNotAccidentallyExpanded() {
    var p=new ManagedFileProfiler();
    assertThatThrownBy(()->p.profile(csv(1,10001,"x"),"text/csv")).hasMessage("row limit exceeded");
    assertThatThrownBy(()->p.profile(new byte[10*1024*1024+1],"text/csv")).hasMessage("file size outside profiling limits");
  }
  @Test void sparseWorstShapeFitsExistingAttemptTimeInThisRunner() {
    byte[] input=csv(1024,10000,"");
    assertThat(input.length).isLessThanOrEqualTo(10*1024*1024);
    long start=System.nanoTime();
    var p=new ManagedFileProfiler().profile(input,"text/csv");
    long elapsed=(System.nanoTime()-start)/1_000_000;
    assertThat(p.columns()).hasSize(1024);assertThat(p.metadata()).containsEntry("rows",10000);
    assertThat(elapsed).isLessThan(60000);
    System.out.println("OUF_WIDE_PROFILE_PROBE={\"columns\":1024,\"rows\":10000,\"inputBytes\":"+input.length+",\"elapsedMs\":"+elapsed+",\"maxHeapBytes\":"+Runtime.getRuntime().maxMemory()+",\"nativeJvm\":true,\"syntheticInput\":true}");
  }

  @Test void denseHighCardinalityStaysWithinTheSameHeapAndKeepsExactCounts() {
    StringBuilder csv=new StringBuilder(10*1024*1024);
    for(int c=0;c<1024;c++)csv.append(c==0?"":";").append("campo_").append(c);
    csv.append('\n');
    for(int r=0;r<2000;r++){
      for(int c=0;c<1024;c++)csv.append(c==0?"":";").append(r);
      csv.append('\n');
    }
    byte[] bytes=csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    csv=null;
    assertThat(bytes.length).isLessThanOrEqualTo(10*1024*1024);
    long start=System.nanoTime();
    var result=new ManagedFileProfiler().profile(bytes,"text/csv");
    assertThat(result.columns()).hasSize(1024).allSatisfy(c->assertThat(c.distinctValues()).isEqualTo(2000));
    assertThat(result.candidateKeys()).hasSize(1024);
    long elapsed=(System.nanoTime()-start)/1_000_000;
    assertThat(elapsed).isLessThan(60_000);
    System.out.println("OUF_DENSE_PROFILE_PROBE={\"columns\":1024,\"rows\":2000,\"inputBytes\":"+bytes.length+",\"elapsedMs\":"+elapsed+",\"maxHeapBytes\":"+Runtime.getRuntime().maxMemory()+"}");
  }
  @Test void streamingRetainsExactTypesDistinctCountsAndLexicalSamples() {
    var p=new ManagedFileProfiler().profile("id;amount;optional;note\n1;1.5;; alpha \n2;2;true;beta\n3;3.5;false;alpha\n".getBytes(StandardCharsets.UTF_8),"text/csv");
    assertThat(p.columns()).containsExactly(
        new ManagedFileProfiler.ColumnProfile("id","LONG",false,3),
        new ManagedFileProfiler.ColumnProfile("amount","DECIMAL",false,3),
        new ManagedFileProfiler.ColumnProfile("optional","BOOLEAN",true,2),
        new ManagedFileProfiler.ColumnProfile("note","STRING",false,2));
    assertThat(p.candidateKeys()).containsExactly("id","amount");
    assertThat(p.sample().getFirst()).containsEntry("note"," alpha ");
  }
}
