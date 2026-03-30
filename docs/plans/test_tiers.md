# Test Tiers Plan

## Tier 1: Unit Tests (no server) — CURRENT FOCUS

Prove Layer 2 and Layer 3 primitives in isolation. These run fast, offline, and must pass before anything else is trustworthy.

### Done
- [x] CRDTTests — GSet idempotency, merge, persistence, sort, filter; LWWMap timestamp conflict, concurrent devices, tombstones, far-future clamping, persistence
- [x] SignalStoreTests — Identity generation, persistence, reload, TOFU, identity change callback, prekey lifecycle (store/load/remove/count/highest), signed prekeys, session CRUD, multi-device sessions, deleteAll isolation
- [x] TTLTests — Parse all units (s/m/h/d), schedule expiration, expired entries filtered from getAll/find, cleanup removes expired, isExpired reports correctly
- [x] ModelTests — Schema definition, create with validation, find, query, upsert (LWW), delete (LWW + GSet rejection), where/first/count, handleSync for both CRDTs, TTL scheduling on create, multi-model isolation
- [x] VerificationCodeTests — 4-digit codes, deterministic, device list codes

### TODO
- [ ] SyncManager targeting: belongs_to group members (group-targeted sync)
- [ ] Association-based targeting in SyncManager (groupMessage → group members)

## Tier 2: Integration Tests (live server) — NEXT

Prove Layer 2 reliability under stress. These require the server but test adversarial/edge conditions.

### Planned
- [ ] Prekey exhaustion: drain prekeys, verify replenishment triggers, messaging continues
- [ ] Session reset recovery: reset session, next message auto-rebuilds via prekey fetch
- [ ] Concurrent sends: two devices send to same recipient simultaneously, both arrive
- [ ] Offline queue depth: queue 50+ messages offline, reconnect, all drain
- [ ] Token refresh during batch send: force expiry mid-send, verify refresh + completion
- [ ] Decrypt failure: verify graceful handling when envelope is invalid (logged, ACK'd, not crash)
- [ ] Multi-device MODEL_SYNC: story created on device1, device2 receives via self-sync
- [ ] Private MODEL_SYNC isolation: settings model sent, friend does NOT receive
- [ ] Group-targeted MODEL_SYNC: groupMessage targets only group members

## Tier 3: Scenario Tests (app developer surface) — LATER

Prove the "Rails developer" experience end-to-end. Each test reads like an app demo.

### Planned
- [ ] Story lifecycle: create → appears in friend's feed → TTL expires → gone
- [ ] Group chat: create group → send groupMessage → only members receive → non-members don't
- [ ] Social feed: stories with comments and reactions, eager-loaded via include()
- [ ] Private settings: change theme on phone → tablet syncs → friend never sees
- [ ] Multi-app: snap clone and insta clone define different schemas, same user, same friends, each ignores the other's models
- [ ] Offline-first: create story while offline → reconnect → friend receives → conversation state correct
- [ ] Device linking: provision new device → existing stories sync via SYNC_BLOB → new device has full state

## Test Principles

1. **Tier 1 tests never touch the network.** In-memory DB, direct class instantiation.
2. **Tier 2 tests use the public API.** No reaching into internals.
3. **Tier 3 tests read like app demos.** A product person should understand them.
4. **If a scenario test reaches into internals, the facade is missing a method.**
5. **All tests verify state, not just delivery.** Check conversations, friend lists, model entries — not just "message arrived."
