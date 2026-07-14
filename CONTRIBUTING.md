# Contributing to ObscuraKit-Kotlin

Thank you for your interest in contributing.

## Development Setup

**Requirements:** JDK 21, Docker (for integration tests).

```bash
git clone --recurse-submodules https://github.com/barrelmaker97/ObscuraKit-Kotlin
cd ObscuraKit-Kotlin
JAVA_HOME=/path/to/jdk-21 ./gradlew :lib:test    # unit tests, no network
```

## Project Structure

See `README.md` for the three-level architecture. Key files:

| File | Purpose |
|------|---------|
| `lib/src/main/kotlin/com/obscura/kit/ObscuraClient.kt` | Public facade |
| `lib/src/main/kotlin/com/obscura/kit/network/` | Level 1 — transport |
| `lib/src/main/kotlin/com/obscura/kit/stores/` | Level 2 — Signal crypto |
| `lib/src/main/kotlin/com/obscura/kit/orm/` | Level 3 — CRDT ORM |

## Tests

- **Unit tests** (`src/test`) — no network, run on every PR: `./gradlew :lib:test`
- **Integration tests** (`src/integrationTest`) — require a live server. See `ci.yml` for
  the Docker compose setup. Run with `./gradlew :lib:integrationTest`.

Read `docs/knowledge/` before touching any of the subsystems — each file documents a
hard-won non-obvious constraint.

## Pull Request Guidelines

1. All unit tests must pass.
2. New public API must have KDoc.
3. Wire/protobuf/CRDT conformance must be preserved. Do not change proto field numbers.
4. Check `CHANGELOG.md` — add an entry under `[Unreleased]` for any user-visible change.
5. Signal-sensitive changes need corresponding unit tests.

## Releasing

1. Bump `version` in `gradle.properties`.
2. Move the `[Unreleased]` section in `CHANGELOG.md` to a new versioned section.
3. Tag the release commit: `git tag vX.Y.Z && git push --tags`.
4. GitHub Actions will publish the artifact to Maven via `publishToMavenLocal` (smoke).
   Add a remote repo target in `build.gradle.kts` for real distribution.
