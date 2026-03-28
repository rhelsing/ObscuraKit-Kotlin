---
name: Multi-device tests must drain ALL device queues
description: befriend() fans out to ALL devices. If Bob has 2 devices, both get FRIEND_REQUEST. Tests must consume messages on ALL connected devices or stale messages poison subsequent assertions.
type: feedback
---

`befriend()` and `sendToAllDevices()` send to EVERY device of the target user. If Bob has devices bob1 and bob2, both receive the FRIEND_REQUEST.

**The bug:** In setup, if you only call `bob1.waitForMessage()` to drain the FRIEND_REQUEST, bob2's WebSocket queue still has it. When test 2 calls `bob2.waitForMessage()` expecting a TEXT, it gets the stale FRIEND_REQUEST instead.

**The fix:** In `@BeforeAll` setup, connect ALL devices before befriending, and drain ALL of them:
```kotlin
bob1.connect(); bob2.connect(); alice.connect()
alice.befriend(bob1.userId!!, bobUsername!!)
bob1.waitForMessage() // FRIEND_REQUEST on bob1
bob2.waitForMessage() // FRIEND_REQUEST on bob2 (fan-out!)
bob1.acceptFriend(...)
alice.waitForMessage() // FRIEND_RESPONSE
```

**How to apply:** Any test involving multiple devices of the same user must account for fan-out. Every `befriend()`, `send()`, `sendModelSync()`, etc. produces N messages where N = number of target devices.
