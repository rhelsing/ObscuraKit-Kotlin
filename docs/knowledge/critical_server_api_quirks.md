---
name: Server API quirks at obscura.barrelmaker.dev
description: Critical server behavior that differs from what you'd assume. Password min 12 chars, listDevices wraps in object, empty response bodies, rate limiting.
type: feedback
---

Server: `obscura.barrelmaker.dev` (OpenAPI spec at `/openapi.yaml`)

1. **Password minimum 12 characters.** Server returns 400 for shorter. Test passwords must be 12+ chars (e.g., "testpass123!xyz").

2. **`GET /v1/devices` returns `{ "devices": [...] }`** not a raw array. Must unwrap: `response.getJSONArray("devices")`.

3. **`POST /v1/devices/keys` returns empty body** on success (200 with no JSON). Don't try to parse response as JSONObject.

4. **`POST /v1/gateway/ticket` returns 201** not 200. OkHttp's `isSuccessful` handles this, but don't check for `response.code == 200`.

5. **Registration rate limit is aggressive.** Need `Thread.sleep(500)` between registrations or you get 429. Tests that register multiple users in `@BeforeAll` must space them out.

6. **Backup download with same ETag returns 304.** After `uploadBackup()`, calling `downloadBackup()` with the cached etag returns 304 (not modified) → null. For fresh download, pass etag=null.

7. **No `/health` endpoint.** Use `/openapi.yaml` for server availability checks.

**How to apply:** Check these whenever adding new API calls or tests. The OpenAPI spec at `/openapi.yaml` is the source of truth for response codes and body shapes.
