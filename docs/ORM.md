# ORM Guide

How to build features on ObscuraKit. Everything here is tested and working against the live server.

## Setup

```kotlin
val client = ObscuraClient(ObscuraConfig(apiUrl = "https://obscura.barrelmaker.dev"))
client.register("alice", "mypassword123!")
client.connect()
```

That's it. You have an authenticated, connected client with E2E encryption ready. Now define your models.

## Defining Models

There are two ways to work with models: **typed** (recommended) and **untyped**. Typed models give you compile-time safety and autocomplete. Untyped models give you runtime flexibility.

### Typed Models (Recommended)

Define your models as data classes with `@Serializable`:

```kotlin
@Serializable
data class Story(val content: String, val authorUsername: String, val mediaUrl: String? = null)

@Serializable
data class DirectMessage(val conversationId: String, val content: String, val senderUsername: String)

@Serializable
data class Profile(val displayName: String, val bio: String? = null)

@Serializable
data class AppSettings(val theme: String, val notificationsEnabled: Boolean)
```

Register them at startup:

```kotlin
client.orm.define(mapOf(
    "story" to ModelConfig(
        fields = mapOf("content" to "string", "authorUsername" to "string", "mediaUrl" to "string?"),
        sync = "gset",
        ttl = "24h"
    ),
    "directMessage" to ModelConfig(
        fields = mapOf("conversationId" to "string", "content" to "string", "senderUsername" to "string"),
        sync = "gset"
    ),
    "profile" to ModelConfig(
        fields = mapOf("displayName" to "string", "bio" to "string?"),
        sync = "lww"
    ),
    "settings" to ModelConfig(
        fields = mapOf("theme" to "string", "notificationsEnabled" to "boolean"),
        sync = "lww",
        private = true
    )
))

// Wrap in typed accessors
val stories = TypedModel.wrap<Story>(client.orm.model("story"))
val messages = TypedModel.wrap<DirectMessage>(client.orm.model("directMessage"))
val profiles = TypedModel.wrap<Profile>(client.orm.model("profile"))
val settings = TypedModel.wrap<AppSettings>(client.orm.model("settings"))
```

Now use them with full type safety:

```kotlin
// Create
stories.create(Story(content = "Beach day!", authorUsername = "alice"))
messages.create(DirectMessage(conversationId = friendId, content = "Hey!", senderUsername = "alice"))
profiles.upsert("profile_alice", Profile(displayName = "Alice", bio = "hello"))
settings.upsert("my_settings", AppSettings(theme = "dark", notificationsEnabled = true))

// Query — returns TypedEntry<Story> with .value: Story
val aliceStories = stories.where { "authorUsername" eq "alice" }.exec()
aliceStories.forEach { println(it.value.content) }  // compile-safe field access

// Observe — returns Flow<List<TypedEntry<Story>>>
val feed = stories.observe().collectAsState(emptyList())
```

### Untyped Models

For dynamic schemas or when you don't need compile-time safety:

```kotlin
client.orm.define(mapOf(
    "story" to ModelConfig(
        fields = mapOf("content" to "string", "mediaUrl" to "string?"),
        sync = "gset",
        ttl = "24h",
        hasMany = listOf("comment", "reaction")
    ),
    "comment" to ModelConfig(
        fields = mapOf("text" to "string", "storyId" to "string"),
        sync = "gset",
        belongsTo = listOf("story")
    )
))

val story = client.orm.model("story")
story.create(mapOf("content" to "Beach day!", "mediaUrl" to null))
```

Both approaches use the same underlying ORM, same CRDT, same sync, same wire format. Typed models just add a serialization layer on top.

### Field Types

- `"string"` — text
- `"number"` — integer or decimal
- `"boolean"` — true/false
- `"timestamp"` — positive integer (epoch ms)
- Append `?` for optional: `"string?"`, `"number?"`

Required fields are validated on `create()`. Missing a required field throws `ValidationException`.

### Sync Strategies

| Strategy | Behavior | Use for |
|----------|----------|---------|
| `"gset"` | Append-only. Entries can't be modified or deleted. Merge = union. | Stories, comments, messages |
| `"lww"` | Last-writer-wins. Newer timestamp overwrites. Supports upsert and delete. | Settings, profiles, reactions |

### Sync Targets

| Config | Who gets it | Use for |
|--------|-------------|---------|
| _(default)_ | All your friends' devices + your other devices | Stories, messages, profiles |
| `private = true` | Only your own devices | Settings, drafts |
| `belongsTo = listOf("group")` | Group members (from parent's `members` field) | Group messages |

### Relationships

- `hasMany = listOf("comment")` — declares that this model has children
- `belongsTo = listOf("story")` — declares that this model is a child. The entry's data must include a `storyId` field (parent name + "Id")

When you create an entry with a `belongsTo`, the association is registered automatically. You can eager-load children with `include()`.

### TTL (Auto-Expiration)

Set `ttl = "24h"` and entries expire automatically. Expired entries are filtered from all queries and cleaned up periodically.

Supported formats: `"30s"`, `"30m"`, `"24h"`, `"7d"`

## Working with Models

### Create

**Typed:**
```kotlin
val entry = stories.create(Story(content = "Beach day!", authorUsername = "alice"))
// entry.id        → "story_1711234567890_a8f3b2c1"
// entry.value     → Story(content = "Beach day!", authorUsername = "alice")
// entry.timestamp → 1711234567890
```

**Untyped:**
```kotlin
val entry = story.create(mapOf("content" to "Beach day!", "mediaUrl" to null))
// entry.data → { content: "Beach day!", mediaUrl: null }
```

One call does everything: validates fields, persists to local DB, encrypts with Signal Protocol, fans out to all friends' devices, and notifies any active observers.

### Find

```kotlin
val entry = stories.find("story_1711234567890_a8f3b2c1")
// entry?.value?.content → "Beach day!"
```

### Query

**DSL syntax:**

```kotlin
stories.where { "authorUsername" eq "alice" }.exec()
stories.where { "likes" atLeast 5; "likes" atMost 100 }.exec()
stories.where { "content" contains "sunset" }.exec()
stories.where { "authorUsername" oneOf listOf("alice", "bob") }.exec()
stories.where { "status" not "draft" }.exec()
```

**Map syntax (cross-platform compatible):**

```kotlin
story.where(mapOf("data.authorUsername" to "alice")).exec()
story.where(mapOf("data.likes" to mapOf("atLeast" to 5, "atMost" to 100))).exec()
```

Both syntaxes support the same operators:

| Operator | DSL | Map |
|----------|-----|-----|
| Equals | `"field" eq value` | `mapOf("data.field" to value)` |
| Not equals | `"field" not value` | `mapOf("data.field" to mapOf("not" to value))` |
| Greater than | `"field" greaterThan n` | `mapOf("data.field" to mapOf("greaterThan" to n))` |
| At least (>=) | `"field" atLeast n` | `mapOf("data.field" to mapOf("atLeast" to n))` |
| Less than | `"field" lessThan n` | `mapOf("data.field" to mapOf("lessThan" to n))` |
| At most (<=) | `"field" atMost n` | `mapOf("data.field" to mapOf("atMost" to n))` |
| One of | `"field" oneOf list` | `mapOf("data.field" to mapOf("oneOf" to list))` |
| None of | `"field" noneOf list` | `mapOf("data.field" to mapOf("noneOf" to list))` |
| Contains | `"field" contains str` | `mapOf("data.field" to mapOf("contains" to str))` |
| Starts with | `"field" startsWith str` | `mapOf("data.field" to mapOf("startsWith" to str))` |
| Ends with | `"field" endsWith str` | `mapOf("data.field" to mapOf("endsWith" to str))` |

DSL auto-prefixes `data.` for you. Map syntax requires the explicit `data.` prefix for data fields. System fields (`id`, `timestamp`, `authorDeviceId`) don't need the prefix in either syntax.

### Sorting and Limiting

```kotlin
stories.where { "published" eq true }
    .orderBy("likes")
    .limit(10)
    .exec()

stories.where { }.orderBy("likes", "asc").exec()
stories.where { }.orderBy("timestamp", "asc").exec()
```

`orderBy` auto-prefixes `data.` for data fields, same as the DSL `where`. System fields (`id`, `timestamp`, `authorDeviceId`) are used as-is.

### Eager Loading (include)

Load child entries in one call instead of N+1 queries. Untyped models only — typed models access associations through the raw entry.

```kotlin
val storiesWithComments = story.where(mapOf())
    .include("comment", "reaction")
    .exec()

for (s in storiesWithComments) {
    val comments = s.associations["comment"] ?: emptyList()
    val reactions = s.associations["reaction"] ?: emptyList()
    println("${s.data["content"]} — ${comments.size} comments, ${reactions.size} reactions")
}
```

### Upsert (LWW models only)

Update an existing entry or create it if it doesn't exist. Newer timestamp always wins.

```kotlin
settings.upsert("my_settings", AppSettings(theme = "dark", notificationsEnabled = true))

// Later...
settings.upsert("my_settings", AppSettings(theme = "light", notificationsEnabled = true))
// "light" wins because it has a newer timestamp
```

### Delete (LWW models only)

Creates a tombstone. The entry disappears from all queries but the delete syncs to other devices.

```kotlin
client.orm.model("reaction").delete(reactionId)
```

GSet models don't support delete — they're append-only by design.

### Shortcuts

```kotlin
stories.all()
stories.allSorted()
stories.where { "authorUsername" eq "alice" }.first()
stories.where { "authorUsername" eq "alice" }.count()
```

## Reactive Observation (Compose)

Models emit Flows that update when the underlying data changes. Wire directly to Compose.

**Typed:**

```kotlin
@Composable
fun StoryFeed(stories: TypedModel<Story>) {
    val feed by stories.observe().collectAsState(emptyList())

    LazyColumn {
        items(feed) { entry ->
            Text(entry.value.content)           // compile-safe
            Text(entry.value.authorUsername)     // compile-safe
        }
    }
}
```

**Filtered:**

```kotlin
@Composable
fun MyStories(stories: TypedModel<Story>, username: String) {
    val mine by stories.where { "authorUsername" eq username }
        .observe()
        .collectAsState(emptyList())

    LazyColumn { items(mine) { Text(it.value.content) } }
}
```

**Untyped:**

```kotlin
@Composable
fun StoryFeed(client: ObscuraClient) {
    val feed by client.orm.model("story").observe().collectAsState(emptyList())

    LazyColumn {
        items(feed) { entry ->
            Text(entry.data["content"] as String)
        }
    }
}
```

## Chat via ORM (DirectMessage)

Chat messages use the ORM instead of a hardcoded message store. When you define a `directMessage` model, `client.send()` automatically creates a DirectMessage entry and syncs it via MODEL_SYNC. This is the same wire format the iOS client uses, so messages are interoperable across platforms.

```kotlin
// Define the model (must match iOS field names for interop)
client.orm.define(mapOf(
    "directMessage" to ModelConfig(
        fields = mapOf("conversationId" to "string", "content" to "string", "senderUsername" to "string"),
        sync = "gset"
    )
))

// Send — creates a directMessage entry, encrypts, delivers
client.send(friendUsername, "Hello!")

// Receive — arrives as MODEL_SYNC, populates conversations StateFlow
val conversations by client.conversations.collectAsState()
val msgs = conversations[friendUserId] ?: emptyList()
```

If `directMessage` is not defined, `client.send()` falls back to the legacy TEXT message type (type 0). This means old clients without the ORM can still send and receive.

### Wire Format

Both iOS and Kotlin send the same bytes:

```
ClientMessage {
    type: MODEL_SYNC (30)
    modelSync: {
        model: "directMessage"
        id: "directMessage_1711734000_abc"
        data: {"conversationId":"user-id","content":"Hello!","senderUsername":"alice"}
        timestamp: 1711734000
    }
}
```

Any client that defines a `directMessage` model with the same field names will interoperate. The server never sees the contents — it's encrypted end-to-end.

## Cross-Platform Interop

The ORM syncs via MODEL_SYNC protobuf messages over Signal-encrypted channels. If two clients (iOS, Android, web) define a model with the same `modelName`, same CRDT strategy, and same data fields — the bytes are identical on the wire. It's JSON inside protobuf inside Signal encryption.

No coordination needed between platforms. GSet merge is union, LWWMap merge is newest-timestamp-wins, regardless of which platform created the entry.

### Shared Model Definitions

For interop, both platforms must agree on field names. Here are the standard models used by both iOS and Kotlin:

| Model | Fields | Sync | Notes |
|-------|--------|------|-------|
| `directMessage` | conversationId, content, senderUsername | gset | Chat messages |
| `story` | content, authorUsername, mediaUrl? | gset | Ephemeral feed (24h TTL) |
| `profile` | displayName, bio? | lww | Display names synced to friends |
| `settings` | theme, notificationsEnabled | lww | Private — never leaves your devices |

## What You Can Rely On

These behaviors are tested against the live server with Signal Protocol encryption.

**Auto-sync:** `model.create()` encrypts and delivers to all friends' devices automatically. You don't call any send method.

**Offline resilience:** If a friend is offline when you create, the server queues it. When they reconnect, it arrives. Tested with single and multiple creates.

**Bidirectional:** Both sides can create and receive. After disconnect/reconnect, both directions still work.

**Private isolation:** Models with `private = true` never reach friends. Tested — a friend connected on the same session receives nothing.

**Type fidelity:** String, number, boolean, and optional fields survive the full round-trip: your code → JSON → protobuf → Signal encrypt → server relay → Signal decrypt → protobuf → JSON → their code. All types arrive intact.

**Multi-model routing:** If you define story + profile + settings, each MODEL_SYNC message routes to the correct model on the receiving side. They don't cross.

**Persistence:** Entries created on a file-backed database survive app restart. Restore the session, re-define the schema, and your data is there.

**Conflict resolution:** LWW models resolve conflicts deterministically — newer timestamp wins, regardless of which device wrote first or which message arrived first. GSet models merge by union — two devices that independently add entries converge automatically.

**Offline conflict resolution:** Alice updates a profile while Bob is offline. Bob reconnects, gets the update. If both updated the same LWW entry, the newer timestamp wins on both sides. Tested.

**TTL expiration:** Entries with a TTL disappear from all queries after the time elapses. Cleanup runs periodically.

**Messages via ORM:** `client.send()` uses the directMessage ORM model when defined. Messages arrive as MODEL_SYNC, populate conversations, survive offline/reconnect, and are interoperable with iOS.

**Legacy fallback:** If directMessage is not defined, `client.send()` falls back to TEXT (type 0). Old and new clients coexist.

## Test Coverage

| Area | Tests | Type |
|------|-------|------|
| GSet/LWWMap CRDTs | 14 | Unit (offline) |
| Signal store (identity, prekeys, sessions) | 17 | Unit (offline) |
| TTL scheduling + expiration | 9 | Unit (offline) |
| Model create/find/query/validate/upsert/delete | 19 | Unit (offline) |
| QueryBuilder operators + DSL + orderBy/limit | 34 | Unit (offline) |
| Typed models (create, find, query, observe) | 12 | Unit (offline) |
| Observation + include (eager loading) | 7 | Unit (offline) |
| Link code generation + validation | 12 | Unit (offline) |
| ORM auto-sync (create → friend receives) | 2 | Integration (live server) |
| ORM offline sync + conflict resolution | 6 | Integration (live server) |
| ORM wire format (LWW conflict, type fidelity, multi-model, persistence) | 4 | Integration (live server) |
| ORM messages (DirectMessage online, offline, bidirectional, legacy fallback) | 6 | Integration (live server) |

## What's Not Built Yet

- **`observe()` on queries with `include()`** — observation works on filtered/sorted queries, but eager-loaded associations don't participate in the reactive stream yet.
- **Counter CRDT** — only GSet and LWWMap are implemented. A PN-Counter for things like like-counts is planned.
- **Cross-platform interop test** — Kotlin↔iOS has not been tested against the live server yet. The wire format is identical by design but hasn't been verified end-to-end.
