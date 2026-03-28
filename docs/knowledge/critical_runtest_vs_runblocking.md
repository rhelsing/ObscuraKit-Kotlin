---
name: runTest vs runBlocking for WebSocket tests
description: Tests using real WebSocket/OkHttp I/O MUST use runBlocking, not runTest. runTest uses virtual time and OkHttp callbacks never fire.
type: feedback
---

Tests that involve real WebSocket connections (OkHttp) or any real I/O timers MUST use `runBlocking`, NOT `runTest`.

**Why:** `runTest` from kotlinx-coroutines-test uses a virtual time dispatcher. OkHttp's WebSocket callbacks run on OkHttp's own thread pool, not the test dispatcher. So `withTimeout(10_000)` inside `runTest` expires instantly because virtual time advances without waiting for real I/O. The `CompletableDeferred` from `connectWebSocket()` never completes.

**How to apply:** Any scenario test that calls `connect()`, `waitForMessage()`, or does real server I/O: use `= runBlocking { }`. Pure local tests (ORM, CRDT, domain logic) can use `= runTest { }`.
