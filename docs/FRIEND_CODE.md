# Friend codes

A friend code is **base64-encoded JSON** carrying a userId and a username:

```
Base64({"u": "<userId>", "n": "<username>"})
```

Example: `eyJ1IjoiYWJjMTIzIiwibiI6ImFsaWNlIn0=` → `{"u":"abc123","n":"alice"}`

The keys are one character each to keep the QR code small — fewer modules is easier to scan. The
string is QR-encoded or shared as text.

## API

`FriendCode` (`lib/src/main/kotlin/com/obscura/kit/FriendCode.kt`) is the codec, and the facade
delegates to it:

```kotlin
val myCode = client.friendCode()         // FriendCode.encode(userId, username)
client.addFriendByCode(scannedOrPasted)  // FriendCode.decode(...) then befriend(...)
```

`addFriendByCode` additionally strips whitespace and soft hyphens (`U+00AD`), which survive a copy
out of an iOS share sheet.

`decode` maps `-`→`+` and `_`→`/` before decoding, because some QR scanners hand back the URL-safe
alphabet, and rejects a payload with an empty `u` or `n`. Both behaviours are pinned by
`FriendCodeTest`.

> Until 2026-08-01 `ObscuraClient.friendCode()` and `addFriendByCode()` each inlined their own copy
> of this codec, and the copies had drifted from the tested one: the inline decode did neither the
> URL-safe mapping nor the empty-field check, so a code decoding to `{}` befriended the
> empty-string user. The seven tests in `FriendCodeTest` covered only the object nobody called.

## Cross-client compatibility

All clients use the same encoding: `{"u": userId, "n": username}`, standard base64 on the way out,
tolerant of the URL-safe alphabet on the way in.

## Security

**The code is not secret.** It carries only a userId and a username, both of which the server sees
anyway. It is a pointer: the actual key exchange happens over Signal when `befriend()` runs, and the
recipient's session pins the sender's identity key on first contact (TOFU). Intercepting a friend
code does not compromise message confidentiality.
