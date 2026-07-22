# Modrith Android: GitHub Issues Backlog

## Labels And Conventions

- Suggested labels: `type:build`, `type:feature`, `type:security`, `type:test`, `type:docs`, `area:parser`, `area:storage`, `area:downloader`, `area:installer`, `area:launcher`, `area:ui`, `priority:p0`, `priority:p1`, `blocked:external`.
- Every implementation issue includes unit or integration tests appropriate to the changed boundary and updates documentation when a contract changes.
- Issues are ordered by phase. Items marked **blocked** require an external decision, device, or launcher-owner collaboration.

## Phase 0: Foundation And Decisions

- [ ] **#0-01 Initialize Android Gradle project and version catalog** `type:build`, `priority:p0`
  - Configure Kotlin DSL, Gradle wrapper checksum verification, Java 17 toolchain, app/module skeletons, and reproducible local build commands.
  - Acceptance: clean checkout builds debug/release variants and runs a placeholder unit test.
- [ ] **#0-02 Establish module dependency rules** `type:build`, `priority:p0`
  - Implement modules in `ARCHITECTURE.md`, dependency verification, and a CI check preventing UI/Android dependencies from entering pure domain/parser modules.
  - Acceptance: architecture checks fail on prohibited dependency direction.
- [ ] **#0-03 Add CI quality and supply-chain pipeline** `type:build`, `type:security`, `priority:p0`
  - Add GitHub Actions for build, unit tests, Android Lint, Detekt, Ktlint, dependency scanning, SBOM generation, and artifact retention.
  - Acceptance: pull requests receive required quality status checks.
- [ ] **#0-04 Create ADR, compatibility, and incident-runbook templates** `type:docs`, `priority:p0`
  - Add templates and initial ADRs for min SDK, URI grants, installer transaction model, and supported MRPack format.
  - Acceptance: decisions required by Phases 1-4 have owners and records.
- [ ] **#0-05 Define fixture licensing and secrets policy** `type:docs`, `type:security`, `priority:p0`
  - Document allowed test fixtures, no-mod-binary rule unless licensed, repository secret scanning, and developer credential policy.
  - Acceptance: the fixture contribution checklist is reviewable in CI and documentation.

## Phase 1: Domain And MRPack Validation

- [ ] **#1-01 Implement domain models and typed install errors** `type:feature`, `priority:p0`
  - Add immutable plan, manifest, artifact, target, state, capability, and failure models in `:core:domain`.
  - Acceptance: state-transition and error-mapping unit tests cover all documented categories.
- [ ] **#1-02 Implement bounded MRPack ZIP inspector** `type:feature`, `type:security`, `area:parser`, `priority:p0`
  - Enforce entry, count, name, size, and compression limits and discover manifest/override entries without extraction.
  - Acceptance: fixture tests reject ZIP traversal, duplicate paths, encrypted or broken archives, and decompression-limit violations.
- [ ] **#1-03 Implement strict Modrinth format-v1 manifest parser** `type:feature`, `area:parser`, `priority:p0`
  - Decode and validate `modrinth.index.json`, dependencies, environments, hashes, HTTPS URLs, and file metadata.
  - Acceptance: official-format fixtures parse; required, optional, unsupported, and default environment semantics are tested; unsupported or ambiguous data returns stable errors.
- [ ] **#1-04 Implement loader requirement normalization** `type:feature`, `area:parser`, `priority:p0`
  - Map `minecraft`, Fabric, Forge, Quilt, and NeoForge dependencies to one explicit v1 requirement.
  - Acceptance: one known client loader is accepted; zero, multiple, or unknown loaders are explained without guessing.
- [ ] **#1-05 Build MRPack parser fixture and property-test suite** `type:test`, `area:parser`, `priority:p0`
  - Add a synthetic archive builder, canonical valid fixtures, and fuzz/property tests for paths and manifest fields.
  - Acceptance: the suite runs on the JVM and covers all parser acceptance and rejection branches.

## Phase 2: Persistence, Storage, And Download Core

- [ ] **#2-01 Create Room schema for installs, downloads, journals, and migrations** `type:feature`, `priority:p0`
  - Model durable install state and checkpoints with an explicit migration policy.
  - Acceptance: migration tests cover schema upgrades and sensitive URI or log data does not leak into summaries.
- [ ] **#2-02 Implement SAF source archive acquisition and permission lifecycle** `type:feature`, `area:storage`, `priority:p0`
  - Support persisted read grants, source validation, private staging copy, revocation detection, and cleanup.
  - Acceptance: instrumented tests cover persistable, non-persistable, and revoked source URIs.
- [ ] **#2-03 Implement SAF destination tree storage abstraction** `type:feature`, `area:storage`, `priority:p0`
  - Wrap document operations, child traversal, temporary writes, capability checks, and URI safety behind interfaces.
  - Acceptance: fake and instrumented providers prove no operation escapes the selected tree.
- [ ] **#2-04 Implement content-addressed verified artifact cache** `type:feature`, `area:downloader`, `priority:p0`
  - Add cache metadata, strong-digest keys, revalidation, active-job pinning, and budgeted eviction.
  - Acceptance: verified reuse works; corrupt and partial cache records are never reused.
- [ ] **#2-05 Implement HTTP downloader with integrity and mirror failover** `type:feature`, `type:security`, `area:downloader`, `priority:p0`
  - Stream HTTPS downloads to partial files; enforce limits, checksums, redirects, timeouts, retry/failover, and cancellation.
  - Acceptance: MockWebServer tests cover hash mismatch, bad mirrors, resume behavior, redirects, 4xx/5xx, and no unverified completion.
- [ ] **#2-06 Integrate WorkManager download checkpoints and constraints** `type:feature`, `area:downloader`, `priority:p0`
  - Persist and recover worker input and apply network, storage, and battery constraints with unique-work semantics.
  - Acceptance: app recreation and retry continue the correct install without duplicate downloads.

## Phase 3: Transactional Installer And Recovery

- [ ] **#3-01 Implement install-plan persistence and destination locking** `type:feature`, `area:installer`, `priority:p0`
  - Persist reviewed plans and serialize competing destinations and install IDs.
  - Acceptance: duplicate enqueue is idempotent and conflicting destination jobs are rejected or queued deterministically.
- [ ] **#3-02 Implement private staging tree and transaction journal** `type:feature`, `area:installer`, `priority:p0`
  - Add transaction lifecycle, inventory, completion marker, and safe cleanup retention policy.
  - Acceptance: the journal reconstructs state after simulated process death.
- [ ] **#3-03 Implement safe override extraction** `type:feature`, `type:security`, `area:installer`, `priority:p0`
  - Extract only validated override entries, enforce collision policy, and verify staging containment.
  - Acceptance: malicious override fixtures cannot create files outside staging.
- [ ] **#3-04 Implement staged artifact materialization and inventory verification** `type:feature`, `area:installer`, `priority:p0`
  - Copy or link verified cache artifacts to staging and validate the final intended file set.
  - Acceptance: missing, duplicate, or modified artifacts prevent promotion.
- [ ] **#3-05 Implement adapter-mediated deployment and promotion protocols** `type:feature`, `area:installer`, `area:launcher`, `priority:p0`
  - Support atomic move when available and journaled temporary-copy promotion otherwise.
  - Acceptance: fake storage tests cover target failures at every deployment step without completed-looking partial output.
- [ ] **#3-06 Implement cancellation, startup recovery, and retained-work cleanup** `type:feature`, `area:installer`, `priority:p0`
  - Recover, roll back, or purge interrupted work safely and expose resumable state.
  - Acceptance: the cancellation and process-death matrix passes with no orphan destination instance.
- [ ] **#3-07 Add installer integration test matrix** `type:test`, `area:installer`, `priority:p0`
  - Test disk-full, collision, provider failure, cancellation, retry, and recovery scenarios.
  - Acceptance: tests exercise every transaction state transition.

## Phase 4: CS Launcher V2 Adapter Discovery

- [ ] **#4-01 Write CS Launcher V2 adapter ADR and compatibility probe specification** `type:docs`, `area:launcher`, `priority:p0`
  - Define observed facts, unverified assumptions, capability levels, version and signature checks, and safety boundaries.
  - Acceptance: no private file format is listed as a supported integration surface.
- [ ] **#4-02 Implement CS Launcher package and provider detection** `type:feature`, `area:launcher`, `priority:p0`
  - Detect package `com.craftstudio.cslauncher`, discover exported provider metadata, and validate authority ownership and version.
  - Acceptance: missing or incompatible installations return `UNAVAILABLE` without prompting for broad storage.
- [ ] **#4-03 Implement CS Launcher SAF destination selection and probe** `type:feature`, `area:launcher`, `area:storage`, `priority:p0`
  - Request a user-approved tree, inspect writable root and instance layout through provider operations, and expose capabilities.
  - Acceptance: URI revocation and provider changes downgrade safely; the selected tree cannot be escaped.
- [ ] **#4-04 Validate files-only instance recognition on physical CS Launcher builds** `type:test`, `area:launcher`, `priority:p0`, `blocked:external`
  - Test representative pack outputs across supported CS Launcher V2 versions and Android API levels.
  - Acceptance: the compatibility matrix documents exact version/device outcomes or files-only support remains disabled.
- [ ] **#4-05 Obtain or design a public CS Launcher registration contract** `type:feature`, `type:docs`, `area:launcher`, `priority:p0`, `blocked:external`
  - Work with CS Launcher maintainers on a stable versioned intent or provider API for profile registration and prerequisite checks.
  - Acceptance: `FULL` capability is implemented only after an upstream contract and end-to-end tests exist.
- [ ] **#4-06 Implement CS Launcher registration only when a public contract is proven** `type:feature`, `area:launcher`, `priority:p1`, `blocked:external`
  - Add the explicit contract client, version gates, handoff result verification, and fallback to `FILES_ONLY`.
  - Acceptance: no profile JSON or preferences are modified by Modrith.

## Phase 5: Install User Experience

- [ ] **#5-01 Build archive import and pack review flow** `type:feature`, `area:ui`, `priority:p0`
  - Implement picker, parse progress, pack metadata, loader requirement, optional client-file selection, warnings, and disk estimate review.
  - Acceptance: malformed sources never reach destination selection; state survives rotation and process recreation.
- [ ] **#5-02 Build launcher and destination selection flow** `type:feature`, `area:ui`, `priority:p0`
  - Show capability levels, request only the necessary SAF grant, validate the instance name, and explain unavailable targets.
  - Acceptance: the destination is always explicit and compatible with the persisted plan.
- [ ] **#5-03 Build durable installation progress and notification UX** `type:feature`, `area:ui`, `priority:p0`
  - Display file and byte progress, current phase, cancellation, retry state, and foreground worker notification.
  - Acceptance: background and foreground transitions preserve accurate progress and cancellation behavior.
- [ ] **#5-04 Build completion, launcher handoff, and history screens** `type:feature`, `area:ui`, `priority:p0`
  - Clearly separate fully registered from files-only completion; provide open-launcher and history actions.
  - Acceptance: the user-visible result matches the persisted adapter result exactly.
- [ ] **#5-05 Build error remediation and diagnostic export UX** `type:feature`, `area:ui`, `priority:p0`
  - Map stable error categories to actionable choices and a user-reviewed redacted log export.
  - Acceptance: UI never shows stack traces, raw URI grants, or sensitive path data.
- [ ] **#5-06 Add Compose accessibility and UI-state tests** `type:test`, `area:ui`, `priority:p0`
  - Cover semantics, TalkBack labels, dynamic text, error states, and recreation.
  - Acceptance: automated UI tests pass on supported screen sizes and font scales.

## Phase 6: Hardening, Release, And Observability

- [ ] **#6-01 Conduct installer threat model and security review** `type:security`, `priority:p0`
  - Review archive, downloader, URI, provider, intent, cache, logging, and release supply-chain attack surfaces.
  - Acceptance: findings are fixed, accepted with an owner and date, or block release.
- [ ] **#6-02 Add archive/parser fuzzing and adversarial fixture suite** `type:test`, `type:security`, `area:parser`, `priority:p0`
  - Exercise names, JSON, ZIP metadata, duplicate entries, and compression limits.
  - Acceptance: crashes and hangs are fixed and the corpus is retained.
- [ ] **#6-03 Execute official pack and physical-device compatibility matrix** `type:test`, `priority:p0`, `blocked:external`
  - Test supported loader families, Android versions, storage providers, network conditions, and CS Launcher versions.
  - Acceptance: results are published in compatibility documentation and all advertised combinations pass.
- [ ] **#6-04 Profile performance, storage, and battery behavior** `type:test`, `priority:p1`
  - Add Macrobenchmark and Baseline Profile, large-manifest tests, low-storage runs, and throttled-network runs.
  - Acceptance: performance budgets are documented and regressions are gated or tracked.
- [ ] **#6-05 Finalize logging, privacy, and support runbooks** `type:docs`, `type:security`, `priority:p0`
  - Validate redaction, retention, diagnostic bundle contents, privacy notice, support triage, and incident response.
  - Acceptance: support can diagnose a failed install without collecting sensitive content.
- [ ] **#6-06 Configure signed release, SBOM, and release checklist** `type:build`, `type:security`, `priority:p0`, `blocked:external`
  - Set signing custody, artifact provenance, dependency review, store metadata and channel policy, and rollout and rollback procedure.
  - Acceptance: a candidate release passes every `PROJECT_PLAN.md` release gate.

## Phase 7: Post-v1 Expansion

- [ ] **#7-01 Design update-to-new-revision workflow** `type:feature`, `area:installer`, `priority:p1`
  - Add an ADR and prototype for provenance comparison, staged updates, and user-file policy.
  - Acceptance: updates never mutate a live instance before complete verification.
- [ ] **#7-02 Design owned-instance cleanup and uninstall workflow** `type:feature`, `area:installer`, `priority:p1`
  - Permit deletion only after ownership and inventory verification plus user confirmation.
  - Acceptance: user-created or non-Modrith files cannot be silently deleted.
- [ ] **#7-03 Add generic export-only launcher target** `type:feature`, `area:launcher`, `priority:p1`
  - Export a verified staged instance to a user-chosen tree without profile registration.
  - Acceptance: it has the same transaction and safety guarantees as adapter deployment.
- [ ] **#7-04 Evaluate each additional launcher as an isolated adapter proposal** `type:docs`, `area:launcher`, `priority:p1`, `blocked:external`
  - Require package, API, and storage research plus an ADR, compatibility matrix, and contract tests per launcher.
  - Acceptance: no adapter is implemented from guessed filesystem paths.
- [ ] **#7-05 Evaluate Modrinth discovery and signed compatibility metadata** `type:feature`, `type:security`, `priority:p2`
  - Define API rate and privacy policy plus fail-closed signature/cache behavior before implementation.
  - Acceptance: the feature has a threat model, UX and privacy review, and offline fallback.
