package it.comune.trieste.ouf.onboarding.application;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThsBearerTimeoutsTest {
    @Test
    void humanBearerForwardingTimeoutsFitTheActivationBudget() {
        var maximum = ThsBearerTimeouts.MAX_REQUEST;
        assertThat(maximum).isEqualTo(Duration.ofSeconds(55));

        Map.ofEntries(
                Map.entry("WorkflowThsApi review", ThsBearerTimeouts.WORKFLOW_REVIEW),
                Map.entry("ResolutionThsApi review", ThsBearerTimeouts.RESOLUTION_REVIEW),
                Map.entry("WorkflowThsApi, ResolutionThsApi and ManagedActivationCoordinator connect",
                        ThsBearerTimeouts.HTTP_CONNECT),
                Map.entry("ManagedFilePickerPage connect", ThsBearerTimeouts.FILE_UPLOAD_CONNECT),
                Map.entry("ManagedFilePickerPage read", ThsBearerTimeouts.FILE_UPLOAD_READ),
                Map.entry("ManagedActivationCoordinator readiness", ThsBearerTimeouts.INGESTION_READINESS),
                Map.entry("ManagedActivationCoordinator identity preflight", ThsBearerTimeouts.UDP_IDENTITY_PREFLIGHT),
                Map.entry("ManagedActivationCoordinator sequential downstream requests",
                        ThsBearerTimeouts.INGESTION_READINESS.plus(ThsBearerTimeouts.UDP_IDENTITY_PREFLIGHT)))
            .forEach((call, timeout) -> assertThat(timeout).as(call)
                    .isPositive().isLessThanOrEqualTo(maximum));
    }
}
