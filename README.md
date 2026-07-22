# Modrith

Modrith is an Android installer for official Modrinth `.mrpack` modpacks. It
validates a user-selected archive, resolves its client files, downloads and
verifies artifacts, and transactionally installs them through a
user-authorized Storage Access Framework tree.

## Current Scope

- Modrinth format version 1 archives for Minecraft.
- Fabric, Forge, NeoForge, and Quilt parsing and resolution.
- HTTPS-only downloads with checksum verification, retries, and resumable cache state.
- Transactional installation with rollback and interrupted-session recovery.
- Read-only CS Launcher V2 profile/version inspection.
- SAF-only source and destination access.
- Material 3 Compose workflow with confirmation, progress, logs, retry, cancellation, and result screens.

Launcher metadata is never modified. Profiles that use a separate `gameDir`
are rejected because Modrith cannot safely map a launcher-private path through
the selected SAF tree. The selected profile version and loader must be
detectable and compatible before downloads begin.

## Modules

- `app`: Android application, navigation, Hilt graph, SAF workflow, and durable UI checkpoints.
- `core`: DataStore settings, shared HTTP client, and coroutine dispatchers.
- `models`: Android-free immutable parser, resolver, and downloader contracts.
- `parser`: bounded MRPack ZIP inspection, extraction, and manifest validation.
- `resolver`: dependency normalization, conflict detection, and deterministic install ordering.
- `downloader`: verified resumable downloads with Room-backed checkpoints.
- `filesystem`: SAF tree and app-private cache storage abstractions.
- `installer`: transactional staging, commit, rollback, and recovery engine.
- `launcher`: read-only CS Launcher structure and version inspection.
- `orchestrator`: end-to-end install coordination, checkpoints, progress, and error mapping.
- `ui`: Compose Material 3 screens, ViewModels, navigation models, and settings.
- `utils`: release logging support.

## Security Boundaries

- No `MANAGE_EXTERNAL_STORAGE` or legacy broad storage permissions.
- No root, hidden APIs, reflection, or direct access to launcher private storage.
- No cleartext network traffic.
- No application-data backup or device-transfer export of persisted URI/checkpoint state.
- No launcher profile or preference writes.

## Build And Verification

Use an Android SDK containing API 36. The project compiles with a Java 17
toolchain:

```bash
./gradlew test
./gradlew build
./gradlew lint
```

Pure JVM modules can be checked without an Android SDK:

```bash
./gradlew :models:test :parser:test :resolver:test
```

Release signing credentials are intentionally not stored in this repository.
Configure signing in the release environment before distribution.

See `RELEASE_NOTES.md` and `PROJECT_COMPLETION.md` for the Phase 9 release-readiness status.

## GitHub Codespaces

The repository includes a dev container based on Ubuntu 24.04. It runs Gradle
on OpenJDK 21, retains OpenJDK 17 for the project's compilation toolchain, and
provides VS Code support for Java, Gradle, and Kotlin.

When a Codespace is created, `.devcontainer/postCreate.sh` automatically:

- downloads Google's latest Android SDK command-line tools for Linux;
- accepts the Android SDK licenses;
- installs Platform Tools, Android Platform 36, and Build Tools 36.0.0;
- sets `ANDROID_HOME` and `ANDROID_SDK_ROOT` to `/opt/android-sdk`;
- writes the correct `sdk.dir` to the ignored `local.properties` file; and
- verifies the SDK, Java, and Gradle installations.

To provision a new Codespace, select **Code**, **Codespaces**, and **Create
codespace** in GitHub. For an existing Codespace, open the Command Palette and
run **Codespaces: Rebuild Container**. The SDK provisioning script is
idempotent and runs automatically after the container is rebuilt.

After provisioning completes, the project is ready for:

```bash
./gradlew clean
./gradlew test
./gradlew build
./gradlew lint
```
