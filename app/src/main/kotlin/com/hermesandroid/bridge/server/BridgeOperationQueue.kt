package com.hermesandroid.bridge.server

import android.util.Log
import com.hermesandroid.bridge.BridgeApplication
import com.hermesandroid.bridge.overlay.HermesPopupOverlay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Serializes device operations from all Hermes clients onto the one physical UI/device.
 * FIFO is intentional: one phone surface means one operation at a time.
 */
object BridgeOperationQueue {
    private const val TAG = "BridgeOperationQueue"
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 120_000L
    const val MAX_QUEUE_DEPTH = 32

    data class Request(
        val requestId: String,
        val clientId: String,
        val method: String,
        val path: String,
        val params: com.google.gson.JsonObject,
        val body: com.google.gson.JsonObject,
        val authenticated: Boolean,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    )

    data class Result(val value: Any, val status: Int)

    private data class JobItem(
        val request: Request,
        val deferred: CompletableDeferred<Result>,
        val enqueuedAt: Long = System.currentTimeMillis(),
    )

    private val queue = Channel<JobItem>(MAX_QUEUE_DEPTH)
    private val depth = AtomicLong(0)
    private val active = AtomicLong(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null

    init { ensureWorker() }

    @Synchronized
    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            for (item in queue) {
                depth.decrementAndGet()
                val waitMs = System.currentTimeMillis() - item.enqueuedAt
                active.incrementAndGet()
                try {
                    val remainingMs = (item.request.timeoutMs - waitMs).coerceAtLeast(1L)
                    val result = withTimeout(remainingMs.coerceAtMost(MAX_TIMEOUT_MS)) {
                        CommandDispatcher.dispatch(
                            item.request.method,
                            item.request.path,
                            item.request.params,
                            item.request.body,
                            item.request.authenticated,
                        )
                    }
                    item.deferred.complete(Result(result.first, result.second))
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    showTimeout(item.request, waitMs)
                    item.deferred.complete(
                        Result(
                            mapOf(
                                "error" to "operation_timeout",
                                "request_id" to item.request.requestId,
                                "client_id" to item.request.clientId,
                                "method" to item.request.method,
                                "path" to item.request.path,
                                "timeout_ms" to item.request.timeoutMs,
                                "queue_wait_ms" to waitMs,
                            ),
                            504,
                        )
                    )
                } catch (e: Exception) {
                    item.deferred.completeExceptionally(e)
                } finally {
                    active.decrementAndGet()
                }
            }
        }
    }

    suspend fun submit(request: Request): Result {
        ensureWorker()
        val deferred = CompletableDeferred<Result>()
        val item = JobItem(request, deferred)
        depth.incrementAndGet()
        if (!queue.trySend(item).isSuccess) {
            depth.decrementAndGet()
            showQueueFull(request)
            return Result(
                mapOf(
                    "error" to "operation_queue_full",
                    "request_id" to request.requestId,
                    "client_id" to request.clientId,
                    "queue_depth" to depth.get(),
                    "max_queue_depth" to MAX_QUEUE_DEPTH,
                ),
                429,
            )
        }
        return deferred.await()
    }

    fun status(): Map<String, Any> = mapOf(
        "queued" to depth.get(),
        "active" to active.get(),
        "max_queue_depth" to MAX_QUEUE_DEPTH,
        "busy" to (active.get() > 0),
        "mode" to "fifo_single_device",
    )

    private fun showQueueFull(request: Request) {
        showNotice(
            "Hermes 操作排队已满",
            "来自 ${shortClient(request.clientId)} 的操作暂时无法加入队列。请稍后重试。",
            request,
        )
    }

    private fun showTimeout(request: Request, waitMs: Long) {
        Log.w(TAG, "Timed out ${request.method} ${request.path} client=${request.clientId} wait=${waitMs}ms")
        showNotice(
            "Hermes 操作超时",
            "${request.method} ${request.path}\n执行超过 ${request.timeoutMs / 1000}s，已结束本次操作。\n请求：${request.requestId}",
            request,
        )
    }

    private fun showNotice(title: String, message: String, request: Request) {
        runCatching {
            HermesPopupOverlay.show(
                BridgeApplication.instance,
                title,
                message,
                autoCloseMs = 10_000L,
                pageId = "bridge-operation-${request.requestId}",
            )
        }.onFailure { Log.w(TAG, "Unable to show operation notice", it) }
    }

    private fun shortClient(clientId: String): String =
        if (clientId.length <= 12) clientId else clientId.take(12)
}
