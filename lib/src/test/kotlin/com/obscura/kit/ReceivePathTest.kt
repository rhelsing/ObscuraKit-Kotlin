package com.obscura.kit

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.obscura.kit.crypto.RecoveryKeys
import com.obscura.kit.db.ObscuraDatabase
import com.obscura.kit.stores.FriendDomain
import com.obscura.kit.stores.FriendStatus
import com.obscura.kit.stores.InboxDomain
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import obscura.client.v1.Client.ClientMessage
import obscura.client.v1.Client.SignalKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The receive path, unit-tested.
 *
 * **Why this file exists.** Until now no unit test constructed an `ObscuraClient`, so
 * `routeMessage`, `inboxMessage`, `clampFutureTimestamp` and every `handle*` were reachable only
 * from the integration suite — which needs a live, correctly configured `obscura-server`. That is
 * exactly where every shipped defect has been: a missing self-check on SENT_SYNC, a
 * signature verified against a key from the same message, a redelivery that notified twice, a
 * typing indicator accepted into a conversation the sender is not in. None of those needs a server
 * to demonstrate.
 *
 * The client is built over an in-memory driver and never connects: the constructor wires managers
 * and opens a database, and `GatewayConnection` does not touch a socket until `connect()`. The
 * handlers are `internal` so this can call them directly with a hand-built `ClientMessage`, which
 * is the same thing `routeMessage` does after a successful decrypt.
 */
class ReceivePathTest {

    private val selfUserId = "019f025a-0000-7de6-84b6-000000000001"
    private val selfDeviceId = "019f025a-0000-7de6-84b6-0000000000d1"
    private val peerUserId = "019f025a-0000-7de6-84b6-000000000002"

    /** An authenticated, connected-to-nothing client. */
    private fun newClient(): ObscuraClient {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        val client = ObscuraClient(ObscuraConfig(apiUrl = "https://obscura.invalid"), driver)
        client.restoreSession(
            token = "t", refreshToken = null, userId = selfUserId,
            deviceId = selfDeviceId, username = "self",
        )
        return client
    }

    private fun modelSync(entryId: String, model: String = "pix", timestamp: Long = 1_700_000_000_000) =
        ClientMessage.newBuilder()
            .setTimestamp(timestamp)
            .setModelSync(obscura.client.v1.modelSync {
                this.model = model
                id = entryId
                this.timestamp = timestamp
                data = ByteString.copyFrom("payload".toByteArray())
            })
            .build()

    // ── SPEC §2.4: clamping a peer-supplied timestamp ─────────────────────────

    @Test
    fun `a past timestamp is stored unchanged`() {
        val c = newClient()
        assertEquals(1_700_000_000_000L, c.clampFutureTimestamp(1_700_000_000_000L))
    }

    /**
     * Without the cap a peer sets `sentAt` far ahead and wins every REPLACE conflict forever — the
     * tie-break can only order writes it can compare honestly.
     */
    @Test
    fun `a far-future timestamp is clamped to roughly now plus a minute`() {
        val c = newClient()
        val clamped = c.clampFutureTimestamp(Long.MAX_VALUE / 2)
        val cap = System.currentTimeMillis() + 60_000L
        assertTrue(clamped <= cap, "must not exceed the cap")
        assertTrue(clamped > cap - 5_000L, "must be the cap, not something far below it")
    }

    /**
     * **The documented cross-kit divergence, and the branch that had no test.**
     *
     * `ModelSync.timestamp` is proto3 `uint64`, which protobuf-java surfaces as a SIGNED Long, so a
     * peer sending >= 2^63 arrives here NEGATIVE and sails under any `minOf` cap. Swift compares in
     * UInt64 space and correctly yields the cap, so identical wire bytes stored roughly -9.2e18 on
     * Android and now+60s on iOS.
     */
    @Test
    fun `a uint64 timestamp above 2 to the 63 arrives negative and still clamps to the cap`() {
        val c = newClient()
        val asSigned = java.lang.Long.parseUnsignedLong("18446744073709551615") // 2^64 - 1
        assertTrue(asSigned < 0, "precondition: protobuf-java hands this back as a negative Long")

        val clamped = c.clampFutureTimestamp(asSigned)

        val cap = System.currentTimeMillis() + 60_000L
        assertTrue(clamped > 0, "a negative sentAt must never be stored — it sorts before everything")
        assertTrue(clamped <= cap && clamped > cap - 5_000L, "it must land on the cap, as Swift does")
    }

    // ── §3.3 rule 8: a redelivery must not notify twice ───────────────────────

    /**
     * Persist-then-ack GUARANTEES redelivery, so this is a normal path. `Inbox.sq` says the dedupe
     * exists so a duplicate cannot "inflate the app's processing counts, and post a second
     * notification for a message the user already has" — which was false while the emits fired
     * unconditionally.
     */
    @Test
    fun `a redelivered envelope is stored once and reported as not-new`() = runBlocking {
        val c = newClient()
        val msg = modelSync("entry-1")

        val first = c.routeMessage(msg, peerUserId, "dev-peer", "env-1")
        val second = c.routeMessage(msg, peerUserId, "dev-peer", "env-1")

        assertTrue(first, "a fresh envelope must be announced to the app")
        assertFalse(second, "a redelivery must be absorbed silently — it is still acked, not notified")
        assertEquals(1L, InboxDomain(c.db).depth())
    }

    @Test
    fun `a distinct envelope carrying the same entry is a separate row`() = runBlocking {
        val c = newClient()

        assertTrue(c.routeMessage(modelSync("entry-1"), peerUserId, "dev-peer", "env-1"))
        assertTrue(c.routeMessage(modelSync("entry-1"), peerUserId, "dev-peer", "env-2"))

        assertEquals(2L, InboxDomain(c.db).depth(),
            "dedupe keys on the envelope id, not on the app's entry id")
    }

    @Test
    fun `an inbox row records the authenticated sender, never the payload`() = runBlocking {
        val c = newClient()
        FriendDomain(c.db).add(peerUserId, "alice", FriendStatus.ACCEPTED)

        c.routeMessage(modelSync("entry-1"), peerUserId, "dev-peer", "env-1")

        val row = InboxDomain(c.db).peek().single()
        assertEquals(peerUserId, row.senderUserId)
        assertEquals("dev-peer", row.senderDeviceId)
        assertEquals("alice", row.senderDisplayName, "SPEC §0.5: the name comes from OUR friend graph")
        assertEquals("MODEL_SYNC", row.kind)
        assertEquals("pix", row.modelKey)
        assertEquals("CREATE", row.op)
    }

    // ── SENT_SYNC: the missing self-check ─────────────────────────────────────

    /**
     * Friendship is not required to deliver a message, so without the `sourceUserId != userId`
     * guard ANY account could send a SentSync and have the kit write a message row with an
     * attacker-chosen conversation, content and timestamp, stamped with OUR device id — rendering
     * as a message we sent. `Message.sq`'s INSERT OR REPLACE keys on the message id, so a chosen id
     * also overwrites a real one.
     */
    @Test
    fun `a SENT_SYNC from another account is ignored`() = runBlocking {
        val c = newClient()
        val forged = ClientMessage.newBuilder()
            .setSentSync(obscura.client.v1.sentSync {
                recipientUsername = "victim"
                messageId = "m1"
                content = ByteString.copyFrom("forged".toByteArray())
                timestamp = 1_700_000_000_000
            }).build()

        c.handleSentSync(forged, sourceUserId = peerUserId)

        assertEquals(0, c.getMessages("victim").size,
            "a SentSync is an echo of OUR OWN send; from anyone else it is a forged message row")
    }

    @Test
    fun `a SENT_SYNC from our own other device is stored`() = runBlocking {
        val c = newClient()
        val own = ClientMessage.newBuilder()
            .setSentSync(obscura.client.v1.sentSync {
                recipientUsername = "bob"
                messageId = "m1"
                content = ByteString.copyFrom("hello".toByteArray())
                timestamp = 1_700_000_000_000
            }).build()

        c.handleSentSync(own, sourceUserId = selfUserId)

        val stored = c.getMessages("bob").single()
        assertEquals("hello", stored.content)
        assertEquals(selfDeviceId, stored.authorDeviceId)
    }

    // ── DEVICE_ANNOUNCE: trust-on-first-use on the recovery key ───────────────

    private val phrase = com.obscura.kit.crypto.Bip39.generateMnemonic()
    private val otherPhrase = com.obscura.kit.crypto.Bip39.generateMnemonic()

    private fun announce(
        deviceIds: List<String>,
        isRevocation: Boolean,
        signWith: String? = null,
        offerKeyFrom: String? = null,
        timestamp: Long = 1_700_000_000_000,
    ): ClientMessage {
        val payload = RecoveryKeys.serializeAnnounceForSigning(deviceIds, timestamp, isRevocation)
        return ClientMessage.newBuilder()
            .setDeviceAnnounce(obscura.client.v1.deviceAnnounce {
                for (id in deviceIds) {
                    devices.add(obscura.client.v1.deviceInfo {
                        deviceUuid = id; deviceId = id; deviceName = "D"
                    })
                }
                this.timestamp = timestamp
                this.isRevocation = isRevocation
                if (signWith != null) {
                    signature = ByteString.copyFrom(RecoveryKeys.signWithPhrase(signWith, payload))
                }
                if (offerKeyFrom != null) {
                    recoveryPublicKey = ByteString.copyFrom(RecoveryKeys.getPublicKey(offerKeyFrom).serialize())
                }
            }).build()
    }

    @Test
    fun `an unsigned announce from a friend updates the device list`() = runBlocking {
        val c = newClient()
        val friends = FriendDomain(c.db)
        friends.add(peerUserId, "alice", FriendStatus.ACCEPTED)

        c.handleDeviceAnnounce(announce(listOf("dev-a"), isRevocation = false), peerUserId)

        assertEquals(listOf("dev-a"), friends.get(peerUserId)!!.devices.map { it.deviceId })
    }

    @Test
    fun `the first announce carrying a recovery key pins it`() = runBlocking {
        val c = newClient()
        val friends = FriendDomain(c.db)
        friends.add(peerUserId, "alice", FriendStatus.ACCEPTED)

        c.handleDeviceAnnounce(
            announce(listOf("dev-a"), isRevocation = false, signWith = phrase, offerKeyFrom = phrase),
            peerUserId,
        )

        assertArrayEquals(
            RecoveryKeys.getPublicKey(phrase).serialize(),
            friends.get(peerUserId)!!.recoveryPublicKey,
            "client.proto calls this the key 'for friend to verify FUTURE revocation signatures'",
        )
    }

    /** Peer-supplied key material must not override a pinned recovery key. */
    @Test
    fun `a self-consistent announce under a different key is rejected once one is pinned`() = runBlocking {
        val c = newClient()
        val friends = FriendDomain(c.db)
        friends.add(peerUserId, "alice", FriendStatus.ACCEPTED)
        c.handleDeviceAnnounce(
            announce(listOf("dev-a"), isRevocation = false, signWith = phrase, offerKeyFrom = phrase),
            peerUserId,
        )

        c.handleDeviceAnnounce(
            announce(listOf("attacker-dev"), isRevocation = true, signWith = otherPhrase, offerKeyFrom = otherPhrase),
            peerUserId,
        )

        assertEquals(listOf("dev-a"), friends.get(peerUserId)!!.devices.map { it.deviceId },
            "a signature that verifies only under a key from the same message proves nothing")
        assertArrayEquals(
            RecoveryKeys.getPublicKey(phrase).serialize(),
            friends.get(peerUserId)!!.recoveryPublicKey,
            "and the pin must not be replaced by the attempt",
        )
    }

    /** A revocation cannot be verified when no recovery key is pinned. */
    @Test
    fun `a revocation with nothing pinned to check it against is rejected`() = runBlocking {
        val c = newClient()
        val friends = FriendDomain(c.db)
        friends.add(peerUserId, "alice", FriendStatus.ACCEPTED, listOf(
            com.obscura.kit.stores.FriendDeviceInfo("dev-a", "dev-a", "A"),
            com.obscura.kit.stores.FriendDeviceInfo("dev-b", "dev-b", "B"),
        ))

        c.handleDeviceAnnounce(announce(listOf("dev-a"), isRevocation = true), peerUserId)

        assertEquals(setOf("dev-a", "dev-b"), friends.get(peerUserId)!!.devices.map { it.deviceId }.toSet(),
            "an unverifiable revocation must not be able to drop a device")
    }

    @Test
    fun `a revocation signed under the pinned key is applied`() = runBlocking {
        val c = newClient()
        val friends = FriendDomain(c.db)
        friends.add(peerUserId, "alice", FriendStatus.ACCEPTED)
        c.handleDeviceAnnounce(
            announce(listOf("dev-a", "dev-b"), isRevocation = false, signWith = phrase, offerKeyFrom = phrase),
            peerUserId,
        )

        c.handleDeviceAnnounce(announce(listOf("dev-a"), isRevocation = true, signWith = phrase), peerUserId)

        assertEquals(listOf("dev-a"), friends.get(peerUserId)!!.devices.map { it.deviceId })
    }

    // ── MODEL_SIGNAL: the receive side must check the audience too ────────────

    private fun typing(contextId: String) = ClientMessage.newBuilder()
        .setModelSignal(obscura.client.v1.modelSignal {
            model = "directMessage"
            kind = SignalKind.SIGNAL_KIND_TYPING
            this.contextId = contextId
        }).build()

    /**
     * The SEND side already fails closed on a contextId that does not name exactly two participants.
     * The RECEIVE side applied no check at all, so a peer could emit a typing indicator into any
     * conversation id — including ones it is not in — and `observeTyping` keys on that id verbatim.
     */
    @Test
    fun `a typing signal for a conversation the sender is not in is dropped`() = runBlocking {
        val c = newClient()
        val strangersConversation = "aaa_bbb"

        c.handleModelSignal(typing(strangersConversation), peerUserId, "dev-peer")

        assertEquals(emptyList<String>(), c.observeTyping("directMessage", strangersConversation).first())
    }

    @Test
    fun `a typing signal with a non-two-party contextId is dropped`() = runBlocking {
        val c = newClient()
        // One participant, not two — the shape the send side already refuses to broadcast.
        val broadcast = selfUserId

        c.handleModelSignal(typing(broadcast), peerUserId, "dev-peer")

        assertEquals(emptyList<String>(), c.observeTyping("directMessage", broadcast).first())
    }

    @Test
    fun `a typing signal in the senders own two-party conversation is accepted`() = runBlocking {
        val c = newClient()
        FriendDomain(c.db).add(peerUserId, "alice", FriendStatus.ACCEPTED)
        val conversation = "${selfUserId}_$peerUserId"

        c.handleModelSignal(typing(conversation), peerUserId, "dev-peer")

        assertEquals(listOf("alice"), c.observeTyping("directMessage", conversation).first())
    }
}
