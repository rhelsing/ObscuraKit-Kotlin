---
name: Protobuf and SQLDelight naming gotchas
description: Proto field p2p_public_key becomes p2PPublicKey (not p2pPublicKey). SQLDelight column 'data' becomes 'data_'. These cause compile errors that waste time.
type: feedback
---

**Protobuf Kotlin DSL naming:**
- `p2p_public_key` → `p2PPublicKey` (capital P after the 2)
- `p2p_private_key` → `p2PPrivateKey`
- NOT `p2pPublicKey` — the codegen capitalizes after each underscore boundary

**SQLDelight column escaping:**
- Column named `data` becomes `data_` in generated Kotlin (reserved word)
- Use `data_` in all insert/query calls: `insertEntry(data_ = jsonString, ...)`

**OkioByteString vs protobuf ByteString:**
- OkHttp WebSocket uses `okio.ByteString`
- Protobuf uses `com.google.protobuf.ByteString`
- Import carefully. For WebSocket send: `OkioByteString.of(*frame.toByteArray())`
- For proto fields: `ByteString.copyFrom(bytes)`

**How to apply:** When writing new proto message builders or SQLDelight queries, watch for these. The compiler errors are confusing if you don't know the naming convention.
