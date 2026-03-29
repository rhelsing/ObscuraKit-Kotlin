# Friend Codes — Implementation Notes

## Format (from JS web client, now shared across all clients)

A friend code is **base64-encoded JSON** containing userId and username:

```
Base64(JSON.stringify({ u: userId, n: username }))
```

Example: `eyJ1IjoiYWJjMTIzIiwibiI6ImFsaWNlIn0=` → `{"u":"abc123","n":"alice"}`

This string gets QR-encoded or shared as text.

## Implementation

Add `lib/src/main/kotlin/com/obscura/kit/FriendCode.kt`:

```kotlin
package com.obscura.kit

import org.json.JSONObject
import java.util.Base64

/**
 * Friend code: base64-encoded JSON containing userId + username.
 * Matches the JS web client and iOS client format.
 * Can be QR-encoded or shared as text.
 *
 * Format: Base64({"u":"<userId>","n":"<username>"})
 */
object FriendCode {
    data class Decoded(val userId: String, val username: String)

    fun encode(userId: String, username: String): String {
        val json = JSONObject().apply {
            put("n", username)
            put("u", userId)
        }
        return Base64.getEncoder().encodeToString(json.toString().toByteArray())
    }

    fun decode(code: String): Decoded {
        val trimmed = code.trim()
            .replace('-', '+')
            .replace('_', '/')
        val bytes = Base64.getDecoder().decode(trimmed)
        val json = JSONObject(String(bytes))
        val userId = json.optString("u", "")
        val username = json.optString("n", "")
        require(userId.isNotEmpty() && username.isNotEmpty()) { "Invalid friend code" }
        return Decoded(userId, username)
    }
}
```

## Usage in Android app

### Generate (show your code)
```kotlin
val myCode = FriendCode.encode(client.userId!!, client.username!!)
// Display as text + QR code
```

### QR generation
Use `com.google.zxing:core`:
```kotlin
val writer = MultiFormatWriter()
val matrix = writer.encode(myCode, BarcodeFormat.QR_CODE, 512, 512)
val bitmap = MatrixToImageWriter.toBufferedImage(matrix)
```

Or Jetpack Compose:
```kotlin
// com.lightspark:compose-qr-code
QrCodeView(data = myCode, modifier = Modifier.size(200.dp))
```

### Scan
Use ML Kit:
```kotlin
val scanner = BarcodeScanning.getClient()
// ... camera preview → scanner.process(inputImage) → barcode.rawValue
val decoded = FriendCode.decode(barcode.rawValue!!)
client.befriend(decoded.userId, decoded.username)
```

### Paste
```kotlin
val decoded = FriendCode.decode(pastedText)
client.befriend(decoded.userId, decoded.username)
```

## Cross-client compatibility

All three clients (JS, iOS, Kotlin) use identical encoding:
- `{"n":"username","u":"userId"}` → base64
- Keys are `u` (userId) and `n` (username) — short to keep QR codes small
- Standard base64 encoding (not URL-safe)
- Decoder handles both standard and URL-safe base64 for robustness

## Security

The friend code is NOT secret. It contains only the userId and username — both are visible to the server anyway. The actual key exchange happens via Signal protocol when `befriend()` is called. Intercepting a friend code doesn't compromise message confidentiality.
