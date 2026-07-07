package com.obscura.kit.orm

import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonic timestamp source for this replica's local writes.
 *
 * LWW conflict resolution uses a total order on (timestamp, authorDeviceId) with
 * the device id as the tie-break (see obscura-proto SPEC §2). That tie-break makes
 * *cross-device* concurrent writes converge deterministically, but it cannot order
 * two writes that share the SAME (timestamp, authorDeviceId) — which happens when a
 * single device issues two writes to the same entry within one wall-clock millisecond.
 * Left alone, the later local write could lose to the earlier one.
 *
 * This clock guarantees a replica's own successive writes get strictly-increasing
 * timestamps, so a local write always supersedes what it just wrote, while remaining
 * ~wall-clock for cross-device comparison. It is effectively a Lamport clock seeded by
 * wall time.
 */
object MonotonicClock {
    private val last = AtomicLong(0L)

    /** @return a timestamp ≥ System.currentTimeMillis() and strictly greater than any previously returned. */
    fun now(): Long {
        while (true) {
            val prev = last.get()
            val wall = System.currentTimeMillis()
            val next = if (wall > prev) wall else prev + 1
            if (last.compareAndSet(prev, next)) return next
        }
    }
}
