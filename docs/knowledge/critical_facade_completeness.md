---
name: Keep scenario tests on the public facade
description: Supported user flows should use ObscuraClient; raw protobuf is reserved for adversarial wire tests.
type: feedback
---

The rule: **supported user behavior uses the public facade.**

If you find yourself writing `ClientMessage.newBuilder().setType(...)` in a test, stop. Add a method to `ObscuraClient` instead: `send()`, `sendModelSync()`, `announceDeviceRevocation()`, etc.

Raw protobuf is appropriate when the test intentionally creates input that no
public API should expose. Current exceptions are:

- `AckSemanticsTests`: corrupted ciphertext and acknowledgement behavior.
- `FriendGraphIntegrityTests`: forged friend request/response payloads.

All other matches are either unused imports or evidence of a missing facade
method.

**Review matches with:**

```bash
rg -n "obscura\.v1\.|obscura\.client\.v1\.|ClientMessage\.newBuilder|com\.google\.protobuf" \
  lib/src/integrationTest/kotlin/scenarios
```

The generated packages are `obscura.v1` and `obscura.client.v1`; checks must
include both.
