# Durable profiling-job discovery

Extend the existing tenant/subject-proven ASSETS owner read with the latest profileJob (jobId, preview-compatible status and allowlisted generic errorCode), selected deterministically by creation time and UUID. This is a read projection over existing durable data: no job creation, recovery, budget change or migration. Exact-job preview supplies the existing safe diagnostic category separately. Private exception text, object paths, worker identity and idempotency keys are not projected.

workLabel is derived solely from already-authorized upload timestamp and size; it does not invent an original filename or domain name. Existing failed uploads do not persist a filename, so describing Farmacie by name after a new chat is not proved by this change alone. Unambiguous selection/paging and future meaningful upload labels remain product qualification items.

PostgreSQL tests exercise failed and running job discovery across repeated reads, no new job, tenant exclusion, missing job, deterministic latest selection and no leakage of private details/unknown error strings. READY is projected as QUEUED, consistently with exact-job preview.

The prior browser jobs timed out in slow Azure Ubuntu package downloads before running assertions. The browser workflow now uses the matching official Playwright 1.62.1 noble image with browsers/dependencies preinstalled; npm SDK remains exactly 1.62.1. The same browser/latency assertions and finite ten-minute bound remain mandatory. Reference: https://playwright.dev/docs/docker . This is a CI environment change, not permission to rerun consumed target operations.

No Farmacie runtime operation or M2 acceptance is performed by this change. D4 profiling recovery and D5 managed semantic mappings remain open.
