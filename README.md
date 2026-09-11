# OUF Source Onboarding & Configuration

Implementation in progress against `OUF_PET_Source_Onboarding_Configuration_THS_v1_5_Development_Ready`.

This first executable increment contains the PostgreSQL 17 source/version workflow core:

- source registration without secret material;
- immutable version history and optimistic locking;
- DRAFT -> IN_REVIEW -> APPROVED -> ACTIVE;
- short-lived approval challenges bound to the canonical configuration hash;
- HUMAN_USER-only confirmation and activation;
- executable validation engine with typed findings and append-only validation evidence;
- persistent and idempotent technical-discovery queue with lease recovery and materialized source types/fields/states;
- mapping workspace for field classification, eligibility, semantic/property mappings and governed DRAFT generation;
- managed CSV/XLSX asset registration and bounded deterministic profiling without storing file blobs in PostgreSQL;
- append-only approval evidence and audit;
- atomic ACTIVE bundle switch with historical bundle retention.

It does not yet claim completion of geospatial file formats, Authorization Policy Registry, or the complete Trusted Human Surface.
