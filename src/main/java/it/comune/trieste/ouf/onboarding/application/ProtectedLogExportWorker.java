package it.comune.trieste.ouf.onboarding.application;

import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ProtectedLogExportWorker {
  private final ProtectedLogService logs;private final String worker;private final Duration lease;private final Duration retention;
  public ProtectedLogExportWorker(ProtectedLogService logs,@Value("${ouf.onboarding.log-export-worker.id:ths-log-export}") String worker,@Value("${ouf.onboarding.log-export-worker.lease:PT2M}") Duration lease,@Value("${ouf.onboarding.log-export-worker.retention:PT1H}") Duration retention){this.logs=logs;this.worker=worker;this.lease=lease;this.retention=retention;}
  @Scheduled(fixedDelayString="${ouf.onboarding.log-export-worker.poll-delay-ms:1000}") public void runOnce(){logs.claimExport(worker,lease,Instant.now()).ifPresent(job->logs.completeExport(job,worker,retention));}
}
