# ObscuraKit-Kotlin

The **native Android/JVM platform layer** for the Obscura app (`obscura-pix`). It is not a
general-purpose framework, it has exactly one consumer, and it owes API stability to no one.

Read [`CLAUDE.md`](CLAUDE.md) before changing anything. The normative brief is
[`obscura-proto/SPEC.md` §0 — the kit boundary](../obscura-proto/SPEC.md), with the app-facing
contract in [`obscura-proto/KIT_API.md`](../obscura-proto/KIT_API.md).

**Why a native kit exists at all:** libsignal ships only as `libsignal-java` / `libsignal-swift` —
there is no supported shared core, so the Signal protocol must be implemented per platform. And the
push path must decrypt a message with the app closed (on iOS, inside a Notification Service
Extension, which cannot run a React Native runtime). Those two facts, and nothing else, are what
justify native code. Everything else belongs in the app.

> **The reset has landed.** This kit used to carry an ORM, a CRDT engine, a query DSL, a schema
> parser and an audience-routing system — implemented twice, here and in Swift — to serve five flat
> models in one app, none of it reachable from that app. All of it is deleted. Merge and audience
> resolution live in `obscura-pix` now, once. Do not re-add them; `CLAUDE.md` says why, and
> `KIT_API.md` §9 names the shape the regression takes.

*(`obscura-client-web` is a throwaway proof-of-concept. It is **not** a reference implementation and
must not be treated as a porting target.)*

## The rule that governs this repo

> **If the kit reads it, it is a field in `client.proto`.
> If it is not in `client.proto`, the kit MUST NOT read it.**

## Quick Start

```kotlin
val client = ObscuraClient(ObscuraConfig(apiUrl = "https://obscura.barrelmaker.dev"))
client.register("alice", "mypassword123!")
client.connect()

// Befriend someone (the code is base64 JSON, QR-friendly — see FriendCode.kt)
client.addFriendByCode(theirCode)

// SEND: the caller names the recipients. The kit fans out to every device of every
// listed userId plus this user's own other devices, and resolves no audience of its
// own (SPEC §0.4). `payload` is opaque bytes the kit never parses.
client.send(
    recipientUserIds = listOf(friendUserId),
    modelKey = "pix",
    entryId = java.util.UUID.randomUUID().toString(),
    op = "CREATE",
    payload = """{"caption":"hello"}""".toByteArray(),
)

// RECEIVE: a durable inbox, drained by the app. peek / consume / discard / depth,
// and there is no insert — the kit is the only writer.
for (row in client.inbox.peek(limit = 50)) {
    // row.senderUserId is authenticated; row.senderDisplayName comes from OUR friend
    // graph, never from the payload (SPEC §0.5). row.payload is the bytes we sent above.
    handle(row)
}
client.inbox.consume(processedIds)

// STORE: a blind key/value table for whatever the app made of them. No merge, no
// CRDT, no TTL, no query API — the app decides who wins.
client.entries.put("pix", StoredEntry(id = entryId, data = json, sentAt = t, authorDeviceId = d))
client.entries.all("pix")

// Ephemeral typing indicators (not persisted, auto-expire after 3s)
client.sendTyping("directMessage", conversationId)
client.observeTyping("directMessage", conversationId)  // Flow<List<String>>
```

See [docs/AUTHENTICATION.md](docs/AUTHENTICATION.md) for auth and device linking, and
[docs/FRIEND_CODE.md](docs/FRIEND_CODE.md) for the friend-code format.

## Architecture

```
┌──────────────────────────────────────────────────────┐
│  obscura-pix (merge, audience, all app semantics)    │
├──────────────────────────────────────────────────────┤
│  ObscuraClient facade                                │
╞══════════════════════════════════════════════════════╡
│  Level 3: InboxDomain + EntryStore + friends/devices │
│           payload bytes are opaque, never parsed     │
╞══════════════════════════════════════════════════════╡
│  Level 2: Signal Protocol encrypt/decrypt            │
│           18 client-to-client arms in client.proto   │
╞══════════════════════════════════════════════════════╡
│  Level 1: WebSocket + REST (server is a dumb relay)  │
╞══════════════════════════════════════════════════════╡
│  SQLDelight (Signal keys, friends, inbox, entries)   │
└──────────────────────────────────────────────────────┘
```

## What this kit does

Tested with **223 unit** tests (no network) + **96 integration** tests (against a containerized
`obscura-server`) — the counts JUnit reports, parsed from `lib/build/test-results/`, not `@Test`
greps, which over-count by also matching `@TestFactory` and `@TestMethodOrder`.

- **Signal Protocol** — identity, prekeys, sessions, encrypt/decrypt. Sessions are addressed by
  **device UUID** (SPEC §0.10): the inbound session comes from `Envelope.sender_device_id`, prekey
  bundles are selected by device UUID with no fallback, and `registrationId` addresses nothing.
- **Persist-then-ack receive loop** (SPEC §0.9) — an ack is a DELETE on the server, so the kit acks
  only what it has durably written. A decrypt failure, a rate-limited sender, or a failed write all
  leave the message on the server to redeliver.
- **The durable inbox** (`KIT_API.md` §3) — `peek` / `consume` / `discard` / `depth`, deduped on
  `envelope_id` because persist-then-ack guarantees redelivery.
- **The entry store** (§8.1) — `put` / `all` / `delete` over opaque JSON. Three methods, no fourth.
- **Friend graph** — request/response/accept, device lists learned from DEVICE_ANNOUNCE, with the
  peer's recovery key pinned trust-on-first-use.
- **Device provisioning, linking and revocation** — `loginAndProvision()` → `PENDING_APPROVAL` →
  QR/link-code approval, which carries the p2p keys, own-device list and friends export.
- **Transport** — REST + gateway WebSocket with auto-reconnect and token refresh; the offline queue
  is the server's, not ours.
- **Attachment crypto** — upload/download with an AES key shipped over Signal.
- **Push-wake drain** — `processPendingMessages(timeoutMs)` returns counts so the bridge can post a
  generic local notification. The kit never posts an OS notification itself.
- **Ephemeral signals** — typing indicators, in memory only, throttled to 2s and expiring after 3s.
  Audience is the canonical two-party conversation id, resolved fail-closed on both send and receive.

## What this kit deliberately does not do

- **No merge, no CRDT, no TTL, no query DSL, no schema, no audience resolution.** All of it moved to
  `obscura-pix`. `EntryStore.all(model)` returns everything and the app filters.
- **No OS notifications**, no UI, no application field names. `modelKey` is opaque throughout.
- **No eviction policy on the inbox.** Rows leave only by an explicit `consume`/`discard` from the
  app, or by the security carve-out in a device wipe (§3.3 rule 2).

## Cross-kit status

`ObscuraKit-swift` must agree with this kit on the **wire** (`conformance/wire.json`) and nothing
more. Known divergences live in `obscura-proto/HISTORY.md`; the ones this kit knows about today:

- Swift stores `recovery_public_key` on its friend rows but never writes it, so its DEVICE_ANNOUNCE
  check is still dead. This kit pins and enforces it.
- `ObscuraError.InvalidSchema` and `DirectRoutingUnresolved` were deleted here; the Swift twin and
  `obscura-pix/src/native/ObscuraModule.ts`'s error union still carry them.

## Build & Test

```bash
export JAVA_HOME=/path/to/jdk-21

./gradlew :lib:test                              # 223 unit tests (fast, no network)
./gradlew :lib:integrationTest                   # 96 server-dependent tests
./gradlew :lib:koverHtmlReport                   # coverage report
```

The integration suite targets `https://obscura.barrelmaker.dev` by default but honors
`OBSCURA_TEST_API` — CI points it at a containerized `obscura-server` (rate limits disabled) so the
suite runs on every PR without touching prod. Each integration test is gated on
`assumeTrue(checkServer())`, so it skips-not-fails when no server is reachable. It also needs the
server *correctly configured*: seed the MinIO `test-bucket` and raise the auth rate limit, or you
get ~63 environmental failures (HTTP 429/500) that are not code failures.

**A non-void `@Test` is silently ignored by JUnit 5.** This has bitten twice. If a test body ends in
`assertThrows(...)`, add a trailing `Unit`.

## Docs

- [Authentication](docs/AUTHENTICATION.md) — register, login, device linking, session restore
- [Friend codes](docs/FRIEND_CODE.md) — the QR/paste format
- [`docs/knowledge/`](docs/knowledge) — hard-won lessons; read before touching the codebase

## Dependencies

- `org.signal:libsignal-client` — Signal Protocol
- `com.google.protobuf:protobuf-kotlin` — wire format
- `app.cash.sqldelight:sqlite-driver` — persistence
- `com.squareup.okhttp3:okhttp` — HTTP + WebSocket
- `org.jetbrains.kotlinx:kotlinx-coroutines-core` — async
- `org.jetbrains.kotlinx:kotlinx-serialization-json` — JSON in the crypto/backup helpers
- `org.json:json` — JSON parsing

## Server

- **API:** https://obscura.barrelmaker.dev
- **Spec:** https://obscura.barrelmaker.dev/openapi.yaml
