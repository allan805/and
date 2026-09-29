package com.hermesandroid.bridge.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermesandroid.bridge.BridgeApplication
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.security.ProtocolSecurity
import com.hermesandroid.bridge.audio.MicrophoneRecordingFiles
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Local HTTP server routing. This is a thin transport adapter: it parses each request
 * into (method, path, params, body) and hands it to [CommandDispatcher], which holds the
 * single source of truth for command behaviour shared with the WebSocket relay path.
 *
 * Auth is enforced by the interceptor in BridgeServer.kt; here we only compute the
 * authenticated flag so `/ping` can report it accurately.
 */
fun Application.configureRouting() {
    routing {
        get("/mic_file") {
            val requestedName = call.request.queryParameters["name"]
            val file = MicrophoneRecordingFiles.resolve(
                BridgeApplication.instance,
                requestedName,
            )
            if (file == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Recording not found"))
                return@get
            }

            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header(HttpHeaders.ContentType, "audio/wav")
            call.response.header(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"${file.name}\"",
            )
            call.respondFile(file)
        }

        get("/bridge/queue") {
            call.respond(HttpStatusCode.OK, BridgeOperationQueue.status())
        }

        route("{path...}") {
            handle {
                val method = call.request.httpMethod.value.uppercase()
                val segments = call.parameters.getAll("path") ?: emptyList()
                val path = "/" + segments.joinToString("/")

                val params = JsonObject().apply {
                    call.request.queryParameters.names().forEach { name ->
                        call.request.queryParameters[name]?.let { addProperty(name, it) }
                    }
                }

                val contentLength = call.request.headers[HttpHeaders.ContentLength]
                    ?.toLongOrNull()

                if (contentLength != null &&
                    contentLength > ProtocolSecurity.MAX_HTTP_BODY_BYTES
                ) {
                    call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        mapOf("error" to "Request body too large")
                    )
                    return@handle
                }

                val body = try {
                    val text = call.receiveText()

                    if (text.toByteArray(Charsets.UTF_8).size >
                        ProtocolSecurity.MAX_HTTP_BODY_BYTES
                    ) {
                        call.respond(
                            HttpStatusCode.PayloadTooLarge,
                            mapOf("error" to "Request body too large")
                        )
                        return@handle
                    }

                    if (text.isBlank()) {
                        JsonObject()
                    } else {
                        JsonParser.parseString(text).asJsonObject
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid JSON body")
                    )
                    return@handle
                }

                val requestId = body.get("request_id")?.takeIf {
                    it.isJsonPrimitive && it.asJsonPrimitive.isString
                }?.asString

                val eventId = body.get("event_id")?.takeIf {
                    it.isJsonPrimitive && it.asJsonPrimitive.isString
                }?.asString

                if (requestId != null &&
                    !ProtocolSecurity.acceptRequestId(requestId)
                ) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        mapOf(
                            "error" to "Duplicate or invalid request_id",
                            "request_id" to requestId
                        )
                    )
                    return@handle
                }

                if (!ProtocolSecurity.validateEventId(eventId)) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid event_id")
                    )
                    return@handle
                }

                val authenticated = PairingManager.validateToken(
                    call.request.header(HttpHeaders.Authorization)
                )

                if (method == "GET" && path == "/bridge/queue") {
                    call.respond(HttpStatusCode.OK, BridgeOperationQueue.status())
                    return@handle
                }

                val finalRequestId = body.get("request_id")?.asString
                    ?: "http-${System.currentTimeMillis()}-${kotlin.random.Random.nextInt(1000, 9999)}"
                val clientId = call.request.headers["X-Hermes-Client-ID"]
                    ?.takeIf { ProtocolSecurity.validId(it) }
                    ?: call.request.local.remoteHost
                val timeoutMs = body.get("operation_timeout_ms")?.asLong
                    ?.coerceIn(1_000L, BridgeOperationQueue.MAX_TIMEOUT_MS)
                    ?: BridgeOperationQueue.DEFAULT_TIMEOUT_MS
                body.remove("operation_timeout_ms")

                val queued = BridgeOperationQueue.submit(
                    BridgeOperationQueue.Request(
                        finalRequestId, clientId, method, path, params, body, authenticated, timeoutMs
                    )
                )
                call.respond(HttpStatusCode.fromValue(queued.status), queued.value)
            }
        }
    }
}
