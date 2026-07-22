# Modrith 0.1.0

Release-readiness review completed July 21, 2026.

## Included

- Safe Modrinth format-v1 MRPack parsing and validation.
- Dependency and client-file resolution for supported Minecraft loaders.
- HTTPS downloads with retry, resume, size checks, and SHA-1/SHA-512 verification.
- SAF-backed destination storage and app-private verified cache storage.
- Transactional installation with staging, backup, rollback, cancellation, and recovery.
- Read-only CS Launcher profile and version inspection.
- Compose Material 3 installation workflow with durable process-death restoration.
- Live installation progress and redacted diagnostic events.

## Phase 9 Hardening

- Disabled application-data backup and device-transfer extraction.
- Explicitly disabled cleartext traffic.
- Removed unused foundation database, worker, marker interfaces, ViewModel, and logger facade.
- Removed unused direct Gradle dependencies.
- Prevented exception stack traces and absolute paths from entering structured install logs.
- Added fail-closed launcher profile, Minecraft version, and loader compatibility checks before download.
- Documented release configuration, security boundaries, and external release prerequisites.

## Known Release Prerequisites

- Configure owner-controlled release signing outside the repository.
- Select and add the project software license.
- Complete physical-device SAF and CS Launcher compatibility testing.
- Restore an Android SDK containing API 36 for full repository build, test, lint, and instrumented verification.
