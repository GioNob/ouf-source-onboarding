# Source Onboarding Operational Awareness — PET v1.6 traceability

Normative baseline: Source Onboarding / Configuration / THS PET v1.6 and Cross-Module Alignment Matrix v1.6.

## Implemented evidence

`SyncProfile v1` now has an optional governed `operationalPolicy` containing timezone, misfire tolerance, source timeout, retry maxima, incident dedup window, operational visibility class and minimum operational retention days.

The schema enforces bounded maxima and a baseline retention minimum of 30 days. These values are published with the versioned configuration consumed by Ingestion; runtime code must not invent them.

The contract freeze checksum is updated for the intentional normative change.

## Evidence boundary

Runtime consumption of every operational-policy property, scheduler misfire detection and THS remediation workflows remain implementation/deployment evidence pending.
