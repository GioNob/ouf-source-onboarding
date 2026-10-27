package it.comune.trieste.ouf.onboarding.application;

import java.time.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(ManagedFileObjectStore.class)
public class ManagedFileProfileWorker {
  private final ManagedFileService files;private final ManagedFileObjectStore objects;private final String workerId;private final Duration lease;private final Duration retryDelay;
  public ManagedFileProfileWorker(ManagedFileService files,ManagedFileObjectStore objects,@Value("${ouf.onboarding.profile-worker.id:onboarding-profiler}") String workerId,@Value("${ouf.onboarding.profile-worker.lease:PT2M}") Duration lease,@Value("${ouf.onboarding.profile-worker.retry-delay:PT30S}") Duration retryDelay){if(retryDelay==null||retryDelay.isNegative())throw new IllegalArgumentException("ONB_RETRY_DELAY_INVALID");this.files=files;this.objects=objects;this.workerId=workerId;this.lease=lease;this.retryDelay=retryDelay;}

  @Scheduled(fixedDelayString="${ouf.onboarding.profile-worker.poll-delay-ms:1000}")
  public void runOnce(){files.claimProfile(workerId,lease,Instant.now()).ifPresent(this::process);}

  private void process(Map<String,Object> job){
    var jobId=(java.util.UUID)job.get("job_id");try{String stagingRef=String.valueOf(job.get("staging_ref"));long expectedSize=((Number)job.get("size_bytes")).longValue();byte[] bytes=objects.read(stagingRef,expectedSize);String sampleRef="profile://managed-files/"+job.get("asset_id")+"/jobs/"+jobId+"/redacted-sample";files.completeProfile(jobId,workerId,bytes,sampleRef);}catch(Exception failure){
      var decision=ManagedProfileFailurePolicy.classify(failure,retryDelay);
      files.failProfile(jobId,workerId,"ONB_FILE_PROFILE_FAILED",decision.retainedDetail(),decision.delay(),decision.retryable());
    }
  }

}
