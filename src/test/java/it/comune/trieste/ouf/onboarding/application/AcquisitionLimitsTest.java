package it.comune.trieste.ouf.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.onboarding.domain.DomainFailure;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpStatus;

class AcquisitionLimitsTest {
  static final AcquisitionLimits DEFAULTS=new AcquisitionLimits(1_000_000,1024,1_000_000,25,100,8,1024);
  private final ManagedFileProfiler profiler=new ManagedFileProfiler();

  private Map<String,Object> csv(String text) {
    var profile=profiler.profile(text.getBytes(StandardCharsets.UTF_8),"text/csv");
    return Map.of("metadata",profile.metadata(),"inferred_schema",profile.columns());
  }

  @Test void csvMeasuresRawUtf16LikeIngestion() {
    // ManagedTabularAdapter.csvRecord passes the RFC 4180 decoded value to checked(String, Limits).
    // Spaces, embedded CRLF, the decoded quote and both UTF-16 units of the non-BMP character count.
    var profile=csv("\uFEFFlong_header_excluded;id\r\n\"  😀\"\";\r\nx \";1\r\n\r\n");
    assertThat(profile.get("metadata")).isEqualTo(Map.of("delimiter",";","encoding","UTF-8","headerRow",1,
        "rows",2,"columns",2,"maxCellChars","  😀\";\r\nx ".length()));
  }

  @Test void headerOnlyAndEmptyDataCellsAdmitOneWithZeroHeadroom() {
    var limits=new AcquisitionLimits(100,10,100,25,0,0,0);
    var header=csv("id,note\r\n");
    assertThat(limits.resolve(header,null)).isEqualTo(new ManagedFileExecutionProposal.Limits(1,3,1));
    assertThat(limits.resolve(header,new ManagedFileExecutionProposal.Limits(1,null,null)).maxRows()).isEqualTo(1);
    var empty=csv("id,note\r\n,\"\"\r\n");
    assertThat(limits.resolve(empty,new ManagedFileExecutionProposal.Limits(null,null,1)))
        .isEqualTo(new ManagedFileExecutionProposal.Limits(2,3,1));
  }

  @Test void ceilingRoundsUpUsesHeadroomAndCapsAtInstallation() {
    var profile=Map.of("metadata",Map.of("rows",801,"maxCellChars",999_999),"inferred_schema",List.of("a","b"));
    assertThat(DEFAULTS.resolve(profile,null)).isEqualTo(new ManagedFileExecutionProposal.Limits(1002,10,1_000_000));
    assertThat(DEFAULTS.resolve(profile,new ManagedFileExecutionProposal.Limits(900,10000,999_999)))
        .isEqualTo(new ManagedFileExecutionProposal.Limits(900,10,999_999));
    assertThat(DEFAULTS.resolve(profile,new ManagedFileExecutionProposal.Limits(801,null,1_000_000)))
        .isEqualTo(new ManagedFileExecutionProposal.Limits(801,10,1_000_000));
    var legacy=Map.of("metadata",Map.of("rows",1),"inferred_schema",List.of("a"));
    assertThat(DEFAULTS.resolve(legacy,null)).isEqualTo(new ManagedFileExecutionProposal.Limits(101,9,1_000_000));
    assertThat(DEFAULTS.resolve(legacy,new ManagedFileExecutionProposal.Limits(null,null,1)).maxCellChars()).isOne();
  }

  @Test void typedFailuresChooseFirstViolatedDimension() {
    var profile=csv("a,b\n12,3\n23,4\n");
    checkFailure(()->DEFAULTS.resolve(profile,new ManagedFileExecutionProposal.Limits(1,1,1)),
        "BELOW_PROFILE","maxRows","ceiling",102);
    checkFailure(()->DEFAULTS.resolve(profile,new ManagedFileExecutionProposal.Limits(null,1,1)),
        "BELOW_PROFILE","maxColumns","ceiling",10);
    checkFailure(()->DEFAULTS.resolve(profile,new ManagedFileExecutionProposal.Limits(null,null,1)),
        "BELOW_PROFILE","maxCellChars","ceiling",1026);
    checkFailure(()->new AcquisitionLimits(1,1,1,0,0,0,0).resolve(profile,null),
        "INSTALLATION_EXCEEDED","maxRows","installationMaximum",1);
  }

  private static void checkFailure(Runnable action,String suffix,String limit,String bound,int value) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainFailure.class,error->{
      assertThat(error.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
      assertThat(error.code()).isEqualTo("ONB_ACQUISITION_LIMIT_"+suffix);
      assertThat(error.properties()).isEqualTo(Map.of("limit",limit,"observed",2,bound,value));
    });
  }

  @Test void frozenSnapshotPreservesEffectiveTargetsAndExplainsOrigins() {
    var effective=Map.of("maxRows",101,"maxColumns",2,"maxCellChars",999);
    var config=Map.of("extractionProfile",Map.of("runtime",Map.of("execution",effective)));
    var profile=Map.of("metadata",Map.of("rows",1),"inferred_schema",List.of(Map.of("name","a"),Map.of("name","b")));
    var installation=new AcquisitionLimits(1000,1024,500,25,100,8,1024);
    var snapshot=ManagedReviewSnapshot.build(config,Map.of(),profile,"owner","source",installation);
    assertThat(snapshot.get("LIMITS")).isEqualTo(effective);
    var basis=ManagedReviewSnapshot.child(snapshot,"LIMITS_BASIS");
    assertThat(basis.get("maxRows")).isEqualTo(Map.of("effective",101,"observed",1,"ceiling",101,
        "installationMaximum",1000,"minHeadroom",100,"marginPercent",25,"origin","DERIVED"));
    assertThat(ManagedReviewSnapshot.child(basis,"maxColumns").get("origin")).isEqualTo("RESTRICTED");
    var cells=ManagedReviewSnapshot.child(basis,"maxCellChars");
    assertThat(cells.get("ceiling")).isEqualTo(500);
    assertThat(cells.get("origin")).isEqualTo("ABOVE_CEILING");
    assertThat(cells.containsKey("observed")).isTrue();
    assertThat(cells.get("observed")).isNull();
  }

  @TestConfiguration(proxyBeanMethods=false)
  @EnableConfigurationProperties(AcquisitionLimits.class)
  static class Settings {}

  @ParameterizedTest
  @CsvSource({"max-rows,0","max-columns,10001","max-cell-chars,1000001","margin-percent,1001",
      "min-headroom-rows,-1","min-headroom-columns,10001","min-headroom-cell-chars,1000001","max-rows,abc"})
  void invalidPropertyStopsContextAndNamesSetting(String property,String invalid) {
    String prefix="ouf.onboarding.acquisition-limits.";
    new ApplicationContextRunner().withUserConfiguration(Settings.class)
        .withPropertyValues(prefix+"max-rows=1000",prefix+"max-columns=100",prefix+"max-cell-chars=100",
            prefix+property+"="+invalid).run(context->{
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure()).hasStackTraceContaining(prefix+property);
        });
  }
}
