# R3a.1a — InstallationConfiguration contract

Normative basis:
- OUF Reality Baseline Package v1.7;
- Cross-Module Alignment Matrix v1.7;
- Source Onboarding / Configuration / THS PET environment-binding responsibilities;
- Authorization PET v1.5;
- Gateway and MCP deployment/security contracts.

## Goal

Turn installation-specific coordinates into a governed configuration object instead of code defaults, shell-memory or per-module conventions.

## Human role and usable surface

Role: platform installer / bootstrap administrator.

The first increment is contract-only. It does not yet claim a finished wizard or persistence lifecycle.

The installer-facing bootstrap will eventually collect values represented by `InstallationConfiguration v1` and validate them before activation.

## Contract

Canonical product schema:

`contracts/installation/installation-configuration-v1.json`

The contract includes:
- installation and organization identity;
- environment;
- network names and internal DNS strategy;
- IAM issuer/realm/admin client/audience;
- Gateway public base URL and MCP path;
- persistence/service references;
- secret provider and secret references;
- observability;
- revision/status/checksum lifecycle.

## Product vs laboratory evidence

The schema contains no laboratory domains, IP addresses, Docker names or database coordinates.

A separate example is intentionally concrete:

`contracts/installation/examples/ouf-lab-v1.json`

It records the current Netcup laboratory coordinates to make the installation process reproducible without turning them into product defaults.

Laboratory values are evidence/examples only.

## Secret boundary

Installation configuration carries secret references only. It never carries passwords, bearer tokens, client secrets or key material.

## Verification

`InstallationConfigurationContractTest` verifies:
- no lab-specific coordinates appear in the product schema;
- the lab example records the real hostnames currently used;
- secret entries are references;
- the lab example remains VALIDATED rather than falsely claiming ACTIVE installation acceptance.

## Explicit deferrals

Not claimed by R3a.1a:
- database persistence of InstallationConfiguration;
- immutable revision lifecycle;
- bootstrap API/THS wizard;
- validation engine for DNS/TLS/IAM/DB/storage;
- runtime projection generation;
- activation/rollback workflow;
- unattended bootstrap.

Those are the next R3a.1 increments.
