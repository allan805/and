package com.hermesandroid.bridge.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight protocol security layer.
 *
 * Authentication remains PairingManager's responsibility.
 * This class only protects authenticated protocol traffic from malformed,
 * oversized and duplicated messages.
 */
object ProtocolSecurity {

    const val MAX_HTTP_BODY_BYTES = 512 * 1024
    const val MAX_WS_MESSAGE_BYTES = 512 * 1024

    private const val MAX_ID_LENGTH = 128
    private const val REQUEST_TTL_MS = 5 * 60 * 1000L
    private const val MAX_TRACKED_REQUESTS = 2048
    private const val MAX_EVENT_QUEUE = 128

    private data class SeenRequest(val timestamp: Long)

    private val seenRequests = ConcurrentHashMap<String, SeenRequest>()

    const val MAX_UI_EVENT_QUEUE = MAX_EVENT_QUEUE

    fun validRequestId(value: String?): Boolean = validId(value)

    fun validId(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        if (value.length > MAX_ID_LENGTH) return false

        return value.all {
            it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == ':'
        }
    }

    /**
     * Returns false when the same request_id has already been processed recently.
     */
    fun acceptRequestId(requestId: String?): Boolean {
        if (!validId(requestId)) return false

        val id = requestId!!
        val now = System.currentTimeMillis()

        cleanup(now)

        if (seenRequests.size >= MAX_TRACKED_REQUESTS) {
            // Do not allow an attacker to grow this map without bound.
            cleanup(now, aggressive = true)
        }

        return seenRequests.putIfAbsent(id, SeenRequest(now)) == null
    }

    fun validateEventId(eventId: String?): Boolean {
        return eventId == null || validId(eventId)
    }

    private fun cleanup(now: Long, aggressive: Boolean = false) {
        val cutoff = now - REQUEST_TTL_MS

        for ((id, entry) in seenRequests.entries) {
            if (entry.timestamp < cutoff ||
                (aggressive && seenRequests.size > MAX_TRACKED_REQUESTS / 2)
            ) {
                seenRequests.remove(id, entry)
            }
        }
    }
}
