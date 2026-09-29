@file:Suppress("unused")

package com.hermesandroid.bridge.server

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

import com.hermesandroid.bridge.audio.CallAudioStreamingService

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.google.gson.JsonObject
import com.hermesandroid.bridge.BridgeApplication
import com.hermesandroid.bridge.audio.MicrophoneRecorderService
import com.hermesandroid.bridge.audio.MicrophoneRecordingFiles
import com.hermesandroid.bridge.audio.MicrophoneRecordingState
import com.hermesandroid.bridge.event.EventStore
import com.hermesandroid.bridge.executor.ActionExecutor
import com.hermesandroid.bridge.executor.ScreenReader
import com.hermesandroid.bridge.media.ScreenRecorder
import com.hermesandroid.bridge.model.DeviceCapabilities
import com.hermesandroid.bridge.model.ScreenNode
import com.hermesandroid.bridge.notification.NotificationStore
import com.hermesandroid.bridge.overlay.HermesPopupOverlay
import com.hermesandroid.bridge.overlay.HermesUiRuntime
import com.hermesandroid.bridge.power.BatteryMonitor
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import com.hermesandroid.bridge.service.BridgeNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Single source of truth for command handling, shared by both transports:
 *  - [RelayClient]  — outbound WebSocket relay (the path Hermes drives the device through)
 *  - [configureRouting] in BridgeRouter.kt — the local ktor HTTP server on :8765
 *
 * Both transports parse their request into (method, path, params, body) and call [dispatch],
 * so endpoint contracts, device-capability gating, and behaviour live in exactly one place.
 *
 * @param authenticated whether the caller is authenticated. The relay connection is
 *   authenticated at connect time (Bearer token in the WS handshake), so it passes `true`; the HTTP
 *   server computes it from the request's Bearer token. Only `/ping` reports it back.
 */
object CommandDispatcher {

    /**
     * Where the battery read gets its Context.
     *
     * A seam rather than a direct `BridgeApplication.instance` read. That
     * property is published from `Application.onCreate`, which Robolectric does
     * not run, and it cannot be assigned from a test because its setter is
     * private — so without this the endpoint is untestable rather than merely
     * awkward to test. Tests point it at `RuntimeEnvironment.getApplication()`;
     * nothing else replaces it.
     */
    internal var batteryContext: () -> Context = { BridgeApplication.instance }

    suspend fun dispatch(
        method: String,
        path: String,
        params: JsonObject,
        body: JsonObject,
        authenticated: Boolean
    ): Pair<Any, Int> {
        return when {
            method == "GET" && path == "/ping" -> {
                val serviceRunning = BridgeAccessibilityService.instance != null
                mapOf(
                    "status" to "ok",
                    "accessibilityService" to serviceRunning,
                    "authenticated" to authenticated
                    // Version omitted: /ping is unauthenticated and version info
                    // helps attackers fingerprint the deployment and target known
                    // vulnerabilities in specific versions.
                ) to 200
            }

            method == "GET" && path == "/accessibility/status" -> {
                val context = BridgeApplication.instance
                val service = BridgeAccessibilityService.instance
                val enabledInSettings =
                    BridgeAccessibilityService.isEnabledInSettings(context)
                val serviceConnected = service != null

                val nodeQueryReady = withContext(Dispatchers.Main) {
                    service?.rootInActiveWindow != null
                }

                mapOf(
                    "success" to true,
                    "accessibility_enabled" to enabledInSettings,
                    "service_connected" to serviceConnected,
                    "node_query_ready" to nodeQueryReady,
                    "ready" to (
                        enabledInSettings &&
                        serviceConnected &&
                        nodeQueryReady
                    ),
                    "state" to when {
                        enabledInSettings &&
                        serviceConnected &&
                        nodeQueryReady -> "A11Y_READY"

                        enabledInSettings &&
                        serviceConnected -> "A11Y_CONNECTED"

                        enabledInSettings -> "A11Y_ENABLED_WAITING"

                        else -> "A11Y_OFF"
                    }
                ) to 200
            }

            method == "POST" && path == "/accessibility/ensure" -> {
                val context = BridgeApplication.instance
                val service = BridgeAccessibilityService.instance

                val enabledInSettings =
                    BridgeAccessibilityService.isEnabledInSettings(context)

                val nodeQueryReady = withContext(Dispatchers.Main) {
                    service?.rootInActiveWindow != null
                }

                if (
                    enabledInSettings &&
                    service != null &&
                    nodeQueryReady
                ) {
                    mapOf(
                        "success" to true,
                        "ready" to true,
                        "state" to "A11Y_READY",
                        "message" to "Accessibility is ready"
                    ) to 200
                } else {
                    if (service != null) {
                        mapOf(
                            "success" to false,
                            "ready" to false,
                            "state" to if (
                                enabledInSettings
                            ) {
                                "A11Y_CONNECTED"
                            } else {
                                "A11Y_OFF"
                            },
                            "message" to
                                "Accessibility service is not ready; enable it in Android Accessibility settings",
                            "user_action_required" to true
                        ) to 409
                    } else {
                        mapOf(
                            "success" to false,
                            "ready" to false,
                            "state" to if (
                                enabledInSettings
                            ) {
                                "A11Y_ENABLED_WAITING"
                            } else {
                                "A11Y_OFF"
                            },
                            "message" to
                                "Please enable Hermes Bridge in Android Accessibility settings",
                            "user_action_required" to true,
                            "settings_action" to
                                "android.settings.ACCESSIBILITY_SETTINGS"
                        ) to 409
                    }
                }
            }

            method == "POST" && path == "/accessibility/disable" -> {
                val service = BridgeAccessibilityService.instance

                if (service == null) {
                    mapOf(
                        "success" to true,
                        "state" to "A11Y_OFF",
                        "message" to
                            "Accessibility service is already disconnected"
                    ) to 200
                } else {
                    withContext(Dispatchers.Main) {
                        service.disableSelfForIdle()
                    }

                    mapOf(
                        "success" to true,
                        "state" to "A11Y_STOPPING",
                        "message" to
                            "Accessibility service disable requested"
                    ) to 200
                }
            }

            method == "POST" && path == "/wake_screen" -> {
                val service = BridgeAccessibilityService.instance

                if (service == null) {
                    mapOf(
                        "success" to false,
                        "message" to "AccessibilityService is not running"
                    ) to 503
                } else {
                    val success = withContext(Dispatchers.Main) {
                        service.wakeScreen()
                    }

                    if (success) {
                        mapOf(
                            "success" to true,
                            "message" to "Screen wake requested"
                        ) to 200
                    } else {
                        mapOf(
                            "success" to false,
                            "message" to "Failed to start WakeActivity"
                        ) to 500
                    }
                }
            }

            method == "GET" && path == "/screen" -> {
                val bounds = params.get("bounds")?.asString == "true"
                val systemUi = params.get("system_ui")?.asString == "true"
                val tree = withContext(Dispatchers.Main) {
                    ScreenReader.readCurrentScreen(bounds, systemUi)
                }
                mapOf("tree" to tree, "count" to countAllNodes(tree)) to 200
            }

            method == "POST" && path == "/tap" -> {
                val x = body.get("x")?.asInt
                val y = body.get("y")?.asInt
                val nodeId = body.get("nodeId")?.asString
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.tap(x, y, nodeId)
                }
                result to 200
            }

            method == "POST" && path == "/tap_sequence" -> {

                val pointsJson = body.getAsJsonArray("points")

                if (pointsJson == null || pointsJson.size() == 0) {
                    mapOf(
                        "success" to false,
                        "message" to "points must be a non-empty array"
                    ) to 400
                } else {

                    val points = mutableListOf<Pair<Float, Float>>()

                    for (item in pointsJson) {

                        if (!item.isJsonObject) {
                            return mapOf(
                                "success" to false,
                                "message" to "Each point must be an object"
                            ) to 400
                        }

                        val obj = item.asJsonObject

                        val x = obj.get("x")?.asFloat
                        val y = obj.get("y")?.asFloat

                        if (x == null || y == null) {
                            return mapOf(
                                "success" to false,
                                "message" to "Each point requires x and y"
                            ) to 400
                        }

                        points.add(x to y)
                    }

                    val intervalMs =
                        body.get("interval_ms")?.asLong ?: 90L

                    val result = withContext(Dispatchers.Main) {
                        ActionExecutor.tapSequence(
                            points = points,
                            intervalMs = intervalMs
                        )
                    }

                    result to if (result.success) 200 else 500
                }
            }

            method == "POST" && path == "/tap_text" -> {
                val text = body.get("text")?.asString ?: ""
                val exact = body.get("exact")?.asBoolean ?: false
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.tapText(text, exact)
                }
                result to 200
            }

            method == "POST" && path == "/type" -> {
                val text = body.get("text")?.asString ?: ""
                val clearFirst = body.get("clearFirst")?.asBoolean ?: false
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.typeText(text, clearFirst)
                }
                result to 200
            }

            method == "POST" && path == "/swipe" -> {
                val direction = body.get("direction")?.asString ?: ""
                val distance = body.get("distance")?.asString ?: "medium"
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.swipe(direction, distance)
                }
                result to 200
            }

            method == "POST" && path == "/open_app" -> {
                val pkg = body.get("package")?.asString
                    ?: return mapOf("error" to "Missing package") to 400
                val result = ActionExecutor.openApp(pkg)
                result to 200
            }

            method == "POST" && path == "/press_key" -> {
                val key = body.get("key")?.asString ?: ""
                val result = ActionExecutor.pressKey(key)
                result to 200
            }

            method == "GET" && path == "/screenshot" -> {
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.takeScreenshot()
                }
                result to 200
            }

            method == "POST" && path == "/scroll" -> {
                val direction = body.get("direction")?.asString ?: ""
                val nodeId = body.get("nodeId")?.asString
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.scroll(direction, nodeId)
                }
                result to 200
            }

            method == "POST" && path == "/wait" -> {
                val text = body.get("text")?.asString
                val className = body.get("className")?.asString
                val timeoutMs = body.get("timeoutMs")?.asInt ?: 5000
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.waitForElement(text, className, timeoutMs)
                }
                result to 200
            }

            method == "GET" && path == "/apps" -> {
                val apps = ActionExecutor.getInstalledApps()
                mapOf("apps" to apps, "count" to apps.size) to 200
            }

            method == "GET" && path == "/current_app" -> {
                val result = withContext(Dispatchers.Main) {
                    val service = BridgeAccessibilityService.instance

                    if (service == null) {
                        mapOf(
                            "package" to "unknown",
                            "className" to "unknown",
                            "source" to "service_unavailable",
                        )
                    } else {
                        val launcherPackages = setOf(
                            "com.miui.home",
                            "com.android.launcher",
                            "com.google.android.apps.nexuslauncher",
                            "com.google.android.launcher",
                        )

                        fun packageNameOf(root: AccessibilityNodeInfo?): String {
                            return root?.packageName?.toString() ?: "unknown"
                        }

                        fun classNameOf(root: AccessibilityNodeInfo?): String {
                            return root?.className?.toString() ?: "unknown"
                        }

                        fun resultFromRoot(
                            root: AccessibilityNodeInfo?,
                            source: String,
                        ): Map<String, String>? {
                            if (root == null) return null

                            val pkg = packageNameOf(root)

                            return if (
                                pkg != "unknown" &&
                                pkg !in launcherPackages
                            ) {
                                mapOf(
                                    "package" to pkg,
                                    "className" to classNameOf(root),
                                    "source" to source,
                                )
                            } else {
                                null
                            }
                        }

                        /*
                         * 1. Android 已经直接提供的 active window。
                         *    这是判断当前前台 App 最可靠的入口。
                         */
                        val activeRoot = service.rootInActiveWindow

                        val activeResult = resultFromRoot(
                            activeRoot,
                            "rootInActiveWindow",
                        )

                        if (activeResult != null) {
                            activeRoot?.recycle()
                            activeResult
                        } else {
                            activeRoot?.recycle()

                            /*
                             * 2. 如果 rootInActiveWindow 没有得到真正的 App，
                             *    再检查所有 AccessibilityWindowInfo。
                             */
                            val windows = service.windows

                            data class Candidate(
                                val window: AccessibilityWindowInfo,
                                val root: AccessibilityNodeInfo,
                                val packageName: String,
                                val className: String,
                            )

                            val candidates = mutableListOf<Candidate>()

                            try {
                                for (window in windows) {
                                    if (
                                        window.type != AccessibilityWindowInfo.TYPE_APPLICATION
                                    ) {
                                        continue
                                    }

                                    val root = window.root ?: continue
                                    val pkg = packageNameOf(root)

                                    if (pkg == "unknown") {
                                        root.recycle()
                                        continue
                                    }

                                    candidates += Candidate(
                                        window = window,
                                        root = root,
                                        packageName = pkg,
                                        className = classNameOf(root),
                                    )
                                }

                                /*
                                 * 排除 Launcher 后的真正应用窗口。
                                 */
                                val appCandidates = candidates.filter {
                                    it.packageName !in launcherPackages
                                }

                                /*
                                 * 3. active + focused
                                 */
                                val activeFocused = appCandidates.firstOrNull {
                                    it.window.isActive && it.window.isFocused
                                }

                                if (activeFocused != null) {
                                    mapOf(
                                        "package" to activeFocused.packageName,
                                        "className" to activeFocused.className,
                                        "source" to "window_active_focused",
                                    )
                                } else {
                                    /*
                                     * 4. focused
                                     */
                                    val focused = appCandidates.firstOrNull {
                                        it.window.isFocused
                                    }

                                    if (focused != null) {
                                        mapOf(
                                            "package" to focused.packageName,
                                            "className" to focused.className,
                                            "source" to "window_focused",
                                        )
                                    } else {
                                        /*
                                         * 5. active
                                         */
                                        val active = appCandidates.firstOrNull {
                                            it.window.isActive
                                        }

                                        if (active != null) {
                                            mapOf(
                                                "package" to active.packageName,
                                                "className" to active.className,
                                                "source" to "window_active",
                                            )
                                        } else if (appCandidates.size == 1) {
                                            /*
                                             * 6. 只有一个真正的应用窗口。
                                             */
                                            val only = appCandidates[0]

                                            mapOf(
                                                "package" to only.packageName,
                                                "className" to only.className,
                                                "source" to "window_single_app",
                                            )
                                        } else {
                                            /*
                                             * 7. 最后的兜底：
                                             *    优先保留 active/focused 的任意窗口。
                                             */
                                            val anyActiveFocused =
                                                candidates.firstOrNull {
                                                    it.window.isActive &&
                                                        it.window.isFocused
                                                }

                                            val anyFocused =
                                                candidates.firstOrNull {
                                                    it.window.isFocused
                                                }

                                            val anyActive =
                                                candidates.firstOrNull {
                                                    it.window.isActive
                                                }

                                            val fallback =
                                                anyActiveFocused
                                                    ?: anyFocused
                                                    ?: anyActive
                                                    ?: candidates.firstOrNull()

                                            if (fallback != null) {
                                                mapOf(
                                                    "package" to fallback.packageName,
                                                    "className" to fallback.className,
                                                    "source" to "window_fallback",
                                                )
                                            } else {
                                                mapOf(
                                                    "package" to "com.miui.home",
                                                    "className" to "unknown",
                                                    "source" to "launcher_or_unknown",
                                                )
                                            }
                                        }
                                    }
                                }
                            } finally {
                                /*
                                 * root / window 都是 Accessibility 对象，
                                 * 用完立即释放。
                                 */
                                candidates.forEach {
                                    it.root.recycle()
                                }

                                windows.forEach {
                                    it.recycle()
                                }
                            }
                        }
                    }
                }

                result to 200
            }

            method == "GET" && path == "/clipboard" -> {
                val result = ActionExecutor.clipboardRead()
                result to 200
            }

            method == "POST" && path == "/clipboard" -> {
                val text = body.get("text")?.asString ?: ""
                val result = ActionExecutor.clipboardWrite(text)
                result to 200
            }

            method == "GET" && path == "/notifications" -> {
                val limit = params.get("limit")?.asString?.toIntOrNull() ?: 50
                val since = params.get("since")?.asString?.toLongOrNull() ?: 0L
                // Dismissed notifications are retained on-device but only
                // served on explicit opt-in — cleared notifications can hold
                // PII the user has already dealt with (#100 follow-up).
                val includeRemoved = params.get("include_removed")?.asString
                    ?.equals("true", ignoreCase = true) == true
                val entries = if (since > 0) {
                    NotificationStore.getSince(since, limit, includeRemoved)
                } else {
                    NotificationStore.getAll(limit, includeRemoved)
                }
                val mapped = entries.map { NotificationStore.toMap(it) }
                val listenerRunning = BridgeNotificationListener.instance != null
                mapOf(
                    "notifications" to mapped,
                    "count" to mapped.size,
                    "listenerActive" to listenerRunning
                ) to 200
            }

            method == "POST" && path == "/long_press" -> {
                val x = body.get("x")?.asInt
                val y = body.get("y")?.asInt
                val nodeId = body.get("nodeId")?.asString
                val duration = body.get("duration")?.asLong ?: 500L
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.longPress(x, y, nodeId, duration)
                }
                result to 200
            }

            method == "POST" && path == "/drag" -> {
                val startX = body.get("startX")?.asInt ?: 0
                val startY = body.get("startY")?.asInt ?: 0
                val endX = body.get("endX")?.asInt ?: 0
                val endY = body.get("endY")?.asInt ?: 0
                val duration = body.get("duration")?.asLong ?: 500L
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.drag(startX, startY, endX, endY, duration)
                }
                result to 200
            }

            method == "POST" && path == "/gesture" -> {
            val pointsJson = body.getAsJsonArray("points")
            val points = pointsJson?.mapNotNull { item ->
                val a = item.asJsonArray
                if (a.size() >= 2) {
                    listOf(a[0].asInt, a[1].asInt)
                } else {
                    null
                }
            } ?: emptyList()

            val duration = body.get("duration")?.asLong ?: 1000L

            val result = withContext(Dispatchers.Main) {
                ActionExecutor.gesture(points, duration)
            }
            result to 200
        }

        method == "POST" && path == "/multi_gesture" -> {
            val strokesJson = body.getAsJsonArray("strokes")
            val strokes = strokesJson?.mapNotNull { strokeElement ->
                val strokeArray = strokeElement.asJsonArray
                val points = strokeArray.mapNotNull { pointElement ->
                    val a = pointElement.asJsonArray
                    if (a.size() >= 2) {
                        listOf(a[0].asInt, a[1].asInt)
                    } else {
                        null
                    }
                }
                if (points.size >= 2) points else null
            } ?: emptyList()

            val duration = body.get("duration")?.asLong ?: 1000L

            val result = withContext(Dispatchers.Main) {
                ActionExecutor.multiGesture(strokes, duration)
            }
            result to 200
        }

        method == "POST" && path == "/describe_node" -> {
                val nodeId = body.get("nodeId")?.asString ?: ""
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.describeNode(nodeId)
                }
                result to 200
            }

            method == "POST" && path == "/find_nodes" -> {
                val text = body.get("text")?.asString
                val className = body.get("className")?.asString
                val clickable = body.get("clickable")?.asBoolean
                val limit = body.get("limit")?.asInt ?: 20
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.findNodes(text, className, clickable, limit)
                }
                result to 200
            }

            method == "POST" && path == "/diff_screen" -> {
                val previousHash = body.get("previousHash")?.asString ?: ""
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.diffScreen(previousHash)
                }
                result to 200
            }

            method == "POST" && path == "/pinch" -> {
                val x = body.get("x")?.asInt ?: 0
                val y = body.get("y")?.asInt ?: 0
                val scale = body.get("scale")?.asFloat ?: 1.5f
                val duration = body.get("duration")?.asLong ?: 300L
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.pinch(x, y, scale, duration)
                }
                result to 200
            }

            method == "GET" && path == "/screen_hash" -> {
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.screenHash()
                }
                result to 200
            }

            method == "GET" && path == "/location" -> {
                val result = ActionExecutor.location()
                result to 200
            }

            method == "GET" && path == "/battery" -> {
                // Both values come from BatteryManager — percentage via
                // getIntProperty (API 21), charge state via isCharging (API 23)
                // — against a minSdk of 26, so there is no version gate and no
                // dependency on the accessibility service. Reading the level
                // out of the status-bar node instead would be language- and
                // OEM-fragile; see BatteryMonitor.
                //
                // No auth check here on purpose. The ktor interceptor in
                // BridgeServer requires a valid Bearer token for every path
                // except /ping, and the relay authenticates at connect time
                // before passing `true`. A second gate in the handler would be
                // unreachable and would only imply the first one is optional.
                //
                // The shape is fixed — both keys, always present. Nullable
                // fields are not an option even though BridgeServer configures
                // serializeNulls(): RelayClient serialises results with a
                // default Gson(), which drops nulls, so a nullable key would
                // appear over HTTP and vanish over the relay.
                val app = batteryContext()
                val percentage = BatteryMonitor.percentage(app)
                val charging = BatteryMonitor.charging(app)
                if (percentage == null || charging == null) {
                    return mapOf(
                        "error" to "Battery state unavailable on this device",
                    ) to 503
                }
                mapOf(
                    "batteryPercentage" to percentage,
                    "charging" to charging,
                ) to 200
            }

            method == "POST" && path == "/send_sms" -> {
                if (!DeviceCapabilities.hasTelephony) {
                    return mapOf("success" to false, "error" to "SMS not available on this device") to 200
                }
                val to = body.get("to")?.asString ?: ""
                val smsBody = body.get("body")?.asString ?: ""
                val result = ActionExecutor.sendSms(to, smsBody)
                result to 200
            }

            method == "POST" && path == "/call" -> {
                if (!DeviceCapabilities.hasTelephony) {
                    return mapOf(
                        "success" to false,
                        "error" to "Phone calls not available on this device"
                    ) to 200
                }

                val number = body.get("number")?.asString ?: ""

                val sim = if (
                    body.has("sim") &&
                    !body.get("sim").isJsonNull
                ) {
                    body.get("sim").asInt
                } else {
                    null
                }

                val result = ActionExecutor.makeCall(number, sim)

                result to 200
            }

            method == "POST" && path == "/media" -> {
                val action = body.get("action")?.asString ?: ""
                val result = ActionExecutor.mediaControl(action)
                result to 200
            }

            method == "GET" && path == "/events" -> {
                val limit = params.get("limit")?.asString?.toIntOrNull() ?: 50
                val since = params.get("since")?.asString?.toLongOrNull() ?: 0L
                val entries = if (since > 0) {
                    EventStore.getSince(since, limit)
                } else {
                    EventStore.getAll(limit)
                }
                val mapped = entries.map { EventStore.toMap(it) }
                mapOf("events" to mapped, "count" to mapped.size, "streaming" to EventStore.streamingEnabled) to 200
            }

            method == "POST" && path == "/events/stream" -> {
                val enabled = body.get("enabled")?.asBoolean ?: false
                EventStore.setStreaming(enabled)
                mapOf("success" to true, "streaming" to enabled) to 200
            }

            method == "GET" && path == "/contacts" -> {
                if (!DeviceCapabilities.hasTelephony) {
                    return mapOf("success" to false, "error" to "Contacts not available on this device") to 200
                }
                val query = params.get("query")?.asString ?: ""
                val limit = params.get("limit")?.asString?.toIntOrNull() ?: 20
                val result = withContext(Dispatchers.IO) {
                    ActionExecutor.searchContacts(query, limit)
                }
                result to 200
            }

            method == "POST" && path == "/intent" -> {
                val action = body.get("action")?.asString ?: ""
                val dataUri = body.get("dataUri")?.asString
                val extrasObj = body.get("extras")?.asJsonObject
                val extras = extrasObj?.let { obj ->
                    val map = mutableMapOf<String, String>()
                    obj.entrySet().forEach { (k, v) -> map[k] = v.asString }
                    map
                }
                val packageOverride = body.get("packageOverride")?.asString
                val result = ActionExecutor.sendIntent(action, dataUri, extras, packageOverride)
                result to 200
            }

            method == "POST" && path == "/broadcast" -> {
                val action = body.get("action")?.asString ?: ""
                val extrasObj = body.get("extras")?.asJsonObject
                val extras = extrasObj?.let { obj ->
                    val map = mutableMapOf<String, String>()
                    obj.entrySet().forEach { (k, v) -> map[k] = v.asString }
                    map
                }
                val result = ActionExecutor.sendBroadcast(action, extras)
                result to 200
            }

            method == "POST" && path == "/speak" -> {
                val text = body.get("text")?.asString ?: ""
                val queue = body.get("queue")?.asInt ?: 1
                val result = ActionExecutor.speak(text, queue)
                result to 200
            }

            method == "POST" && path == "/overlay/show" -> {
                val context = BridgeApplication.instance
                val validation = HermesUiRuntime.validatePage(body)
                if (!validation.first) return mapOf("success" to false, "error" to validation.second) to 400
                if (!HermesPopupOverlay.canShow(context)) {
                    mapOf("success" to false, "error" to "OVERLAY_PERMISSION_REQUIRED", "message" to "Enable Display over other apps for Hermes Bridge", "settings_action" to "android.settings.action.MANAGE_OVERLAY_PERMISSION") to 403
                } else {
                    fun parsePage(obj: JsonObject): HermesPopupOverlay.Page? {
                        val id = obj.get("page_id")?.asString?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                        val title = obj.get("title")?.asString?.takeIf { it.isNotBlank() } ?: "Hermes"
                        val kind = when (obj.get("kind")?.asString?.lowercase()) {
                            "form", "survey" -> HermesPopupOverlay.Kind.FORM
                            "panel", "control", "action" -> HermesPopupOverlay.Kind.PANEL
                            else -> HermesPopupOverlay.Kind.NOTIFICATION
                        }
                        val fields = obj.get("fields")?.let(HermesPopupOverlay::parseFields) ?: emptyList()
                        val actions = obj.get("actions")?.let(HermesPopupOverlay::parseActions) ?: emptyList()
                        val message = obj.get("message")?.asString ?: obj.get("description")?.asString ?: ""
                        val revision = obj.get("revision")?.asLong ?: 0L
                        val progress = obj.get("progress")?.asFloat
                        val progressLabel = obj.get("progress_label")?.asString
                        if (kind == HermesPopupOverlay.Kind.NOTIFICATION && message.isBlank() && actions.isEmpty()) return null
                        return HermesPopupOverlay.Page(
                            id = id,
                            title = title,
                            message = message,
                            kind = kind,
                            fields = fields,
                            actions = actions,
                            autoCloseMs = (obj.get("auto_close_ms")?.asLong ?: 0L).coerceIn(0L, 10 * 60 * 1000L),
                            revision = revision,
                            progress = progress,
                            progressLabel = progressLabel,
                            flowId = obj.get("flow_id")?.asString,
                            stepIndex = obj.get("step_index")?.asInt,
                            stepCount = obj.get("step_count")?.asInt,
                            backPageId = obj.get("back_page_id")?.asString,
                            nextPageId = obj.get("next_page_id")?.asString,
                            status = obj.get("status")?.asString ?: "ready",
                            lists = obj.get("lists")?.asJsonArray?.mapNotNull { x -> x.asJsonObject.let { q -> HermesPopupOverlay.ListBlock(q.get("id")?.asString ?: "list", q.get("title")?.asString ?: "", q.get("items")?.asJsonArray?.map { it.asString } ?: emptyList()) } } ?: emptyList(),
                            tabs = obj.get("tabs")?.asJsonArray?.map { it.asString } ?: emptyList(),
                            activeTab = obj.get("active_tab")?.asInt ?: 0,
                        )
                    }
                    val pages = body.get("pages")?.asJsonArray?.mapNotNull { parsePage(it.asJsonObject) }
                    if (pages != null) {
                        if (pages.isEmpty()) mapOf("success" to false, "error" to "pages must contain valid page definitions") to 400
                        else {
                            val accepted = HermesPopupOverlay.showPages(context, pages)
                            mapOf("success" to true, "accepted" to accepted, "page_count" to HermesPopupOverlay.pageCount()) to 200
                        }
                    } else {
                        val page = parsePage(body)
                        if (page == null) mapOf("success" to false, "error" to "message, actions, or valid page content is required") to 400
                        else {
                            val id = HermesPopupOverlay.showPage(context, page)
                            mapOf("success" to true, "page_id" to id, "page_count" to HermesPopupOverlay.pageCount()) to 200
                        }
                    }
                }
            }

            method == "POST" && path == "/overlay/update" -> {
                val validation = HermesUiRuntime.validatePage(body)
                if (!validation.first) return mapOf("success" to false, "error" to validation.second) to 400
                val pageId = body.get("page_id")?.asString?.trim().orEmpty()
                if (pageId.isBlank()) {
                    mapOf("success" to false, "error" to "page_id is required") to 400
                } else {
                    val kind = when (body.get("kind")?.asString?.lowercase()) {
                        "form", "survey" -> HermesPopupOverlay.Kind.FORM
                        "panel", "control", "action" -> HermesPopupOverlay.Kind.PANEL
                        else -> HermesPopupOverlay.Kind.NOTIFICATION
                    }
                    val page = HermesPopupOverlay.Page(
                        id = pageId,
                        title = body.get("title")?.asString ?: "Hermes",
                        message = body.get("message")?.asString ?: "",
                        kind = kind,
                        fields = body.get("fields")?.let(HermesPopupOverlay::parseFields) ?: emptyList(),
                        actions = body.get("actions")?.let(HermesPopupOverlay::parseActions) ?: emptyList(),
                        autoCloseMs = (body.get("auto_close_ms")?.asLong ?: 0L).coerceIn(0L, 10 * 60 * 1000L),
                        revision = body.get("revision")?.asLong ?: 0L,
                        progress = body.get("progress")?.asFloat,
                        progressLabel = body.get("progress_label")?.asString,
                        flowId = body.get("flow_id")?.asString,
                        stepIndex = body.get("step_index")?.asInt,
                        stepCount = body.get("step_count")?.asInt,
                        backPageId = body.get("back_page_id")?.asString,
                        nextPageId = body.get("next_page_id")?.asString,
                        status = body.get("status")?.asString ?: "ready",
                        lists = body.get("lists")?.asJsonArray?.mapNotNull { x -> x.asJsonObject.let { q -> HermesPopupOverlay.ListBlock(q.get("id")?.asString ?: "list", q.get("title")?.asString ?: "", q.get("items")?.asJsonArray?.map { it.asString } ?: emptyList()) } } ?: emptyList(),
                        tabs = body.get("tabs")?.asJsonArray?.map { it.asString } ?: emptyList(),
                        activeTab = body.get("active_tab")?.asInt ?: 0,
                    )
                    val accepted = HermesPopupOverlay.updatePage(BridgeApplication.instance, page)
                    mapOf("success" to accepted, "page_id" to pageId, "revision" to page.revision, "page_count" to HermesPopupOverlay.pageCount()) to if (accepted) 200 else 409
                }
            }

            method == "POST" && path == "/overlay/patch" -> {
                val validation = HermesUiRuntime.validatePatch(body)
                if (!validation.first) return mapOf("success" to false, "error" to validation.second) to 400
                val pageId = body.get("page_id")?.asString?.trim().orEmpty()
                if (pageId.isBlank()) mapOf("success" to false,"error" to "page_id is required") to 400
                else {
                    val patch = body.deepCopy()
                    patch.remove("page_id")
                    val accepted = HermesPopupOverlay.patchPage(BridgeApplication.instance,pageId,patch)
                    mapOf("success" to accepted,"page_id" to pageId,"page_count" to HermesPopupOverlay.pageCount()) to if(accepted)200 else 409
                }
            }

            method == "POST" && path == "/overlay/component_patch" -> {
                val patchObj = body.get("patch")?.takeIf { it.isJsonObject }?.asJsonObject
                val validation = HermesUiRuntime.validateComponentPatch(patchObj)
                if (!validation.first) return mapOf("success" to false, "error" to validation.second) to 400
                val pageId = body.get("page_id")?.asString?.trim().orEmpty()
                val componentId = body.get("component_id")?.asString?.trim().orEmpty()
                val patch = body.get("patch")?.asJsonObject
                if (pageId.isBlank() || componentId.isBlank() || patch == null) mapOf("success" to false, "error" to "page_id, component_id and patch are required") to 400
                else { val ok = HermesPopupOverlay.patchComponent(BridgeApplication.instance, pageId, componentId, patch); mapOf("success" to ok, "page_id" to pageId, "component_id" to componentId) to if(ok) 200 else 409 }
            }

            method == "POST" && path == "/overlay/action_state" -> {
                val pageId = body.get("page_id")?.asString?.trim().orEmpty(); val actionId = body.get("action_id")?.asString?.trim().orEmpty(); val state = body.get("state")?.asString?.trim().orEmpty()
                if (pageId.isBlank() || actionId.isBlank() || state.isBlank()) mapOf("success" to false, "error" to "page_id, action_id and state are required") to 400
                else { val ok = HermesPopupOverlay.updateActionState(BridgeApplication.instance, pageId, actionId, state, body.get("progress")?.asFloat, body.get("message")?.asString); mapOf("success" to ok) to if(ok) 200 else 409 }
            }

            method == "GET" && path == "/overlay/state" -> {
                val pages = HermesPopupOverlay.debugSnapshot().map { p -> mapOf("page_id" to p.id, "title" to p.title, "kind" to p.kind.name.lowercase(), "revision" to p.revision, "status" to p.status, "action_count" to p.actions.size, "field_count" to p.fields.size, "list_count" to p.lists.size) }
                mapOf("success" to true, "page_count" to pages.size, "pages" to pages) to 200
            }

            method == "POST" && path == "/overlay/toast" -> {
                val text = body.get("text")?.asString?.take(500).orEmpty(); if (text.isBlank()) mapOf("success" to false,"error" to "text is required") to 400 else { android.widget.Toast.makeText(BridgeApplication.instance, text, android.widget.Toast.LENGTH_SHORT).show(); mapOf("success" to true) to 200 }
            }

            method == "POST" && path == "/overlay/close" -> {
                val pageId = body.get("page_id")?.asString?.trim().orEmpty()
                if (pageId.isBlank()) {
                    mapOf("success" to false, "error" to "page_id is required") to 400
                } else {
                    val closed = HermesPopupOverlay.close(BridgeApplication.instance, pageId)
                    mapOf("success" to closed, "page_id" to pageId) to 200
                }
            }

            method == "POST" && path == "/overlay/close_all" -> {
                HermesPopupOverlay.closeAll(BridgeApplication.instance)
                mapOf("success" to true, "page_count" to 0) to 200
            }

            method == "POST" && path == "/overlay/minimize" -> {
                HermesPopupOverlay.minimize(BridgeApplication.instance)
                mapOf("success" to true, "state" to "minimized", "page_count" to HermesPopupOverlay.pageCount()) to 200
            }

            method == "POST" && path == "/overlay/restore" -> {
                HermesPopupOverlay.restore(BridgeApplication.instance)
                mapOf("success" to true, "state" to "expanded", "page_count" to HermesPopupOverlay.pageCount()) to 200
            }

            method == "POST" && path == "/overlay/hide" -> {
                HermesPopupOverlay.hide(BridgeApplication.instance)
                mapOf("success" to true, "message" to "Overlay hidden") to 200
            }

            method == "POST" && path == "/stop_speaking" -> {
                val result = ActionExecutor.stopSpeaking()
                result to 200
            }

            // ========================================================
            // Realtime telephone downlink audio
            //
            // Separate from /mic_start so the existing microphone
            // recording pipeline remains unchanged.
            // ========================================================

            method == "POST" && path == "/call_audio_start" -> {

                val app = BridgeApplication.instance

                if (
                    app.checkSelfPermission(
                        Manifest.permission.RECORD_AUDIO,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return mapOf(
                        "success" to false,
                        "error" to "Microphone permission is not granted",
                    ) to 403
                }

                if (
                    CallAudioStreamingService.isRunning()
                ) {
                    return mapOf(
                        "success" to false,
                        "error" to "Call audio streaming is already active",
                        "requestId" to CallAudioStreamingService.requestId(),
                    ) to 409
                }

                val requestId = runCatching {
                    CallAudioStreamingService.start(app)
                }.getOrElse { error ->

                    return mapOf(
                        "success" to false,
                        "error" to (
                            "Could not start call audio service " +
                                "(${error.javaClass.simpleName})"
                            ),
                    ) to 500
                }

                mapOf(
                    "success" to true,
                    "status" to "starting",
                    "requestId" to requestId,
                    "source" to "VOICE_DOWNLINK",
                    "sampleRate" to 16000,
                    "channels" to 1,
                    "encoding" to "PCM16",
                    "frameMs" to 40,
                ) to 202
            }

            method == "POST" && path == "/call_audio_stop" -> {

                if (
                    !CallAudioStreamingService.isRunning()
                ) {
                    return mapOf(
                        "success" to true,
                        "status" to "idle",
                    ) to 200
                }

                runCatching {

                    CallAudioStreamingService.stop(
                        BridgeApplication.instance,
                    )

                    mapOf(
                        "success" to true,
                        "status" to "stopping",
                        "requestId" to CallAudioStreamingService.requestId(),
                    ) to 202

                }.getOrElse { error ->

                    mapOf(
                        "success" to false,
                        "error" to (
                            "Could not stop call audio service " +
                                "(${error.javaClass.simpleName})"
                            ),
                    ) to 500
                }
            }

            method == "GET" && path == "/call_audio_status" -> {

                CallAudioStreamingService.status() to 200
            }

            method == "POST" && path == "/mic_start" -> {
                val durationValue = body.get("duration")
                val durationSec = when {
                    durationValue == null -> 0
                    !durationValue.isJsonPrimitive || !durationValue.asJsonPrimitive.isNumber -> {
                        return mapOf("error" to "duration must be an integer number of seconds") to 400
                    }
                    else -> durationValue.asJsonPrimitive.asString.toIntOrNull()
                        ?: return mapOf("error" to "duration must be an integer number of seconds") to 400
                }
                val app = BridgeApplication.instance
                if (durationSec !in 0..MicrophoneRecorderService.MAX_DURATION_SECONDS) {
                    return mapOf(
                        "error" to "duration must be between 0 and ${MicrophoneRecorderService.MAX_DURATION_SECONDS} seconds"
                    ) to 400
                }
                if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    return mapOf("error" to "Microphone permission is not granted") to 403
                }
                if (!MicrophoneRecordingState.tryReserveStart()) {
                    return mapOf("error" to "A microphone recording is already active") to 409
                }

                runCatching {
                    MicrophoneRecorderService.start(app, durationSec)
                    mapOf(
                        "status" to "starting",
                        "duration" to durationSec,
                    ) to 202
                }.getOrElse { error ->
                    MicrophoneRecordingState.markError(
                        "Could not start microphone service (${error.javaClass.simpleName})",
                    )
                    mapOf(
                        "error" to "Could not start microphone service (${error.javaClass.simpleName})"
                    ) to 500
                }
            }

            method == "POST" && path == "/mic_stop" -> {
                val snapshot = MicrophoneRecordingState.snapshot()
                if (!snapshot.isActive) {
                    return mapOf("status" to "idle") to 200
                }
                runCatching {
                    MicrophoneRecorderService.stop(BridgeApplication.instance)
                    mapOf("status" to "stopping") to 202
                }.getOrElse { error ->
                    mapOf(
                        "error" to "Could not stop microphone service (${error.javaClass.simpleName})"
                    ) to 500
                }
            }

            method == "GET" && path == "/mic_status" -> {
                val app = BridgeApplication.instance
                val snapshot = MicrophoneRecordingState.snapshot()
                val files = MicrophoneRecordingFiles.listCompleted(app)
                val latest = files.firstOrNull()
                mapOf(
                    "phase" to snapshot.phase.name.lowercase(),
                    "recording" to snapshot.isActive,
                    "count" to files.size,
                    "retentionLimit" to MicrophoneRecordingFiles.MAX_COMPLETED_RECORDINGS,
                    "latest" to latest?.name,
                    "latestSize" to latest?.length(),
                    "bytesWritten" to snapshot.bytesWritten,
                    "startedAt" to snapshot.startedAtMs,
                    "error" to snapshot.error,
                ) to 200
            }

            method == "POST" && path == "/screen_record" -> {
                val durationMs = body.get("durationMs")?.asLong?.coerceAtMost(30_000L) ?: 5000L
                val result = withContext(Dispatchers.IO) {
                    ScreenRecorder.record(durationMs)
                }
                result to 200
            }

            method == "GET" && path == "/widgets" -> {
                val result = withContext(Dispatchers.Main) {
                    ActionExecutor.readWidgets()
                }
                result to 200
            }

            else -> {
                mapOf("error" to "Unknown command: $method $path") to 404
            }
        }
    }

    private fun countAllNodes(nodes: List<ScreenNode>): Int {
        var count = 0
        for (node in nodes) {
            count += 1 + countAllNodes(node.children)
        }
        return count
    }
}
