package it.comune.trieste.ouf.onboarding.application;

import java.time.Duration;

/** Timeouts on server-side requests that forward the HUMAN bearer. */
public final class ThsBearerTimeouts {
    public static final Duration MAX_REQUEST = Duration.ofSeconds(55);
    public static final Duration HTTP_CONNECT = Duration.ofSeconds(3);
    public static final Duration WORKFLOW_REVIEW = Duration.ofSeconds(15);
    public static final Duration RESOLUTION_REVIEW = Duration.ofSeconds(15);
    public static final Duration FILE_UPLOAD_CONNECT = Duration.ofSeconds(5);
    public static final Duration FILE_UPLOAD_READ = Duration.ofSeconds(30);
    public static final Duration INGESTION_READINESS = Duration.ofSeconds(35);
    public static final Duration UDP_IDENTITY_PREFLIGHT = Duration.ofSeconds(8);

    private ThsBearerTimeouts() {}
}
