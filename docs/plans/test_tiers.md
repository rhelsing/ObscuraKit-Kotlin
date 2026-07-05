# Test Tiers

Two Gradle source sets, two CI jobs:

| Suite | Task | Tests | Network | When |
|---|---|---|---|---|
| **Unit** | `:lib:test` | 297 | none (in-memory SQLite, pure crypto) | every PR — the fast gate |
| **Integration** | `:lib:integrationTest` | 109 | live/containerized `obscura-server` | every PR (against a container in CI) |

**Rule:** a test that does not talk to the server belongs in `src/test`, not
`src/integrationTest`. Integration tests are gated on `assumeTrue(checkServer())`
so they skip-not-fail when no server is reachable; `OBSCURA_TEST_API` repoints
them at a local container.

Current unit-suite coverage (Kover): ~57% instruction / ~67% line. Regenerate
with `:lib:koverHtmlReport`.

---

## Tier 1 — Unit tests (`src/test`, no server)

Prove the Layer 2/3 primitives in isolation. Fast, offline, run on every PR.

- **CRDTs** — `GSetTest`, `LWWMapTest`: idempotent add/merge, tombstones,
  LWW timestamp conflicts + far-future clamp, order-independent convergence,
  reload-from-DB persistence.
- **ORM** — `ModelTests`, `ModelValidationTest`, `QueryBuilderTests`,
  `TypedModelTests`, `TTLManagerTest`, `DeviceIdPropagationTest`,
  `ObserveAndIncludeTests`: schema define/validate, create/find/upsert/delete,
  the full query operator + DSL surface, typed encode/decode, TTL parse/expiry/
  cleanup, observe() + include() eager loading.
- **Signal** — `SignalStoreTests`, `SignalKeyUtilsTest`, `ConnectionUnitTests`:
  identity/prekey/session persistence, signed-prekey signature generation,
  reconnect backoff + signal throttle mechanics.
- **Crypto** — `Bip39Test`, `BackupCryptoTest`, `RecoveryKeysTest`,
  `SyncBlobTest`, `AttachmentCryptoTest`, `LinkCodeTest`, `VerificationCodeTest`,
  `UuidCodecTest`, `Base64ExtensionsTest`.
- **Stores / misc** — `FriendDomainTest`, `DeviceDomainTest`, `MessageDomainTest`,
  `FriendCodeTest`, `ObscuraConfigTest`, `ObscuraEventTest`, `ApiTypesTest`,
  `HttpExceptionTest`, `SyncTargetingTests`, `PixViewOnceTests`.

### Known unit gaps (need a server or heavy mocking — covered by Tier 2)
`ObscuraClient` facade, `AuthManager`, `DeviceManager`, `MessagingManager`,
`MessageSender`, `APIClient`, `GatewayConnection`, `MessengerDomain`,
`ClientSyncManager`, `RecoveryManager`, `FriendshipManager` read low in the
unit-only coverage report because their behavior is exercised by the
integration suite, not by unit tests.

---

## Tier 2 — Integration tests (`src/integrationTest`, server-dependent)

Prove Layer 1/2 reliability end-to-end against a real server. In CI these run
against `ghcr.io/barrelmaker97/obscura-server:latest` with rate limits disabled.

Implemented (109 tests): core register/login/connect flows, ORM auto-sync +
offline sync + wire format, messaging + recovery messaging, multi-device
fan-out + linking + takeover + revocation, device link flow, session
reset/reconnect + reconnect resilience, attachments + story attachments, push,
sync + backup, Signal ECS + edge cases, pix flow, refresh-scope parity,
persistence, general edge cases.

### Remaining Tier 2 candidates
These are genuine gaps, but each currently has **no public API surface** to drive
or observe it — the relevant logic (`checkAndReplenishPreKeys`, decrypt-failure
handling, token refresh) is private to `ObscuraClient`. Per principle #2 below,
testing them cleanly needs a facade hook first (a production change), not
internals-poking tests. Tracked here so that lands with the feature:
- Prekey exhaustion -> replenishment -> messaging continues
- Token refresh forced mid-batch-send -> refresh + completion
- Decrypt failure on a malformed envelope -> logged, ACK'd, no crash

_Validation note: the full 95-scenario suite has been run green against the
containerized `obscura-server` locally, confirming the CI setup end-to-end._

---

## Test principles

1. Tier 1 never touches the network. In-memory DB, direct instantiation.
2. Tier 2 uses the public API. If a Tier 2 test reaches into internals, the
   facade is missing a method.
3. Tests verify state (friend lists, conversations, model entries), not just
   delivery.
