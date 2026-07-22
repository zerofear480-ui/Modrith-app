# Modrith Android: Architecture

## Architectural Principles

- Treat a modpack archive and every network response as untrusted input.
- Keep domain policy independent of Android, UI, network, and a particular launcher.
- Make installs transactional, durable, observable, resumable, and idempotent by install ID.
- Use explicit launcher capabilities instead of filesystem assumptions.
- Prefer a conservative unsupported result to an installation that appears successful but cannot launch.
- Minimize permanent permissions and retain URI grants only while an install or record needs them.

## System Context

```text
Compose UI
  -> ViewModels / use cases
  -> domain contracts
      -> MRPack parser
      -> download repository
      -> installer engine
      -> launcher adapter registry
  -> Android infrastructure
      -> Room / DataStore / private files
      -> WorkManager / notification
      -> SAF ContentResolver / DocumentsContract
      -> OkHttp / DNS / TLS
      -> CS Launcher V2 provider or future launcher contract
```

The UI requests use cases; it never writes files or executes launcher-specific logic. Use cases depend on interfaces in `:core:domain`. Implementations live in separate data/integration modules and are assembled by Hilt in `:app`.

## Gradle Module Layout

```text
:app           Android application, Compose navigation, Hilt assembly, SAF workflow
:core          DataStore settings, shared HTTP client, coroutine dispatchers
:models        Immutable Android-free domain and engine contracts
:parser        MRPack ZIP inspection, validation, and safe extraction
:resolver      Dependency normalization and install-plan resolution
:downloader    Verified resumable artifact downloads and Room checkpoints
:filesystem    SAF tree and app-private cache storage providers
:installer     Transactional staging, commit, rollback, and recovery
:launcher      Read-only CS Launcher profile and version inspection
:orchestrator  End-to-end coordination, checkpoints, progress, and error mapping
:ui            Material 3 Compose screens, ViewModels, and navigation models
:utils         Release logging support
```

Dependency direction is inward: `app -> ui/features -> domain <- implementations`; parser, downloader, installer, and adapters may depend on `core:common` and domain contracts but not on UI. `launcher:cslauncher` depends on `launcher:api`, storage, and Android APIs, not installer internals. `test:fixtures` is never shipped.

The implemented flattened module graph preserves the same ownership rules:
`:app` is the composition root, `:ui` does not depend on engine
implementations, and engine modules do not depend on `:ui` or `:app`.

## Repository Folder Structure

At implementation start, replace the current empty placeholders with Gradle modules. Keep repository-level material organized as follows:

```text
.
├── app/
├── core/{common,domain,database,storage,network}/
├── parser/mrpack/
├── downloader/
├── installer/
├── launcher/{api,cslauncher}/
├── ui/{designsystem,feature-install,feature-history}/
├── test/fixtures/
├── benchmark/
├── docs/
│   ├── adr/
│   ├── compatibility/
│   └── runbooks/
├── scripts/
├── gradle/libs.versions.toml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── PROJECT_PLAN.md
├── ARCHITECTURE.md
├── DEVELOPMENT_PHASES.md
└── TASKS.md
```

Use architecture decision records for irreversible decisions: min SDK, URI permission retention, supported `.mrpack` format versions, installer transaction protocol, and every launcher adapter contract.

## Domain Data Models

All domain models are immutable Kotlin `data class`es or sealed interfaces. JSON DTOs and Room entities stay in their owning data modules and map into these models.

| Model | Key fields | Purpose |
| --- | --- | --- |
| `PackSource` | `uri`, `displayName`, `persistedReadGrant`, `copiedArchiveId` | User-selected archive provenance |
| `MrPackManifest` | `formatVersion`, `game`, `versionId`, `name`, `summary`, `files`, `dependencies` | Validated `modrinth.index.json` |
| `PackFile` | `relativePath`, `downloads`, `sizeBytes`, `sha1`, `sha512`, `environment` | One manifest artifact |
| `ArtifactSelection` | `fileId`, `requirement`, `selected` | Required/optional client-file decision captured before execution |
| `LoaderRequirement` | `minecraftVersion`, `loader`, `loaderVersion` | Normalized launcher prerequisite |
| `OverrideEntry` | `archivePath`, `destinationPath`, `sourceRoot` | Allowed client override to extract |
| `InstallPlan` | `installId`, `manifest`, `files`, `overrides`, `target`, `diskEstimate`, `warnings` | Immutable reviewed execution input |
| `LauncherTarget` | `launcherId`, `instanceName`, `treeUri`, `capabilities` | Approved destination |
| `LauncherCapabilities` | `installMode`, `supportsRegistration`, `supportsAtomicMove`, `issues` | Runtime adapter probe result |
| `InstallRecord` | `id`, `state`, `timestamps`, `target`, `result`, `failure`, `logId` | Durable history and recovery source |
| `InstallState` | `Draft`, `AwaitingDestination`, `Queued`, `Downloading`, `Staging`, `Deploying`, `Completed`, `CompletedNeedsLauncherImport`, `Cancelled`, `Failed` | User-visible state machine |
| `DownloadRecord` | URL/mirror, cache key, bytes, digest, validator, retry count | Download checkpoint and cache provenance |
| `InstallFailure` | stable `InstallErrorCode`, recoverability, user message key, cause metadata | Typed error boundary |
| `DiagnosticEvent` | timestamp, install ID, operation, level, safe attributes | Redacted structured diagnostic event |

## MRPack Parser Design

### Input and output

`MrPackParser.parse(source: SeekableArchiveSource): ParseResult` streams a ZIP archive and emits either a fully validated `ParsedMrPack` or a typed parse failure. It does not perform network I/O, create instances, or mutate destination storage.

### Supported archive contract

- Require a root-level `modrinth.index.json`; reject absent, duplicate, encrypted, or unreadable manifests.
- Decode JSON with strict structural validation. Format versions are allowlisted, initially version `1` only. Unknown optional fields can be retained by a compatibility DTO without changing domain semantics.
- Require `game == "minecraft"`, nonblank `name` and `versionId`, and a `minecraft` dependency.
- Require exactly one known client loader dependency for v1. Record unsupported or ambiguous loader sets as a compatibility error before download.
- For every file, require a safe relative path, at least one HTTPS download URL, nonnegative bounded size, and at least one supported digest. Preserve `env`: client `required` files are always selected, client `optional` files are presented for user choice, and client `unsupported` files are excluded and reported. Missing client environment follows the Modrinth format default defined for the supported format version.
- Recognize only `overrides/` and `client-overrides/`. Client overrides win on exact path collisions only when product policy explicitly permits it; otherwise collisions fail validation. No executable code is run during parsing.

### ZIP safety

- Enumerate entries before extraction with configurable caps: archive count, individual uncompressed size, total uncompressed size, name length, compression ratio, and JSON size.
- Normalize names with a platform-independent POSIX path checker. Reject absolute paths, drive prefixes, empty segments, `.`/`..`, NUL, backslash normalization ambiguities, duplicate normalized names, and symlink-like entries.
- Never call a generic unzip-to-destination helper. Stream each approved entry to a file created by the storage abstraction only after rechecking that the resolved destination is inside its staging root.
- Parse the manifest from a bounded stream and close every archive/URI stream with `use`.

## Downloader Design

`ArtifactDownloader` consumes validated `PackFile`s and produces verified cache artifacts. The downloader owns transport and cache state; the installer sees only verified local artifacts.

1. Deduplicate by strongest available digest, preferring SHA-512 and using SHA-1 only when the official manifest provides no SHA-512.
2. Check a content-addressed private cache. Rehash before reuse if cache metadata is incomplete or the file age exceeds the verification policy.
3. Select only HTTPS URLs. Follow a limited redirect chain only when each target is HTTPS and passes host/network policy. Do not send authentication, cookies, or device identifiers to artifact hosts.
4. Download to `*.part`, stream bytes through digest calculation, enforce manifest size and per-artifact quota, flush and close, verify the digest, then atomically rename into cache where supported.
5. Use bounded parallelism, initially three concurrent files and one per host, shared byte buffer pooling, exponential backoff with jitter, per-mirror failover, and WorkManager network/battery/storage constraints.
6. Resume only when an existing partial file is associated with the same digest and the server safely supports validated range requests. Otherwise remove the partial through a safe cleanup routine and restart.
7. Emit byte/file progress through a throttled `Flow`; Room checkpoints resume work after process death.

Failures distinguish bad source data, temporary network issues, permanent HTTP failures, cancellation, cache I/O, and storage exhaustion. A digest mismatch blacklists that URL for the current install and tries the next declared mirror; it never installs the unverified result.

## Installer Engine Design

`InstallerEngine.execute(planId)` is the sole writer of installation state. A unique WorkManager job serializes one install; destination-level locking prevents competing jobs from targeting the same instance name.

### Transaction protocol

1. Reload and validate the persisted plan, URI grant, launcher capabilities, disk budget, and prerequisites.
2. Create an app-private staging root at `filesDir/install-staging/<installId>/instance` and write a journal with the intended target and file inventory.
3. Resolve and download all artifacts into the verified cache. Link or copy them into staging using safe relative paths; no downloader writes directly to a launcher tree.
4. Safely extract approved overrides into staging. Apply deterministic collision rules and verify the expected file inventory.
5. Write `.modrith-install.json` as a completion manifest in staging. It records only non-secret pack metadata, source hash, artifact hashes, Modrith version, and target adapter version.
6. Re-probe the target and deploy through `LauncherAdapter.deploy`. Prefer a same-provider atomic directory move; otherwise copy the completed staging tree to an adapter-created temporary destination, verify inventory, then rename or promote. For providers without atomic operations, retain a destination journal and completion marker so recovery can distinguish incomplete work.
7. Call `LauncherAdapter.registerInstance` only when a verified public capability allows it. Persist `Completed` or `CompletedNeedsLauncherImport` with precise next-action metadata.
8. Retain staging on retryable failures for a time-bounded recovery window; purge it after success, cancellation, expiry, or user-confirmed discard.

The engine never overwrites a non-Modrith directory. A name collision produces a new suggested name or requires an explicit future replace workflow. Startup recovery scans journals and converts interrupted work into `Queued`, `Failed`, or safely purged state.

## Storage Access Framework Integration

### Source archive

- Launch `ACTION_OPEN_DOCUMENT` with `CATEGORY_OPENABLE`, `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`, and a MIME set that includes `application/x-modrinth-modpack+zip`, `application/zip`, and `application/octet-stream` because OEM MIME registration is inconsistent.
- Validate extension and contents independently of MIME type.
- Persist only requested read permission; detect and explain non-persistable providers. Copy the archive into private staging if a durable seekable copy is required.

### Launcher destination

- Request `ACTION_OPEN_DOCUMENT_TREE` only after the adapter specifies a needed tree and the user selects the launcher target. Persist read/write flags and record the tree URI, provider authority, and adapter capability fingerprint.
- Wrap `DocumentFile` behind `TreeStorage` and `DocumentStorage` ports. The rest of the app cannot manipulate raw document URIs.
- Before each operation, verify the URI grant, root authority, writable flags, and that child document IDs remain beneath the chosen tree. Treat revoked grants, provider disappearance, and document changes as recoverable target failures.
- Do not request broad media/storage permissions or `MANAGE_EXTERNAL_STORAGE`.

## Launcher Abstraction Layer

```kotlin
interface LauncherAdapter {
    val id: LauncherId
    suspend fun detect(): LauncherDetection
    suspend fun probe(targetHint: LauncherTargetHint?): LauncherCapabilities
    suspend fun validate(plan: InstallPlan, target: LauncherTarget): ValidationResult
    suspend fun prepareDestination(
        target: LauncherTarget,
        instanceName: InstanceName,
    ): PreparedDestination
    suspend fun deploy(
        prepared: PreparedDestination,
        stagedInstance: StagedInstance,
    ): DeploymentResult
    suspend fun registerInstance(
        plan: InstallPlan,
        deployment: DeploymentResult,
    ): RegistrationResult
    suspend fun openLauncher(): OpenLauncherResult
}
```

`LauncherAdapterRegistry` exposes installed adapters and a generic `ExportOnlyAdapter` for future manual delivery. The installer depends on this contract, not package IDs, provider authorities, paths, or profile formats. Capabilities are versioned so an adapter can degrade safely when a launcher update changes its surface.

## CS Launcher V2 Compatibility Strategy

### Evidence to validate in implementation

- Detect installed package ID `com.craftstudio.cslauncher` through package visibility declarations and `PackageManager`.
- Verify an exported document provider authority from the installed package rather than hard-coding one as an unconditional contract.
- Inspect provider root metadata and request the user-selected tree through SAF. The adapter owns expected logical locations such as `custom_instances`, but discovers or creates them only through provider operations.
- Read launcher version and package signatures as compatibility inputs. Maintain `docs/compatibility/cs-launcher-v2.md` with tested version ranges, capability level, test devices/API levels, and known caveats.

### Deployment policy

- `FULL` is permitted only with a documented launcher-owned registration endpoint, such as an intent, provider API, signed contract, or upstream-supported integration, plus end-to-end contract tests.
- `FILES_ONLY` may create a new instance directory under a user-approved CS Launcher tree when provider operations prove the tree writable. It writes pack files and Modrith metadata only; it does not parse or mutate CS Launcher profiles or preferences.
- When the selected tree cannot expose the desired custom-instance path or CS Launcher cannot recognize files-only instances, return `UNAVAILABLE` instead of using raw filesystem paths or private data access.
- An upstream integration proposal should request a stable versioned API for instance creation, loader prerequisite query/install, profile registration, result callback, and an exported destination provider scoped to user-approved data.

## Error Handling Strategy

Use `InstallErrorCode` as the cross-layer contract. Each error contains a safe operation context, whether retry is meaningful, a localized UI message key, and a technical cause retained only in diagnostics. Never expose raw URLs, absolute device paths, stack traces, tokens, or private provider document IDs in normal UI.

| Category | Examples | User action |
| --- | --- | --- |
| `SOURCE_INVALID` | not ZIP, missing manifest, unsupported format, unsafe entry | Choose a valid official pack |
| `PACK_UNSUPPORTED` | server-only, unsupported game/loader, incompatible dependency set | Choose a compatible pack or launcher |
| `URI_ACCESS` | permission revoked, source/provider unavailable | Re-select archive or destination |
| `NETWORK_TRANSIENT` | timeout, DNS, 5xx, disconnected | Retry or resume |
| `ARTIFACT_INTEGRITY` | wrong size/hash, all mirrors fail verification | Retry with a fresh source; report pack issue |
| `STORAGE` | disk full, unreadable cache/tree, quota reached | Free space or re-select destination |
| `LAUNCHER_UNAVAILABLE` | not installed, provider missing, incompatible version | Install, update, or select a launcher |
| `DEPLOYMENT` | collision, failed promotion, target changed | Select another name/tree; retry recovery |
| `CANCELLED` | user/system cancellation | Resume or discard safely |
| `INTERNAL` | invariant or migration bug | Export diagnostics and report |

Exceptions are mapped at module boundaries; no `Throwable` crosses into UI state. `CancellationException` is rethrown to preserve structured cancellation. Retry policy is data-driven and retries only idempotent operations.

## Logging And Diagnostics

- `DiagnosticLogger` emits structured events with `installId`, state, operation, elapsed time, byte counts, result code, adapter ID/version, and a redacted exception class.
- Keep a rolling app-private JSONL log and per-install event stream with capped size/count. Room stores user-visible summaries; log files store detail.
- Release builds log `INFO+` locally and never send telemetry by default. Debug builds may emit richer logs through Timber.
- Redact query strings, authorization headers, cookies, source/destination URI values, absolute paths, account data, and untrusted filenames before persistence or export.
- The history screen exports a user-reviewed, redacted diagnostic bundle containing app/device/adapter versions, capabilities, state transitions, and error codes, never pack binaries or URI grants.

## Update System

### App updates

- Distribute signed builds through the chosen channel, such as Play, an F-Droid-compatible pipeline, GitHub Releases, or an enterprise channel. Decide the channel before release. The app must work without an in-app self-updater.
- Maintain database migrations, WorkManager job schema versioning, and backward-compatible install journal readers. New releases recover or explicitly migrate old interrupted jobs.
- Use dependency update automation with human review, pinned Gradle wrapper checksums, SBOM generation, and signed release artifacts.

### Pack updates

v1 stores enough provenance (`versionId`, source archive digest, selected artifact digests, and install inventory) to detect a later update. It does not mutate a live instance. A future update flow creates a new staged revision, computes file changes, preserves user files through a declared policy, and swaps only after a complete verified deployment.

### Adapter updates

Adapters publish a schema/capability version. A CS Launcher version change outside the tested matrix defaults to a probe and conservative capability downgrade. Remote compatibility data is not required for core operation; any future signed compatibility manifest must fail closed and be independently cached and versioned.

## Security Considerations

- Validate all ZIP names and target paths against traversal, symlink, duplicate, Unicode normalization, and decompression-bomb attacks.
- Enforce HTTPS, bounded redirects, connect/read/call timeouts, response-size limits, and no credentials or cookies on artifact downloads.
- Verify each artifact digest before staging and deployment; do not treat a successful HTTP response as trusted content. Record SHA-1 as legacy manifest compatibility, not as a preferred security primitive.
- Persist only minimal SAF grants. Provide destination/source disconnect controls and delete stale grants when records no longer need them.
- Keep staging, cache, database, and logs app-private. Never use root, broad all-files access, reflection into launcher internals, or writes to `Android/data`.
- Treat launcher package detection and provider metadata as untrusted runtime input. Verify authority ownership, exported access, and write capability before enabling an adapter.
- Prevent intent hijacking with explicit package/component resolution when opening a known launcher; validate every returned URI and never trust extras as authority.
- Use release signing, secret-free source control, CI dependency scanning, reproducible-build investigation, and privacy review before distribution.

## Performance Optimization

- Parse manifests and archive entries in a bounded streaming pass; do not inflate the whole archive or JSON into memory.
- Use buffered I/O and direct streaming from network to `*.part` with incremental digesting.
- Deduplicate artifacts by hash across pack installs and use an LRU cache with configurable disk budget plus pinning for active transactions.
- Limit parallel downloads by network, battery state, and host to prevent thermal pressure, provider overload, and foreground-service churn.
- Batch Room writes for progress checkpoints and throttle UI progress emissions to avoid recomposition and database pressure.
- Perform hashing, ZIP I/O, and document I/O on injected background dispatchers; use Baseline Profiles and Macrobenchmark after core flows are stable.
- Estimate required space before downloading: archive copy/staging, remaining artifact bytes, override expansion, and destination copy multiplier for non-atomic providers. Refuse early when the estimate cannot fit.

## Testing Strategy

| Layer | Focus | Tooling |
| --- | --- | --- |
| Unit | path normalization, manifest rules, loader resolution, state transitions, retry policy | JUnit/Kotest, property-based tests |
| Parser integration | valid/invalid ZIP fixtures, duplicate entries, ZIP bomb limits, malformed JSON, overrides | JVM tests, fixture archives |
| Downloader integration | mirrors, redirects, ranges, resumes, bad hashes, HTTP failures | MockWebServer, temporary filesystem |
| Installer integration | staging/promotion, journal recovery, collisions, cancellation, disk failures | fake storage/launcher ports, Robolectric where needed |
| Adapter contract | detection, capability downgrade, provider tree semantics, registration behavior | Android instrumented tests and CS Launcher test matrix |
| UI | picker state, warning review, progress, recoverable errors, accessibility | Compose UI Test |
| End-to-end | representative official packs on physical Android devices | instrumented/manual release checklist |
| Performance/security | large manifests, constrained RAM/storage/network, malicious archives | Macrobenchmark, fuzz/property tests, static scanners |

Fixtures must be license-reviewed, small, deterministic, and contain no redistributed third-party mod binaries unless explicit permission exists. Network end-to-end tests are opt-in and never gate local developer work.

## Future Launcher Support

Each new launcher gets its own `:launcher:<id>` adapter, ADR, compatibility matrix, capability tests, and explicit storage/profile contract. Candidate adapters may use a launcher-provided document provider, official intent/API, app-owned shared location selected with SAF, or export-only handoff. They must not be added by copying CS Launcher paths or private formats.

Future support for PojavLauncher variants, Fold Craft, or other Java launchers proceeds only after per-launcher legal and technical validation. The generic installer remains unchanged when a new adapter conforms to `LauncherAdapter`; launcher-specific profile creation, loader installation, and launch handoff stay isolated.

## Planning References

- Modrinth `.mrpack` format definition: <https://support.modrinth.com/en/articles/8802351-modrinth-modpack-format-mrpack>
- Android Storage Access Framework: <https://developer.android.com/training/data-storage/shared/documents-files>
- Android 11 scoped-storage restrictions: <https://developer.android.com/about/versions/11/privacy/storage>
- Android long-running WorkManager guidance: <https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running>
- CS Launcher V2 source snapshot inspected for planning: <https://github.com/craftstudioteam/CS-LAUNCHER-v2/tree/69fdeb76eabdf502aee17eb3875487b7b9e6625f>
