# LITV2 AI Development Guide

## 1. Project purpose

LITV2 is a privately deployed IPTV source-management and playback platform for fnOS. It does not provide, bundle, host, or distribute media sources. Users are responsible for the legality of imported sources.

## 2. System boundaries

- `server/`: Go service. Owns authentication, SQLite data, source parsing, EPG, streaming, recording, timeshift, remote control, DLNA and the HTTP API.
- `admin/`: Vue 3 + TypeScript + Vite administration UI, deployed below `/app/litv/`.
- `android/`: Android TV and phone client. Media3 is primary; VLC and mpv are fallback engines.
- `ios/`: SwiftUI client with MobileVLCKit and AVPlayer fallback.
- `packaging/fnos/`: fnOS package manifests, lifecycle scripts and install/uninstall wizards.
- `build/test/`: integration and smoke-test scripts.
- `docs/design/`: architecture and API design documents.

## 3. Core security model

1. Browser administration normally enters through the fnOS Unix-socket gateway.
2. `X-Trim-*` identity headers are trusted only for requests known to come through that gateway.
3. TCP requests must never become administrators by supplying `X-Trim-*` headers.
4. Native clients authenticate with device bearer tokens.
5. The server stores token hashes, not plaintext tokens.
6. Stream URLs use HMAC signatures with expiration.
7. Emergency password login is an exceptional path and must be rate-limited, audited and reviewed whenever changed.

## 4. Source-of-truth rules

- Edit source files, never generated or packaged copies.
- Do not edit `admin/dist`, `server/internal/web/www`, Android build output, generated bindings or Gradle intermediates.
- When an API changes, update the Go handler, TypeScript/Kotlin/Swift types, mock implementation, tests and API documentation together.
- The server response envelope is `{ code, msg, data }`. Do not silently accept unrelated response shapes without documenting compatibility.
- Pages and clients must not construct privileged server URLs from untrusted input.

## 5. Files AI must ignore

Do not read or modify these unless the task explicitly concerns generated artifacts:

```text
**/node_modules/**
**/dist/**
**/build/**
**/generated/**
**/intermediates/**
**/.gradle/**
**/.git/**
server/internal/web/www/**
packaging/fnos/app/server/litvd_*
```

## 6. Required engineering rules

### Administration UI

- Production builds must never enable the mock API through URL parameters or browser storage.
- Keep API calls in `admin/src/api/modules` rather than Vue templates or unrelated components.
- Treat 401 as unauthenticated and 403 as authenticated but unauthorized.
- Destructive operations need clear confirmation text describing their scope.
- Do not log passwords, bearer tokens, signed stream URLs or imported source credentials.

### Go server

- Validate request bodies, path IDs, sizes and allowed values at the HTTP boundary.
- Use parameterized SQL. Never concatenate untrusted values into SQL statements.
- Multi-record destructive operations must use transactions.
- Reject unknown or malformed authentication contexts.
- Authentication endpoints require rate limiting and security logging without secrets.
- Outbound fetchers must consider SSRF. Restrict sensitive local and link-local destinations where product requirements permit.
- File deletion must prove that the canonical target belongs to an approved application-owned root.

### Android and iOS

- Prefer HTTPS. Plain HTTP may be supported for user-owned LAN servers, but the UI must make the transport choice explicit.
- Never disable TLS certificate or hostname verification.
- Do not emit tokens or complete signed URLs through logs or crash reports.
- Persist authentication tokens using platform-protected storage when available.
- Release builds must not include verbose HTTP logging.
- Player changes must validate release, activity lifecycle, rapid channel switching, network recovery and bounded retry behavior.

### fnOS lifecycle scripts

- Uninstall defaults to preserving data.
- Recursive deletion is allowed only after canonical-path validation against documented application-owned roots.
- Never delete a path solely because its text contains `litv`.
- Do not follow symlinks outside approved roots.
- Log skipped and failed cleanup actions without exposing secrets.

## 7. Change workflow

Before editing:

1. Read `README.md`, the relevant design document and all directly affected source files.
2. Check `git status` and preserve unrelated user changes.
3. State whether the change affects authentication, deletion, networking, storage, playback or packaging.

After editing:

1. Run the narrowest relevant tests first.
2. Run formatting and type or compile checks.
3. Review the diff for generated files, credentials and accidental binary changes.
4. Update documentation when behavior or contracts changed.
5. Report what was verified and what remains unverified.

## 8. Toolchains: use WSL, not Windows

**The Windows host has no Go, JDK or Gradle. All toolchains live in WSL (Ubuntu).**
Do not spend time searching for a Windows Go/JDK — there is none, and there is no bundled
Go under `.review-artifacts/toolchains/go` that works from Windows (its build cache is
outside the writable workspace and fails with `Access is denied`).

Every build and test command must be run through WSL, from the repo as seen at
`/mnt/d/Documents/deepseek-harness/default-workspace/LITV2`:

```bash
wsl.exe -e bash -lc '<command>'
```

Verified WSL inventory (re-verify before trusting any version number):

| Tool | Location / command | Version seen |
|---|---|---|
| Go | on `PATH` in WSL | `go1.23.12 linux/amd64` |
| Node / npm | on `PATH` in WSL | `v22.22.1` / npm 11 |
| JDK | `source ~/android-dev/env.sh` → `$JAVA_HOME` | `openjdk 21.0.12.1` |
| Gradle | `~/android-dev/gradle-dist/gradle-8.7/bin/gradle` | 8.7 |
| Android SDK | `~/android-dev/sdk` (also `ANDROID_HOME`) | `platforms/android-35`, `build-tools/34.0.0`, `35.0.0` |
| Android NDK | `$ANDROID_NDK_HOME` | `27.0.12077973` |

`android/gradlew` is a **shim, not a real wrapper**: it sources `~/android-dev/env.sh`
and execs the local Gradle 8.7 distribution, because the standard wrapper tries to
reach `services.gradle.org`, which fails from this network. Run `./gradlew` from
inside WSL; do not regenerate the wrapper casually.

`android/local.properties` already points at `sdk.dir=/home/Z/android-dev/sdk`, so the
Android build only works from WSL — Windows paths would break it.

**The output of `wsl.exe` piped into Git Bash is UTF-16 and renders as garbage.**
Pipe through `iconv -f UTF-16LE -t UTF-8` or `tr -d '\r'` when reading localized text;
ASCII build output is usually fine as-is.

### GitHub Actions is the second supported build path

`.github/workflows/release.yml` builds all three targets on hosted runners and is the
**only** path that can produce iOS — iOS needs Apple's SDK, so it cannot be built on
Windows or Linux, only on the `macos-latest` job. Use the workflow when:

- you need an iOS build, or
- a local WSL build is blocked (missing tool, network, disk), or
- you want the same artefact the release actually ships.

Trigger it with workflow_dispatch or by pushing a `v*` tag. Note the known defects in
that workflow: the version is taken from `DEFAULT_VERSION`/input rather than the pushed
tag, `build.sh` is called without `--version`, and the publish step can delete a
release/tag other than the one being built. Treat CI as a build path, not yet as a
trustworthy release path.

## 9. Standard verification commands

Run these **inside WSL** (`wsl.exe -e bash -lc '...'`), from the repo path above.

### Go server

```bash
cd server
gofmt -w <changed-go-files>
go build ./...
go test ./...
go vet ./...
```

`go test ./...` currently reports `[no test files]` for every package: **the repository
has no Go unit tests.** Exit code 0 from that command is not evidence of correctness.
When you fix behaviour, add the `_test.go` files rather than citing this as a pass.

### Administration UI

```bash
cd admin
npm ci
npm run typecheck
npm run build
```

### Android

```bash
cd android
./gradlew lintDebug testDebugUnitTest assembleDebug
```

Release/R8 behaviour (JNI callbacks and Gson reflection) needs an `assembleRelease`
check; a debug-only pass does not prove it.

### fnOS package

```bash
cd packaging/fnos
./build.sh              # x86; --arch arm for ARM
```

Verify the frontend actually embedded in the package, not just that the `.fpk` was
produced — `go:embed` only serves `server/internal/web/www`, and the packaging script
also stages an external `www` that the handler never reads.

### iOS

Not buildable locally. Use the `ios` job in the GitHub Actions release workflow.

### Integration

Run the relevant scripts in `build/test/`. Authentication, binding, stream signing, recording, uninstall and upgrade changes require dedicated regression tests.

## 10. Definition of done

A change is complete only when:

- Source, clients, mocks and documentation agree.
- Relevant type checks, tests and builds pass.
- No secret, signing credential or generated build output was added.
- Destructive behavior has an explicit scope, safe failure behavior and a regression test.
- Security-sensitive changes explain the threat addressed and the remaining limitations.
