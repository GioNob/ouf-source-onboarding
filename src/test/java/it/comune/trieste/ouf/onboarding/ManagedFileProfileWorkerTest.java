package it.comune.trieste.ouf.onboarding;

import static org.mockito.Mockito.*;
import it.comune.trieste.ouf.onboarding.application.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ManagedFileProfileWorkerTest {
  @Test void readsTheGovernedReferenceAndCompletesTheOnboardingJob(){ManagedFileService files=mock(ManagedFileService.class);ManagedFileObjectStore objects=mock(ManagedFileObjectStore.class);UUID jobId=UUID.randomUUID(),assetId=UUID.randomUUID();byte[] bytes="id,name\n1,A\n".getBytes();when(files.claimProfile(eq("worker"),eq(Duration.ofMinutes(2)),any(Instant.class))).thenReturn(Optional.of(Map.of("job_id",jobId,"asset_id",assetId,"staging_ref","object://staging/a.csv","size_bytes",bytes.length)));when(objects.read("object://staging/a.csv",bytes.length)).thenReturn(bytes);ManagedFileProfileWorker worker=new ManagedFileProfileWorker(files,objects,"worker",Duration.ofMinutes(2),Duration.ofSeconds(30));worker.runOnce();verify(files).completeProfile(jobId,"worker",bytes,"profile://managed-files/"+assetId+"/jobs/"+jobId+"/redacted-sample");verify(files,never()).failProfile(any(),any(),any(),any(),any());}

  @Test void convertsObjectReadFailuresIntoBoundedRetryState(){ManagedFileService files=mock(ManagedFileService.class);ManagedFileObjectStore objects=mock(ManagedFileObjectStore.class);UUID jobId=UUID.randomUUID(),assetId=UUID.randomUUID();when(files.claimProfile(eq("worker"),any(),any())).thenReturn(Optional.of(Map.of("job_id",jobId,"asset_id",assetId,"staging_ref","object://staging/a.csv","size_bytes",12L)));when(objects.read("object://staging/a.csv",12L)).thenThrow(new IllegalStateException("temporary gateway failure"));ManagedFileProfileWorker worker=new ManagedFileProfileWorker(files,objects,"worker",Duration.ofMinutes(2),Duration.ofSeconds(30));worker.runOnce();verify(files).failProfile(jobId,"worker","ONB_FILE_PROFILE_FAILED","temporary gateway failure",Duration.ofSeconds(30));verify(files,never()).completeProfile(any(),any(),any(),any());}
}
