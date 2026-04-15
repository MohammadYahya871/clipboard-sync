package com.clipboardsync.android.clipboard

import java.util.LinkedHashMap

class LoopGuard(
    private val maxEntries: Int = 128,
    private val suppressionWindowMillis: Long = 4_000L
) {
    private val recentEventIds = object : LinkedHashMap<String, Long>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > maxEntries
    }
    private val suppressedHashes = object : LinkedHashMap<String, Long>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > maxEntries
    }

    fun rememberSeenEvent(eventId: String) {
        recentEventIds[eventId] = System.currentTimeMillis()
    }

    fun hasSeenEvent(eventId: String): Boolean = recentEventIds.containsKey(eventId)

    fun markRemoteApplied(hash: String) {
        suppressedHashes[hash] = System.currentTimeMillis() + suppressionWindowMillis
    }

    fun shouldSuppressLocal(hash: String): Boolean {
        val until = suppressedHashes[hash] ?: return false
        if (until < System.currentTimeMillis()) {
            suppressedHashes.remove(hash)
            return false
        }
        return true
    }
}
