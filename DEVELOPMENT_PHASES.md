# Development Phases

## Delivery Rules

- Each phase has an acceptance gate. Do not start a dependent phase with known failures in the prior gate.
- No launcher-specific private-file integration is acceptable as a shortcut for a public adapter contract.
- All work is tracked as GitHub-style issues in `TASKS.md`; issue IDs are planning IDs until created in GitHub.
- Security and recovery tests are release requirements, not post-release hardening.

## Phase 0: Foundation And Decisions

**Goal:** Turn the empty repository into a reproducible, quality-gated Android build without product functionality.

**Deliverables:** Gradle Kotlin DSL project, version catalog, module skeletons, CI, code style and static analysis, ADR template, contribution/runbook baseline, and test fixture policy.

**Exit criteria:** Debug and release builds compile; unit tests, Android Lint, Detekt, and Ktlint run in CI; no app feature screens or installer behavior are required yet.

**Dependencies:** Plan approval.

## Phase 1: Domain And MRPack Validation

**Goal:** Prove safe interpretation of `.mrpack` archives in pure Kotlin.

**Deliverables:** Domain models, typed failures, Modrinth format-v1 parser, ZIP guardrails, safe path validation, loader normalization, and fixture suite.

**Exit criteria:** Valid packs produce immutable plans; malformed JSON, duplicate manifests/entries, traversal, unsupported loaders, invalid hash/URL/path values, server-only artifacts, and bounded ZIP abuse fixtures fail deterministically.

**Dependencies:** Phase 0.

## Phase 2: Persistence, Storage, And Download Core

**Goal:** Build durable source/destination permissions, an artifact cache, and verified network transfers.

**Deliverables:** Room schema and migrations, DataStore settings, source archive staging, SAF storage ports, cache index and eviction, OkHttp downloader, checksum verifier, and retry/failover/checkpoint behavior.

**Exit criteria:** A fake validated plan survives app recreation, resumes transfer safely, reuses verified cache entries, responds correctly to revoked URI grants, and never presents an unverified artifact as complete.

**Dependencies:** Phases 0-1.

## Phase 3: Transactional Installer And Recovery

**Goal:** Convert a validated plan plus verified artifacts into a complete, recoverable staged instance.

**Deliverables:** WorkManager orchestration, transaction journal, staging inventory, override extractor, destination locking, promotion strategy, cancellation and recovery, installation history records, and diagnostic events.

**Exit criteria:** Process death, cancellation, insufficient space, target collision, and deployment failure leave no completed-looking partial instance. Startup recovery either resumes, rolls back safely, or provides a precise remediation state.

**Dependencies:** Phase 2.

## Phase 4: CS Launcher V2 Adapter Discovery

**Goal:** Establish a real, versioned compatibility contract before advertising launcher support.

**Deliverables:** Package/provider detection, SAF tree selection, adapter capability probe, physical-device matrix, documentation of tested CS Launcher versions, and an upstream integration proposal if registration is not publicly supported.

**Exit criteria:** The adapter reliably reports `FULL`, `FILES_ONLY`, or `UNAVAILABLE`; no raw path or private-data assumptions exist. `FULL` requires end-to-end public contract proof. `FILES_ONLY` requires verified document-provider writes and a launcher-recognized instance workflow.

**Dependencies:** Phases 1-3 plus installed CS Launcher test builds and devices.

## Phase 5: Install User Experience

**Goal:** Deliver an accessible, complete user flow over the proven engine and adapter.

**Deliverables:** Archive picker, pack review, launcher and destination selection, prerequisite warnings, disk estimate, foreground progress notification, cancellation/resume, result and handoff, history, and redacted diagnostic export.

**Exit criteria:** A new user can complete or safely abandon an install without developer tools; all error categories have actionable UI; TalkBack labels and configuration-change/process recreation behavior are tested.

**Dependencies:** Phase 4 for the CS destination flow; a generic export flow may be developed earlier behind feature flags.

## Phase 6: Hardening, Release, And Observability

**Goal:** Validate quality under realistic device and failure conditions, then ship a controlled first release.

**Deliverables:** Threat-model review, fuzz and property tests, representative pack matrix, network/storage stress tests, baseline profile and macrobenchmark results, release signing, SBOM and dependency scan, privacy/support docs, and a release checklist.

**Exit criteria:** All release gates in `PROJECT_PLAN.md` pass; known CS Launcher constraints are documented; support diagnostics are redacted; rollback and incident procedures are reviewed.

**Dependencies:** Phases 0-5.

## Phase 7: Post-v1 Expansion

**Goal:** Improve lifecycle management and safely add launchers without destabilizing v1.

**Candidate deliverables:** Pack update-to-new-revision flow, cleanup/uninstall with ownership checks, loader prerequisite contract, optional Modrinth discovery, export-only target, additional launcher adapters, signed remote compatibility data, and localization.

**Exit criteria:** Each feature has an ADR, migration and recovery behavior, security review, and adapter-specific compatibility tests. No Phase 7 item is implied by v1 delivery.

**Dependencies:** A stable v1 release and production feedback.

## Phase 8: Compose Installation Workflow

**Goal:** Connect the completed engines into a durable Android installation experience.

**Deliverables:** SAF MRPack and launcher-tree pickers, pack confirmation,
installation progress, live logs, success/error states, cancellation, retry,
interrupted-session recovery, process-death restoration, Hilt assembly,
StateFlow ViewModels, and Compose navigation.

**Exit criteria:** The UI drives the existing orchestrator, installer,
downloader, and read-only launcher boundaries without broad storage access or
launcher-private file access. Recovery and UI-state tests pass.

**Dependencies:** Completed parser, resolver, downloader, filesystem,
installer, launcher, and orchestrator modules.

## Phase 9: Release And Production Readiness

**Goal:** Complete the final repository, quality, security, integration, and
release-readiness review without adding product features.

**Deliverables:** Repository cleanup, accurate documentation, dependency and
public API review, release configuration audit, dead-code removal, storage and
permission audit, cross-module verification, release notes, and final project
completion report.

**Exit criteria:** No known implementation defects remain in the verified
environment; security and storage boundaries are documented and enforced;
all available tests, builds, and static checks pass. External signing,
licensing, and physical-device validation are explicitly recorded rather than
represented as code failures.

**Dependencies:** Phase 8.
