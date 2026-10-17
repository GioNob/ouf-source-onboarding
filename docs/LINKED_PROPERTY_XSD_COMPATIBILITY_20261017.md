# Linked property activation compatibility — 17 October 2026

Adding a native reconciliation rule to a previously published XML Schema string property currently fails validation. Accept the exact XML Schema string IRI as the existing string comparison type, preserving the approved configuration and semantic datatype.

Tests cover EXACT_TYPED/NFC_TRIM, unchanged configuration and rejection of unsupported aliases/types. This validation change must be deployed together with UDP support; no profile publication, source activation or historical reprocessing occurs in this PR.

Normative basis: attached Reality Baseline v1.7, Matrix1.7 §1, Onboarding PET1.6 §§7.5–7.6,90,37. Technical8/8; functional2/6 unchanged.

Only `http://www.w3.org/2001/XMLSchema#string` is added. No generic URI suffix matching, implicit datatype conversion, cardinality inference or source-name branch is introduced. Published semantic IRIs and legacy rule serialization remain intact. Other XML Schema types require separate typed contract qualification.

Activation remains blocked until both application changes are qualified and installed, a complete configuration-preserving candidate is validated, and scheduler effects are explicitly handled. A new MANAGED publication can re-arm a consumed one-shot schedule; missing top-level triggerOnce does not disable that behavior. The deployment preparation and target evidence belong in GioNob/ouf-deploy.
