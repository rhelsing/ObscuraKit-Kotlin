---
name: Raw protobuf in tests = missing facade method
description: If a test constructs ClientMessage.newBuilder() directly, that's a sign ObscuraClient is missing a public method. Every proto usage in test code should be replaced with a clean facade call.
type: feedback
---

The rule: **zero protobuf imports in scenario test files.**

If you find yourself writing `ClientMessage.newBuilder().setType(...)` in a test, stop. Add a method to `ObscuraClient` instead: `send()`, `sendModelSync()`, `announceDeviceRevocation()`, etc.

**Why:** The tests are the proof that the library API is complete. If tests need raw proto, views would too — and views should never touch protobuf.

**How to verify:**

```bash
grep -rn "obscura\.v1\.\|obscura\.client\.v1\.\|ClientMessage\.newBuilder\|com\.google\.protobuf" \
  lib/src/integrationTest/kotlin/scenarios/
```

Should be empty for every file. (This used to grep for `obscura.v2.`, a package that has never
existed in this repo — the generated packages are `obscura.v1` and `obscura.client.v1` — so the
check reported a clean result no matter what the files contained.)

**The refactor pattern:** Working test with raw proto → extract the proto building into an ObscuraClient method → test calls the clean method → verify tests still pass.
