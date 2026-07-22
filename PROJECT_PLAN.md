# Modrith Android: Project Plan

## Purpose

Modrith is a standalone Android installer for official Modrinth `.mrpack` files. Its first supported target is CS Launcher V2, a Java Minecraft launcher on Android. The app accepts a user-selected pack, validates its manifest, downloads and verifies client files, applies allowed overrides, and deploys a complete instance through a launcher-specific adapter.

The product is an installer, not a Minecraft launcher, mod browser, account manager, or unofficial pack republisher. It must preserve the pack author's declared URLs and hashes, require user consent for storage access, and never silently modify an existing instance.

## Current Repository Assessment

The repository now contains the complete v1 implementation: a Gradle Kotlin
DSL Android project, pure Kotlin parser/resolver modules, verified downloader,
SAF filesystem layer, transactional installer, read-only launcher inspection,
orchestrator, Compose UI, Hilt assembly, durable workflow restoration, and
module-level tests. Phase 9 is the final repository, security, integration,
and release-readiness review.

## Scope

### Initial release (v1)

- Import a local `.mrpack` through Android's Storage Access Framework (SAF).
- Parse Modrinth format version 1 packs for the `minecraft` game.
- Validate pack metadata, dependencies, file paths, file sizes, download URLs, and SHA-1/SHA-512 hashes before installation.
- Download required client files and user-selected optional client files with retry, mirror failover, resumable cache support where safe, and checksum verification.
- Apply `overrides/` and `client-overrides/` from the archive after validation.
- Install to a new, deterministic instance directory; never merge into a pre-existing directory by default.
- Persist install records, download cache metadata, structured diagnostic logs, and resumable work state.
- Integrate with CS Launcher V2 through a capability-detected adapter and its documented or exported storage surface where available.
- Surface a clear post-install outcome when files are deployed but launcher profile registration needs an in-launcher user action.

### Explicit non-goals for v1

- Installing server packs or honoring server-side files.
- Downloading packs from Modrinth search or accepting a remote `.mrpack` URL as the primary flow.
- Installing Minecraft, a Java runtime, or mod loaders.
- Editing `launcher_profiles.json` or other private CS Launcher data from a separate app without a verified public contract.
- Root access, `MANAGE_EXTERNAL_STORAGE`, bypassing scoped storage, or writing directly into another app's private `Android/data` directory.
- In-place upgrades, uninstalling an instance, conflict resolution with user-modified instances, and launcher deep-link automation. These are planned after the first adapter contract is proven.

## Success Criteria

1. A valid official client `.mrpack` selected from a document provider completes in a new staged instance only when every required file and override is verified and written.
2. Cancellation, process death, network loss, insufficient storage, invalid archives, and hash mismatches leave no partial target instance or untracked files.
3. The user can resume a deferred download/install through WorkManager after the app is recreated.
4. CS Launcher V2 support is enabled only after runtime verification of the adapter's package, document provider, root layout, and launcher version compatibility matrix.
5. All failures have a stable user-facing category and a correlated, redacted diagnostic record.
6. Core parser, downloader, installer, and launcher adapter logic can run as JVM tests without Android UI.

## Product Flow

1. The user chooses **Install pack** and selects a `.mrpack` with `ACTION_OPEN_DOCUMENT`.
2. The app requests a persistable read grant, copies the archive to private staging if the provider is non-seekable, and records the source URI only after consent succeeds.
3. The parser inspects the ZIP safely, reads exactly one `modrinth.index.json`, and produces a validated immutable install plan.
4. The user reviews pack name, Minecraft version, loader dependencies, optional client files, disk estimate, target launcher, and destination name.
5. The selected launcher adapter probes capabilities and asks for a writable destination tree when needed.
6. A unique WorkManager job downloads verified files into the private cache, extracts validated overrides into a private staging tree, and writes a transaction journal.
7. The installer atomically promotes the staged tree into the launcher destination when the storage backend supports rename; otherwise it uses a journaled copy with a completion marker.
8. The adapter performs supported registration or returns an actionable handoff result. The install record becomes `Completed`, `CompletedNeedsLauncherImport`, `Cancelled`, or `Failed`.

## Technical Baseline

- Language: Kotlin; Java interoperability only at Android/framework boundaries if needed.
- UI: Jetpack Compose with Material 3, Navigation Compose, ViewModel, and unidirectional UI state.
- Minimum SDK: 26. This makes SAF, notification channels, foreground work, and modern crypto APIs predictable while retaining broad Android launcher compatibility. Reassess only with device-distribution evidence.
- Target/compile SDK: latest stable Android SDK at implementation start; do not hard-code a future SDK in planning documents.
- Build: Gradle Kotlin DSL, Kotlin JVM toolchain 17, Android Gradle Plugin/Kotlin versions pinned in a version catalog and updated by Dependabot or Renovate.
- Architecture: clean boundaries with repository interfaces, coroutine-based use cases, Room for durable state, WorkManager for deferrable background work, and no network or filesystem work on the main thread.

## Required Libraries

| Area | Library or platform API | Use |
| --- | --- | --- |
| UI | Jetpack Compose, Material 3, Navigation Compose | Accessible installation workflow and navigation |
| State | AndroidX ViewModel, Lifecycle, Kotlin coroutines/Flow | Lifecycle-safe state and progress |
| Persistence | Room, DataStore Preferences | Install/job records and lightweight settings |
| Work | WorkManager, foreground worker APIs | Durable constrained downloads and installation |
| HTTP | OkHttp | Streaming downloads, redirects, timeouts, retry classification |
| Serialization | `kotlinx.serialization` JSON | Strict manifest decoding and persisted data |
| ZIP | `java.util.zip` with bounded streaming; Apache Commons Compress only if edge-case testing proves it necessary | Archive inspection and safe extraction |
| SAF | AndroidX DocumentFile plus `ContentResolver`/`DocumentsContract` | Persisted document and tree access |
| Logging | Timber facade plus a custom redacting `DiagnosticLogger` | Structured local diagnostics |
| DI | Hilt | Explicit graph and test replacement bindings |
| Tests | JUnit 5, Kotest or Truth, Turbine, MockWebServer, Robolectric, AndroidX Test, Compose UI Test, Macrobenchmark | Unit, integration, device, UI, and performance testing |
| Quality | Detekt, Ktlint, Android Lint, dependency scanning, SBOM generation | Static quality and supply-chain controls |

Libraries will be introduced only in the module that owns the behavior. The parser and domain modules must remain Android-free; UI must not depend directly on OkHttp, Room, ZIP APIs, or a launcher implementation.

## Delivery Risks And Decisions

### CS Launcher V2 compatibility

The CS Launcher V2 source inspected on July 19, 2026 identifies package `com.craftstudio.cslauncher`, uses app-specific external storage on Android 10+, creates imported pack directories below `custom_instances/` in its launcher storage root, and exports a `DocumentsProvider` rooted at that storage. It also has its own `.mrpack` importer. Modrith will not treat internal source details as a permanent public API.

The CS adapter therefore has three capability levels:

| Level | Capability | v1 behavior |
| --- | --- | --- |
| `FULL` | A verified public integration endpoint can create/register an instance and expose a writable destination | Deploy and register automatically |
| `FILES_ONLY` | A trusted, writable launcher-owned document tree exists but profile registration has no contract | Deploy instance files and show the minimal launcher-side import/create action |
| `UNAVAILABLE` | Package, provider, permissions, storage layout, or version verification fails | Disable installation to CS Launcher and explain the requirement |

The implementation phase must include physical-device tests against a versioned CS Launcher V2 compatibility matrix. A direct source-level partnership, intent contract, or provider API is preferred before claiming `FULL` support. Until then, `FILES_ONLY` is the safe acceptance target.

### Android storage

SAF is the primary storage model. Modrith asks the user for the smallest useful document/tree grant, persists it with `takePersistableUriPermission`, checks it before every job, and supports revocation. Android restricts picking storage roots, reliable access to other applications' app-specific directories, and `Android/data` paths on modern releases; the app must never rely on unsupported path access. Its own cache, database, staging tree, and diagnostic files remain inside app-private storage.

### Pack compatibility

Modrinth dependencies are informative compatibility data, not a complete installation recipe. A pack needs a compatible Minecraft version and loader available in the destination launcher. The v1 adapter validates what it can discover, reports missing prerequisites before downloads, and does not fabricate loader profiles. Initial support requires exactly one recognized client loader dependency (`fabric-loader`, `forge`, `quilt-loader`, or `neoforge`) plus `minecraft`; unknown or ambiguous loader sets enter a user-visible unsupported state rather than guessing.

## Release Gates

- Security review completed for ZIP extraction, paths, URLs, hashes, URI grants, and log redaction.
- At least three official packs cover Fabric, Forge/NeoForge, and Quilt or a documented unsupported-loader result.
- Offline, captive portal, cancellation, app restart, URI permission revocation, disk-full, malformed ZIP, and checksum mismatch tests pass.
- A CS Launcher V2 compatibility device matrix passes for each advertised capability level.
- Release build is signed through CI, includes a software bill of materials, has no high-severity unresolved dependency findings, and passes Android Lint, Detekt, and Ktlint.

## Approval Boundary

The original planning boundary has been satisfied. Product implementation is
complete through Phase 8, and Phase 9 records final release-readiness work.
Distribution still requires owner-controlled signing credentials, a selected
software license, and physical-device compatibility verification.
