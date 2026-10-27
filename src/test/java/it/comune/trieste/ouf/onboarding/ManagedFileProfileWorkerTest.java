package it.comune.trieste.ouf.onboarding;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.onboarding.application.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ManagedFileProfileWorkerTest {
  final ManagedFileService files=mock(ManagedFileService.class);
  final ManagedFileObjectStore objects=mock(ManagedFileObjectStore.class);
  final UUID job=UUID.randomUUID(),asset=UUID.randomUUID();
  ManagedFileProfileWorker worker(){
    when(files.claimProfile(eq("worker"),any(),any())).thenReturn(Optional.of(Map.of("job_id",job,"asset_id",asset,"staging_ref","object://staging/a.csv","size_bytes",12L)));
    return new ManagedFileProfileWorker(files,objects,"worker",Duration.ofMinutes(2),Duration.ofSeconds(30));
  }
  @Test void readsGovernedReferenceAndCompletesJob(){
    var worker=worker();byte[] content="id,name\n1,A\n".getBytes();when(objects.read("object://staging/a.csv",12L)).thenReturn(content);
    worker.runOnce();verify(files).completeProfile(job,"worker",content,"profile://managed-files/"+asset+"/jobs/"+job+"/redacted-sample");
    verify(files,never()).failProfile(any(),any(),any(),any(),any(),anyBoolean());
  }
  @Test void forbiddenReadTerminatesWithoutSpendingAutomaticRetries(){
    var worker=worker();when(objects.read(anyString(),anyLong())).thenThrow(ManagedFileObjectReadFailure.http(403,null,Instant.now()));
    worker.runOnce();verify(files).failProfile(job,"worker","ONB_FILE_PROFILE_FAILED","Gateway object read failed with status 403",Duration.ofSeconds(30),false);
    verify(files,never()).completeProfile(any(),any(),any(),any());
  }
  @Test void typedTransientReadRespectsRetryAfterWithinExistingBudget(){
    var worker=worker();when(objects.read(anyString(),anyLong())).thenThrow(ManagedFileObjectReadFailure.http(503,"120",Instant.now()));
    worker.runOnce();verify(files).failProfile(job,"worker","ONB_FILE_PROFILE_FAILED","Gateway object read failed with status 503",Duration.ofSeconds(120),true);
  }
  @Test void arbitraryFailureTextCannotAuthorizeARetryOrPersistSecrets(){
    for(String detail:List.of("PRIVATE_CANARY token=PRIVATE","Gateway object read failed with status 503")){
      var decision=ManagedProfileFailurePolicy.classify(new IllegalStateException(detail),Duration.ofSeconds(30));
      assertThat(decision.retryable()).isFalse();
      assertThat(decision.retainedDetail()).isEqualTo("Profile failure requires owner reconciliation");
    }
  }
  @Test void parserValidationStopsWithoutRetryAndPreservesSafeDiagnosis(){
    var decision=ManagedProfileFailurePolicy.classify(new IllegalArgumentException("headers must be non-empty and unique"),Duration.ofSeconds(30));
    assertThat(decision.retryable()).isFalse();
    assertThat(decision.retainedDetail()).isEqualTo("headers must be non-empty and unique");
  }
}
