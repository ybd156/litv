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

## 8. Standard verification commands

### Go server

```bash
cd server
gofmt -w <changed-go-files>
go test ./...
go vet ./...
```

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

### Integration

Run the relevant scripts in `build/test/`. Authentication, binding, stream signing, recording, uninstall and upgrade changes require dedicated regression tests.

## 9. Definition of done

A change is complete only when:

- Source, clients, mocks and documentation agree.
- Relevant type checks, tests and builds pass.
- No secret, signing credential or generated build output was added.
- Destructive behavior has an explicit scope, safe failure behavior and a regression test.
- Security-sensitive changes explain the threat addressed and the remaining limitations.
