package com.hermesandroid.bridge.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.google.gson.JsonObject
import com.hermesandroid.bridge.client.RelayClient
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.math.roundToInt

/**
 * Hermes dynamic mobile UI host.
 *
 * This is intentionally a renderer/state host rather than a business-logic layer:
 * Hermes owns the page schema and actions; Android owns rendering, local input,
 * lifecycle and user interaction.
 */
object HermesPopupOverlay {
    private const val DEFAULT_TITLE = "Hermes"
    private const val MAX_AUTO_CLOSE_MS = 10 * 60 * 1000L
    private const val MAX_PAGES = 12
    private const val PREFS = "hermes_overlay_prefs"
    private const val KEY_BALL_COLOR = "ball_color"
    private const val KEY_BALL_SIZE = "ball_size"
    private const val KEY_BALL_X = "ball_x"
    private const val KEY_BALL_Y = "ball_y"
    private const val KEY_WINDOW_X = "window_x"
    private const val KEY_WINDOW_Y = "window_y"
    private const val KEY_OPACITY = "ui_opacity"
    private const val KEY_TEXT_COLOR = "ui_text_color"
    private const val KEY_BACKGROUND_COLOR = "ui_background_color"
    private const val KEY_BACKGROUND_STYLE = "ui_background_style"
    private const val KEY_BALL_SHAPE = "ball_shape"
    private const val KEY_POPUP_WIDTH = "popup_width_dp"
    private const val KEY_POPUP_HEIGHT = "popup_height_percent"
    private const val KEY_DEFAULT_OPACITY = "default_ui_opacity"
    private const val KEY_DEFAULT_TEXT_COLOR = "default_ui_text_color"
    private const val KEY_DEFAULT_BACKGROUND_COLOR = "default_ui_background_color"
    private const val KEY_DEFAULT_BACKGROUND_STYLE = "default_ui_background_style"
    private const val KEY_DEFAULT_BALL_COLOR = "default_ui_ball_color"
    private const val KEY_DEFAULT_BALL_SIZE = "default_ui_ball_size"
    private const val KEY_DEFAULT_BALL_SHAPE = "default_ui_ball_shape"
    private const val KEY_DEFAULT_POPUP_WIDTH = "default_ui_popup_width"
    private const val KEY_DEFAULT_POPUP_HEIGHT = "default_ui_popup_height"

    enum class Kind { NOTIFICATION, FORM, PANEL }

    /*
     * 通用 section/group 折叠状态。
     * key = pageId:fieldId
     * 只保存当前页面运行期间的 UI 状态，不影响插件字段值。
     */
    private val collapsedSections = mutableMapOf<String, Boolean>()

    data class Field(
        val id: String,
        val label: String,
        val type: String = "text",
        val value: String = "",
        val placeholder: String = "",
        val options: List<String> = emptyList(),
        val required: Boolean = false,
        val min: Double? = null,
        val max: Double? = null,
        val step: Double? = null,
        val onChangeActionId: String? = null,
        val children: List<Field> = emptyList(),
    )

    data class Action(
        val id: String,
        val label: String,
        val style: String = "primary",
        val disabled: Boolean = false,
        val role: String = "action",
        val targetPageId: String? = null,
        val event: String? = null,
        val debounceMs: Long? = null,
        val state: String = "idle",
    )

    data class Page(
        val id: String,
        val title: String,
        val message: String = "",
        val kind: Kind = Kind.NOTIFICATION,
        val fields: List<Field> = emptyList(),
        val actions: List<Action> = emptyList(),
        val autoCloseMs: Long = 0L,
        val revision: Long = 0L,
        val progress: Float? = null,
        val progressLabel: String? = null,
        val flowId: String? = null,
        val stepIndex: Int? = null,
        val stepCount: Int? = null,
        val backPageId: String? = null,
        val nextPageId: String? = null,
        val status: String = "ready",
        val lists: List<ListBlock> = emptyList(),
        val tabs: List<String> = emptyList(),
        val activeTab: Int = 0,
    )

    data class ListBlock(val id: String, val title: String = "", val items: List<String> = emptyList())

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pages = LinkedHashMap<String, Page>()
    private var expanded = true

    // One persistent WindowManager window.
    // Expanded/minimized only swaps the child content.
    private var overlayView: FrameLayout? = null
    private var windowManager: WindowManager? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private val autoCloseTasks = mutableMapOf<String, Runnable>()
    private val fieldValues = mutableMapOf<String, MutableMap<String, String>>()
    private val pageRevisions = mutableMapOf<String, Long>()
    private val onChangeJobs = mutableMapOf<String, Runnable>()
    private val actionState = mutableMapOf<String, MutableMap<String, String>>()
    private var restored = false
    private var expandedStructureKey: String? = null
    private var imeBottomInset = 0
    private var imeVisible = false
    private var pendingExpandedRefresh = false

    fun init(context: Context) {
        if (restored) return
        restored = true
        HermesUiRuntime.loadPages(context)?.let { (saved, savedExpanded) ->
            saved.take(MAX_PAGES).forEach { pages[it.id] = it.normalized(); pageRevisions[it.id] = it.revision }
            expanded = savedExpanded
        }
    }

    fun canShow(context: Context) = Settings.canDrawOverlays(context)
    fun pageCount(): Int = pages.size

    fun ballColor(context: Context): Int = context.getSharedPreferences(PREFS, 0)
        .getInt(KEY_BALL_COLOR, Color.rgb(42, 43, 50))

    fun ballSize(context: Context): Int = context.getSharedPreferences(PREFS, 0)
        .getInt(KEY_BALL_SIZE, 56)

    fun overlayOpacity(context: Context): Float = context.getSharedPreferences(PREFS, 0)
        .getFloat(KEY_OPACITY, 0.94f).coerceIn(0.25f, 1f)

    fun textColor(context: Context): Int = context.getSharedPreferences(PREFS, 0)
        .getInt(KEY_TEXT_COLOR, Color.WHITE)

    fun backgroundColor(context: Context): Int = context.getSharedPreferences(PREFS, 0)
        .getInt(KEY_BACKGROUND_COLOR, Color.rgb(20, 21, 25))

    fun secondaryTextColor(context: Context): Int {
        val c = textColor(context)
        return Color.argb(190, Color.red(c), Color.green(c), Color.blue(c))
    }

    fun backgroundStyle(context: Context): Int = context.getSharedPreferences(PREFS, 0)
        .getInt(KEY_BACKGROUND_STYLE, 0).coerceIn(0, 2)

    fun ballShape(context: Context): Int = context.getSharedPreferences(PREFS, 0)
        .getInt(KEY_BALL_SHAPE, 0).coerceIn(0, 2)

    fun popupWidth(context: Context): Int = context.getSharedPreferences(PREFS, 0)
        .getInt(KEY_POPUP_WIDTH, 380).coerceIn(200, 600)

    fun popupHeightPercent(context: Context): Int = context.getSharedPreferences(PREFS, 0)
        .getInt(KEY_POPUP_HEIGHT, 72).coerceIn(45, 90)

    fun saveUiStyle(
        context: Context,
        opacity: Float,
        textColor: Int,
        backgroundColor: Int,
        backgroundStyle: Int,
        ballColor: Int,
        ballSize: Int,
        ballShape: Int,
        popupWidthDp: Int = popupWidth(context),
        popupHeightPct: Int = popupHeightPercent(context),
    ) {
        context.getSharedPreferences(PREFS, 0).edit()
            .putFloat(KEY_OPACITY, opacity.coerceIn(0.25f, 1f))
            .putInt(KEY_TEXT_COLOR, textColor)
            .putInt(KEY_BACKGROUND_COLOR, backgroundColor)
            .putInt(KEY_BACKGROUND_STYLE, backgroundStyle.coerceIn(0, 2))
            .putInt(KEY_BALL_COLOR, ballColor)
            .putInt(KEY_BALL_SIZE, ballSize.coerceIn(32, 120))
            .putInt(KEY_BALL_SHAPE, ballShape.coerceIn(0, 2))
            .putInt(KEY_POPUP_WIDTH, popupWidthDp.coerceIn(200, 600))
            .putInt(KEY_POPUP_HEIGHT, popupHeightPct.coerceIn(45, 90))
            .apply()
        runOnMainSync {
            // Recreate the overlay so size, alpha and all theme colors take effect immediately.
            removeOverlay(context.applicationContext)
            render(context.applicationContext)
        }
    }

    fun setCurrentUiAsDefault(context: Context) {
        val p = context.getSharedPreferences(PREFS, 0)
        p.edit()
            .putFloat(KEY_DEFAULT_OPACITY, overlayOpacity(context))
            .putInt(KEY_DEFAULT_TEXT_COLOR, textColor(context))
            .putInt(KEY_DEFAULT_BACKGROUND_COLOR, backgroundColor(context))
            .putInt(KEY_DEFAULT_BACKGROUND_STYLE, backgroundStyle(context))
            .putInt(KEY_DEFAULT_BALL_COLOR, ballColor(context))
            .putInt(KEY_DEFAULT_BALL_SIZE, ballSize(context))
            .putInt(KEY_DEFAULT_BALL_SHAPE, ballShape(context))
            .putInt(KEY_DEFAULT_POPUP_WIDTH, popupWidth(context))
            .putInt(KEY_DEFAULT_POPUP_HEIGHT, popupHeightPercent(context))
            .apply()
    }

    fun resetEnvironment(context: Context) {
        runOnMainSync {
            autoCloseTasks.values.forEach(mainHandler::removeCallbacks)
            onChangeJobs.values.forEach(mainHandler::removeCallbacks)
            autoCloseTasks.clear(); onChangeJobs.clear()
            pages.clear(); fieldValues.clear(); pageRevisions.clear(); actionState.clear()
            val p = context.getSharedPreferences(PREFS, 0)
            val hasDefault = p.contains(KEY_DEFAULT_OPACITY)
            val editor = p.edit()
                .remove(KEY_BALL_X).remove(KEY_BALL_Y).remove(KEY_WINDOW_X).remove(KEY_WINDOW_Y)
            if (hasDefault) {
                editor.putFloat(KEY_OPACITY, p.getFloat(KEY_DEFAULT_OPACITY, 0.94f))
                    .putInt(KEY_TEXT_COLOR, p.getInt(KEY_DEFAULT_TEXT_COLOR, Color.WHITE))
                    .putInt(KEY_BACKGROUND_COLOR, p.getInt(KEY_DEFAULT_BACKGROUND_COLOR, Color.rgb(20, 21, 25)))
                    .putInt(KEY_BACKGROUND_STYLE, p.getInt(KEY_DEFAULT_BACKGROUND_STYLE, 0))
                    .putInt(KEY_BALL_COLOR, p.getInt(KEY_DEFAULT_BALL_COLOR, Color.rgb(42, 43, 50)))
                    .putInt(KEY_BALL_SIZE, p.getInt(KEY_DEFAULT_BALL_SIZE, 56))
                    .putInt(KEY_BALL_SHAPE, p.getInt(KEY_DEFAULT_BALL_SHAPE, 0))
                    .putInt(KEY_POPUP_WIDTH, p.getInt(KEY_DEFAULT_POPUP_WIDTH, 380))
                    .putInt(KEY_POPUP_HEIGHT, p.getInt(KEY_DEFAULT_POPUP_HEIGHT, 72))
            } else {
                editor.remove(KEY_OPACITY).remove(KEY_TEXT_COLOR).remove(KEY_BACKGROUND_COLOR)
                    .remove(KEY_BACKGROUND_STYLE).remove(KEY_BALL_COLOR).remove(KEY_BALL_SIZE)
                    .remove(KEY_BALL_SHAPE).remove(KEY_POPUP_WIDTH).remove(KEY_POPUP_HEIGHT)
            }
            editor.apply()
            HermesUiRuntime.clear(context.applicationContext)
            expanded = false
            render(context.applicationContext)
        }
    }

    fun setBallStyle(context: Context, color: Int, sizeDp: Int) {
        context.getSharedPreferences(PREFS, 0).edit()
            .putInt(KEY_BALL_COLOR, color)
            .putInt(KEY_BALL_SIZE, sizeDp.coerceIn(32, 120))
            .apply()
        if (!expanded && pages.isNotEmpty()) runOnMainSync { render(context.applicationContext) }
    }

    fun show(context: Context, title: String?, message: String, autoCloseMs: Long = 0L, pageId: String? = null): String? =
        showPage(
            context,
            Page(
                id = pageId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                title = title?.takeIf { it.isNotBlank() } ?: DEFAULT_TITLE,
                message = message,
                autoCloseMs = autoCloseMs.coerceIn(0, MAX_AUTO_CLOSE_MS)
            )
        )

    fun showPage(context: Context, page: Page): String? {
        if (!canShow(context)) return null
        runOnMainSync {
            val old = pages[page.id]
            val currentRevision = pageRevisions[page.id] ?: old?.revision ?: Long.MIN_VALUE
            if (page.revision <= currentRevision) return@runOnMainSync
            pages[page.id] = page.normalized()
            pageRevisions[page.id] = page.revision
            sendLifecycle(page.id, if (old == null) "page_created" else "page_updated", if (old == null) null else "replace")
            trimPages()
            expanded = true
            persist(context.applicationContext)
            render(context.applicationContext)
            sendLifecycle(page.id, "page_shown", null)
            scheduleAutoClose(context.applicationContext, page)
        }
        return page.id
    }

    fun showPages(context: Context, incoming: List<Page>): Int {
        if (!canShow(context)) return 0
        var accepted = 0
        runOnMainSync {
            val acceptedPages = mutableListOf<Page>()
            incoming.take(MAX_PAGES).forEach { page ->
                val currentRevision = pageRevisions[page.id] ?: pages[page.id]?.revision ?: Long.MIN_VALUE
                if (page.revision > currentRevision) {
                    val normalized = page.normalized()
                    pages[page.id] = normalized
                    pageRevisions[page.id] = normalized.revision
                    acceptedPages += normalized
                    accepted++
                }
            }
            trimPages()
            if (accepted > 0) expanded = true
            persist(context.applicationContext)
            render(context.applicationContext)
            acceptedPages.forEach { scheduleAutoClose(context.applicationContext, it) }
        }
        return accepted
    }

    /** Full replacement of an existing live page. User input and minimized state are preserved. */
    fun updatePage(context: Context, page: Page): Boolean {
        if (!canShow(context)) return false
        var accepted = false
        runOnMainSync {
            val old = pages[page.id]
            val currentRevision = pageRevisions[page.id] ?: old?.revision ?: Long.MIN_VALUE
            if (page.revision <= currentRevision) return@runOnMainSync
            if (old == null) pages[page.id] = page.normalized() else pages[page.id] = page.normalized()
            pageRevisions[page.id] = page.revision
            trimPages()
            persist(context.applicationContext)
            render(context.applicationContext)
            scheduleAutoClose(context.applicationContext, page)
            accepted = true
        }
        return accepted
    }

    /** Apply a partial update to an existing live page without changing its UI mode. */
    fun patchPage(context: Context, pageId: String, patch: JsonObject): Boolean {
        if (!canShow(context)) return false
        var accepted = false
        runOnMainSync {
            val old = pages[pageId] ?: return@runOnMainSync
            val revision = patch.get("revision")?.asLong ?: old.revision
            if (revision <= old.revision) return@runOnMainSync
            val next = old.copy(
                title = patch.get("title")?.asString ?: old.title,
                message = patch.get("message")?.asString ?: old.message,
                kind = patch.get("kind")?.asString?.let(::parseKind) ?: old.kind,
                fields = patch.get("fields")?.let(::parseFields) ?: old.fields,
                actions = patch.get("actions")?.let(::parseActions) ?: old.actions,
                autoCloseMs = (patch.get("auto_close_ms")?.asLong ?: old.autoCloseMs).coerceIn(0L, MAX_AUTO_CLOSE_MS),
                revision = revision,
                progress = patch.get("progress")?.asFloat?.coerceIn(0f, 1f) ?: old.progress,
                progressLabel = patch.get("progress_label")?.asString ?: old.progressLabel,
                flowId = patch.get("flow_id")?.asString ?: old.flowId,
                stepIndex = patch.get("step_index")?.asInt ?: old.stepIndex,
                stepCount = patch.get("step_count")?.asInt ?: old.stepCount,
                backPageId = patch.get("back_page_id")?.asString ?: old.backPageId,
                nextPageId = patch.get("next_page_id")?.asString ?: old.nextPageId,
                status = patch.get("status")?.asString ?: old.status,
                lists = patch.get("lists")?.asJsonArray?.mapNotNull { x -> x.asJsonObject.let { q -> ListBlock(q.get("id")?.asString ?: "list", q.get("title")?.asString ?: "", q.get("items")?.asJsonArray?.map { it.asString } ?: emptyList()) } } ?: old.lists,
                activeTab = patch.get("active_tab")?.asInt ?: old.activeTab,
                tabs = patch.get("tabs")?.asJsonArray?.map { it.asString } ?: old.tabs,
            ).normalized()
            pages[pageId] = next
            pageRevisions[pageId] = revision
            persist(context.applicationContext)
            render(context.applicationContext)
            scheduleAutoClose(context.applicationContext, next)
            accepted = true
        }
        return accepted
    }

    fun patchComponent(context: Context, pageId: String, componentId: String, patch: JsonObject): Boolean {
        return runOnMainSyncResult {
            val old = pages[pageId] ?: return@runOnMainSyncResult false
            val revision = patch.get("revision")?.asLong ?: (old.revision + 1)
            if (revision <= old.revision) return@runOnMainSyncResult false
            if (patch.get("text")?.asString?.length ?: 0 > HermesUiRuntime.MAX_TEXT) return@runOnMainSyncResult false
            val next = when {
                componentId == "message" -> old.copy(
                    message = patch.get("text")?.asString ?: old.message,
                    revision = revision,
                )
                componentId.startsWith("field:") -> {
                    val id = componentId.removePrefix("field:")
                    old.copy(
                        fields = old.fields.map {
                            if (it.id == id) it.copy(
                                label = patch.get("label")?.asString ?: it.label,
                                value = patch.get("value")?.asString ?: it.value,
                                placeholder = patch.get("placeholder")?.asString ?: it.placeholder,
                            ) else it
                        },
                        revision = revision,
                    )
                }
                componentId.startsWith("action:") -> {
                    val id = componentId.removePrefix("action:")
                    old.copy(
                        actions = old.actions.map {
                            if (it.id == id) it.copy(
                                label = patch.get("label")?.asString ?: it.label,
                                disabled = patch.get("disabled")?.asBoolean ?: it.disabled,
                                state = patch.get("state")?.asString ?: it.state,
                            ) else it
                        },
                        revision = revision,
                    )
                }
                else -> return@runOnMainSyncResult false
            }.normalized()
            pages[pageId] = next
            pageRevisions[pageId] = revision
            persist(context.applicationContext)
            render(context.applicationContext)
            true
        }
    }

    fun updateActionState(context: Context, pageId: String, actionId: String, state: String, progress: Float? = null, message: String? = null): Boolean {
        val page = pages[pageId] ?: return false
        val updated = page.copy(actions = page.actions.map { if (it.id == actionId) it.copy(state = state, disabled = state == "busy" || state == "loading") else it }, status = if (state == "error") "error" else page.status, revision = page.revision + 1)
        val ok = updatePage(context, updated)
        if (ok) { val payload = JsonObject().apply { addProperty("event", "action_state_changed"); addProperty("page_id", pageId); addProperty("action_id", actionId); addProperty("state", state); progress?.let { addProperty("progress", it) }; message?.let { addProperty("message", it) } }; RelayClient.sendUiEvent(payload) }
        return ok
    }

    fun getPage(pageId: String): Page? = pages[pageId]

    fun debugSnapshot(): List<Page> = runOnMainSyncResult { pages.values.toList() }

    fun close(context: Context, pageId: String, reason: String = "user_dismissed"): Boolean {
        var existed = false
        runOnMainSync {
            existed = pages.remove(pageId) != null
            clearPageState(pageId)
            if (existed) sendLifecycle(pageId, "page_closed", reason)
            persist(context.applicationContext)
            if (pages.isEmpty()) expanded = false
            render(context.applicationContext)
        }
        return existed
    }

    private fun sendLifecycle(pageId: String, event: String, reason: String? = null) {
        val payload = JsonObject().apply {
            addProperty("event", event)
            addProperty("page_id", pageId)
            reason?.let { addProperty("reason", it) }
        }
        RelayClient.sendUiEvent(payload)
    }

    fun closeAll(context: Context) = runOnMainSync {
        autoCloseTasks.values.forEach(mainHandler::removeCallbacks)
        onChangeJobs.values.forEach(mainHandler::removeCallbacks)
        autoCloseTasks.clear(); onChangeJobs.clear()
        pages.keys.toList().forEach { sendLifecycle(it, "page_closed", "close_all") }
        pages.clear(); fieldValues.clear(); pageRevisions.clear(); actionState.clear()
        HermesUiRuntime.clear(context.applicationContext)
        expanded = false
        persist(context.applicationContext)
        render(context.applicationContext)
    }

    fun hide(context: Context) = runOnMainSync {
        if (pages.isNotEmpty()) sendLifecycle("*", "overlay_hidden", "programmatic")
        removeOverlay(context.applicationContext)
    }

    fun minimize(context: Context) = runOnMainSync {
        expanded = false
        if (pages.isNotEmpty()) sendLifecycle("*", "overlay_minimized", "user")
        persist(context.applicationContext)
        render(context.applicationContext)
    }

    fun restore(context: Context) = runOnMainSync {
        expanded = true
        if (pages.isNotEmpty()) sendLifecycle("*", "overlay_restored", "user")
        render(context.applicationContext)
    }

    private fun Page.normalized(): Page = copy(
        title = title.ifBlank { DEFAULT_TITLE }.take(120),
        message = message.take(HermesUiRuntime.MAX_TEXT),
        fields = fields.take(30),
        actions = actions.take(20),
        lists = lists.take(20).map { it.copy(items = it.items.take(HermesUiRuntime.MAX_LIST_ITEMS)) },
        tabs = tabs.take(8),
        autoCloseMs = autoCloseMs.coerceIn(0L, MAX_AUTO_CLOSE_MS),
        progress = progress?.coerceIn(0f, 1f)
    )

    private fun trimPages() {
        while (pages.size > MAX_PAGES) {
            val removed = pages.keys.first()
            pages.remove(removed)
            clearPageState(removed)
            sendLifecycle(removed, "page_closed", "trimmed")
        }
    }

    private fun clearPageState(pageId: String) {
        fieldValues.remove(pageId)
        pageRevisions.remove(pageId)
        autoCloseTasks.remove(pageId)?.let(mainHandler::removeCallbacks)
        onChangeJobs.remove(pageId)?.let(mainHandler::removeCallbacks)
    }

    private fun persist(context: Context) { HermesUiRuntime.savePages(context, pages.values, expanded) }

    private fun scheduleAutoClose(context: Context, page: Page) {
        autoCloseTasks.remove(page.id)?.let(mainHandler::removeCallbacks)
        if (page.autoCloseMs <= 0) return
        val task = Runnable {
            val existed = pages.remove(page.id) != null
            clearPageState(page.id)
            if (existed) sendLifecycle(page.id, "page_closed", "timeout")
            if (pages.isEmpty()) expanded = false
            persist(context.applicationContext)
            render(context.applicationContext)
        }
        autoCloseTasks[page.id] = task
        mainHandler.postDelayed(task, page.autoCloseMs)
    }

    fun refresh(context: Context) {
        render(context)
    }



    private fun render(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, 0)

        val displayWidth = appContext.resources.displayMetrics.widthPixels
        val displayHeight = appContext.resources.displayMetrics.heightPixels

        val defaultX = dp(appContext, 12)
        val defaultY = dp(appContext, 52)

        /*
         * IMPORTANT:
         *
         * There is exactly ONE WindowManager window.
         * We never remove/add the floating ball when switching between
         * minimized and expanded mode.
         *
         * This prevents the classic:
         *     ball -> ball -> ball
         * duplication caused by stale overlay Window references.
         */
        var root = overlayView
        var wm = windowManager
        var params = overlayParams

        if (root == null || wm == null || params == null) {
            root = FrameLayout(appContext).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }

            wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            params = WindowManager.LayoutParams(
                WRAP,
                WRAP,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = if (expanded) prefs.getInt(KEY_WINDOW_X, defaultX) else prefs.getInt(KEY_BALL_X, defaultX)
                y = if (expanded) prefs.getInt(KEY_WINDOW_Y, defaultY) else prefs.getInt(KEY_BALL_Y, defaultY)
            }

            try {
                wm.addView(root, params)

                overlayView = root
                windowManager = wm
                overlayParams = params
            } catch (e: Exception) {
                overlayView = null
                windowManager = null
                overlayParams = null

                Toast.makeText(
                    appContext,
                    "Hermes UI failed: ${e.message ?: "unknown"}",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
        }

        val currentRoot = root
        val currentWm = wm
        val currentParams = params

        if (expanded) {
            /*
             * Keep the configured dp value and the actual Window width
             * consistent. The same 16dp screen margin is used by the
             * settings screen.
             */
            val maxPopupWidthPx =
                (displayWidth - dp(appContext, 16))
                    .coerceAtLeast(dp(appContext, 200))

            val popupWidthPx =
                dp(appContext, popupWidth(appContext))
                    .coerceIn(dp(appContext, 200), maxPopupWidthPx)

            val popupHeightPx =
                (displayHeight * popupHeightPercent(appContext) / 100f)
                    .roundToInt()
                    .coerceIn(
                        dp(appContext, 320),
                        (displayHeight - dp(appContext, 24)).coerceAtLeast(dp(appContext, 320))
                    )

            currentParams.width = popupWidthPx
            currentParams.height = popupHeightPx
            currentParams.flags =
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            // IME must not resize or reposition the floating window.
            // The content ScrollView handles keyboard avoidance instead.
            currentParams.softInputMode =
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            currentParams.x = prefs.getInt(KEY_WINDOW_X, defaultX).coerceAtLeast(0)
            currentParams.y = prefs.getInt(KEY_WINDOW_Y, defaultY).coerceAtLeast(0)
            currentParams.alpha = overlayOpacity(appContext)

            val structureKey = expandedStructureKey
            val existingPopup = currentRoot.findViewWithTag<View>("hermes_expanded_root")

            if (existingPopup == null) {
                currentRoot.removeAllViews()
                val popup = buildExpanded(appContext)
                currentRoot.addView(
                    popup,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                expandedStructureKey = structureKey
                popup.findViewWithTag<View>("hermes_drag_strip")?.let { strip ->
                    attachDrag(appContext, strip, currentParams, false)
                }
            } else {
                val content = existingPopup.findViewWithTag<LinearLayout>("hermes_content")
                val scroll = existingPopup.findViewWithTag<ScrollView>("hermes_scroll")
                val focused = existingPopup.findFocus()
                if (focused is EditText && imeVisible) {
                    // Never destroy/recreate an active EditText while the IME is attached.
                    // Queue the visual refresh until focus leaves the field.
                    pendingExpandedRefresh = true
                } else if (content != null) {
                    val savedScroll = scroll?.scrollY ?: 0
                    refreshExpandedContent(appContext, existingPopup as LinearLayout, content)
                    expandedStructureKey = structureKey
                    scroll?.post { scroll.scrollTo(0, savedScroll.coerceAtLeast(0)) }
                }
            }

            try {
                currentWm.updateViewLayout(currentRoot, currentParams)
            } catch (_: Exception) {
            }
        } else {
            currentParams.width = WRAP
            currentParams.height = WRAP
            currentParams.flags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            currentParams.softInputMode =
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            currentParams.x =
                prefs.getInt(KEY_BALL_X, defaultX).coerceAtLeast(0)
            currentParams.y =
                prefs.getInt(KEY_BALL_Y, defaultY).coerceAtLeast(0)
            currentParams.alpha = overlayOpacity(appContext)

            currentRoot.removeAllViews()

            val ball = buildMinimized(appContext)
            currentRoot.addView(
                ball,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )

            attachDrag(appContext, ball, currentParams, true)

            try {
                currentWm.updateViewLayout(currentRoot, currentParams)
            } catch (_: Exception) {
            }
        }
    }

    private fun pageSidebarTitle(title: String): String {
        val clean = title.trim().ifBlank { "页" }
        return clean.toCharArray().joinToString("\n")
    }

    private fun buildPageSidebar(context: Context): ScrollView {
        val scroll = ScrollView(context).apply {
            tag = "hermes_page_sidebar_scroll"
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            setPadding(0, 0, 0, dp(context, 8))
        }

        val list = LinearLayout(context).apply {
            tag = "hermes_page_sidebar"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }

        val activeId = pages.keys.lastOrNull()

        pages.values.forEach { page ->
            val selected = page.id == activeId

            val item = TextView(context).apply {
                text = pageSidebarTitle(page.title)
                textSize = 12f
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                setPadding(
                    dp(context, 1),
                    dp(context, 5),
                    dp(context, 1),
                    dp(context, 5)
                )
                setTextColor(
                    if (selected) Color.WHITE
                    else secondaryTextColor(context)
                )
                background = rounded(
                    if (selected) accentColor(context)
                    else Color.TRANSPARENT,
                    8
                )
                contentDescription = page.title

                setOnClickListener {
                    val target = pages.remove(page.id) ?: return@setOnClickListener
                    pages[page.id] = target
                    persist(context)
                    render(context)
                }
            }

            list.addView(
                item,
                LinearLayout.LayoutParams(
                    dp(context, 38),
                    dp(context, 52)
                ).apply {
                    bottomMargin = dp(context, 4)
                }
            )
        }

        scroll.addView(
            list,
            ViewGroup.LayoutParams(
                dp(context, 40),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        return scroll
    }

    private fun refreshPageSidebar(context: Context, root: LinearLayout) {
        val sidebar =
            root.findViewWithTag<ScrollView>("hermes_page_sidebar_scroll")
                ?: return

        val oldY = sidebar.scrollY
        val parent = sidebar.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(sidebar)

        parent.removeViewAt(index)
        parent.addView(buildPageSidebar(context), index)

        parent.getChildAt(index)?.post {
            parent.getChildAt(index).scrollTo(0, oldY.coerceAtLeast(0))
        }
    }

    private fun refreshExpandedContent(
        context: Context,
        root: LinearLayout,
        content: LinearLayout
    ) {
        root.findViewWithTag<TextView>("hermes_brand")?.text =
            "HERMES  •  ${pages.size}"

        if (pages.isEmpty()) {
            content.removeAllViews()
            return
        }

        if (pages.isNotEmpty()) {
            refreshPageSidebar(context, root)
        }

        content.removeAllViews()

        val active = pages.values.lastOrNull() ?: return
        content.addView(activeCard(context, active))

        applyDisplaySettings(context, root)
    }

    private fun buildExpanded(context: Context): View {
        val root = LinearLayout(context).apply {
            tag = "hermes_expanded_root"
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(context, 10),
                dp(context, 8),
                dp(context, 10),
                dp(context, 10)
            )
            background = popupBackground(
                context,
                backgroundColor(context),
                backgroundStyle(context),
                22
            )
            elevation = dp(context, 18).toFloat()
        }

        val dragStrip = FrameLayout(context).apply {
            tag = "hermes_drag_strip"
            minimumHeight = dp(context, 32)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Move Hermes window"
        }

        val dragIcon = TextView(context).apply {
            text = "↑\n← • →\n↓"
            textSize = 8f
            setTextColor(accentColor(context))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(
                dp(context, 3),
                dp(context, 1),
                dp(context, 3),
                dp(context, 1)
            )
            contentDescription = "Drag Hermes window"
        }

        dragStrip.addView(
            dragIcon,
            FrameLayout.LayoutParams(
                dp(context, 34),
                dp(context, 30)
            ).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                leftMargin = dp(context, 2)
            }
        )

        val closeButton = smallAction(
            context,
            "×",
            "Close all Hermes pages"
        ) {
            closeAll(context)
        }

        val minimizeButton = smallAction(
            context,
            "—",
            "Minimize Hermes"
        ) {
            minimize(context)
        }

        dragStrip.addView(
            closeButton,
            FrameLayout.LayoutParams(
                dp(context, 42),
                dp(context, 32)
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        )

        dragStrip.addView(
            minimizeButton,
            FrameLayout.LayoutParams(
                dp(context, 42),
                dp(context, 32)
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                rightMargin = dp(context, 42)
            }
        )

        root.addView(
            dragStrip,
            LinearLayout.LayoutParams(
                -1,
                dp(context, 32)
            )
        )

        /*
         * 多页面：
         *
         * ┌──────┬────────────────────┐
         * │ 首   │                    │
         * │ 页   │  右侧独立滚动       │
         * │      │                    │
         * │ 设   │                    │
         * │ 置   │                    │
         * │      │                    │
         * │ 状   │                    │
         * │ 态   │                    │
         * │  ↕   │        ↕           │
         * └──────┴────────────────────┘
         *
         * 左右两个 ScrollView 完全独立。
         */

        val rightScroll = ScrollView(context).apply {
            tag = "hermes_scroll"
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            setPadding(0, 0, 0, dp(context, 12))
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val ime = insets.getInsets(WindowInsets.Type.ime())
                    imeBottomInset = ime.bottom
                    imeVisible =
                        insets.isVisible(WindowInsets.Type.ime()) && ime.bottom > 0

                    val bottomPadding =
                        if (imeVisible) ime.bottom + dp(context, 12)
                        else dp(context, 12)

                    if (view.paddingBottom != bottomPadding) {
                        view.setPadding(
                            view.paddingLeft,
                            view.paddingTop,
                            view.paddingRight,
                            bottomPadding
                        )
                    }

                    if (imeVisible) {
                        view.post {
                            val focused =
                                view.findFocus() ?: overlayView?.findFocus()
                            if (focused is EditText) {
                                ensureFocusedFieldVisible(
                                    context,
                                    focused,
                                    false
                                )
                            }
                        }
                    }
                }
                insets
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setWindowInsetsAnimationCallback(
                    object : WindowInsetsAnimation.Callback(
                        WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
                    ) {
                        override fun onProgress(
                            insets: WindowInsets,
                            runningAnimations: MutableList<WindowInsetsAnimation>
                        ): WindowInsets {
                            val ime = insets.getInsets(WindowInsets.Type.ime())
                            imeBottomInset = ime.bottom
                            imeVisible =
                                insets.isVisible(WindowInsets.Type.ime()) &&
                                    ime.bottom > 0

                            val bottomPadding =
                                if (imeVisible) ime.bottom + dp(context, 12)
                                else dp(context, 12)

                            if (paddingBottom != bottomPadding) {
                                setPadding(
                                    paddingLeft,
                                    paddingTop,
                                    paddingRight,
                                    bottomPadding
                                )
                            }

                            val focused =
                                findFocus() ?: overlayView?.findFocus()

                            if (focused is EditText && imeVisible) {
                                ensureFocusedFieldVisible(
                                    context,
                                    focused,
                                    false
                                )
                            }

                            return insets
                        }
                    }
                )
            }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "hermes_content"
        }

        rightScroll.addView(
            content,
            ViewGroup.LayoutParams(-1, -2)
        )

        val list = pages.values.toList()

        if (list.isEmpty()) {
            content.addView(emptyStateCard(context))
        } else {
            val active = list.last()
            content.addView(activeCard(context, active))
        }

        if (pages.isNotEmpty()) {
            val shell = LinearLayout(context).apply {
                tag = "hermes_page_shell"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }

            shell.addView(
                buildPageSidebar(context),
                LinearLayout.LayoutParams(
                    dp(context, 44),
                    0
                ).apply {
                    weight = 1f
                }
            )

            shell.addView(
                rightScroll,
                LinearLayout.LayoutParams(
                    0,
                    0,
                    1f
                )
            )

            root.addView(
                shell,
                LinearLayout.LayoutParams(
                    -1,
                    0,
                    1f
                )
            )
        } else {
            root.addView(
                rightScroll,
                LinearLayout.LayoutParams(
                    -1,
                    0,
                    1f
                )
            )
        }

        applyDisplaySettings(context, root)

        return root
    }

    private fun ensureFocusedFieldVisible(
        context: Context,
        field: EditText,
        animated: Boolean
    ) {
        var ancestor = field.parent
        while (ancestor != null && ancestor !is ScrollView) {
            ancestor = ancestor.parent
        }

        val scroll = ancestor as? ScrollView ?: return

        val rect = Rect()
        field.getDrawingRect(rect)
        scroll.offsetDescendantRectToMyCoords(field, rect)

        // The IME height is already represented by ScrollView bottom padding.
        // Do not subtract imeBottomInset again here.
        val visibleTop = scroll.scrollY + scroll.paddingTop
        val visibleBottom = scroll.scrollY + scroll.height - scroll.paddingBottom
        val margin = dp(context, 12)

        val dy = when {
            rect.bottom > visibleBottom - margin ->
                rect.bottom - (visibleBottom - margin)

            rect.top < visibleTop + margin ->
                rect.top - (visibleTop + margin)

            else -> 0
        }

        if (dy != 0) {
            if (animated) {
                scroll.smoothScrollBy(0, dy)
            } else {
                scroll.scrollBy(0, dy)
            }
        }
    }

    private fun emptyStateCard(context: Context): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                dp(context, 20),
                dp(context, 24),
                dp(context, 20),
                dp(context, 24)
            )

            addView(TextView(context).apply {
                text = "Hermes"
                textSize = 16f
                setTextColor(secondaryTextColor(context))
                gravity = Gravity.CENTER
            })

            addView(TextView(context).apply {
                text = "暂无页面"
                textSize = 13f
                setTextColor(secondaryTextColor(context))
                gravity = Gravity.CENTER
                setPadding(0, dp(context, 6), 0, 0)
            })
        }

    private fun activeCard(context: Context, page: Page): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 12), dp(context, 10), dp(context, 12))
            background = rounded(backgroundColor(context).adjustCard(), 17)
        }
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(context).apply {
            text = page.title
            textSize = 17f
            setTextColor(textColor(context))
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(smallAction(context, "×", "Close this page") { close(context, page.id) })
        card.addView(row)
        if (page.stepCount != null && page.stepCount!! > 1) {
            card.addView(TextView(context).apply {
                text = "Step ${(page.stepIndex ?: 0) + 1} of ${page.stepCount}"
                textSize = 11f; setTextColor(secondaryTextColor(context))
                setPadding(0, dp(context, 4), 0, 0)
            })
        }
        if (page.status != "ready") card.addView(TextView(context).apply {
            text = page.status.replace('_',' ').replaceFirstChar { it.uppercase() }
            textSize = 11f; setTextColor(if (page.status == "error") Color.rgb(255, 105, 105) else accentColor(context))
            setPadding(0, dp(context, 4), 0, 0)
        })
        if (page.tabs.isNotEmpty()) addTabs(context, card, page)
        if (page.message.isNotBlank()) card.addView(TextView(context).apply {
            text = page.message
            textSize = 15f
            setTextColor(textColor(context))
            setPadding(0, dp(context, 7), 0, 0)
        })
        if (page.progress != null) addProgress(context, card, page)
        addLists(context, card, page)
        when (page.kind) {
            Kind.FORM -> addForm(context, card, page)

            Kind.PANEL -> {
                if (page.fields.isNotEmpty()) {
                    addForm(context, card, page)
                } else {
                    addPanel(context, card, page)
                }
            }

            Kind.NOTIFICATION -> {
                if (page.fields.isNotEmpty()) {
                    addForm(context, card, page)
                } else if (page.actions.isNotEmpty()) {
                    addActions(context, card, page, emptyMap())
                }
            }
        }
        addNavigation(context, card, page)
        return card
    }

    private fun addTabs(context: Context, card: LinearLayout, page: Page) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(context, 6), 0, 0) }
        page.tabs.forEachIndexed { index, label ->
            row.addView(smallAction(context, label, label) {
                val next = page.copy(activeTab = index, revision = page.revision + 1)
                pages[page.id] = next; pageRevisions[page.id] = next.revision; sendLifecycle(page.id, "tab_selected", label); persist(context); render(context)
            })
        }
        card.addView(row)
    }

    private fun addLists(context: Context, card: LinearLayout, page: Page) {
        page.lists.forEach { list ->
            if (list.title.isNotBlank()) card.addView(TextView(context).apply { text = list.title; textSize = 12f; setTextColor(secondaryTextColor(context)); setPadding(0,dp(context,8),0,dp(context,4)) })
            list.items.forEachIndexed { index, item ->
                card.addView(TextView(context).apply { text = item; textSize = 14f; setTextColor(textColor(context)); setPadding(dp(context,10),dp(context,8),dp(context,10),dp(context,8)); background = rounded(backgroundColor(context).adjustCard(),10); setOnClickListener { sendLifecycle(page.id, "list_item_selected", "${list.id}:$index") } })
            }
        }
    }

    private fun addNavigation(context: Context, card: LinearLayout, page: Page) {
        if (page.backPageId == null && page.nextPageId == null) return
        val row = LinearLayout(context).apply { gravity = Gravity.END; setPadding(0, dp(context, 10), 0, 0) }
        page.backPageId?.let { target ->
            row.addView(smallAction(context, "Back", "Go back") { navigateTo(context, target, page.id) })
        }
        page.nextPageId?.let { target ->
            row.addView(smallAction(context, "Next", "Go to next step") { navigateTo(context, target, page.id) })
        }
        card.addView(row)
    }

    private fun navigateTo(context: Context, targetPageId: String, fromPageId: String) {
        val target = pages[targetPageId]
        if (target == null) {
            Toast.makeText(context, "Next page is not available", Toast.LENGTH_SHORT).show(); return
        }
        pages.remove(targetPageId); pages[targetPageId] = target
        sendLifecycle(fromPageId, "page_navigated", targetPageId)
        render(context)
    }

    private fun addProgress(context: Context, card: LinearLayout, page: Page) {
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(context, 10), 0, 0) }
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                        this.max = 1000
            progress = ((page.progress ?: 0f) * 1000f).roundToInt()
        }
        row.addView(progress, LinearLayout.LayoutParams(0, dp(context, 6), 1f))
        if (!page.progressLabel.isNullOrBlank()) row.addView(TextView(context).apply {
            text = page.progressLabel
            textSize = 11f
            setTextColor(secondaryTextColor(context))
            setPadding(dp(context, 8), 0, 0, 0)
        })
        card.addView(row)
    }

    private fun addForm(context: Context, card: LinearLayout, page: Page) {
        val state = fieldValues.getOrPut(page.id) { mutableMapOf() }
        val readers = mutableMapOf<String, () -> String>()
        page.fields.forEach { f -> renderField(context, card, page, f, state, readers) }
        addActions(context, card, page, readers)
    }

    private fun renderField(
        context: Context,
        parent: LinearLayout,
        page: Page,
        f: Field,
        state: MutableMap<String, String>,
        readers: MutableMap<String, () -> String>,
    ) {
        val type = f.type.lowercase()

        if (type == "section" || type == "group") {
            renderCollapsibleSection(context, parent, page, f, state, readers)
            return
        }

        if (type == "row" || type == "column") {
            val box = LinearLayout(context).apply {
                orientation = if (type == "row") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    dp(context, if (type == "row") 0 else 10),
                    dp(context, if (type == "row") 0 else 6),
                    dp(context, 2),
                    dp(context, if (type == "row") 0 else 6)
                )
            }

            f.children.forEach { child ->
                if (type == "row") {
                    val slot = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.TOP
                    }
                    renderField(context, slot, page, child, state, readers)
                    box.addView(
                        slot,
                        LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        ).apply {
                            marginStart = dp(context, 3)
                            marginEnd = dp(context, 3)
                        }
                    )
                } else {
                    renderField(context, box, page, child, state, readers)
                }
            }

            parent.addView(
                box,
                LinearLayout.LayoutParams(
                    -1,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(context, 2)
                    bottomMargin = dp(context, 2)
                }
            )
            return
        }

        val initial = state[f.id] ?: f.value
        if (!state.containsKey(f.id)) state[f.id] = initial
        when (type) {
            "checkbox", "switch" -> {
                val v = if (type == "checkbox") CheckBox(context) else Switch(context)
                v.apply { text = f.label; textSize = 13f; isChecked = initial.equals("true", true); setTextColor(textColor(context)); minHeight = dp(context, 36) }
                v.setOnCheckedChangeListener { _, checked -> state[f.id] = checked.toString(); fireOnChange(context, page, f, state) }
                parent.addView(v); readers[f.id] = { v.isChecked.toString() }
            }
            "select" -> {
                addLabel(context, parent, f)
                val sp = Spinner(context)
                sp.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, f.options)
                val idx = f.options.indexOf(initial); if (idx >= 0) sp.setSelection(idx)
                sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parentView: AdapterView<*>?) = Unit
                    override fun onItemSelected(parentView: AdapterView<*>?, view: View?, position: Int, id: Long) { state[f.id] = parentView?.getItemAtPosition(position)?.toString().orEmpty(); fireOnChange(context, page, f, state) }
                }
                parent.addView(sp); readers[f.id] = { sp.selectedItem?.toString().orEmpty() }
            }
            "radio" -> {
                addLabel(context, parent, f)
                val group = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
                f.options.forEach { option -> group.addView(RadioButton(context).apply { id = View.generateViewId(); text = option; setTextColor(textColor(context)); minHeight = dp(context, 36); isChecked = option == initial }) }
                group.setOnCheckedChangeListener { _, checkedId -> if (checkedId != View.NO_ID) { state[f.id] = group.findViewById<RadioButton>(checkedId)?.text?.toString().orEmpty(); fireOnChange(context, page, f, state) } }
                parent.addView(group); readers[f.id] = { group.findViewById<RadioButton>(group.checkedRadioButtonId)?.text?.toString().orEmpty() }
            }
            "slider" -> {
                addLabel(context, parent, f)
                val min = f.min ?: 0.0; val max = (f.max ?: 100.0).coerceAtLeast(min); val step = (f.step ?: 1.0).coerceAtLeast(0.0001)
                val seek = SeekBar(context).apply { this.max = 1000; val current = initial.toDoubleOrNull() ?: min; progress = (((current-min)/(max-min).coerceAtLeast(step))*1000).roundToInt().coerceIn(0,1000); minHeight = dp(context,44) }
                fun currentValue(): String { val raw=min+(max-min)*seek.progress/1000.0; val snapped=(raw/step).roundToInt()*step; return if (snapped%1.0==0.0) snapped.toInt().toString() else "%.2f".format(snapped.coerceIn(min,max)) }
                val valueText=TextView(context).apply{textSize=13f;setTextColor(textColor(context));gravity=Gravity.CENTER;minWidth=dp(context,52)}
                val box=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}; box.addView(seek,LinearLayout.LayoutParams(-1,dp(context,44))); box.addView(valueText)
                parent.addView(box); valueText.text=currentValue(); seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(b:SeekBar?,p:Int,fromUser:Boolean){val v=currentValue();valueText.text=v;state[f.id]=v;if(fromUser)fireOnChange(context,page,f,state)};override fun onStartTrackingTouch(b:SeekBar?)=Unit;override fun onStopTrackingTouch(b:SeekBar?)=Unit}); state[f.id]=currentValue(); readers[f.id]={currentValue()}
            }
            else -> {
                addLabel(context, parent, f)
                val e=EditText(context).apply{setText(initial);hint=f.placeholder;textSize=14f;setTextColor(textColor(context));setHintTextColor(secondaryTextColor(context));setSingleLine(type!="multiline");inputType=when(type){"number"->InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL;"multiline"->InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE;"password"->InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;"email"->InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;else->InputType.TYPE_CLASS_TEXT};if(type=="multiline")minLines=3;background=rounded(Color.rgb(25,27,32),12);setPadding(dp(context,10),dp(context,8),dp(context,10),dp(context,8))}
                e.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int)=Unit;override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int){state[f.id]=s?.toString().orEmpty();fireOnChange(context,page,f,state)};override fun afterTextChanged(e:Editable?)=Unit})
                parent.addView(e); readers[f.id]={e.text.toString()}
            }
        }
    }

    private fun renderCollapsibleSection(
        context: Context,
        parent: LinearLayout,
        page: Page,
        f: Field,
        state: MutableMap<String, String>,
        readers: MutableMap<String, () -> String>,
    ) {
        val stateKey = "${page.id}:${f.id}"
        val expanded = collapsedSections[stateKey] != true

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(45, 255, 255, 255), 12)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(
                dp(context, 10),
                dp(context, 5),
                dp(context, 8),
                dp(context, 5)
            )
            contentDescription = if (expanded) {
                "${f.label}, 点击收起"
            } else {
                "${f.label}, 点击展开"
            }
        }

        val arrow = TextView(context).apply {
            text = if (expanded) "▼" else "▶"
            textSize = 11f
            setTextColor(accentColor(context))
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        header.addView(
            arrow,
            LinearLayout.LayoutParams(
                dp(context, 22),
                dp(context, 32)
            )
        )

        header.addView(
            TextView(context).apply {
                text = f.label.ifBlank { f.id }
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textColor(context))
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(
                0,
                dp(context, 32),
                1f
            )
        )

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
            setPadding(
                dp(context, 8),
                0,
                dp(context, 8),
                dp(context, 4)
            )
        }

        f.children.forEach { child ->
            renderField(context, body, page, child, state, readers)
        }

        header.setOnClickListener {
            val nextExpanded = body.visibility != View.VISIBLE
            collapsedSections[stateKey] = !nextExpanded
            body.visibility = if (nextExpanded) View.VISIBLE else View.GONE
            arrow.text = if (nextExpanded) "▼" else "▶"
            header.contentDescription = if (nextExpanded) {
                "${f.label}, 点击收起"
            } else {
                "${f.label}, 点击展开"
            }
        }

        container.addView(
            header,
            LinearLayout.LayoutParams(
                -1,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            body,
            LinearLayout.LayoutParams(
                -1,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        parent.addView(
            container,
            LinearLayout.LayoutParams(
                -1,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 4)
                bottomMargin = dp(context, 4)
            }
        )
    }

    private fun addLabel(context: Context, card: LinearLayout, field: Field) {
        card.addView(TextView(context).apply {
            text = (if (field.required) "* " else "") + field.label
            textSize = 12f
            setTextColor(secondaryTextColor(context))
            setPadding(0, dp(context, 10), 0, dp(context, 4))
        })
    }

    private fun addPanel(context: Context, card: LinearLayout, page: Page) = addActions(context, card, page, emptyMap())


    /**
     * 通用 Action 自适应布局。
     *
     * 不包含任何业务判断，只根据实际可用宽度排列子 View。
     * 当前行放不下时自动换行。
     */
    private class AdaptiveActionLayout @JvmOverloads constructor(
        context: Context,
        attrs: android.util.AttributeSet? = null,
    ) : ViewGroup(context, attrs) {

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int
        ) {
            val availableWidth =
                (MeasureSpec.getSize(widthMeasureSpec) -
                    paddingLeft -
                    paddingRight).coerceAtLeast(1)

            var lineWidth = 0
            var lineHeight = 0
            var totalHeight = paddingTop + paddingBottom
            var maxLineWidth = 0
            var hasLine = false

            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue

                val lp = child.layoutParams as? MarginLayoutParams
                    ?: MarginLayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT
                    )

                measureChildWithMargins(
                    child,
                    widthMeasureSpec,
                    paddingLeft + paddingRight,
                    heightMeasureSpec,
                    paddingTop + paddingBottom
                )

                val childWidth =
                    child.measuredWidth + lp.leftMargin + lp.rightMargin

                val childHeight =
                    child.measuredHeight + lp.topMargin + lp.bottomMargin

                val nextWidth =
                    if (lineWidth == 0) childWidth
                    else lineWidth + childWidth

                if (hasLine && nextWidth > availableWidth) {
                    totalHeight += lineHeight
                    maxLineWidth = maxOf(maxLineWidth, lineWidth)

                    lineWidth = childWidth
                    lineHeight = childHeight
                } else {
                    lineWidth = nextWidth
                    lineHeight = maxOf(lineHeight, childHeight)
                }

                hasLine = true
            }

            if (hasLine) {
                totalHeight += lineHeight
                maxLineWidth = maxOf(maxLineWidth, lineWidth)
            }

            val desiredWidth =
                maxLineWidth + paddingLeft + paddingRight

            setMeasuredDimension(
                resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(totalHeight, heightMeasureSpec)
            )
        }

        override fun onLayout(
            changed: Boolean,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int
        ) {
            val availableWidth =
                width - paddingLeft - paddingRight

            var x = paddingLeft
            var y = paddingTop
            var lineHeight = 0

            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == GONE) continue

                val lp = child.layoutParams as? MarginLayoutParams
                    ?: MarginLayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT
                    )

                val childWidth = child.measuredWidth
                val childHeight = child.measuredHeight

                val fullWidth =
                    childWidth + lp.leftMargin + lp.rightMargin

                if (
                    x > paddingLeft &&
                    x + fullWidth > paddingLeft + availableWidth
                ) {
                    x = paddingLeft
                    y += lineHeight
                    lineHeight = 0
                }

                val childLeft = x + lp.leftMargin
                val childTop = y + lp.topMargin

                child.layout(
                    childLeft,
                    childTop,
                    childLeft + childWidth,
                    childTop + childHeight
                )

                x += fullWidth

                lineHeight = maxOf(
                    lineHeight,
                    childHeight + lp.topMargin + lp.bottomMargin
                )
            }
        }

        override fun generateDefaultLayoutParams(): LayoutParams =
            MarginLayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )

        override fun generateLayoutParams(
            attrs: android.util.AttributeSet?
        ): LayoutParams =
            MarginLayoutParams(context, attrs)

        override fun generateLayoutParams(
            p: LayoutParams?
        ): LayoutParams =
            MarginLayoutParams(
                p ?: generateDefaultLayoutParams()
            )

        override fun checkLayoutParams(
            p: LayoutParams?
        ): Boolean = p is MarginLayoutParams
    }

    private fun addActions(
        context: Context,
        card: LinearLayout,
        page: Page,
        values: Map<String, () -> String>
    ) {
        if (page.actions.isEmpty()) return

        val row = AdaptiveActionLayout(context).apply {
            setPadding(
                0,
                dp(context, 6),
                0,
                0
            )
        }

        page.actions.forEach { action ->
            val runtimeState =
                actionState[page.id]
                    ?.get(action.id)
                    ?.lowercase()
                    ?: action.state.lowercase()

            val runtimeDisabled =
                runtimeState == "disabled"

            val disabled =
                action.disabled || runtimeDisabled

            val buttonLabel = when (runtimeState) {
                "busy", "loading" ->
                    "${action.label}…"

                "success" ->
                    "✓ ${action.label}"

                "error" ->
                    "! ${action.label}"

                else ->
                    action.label
            }

            val b = smallAction(
                context,
                buttonLabel,
                action.label
            ) {
                android.util.Log.d(
                    "HermesPopupOverlay",
                    "ACTION CLICK page=${page.id} " +
                        "action=${action.id} " +
                        "disabled=$disabled " +
                        "runtimeState=$runtimeState"
                )

                if (!disabled) {
                    sendAction(
                        context,
                        page,
                        action,
                        values
                    )
                } else {
                    android.util.Log.w(
                        "HermesPopupOverlay",
                        "ACTION BLOCKED page=${page.id} " +
                            "action=${action.id} " +
                            "disabled=$disabled " +
                            "runtimeState=$runtimeState"
                    )
                }
            }

            b.textSize = 12f
            b.isSingleLine = true
            b.ellipsize = TextUtils.TruncateAt.END
            b.minWidth = 0
            b.isEnabled = !disabled
            b.alpha = if (disabled) .45f else 1f
            b.background =
                actionBackground(context, action.style)

            b.setPadding(
                dp(context, 7),
                0,
                dp(context, 7),
                0
            )

            row.addView(
                b,
                ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(context, 34)
                ).apply {
                    marginStart = dp(context, 2)
                    marginEnd = dp(context, 2)
                    topMargin = dp(context, 2)
                    bottomMargin = dp(context, 2)
                }
            )
        }

        card.addView(
            row,
            LinearLayout.LayoutParams(
                -1,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun leafFields(fields: List<Field>): List<Field> = buildList {
        fields.forEach { field ->
            if (field.type.lowercase() in setOf("row", "column", "group", "section")) addAll(leafFields(field.children))
            else add(field)
        }
    }

    private fun sendAction(context: Context, page: Page, action: Action, values: Map<String, () -> String>) {
        android.util.Log.d(
            "HermesPopupOverlay",
            "sendAction ENTER page=${page.id} action=${action.id} disabled=${action.disabled}"
        )
        // 最小化/恢复或页面刷新后，旧 View reader 可能已经属于旧 View 树。
        // Action 数据统一从当前 page + fieldValues 读取。
        val currentPage = pages[page.id] ?: return
        val currentAction = currentPage.actions.firstOrNull { it.id == action.id } ?: return
        if (currentAction.disabled) return

        val state = fieldValues[currentPage.id].orEmpty()

        val leaf = leafFields(currentPage.fields)
        val missing = leaf.filter {
            it.required && state[it.id].orEmpty().isBlank()
        }

        if (missing.isNotEmpty()) {
            Toast.makeText(
                context,
                "Please fill: ${missing.joinToString { it.label }}",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val payload = JsonObject().apply {
            addProperty("event", "ui_action")
            addProperty("role", currentAction.role)
            currentAction.targetPageId?.let {
                addProperty("target_page_id", it)
            }
            addProperty("page_id", currentPage.id)
            addProperty("action_id", currentAction.id)
            addProperty("kind", currentPage.kind.name.lowercase())
            currentAction.event?.let {
                addProperty("event_name", it)
            }

            val fields = JsonObject()

            leaf.forEach { field ->
                fields.addProperty(
                    field.id,
                    state[field.id].orEmpty()
                )
            }

            add("values", fields)
        }

        val sent = RelayClient.sendUiEvent(payload)

        android.util.Log.d(
            "HermesPopupOverlay",
            "UI EVENT SENT page=${currentPage.id} action=${currentAction.id} result=$sent payload=$payload"
        )
    }

    private fun fireOnChange(context: Context, page: Page, field: Field, state: MutableMap<String, String>) {
        val actionId = field.onChangeActionId?.takeIf { it.isNotBlank() } ?: return
        onChangeJobs[page.id]?.let(mainHandler::removeCallbacks)
        val job = Runnable {
            val action = page.actions.firstOrNull { it.id == actionId } ?: return@Runnable
            sendAction(context, page, action, leafFields(page.fields).associate { f -> f.id to { state[f.id].orEmpty() } })
        }
        onChangeJobs[page.id] = job
        val delayMs = page.actions.firstOrNull { it.id == actionId }?.debounceMs?.coerceIn(0L, 5000L) ?: 120L
        mainHandler.postDelayed(job, delayMs)
    }

    private fun backCard(context: Context, page: Page, depth: Int) = TextView(context).apply {
        text = "${page.title}  ·  ${page.message.replace("\n", " ").take(55)}"
        textSize = 12f
        setTextColor(secondaryTextColor(context))
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
        background = rounded(backgroundColor(context).adjustCard(), 14)
        alpha = if (depth == 1) .92f else .72f
        minHeight = dp(context, 36)
        setOnClickListener {
            pages.remove(page.id)
            pages[page.id] = page
            sendLifecycle(page.id, "page_selected", "user")
            render(context)
        }
        layoutParams = LinearLayout.LayoutParams(-1, dp(context, 40)).apply { bottomMargin = dp(context, -2) }
    }

    private fun buildMinimized(context: Context): View = FrameLayout(context).apply {
        val size = dp(context, ballSize(context))
        layoutParams = FrameLayout.LayoutParams(size, size)
        background = floatingBallBackground(context, ballColor(context), size)
        elevation = dp(context, 16).toFloat()
        contentDescription = "Restore Hermes"
        addView(TextView(context).apply {
            text = if (pageCount() > 9) "9+" else pageCount().toString()
            textSize = 11f
            setTextColor(textColor(context))
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(Color.argb(120, 255, 255, 255), size / 2)
        }, FrameLayout.LayoutParams(dp(context, 24), dp(context, 24), Gravity.CENTER))
    }

    private fun attachDrag(
        context: Context,
        view: View,
        params: WindowManager.LayoutParams,
        restoreOnClick: Boolean = false
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).roundToInt()
                    val dy = (event.rawY - downY).roundToInt()

                    if (
                        kotlin.math.abs(dx) > dp(context, 5) ||
                        kotlin.math.abs(dy) > dp(context, 5)
                    ) {
                        moved = true
                    }

                    params.x = (startX + dx).coerceAtLeast(0)
                    params.y = (startY + dy).coerceAtLeast(0)

                    val wm = windowManager
                    val overlay = overlayView

                    if (wm != null && overlay != null) {
                        try {
                            wm.updateViewLayout(overlay, params)
                        } catch (_: Exception) {
                        }
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved && restoreOnClick) {
                        restore(context)
                    } else if (moved) {
                        val prefs = context.getSharedPreferences(PREFS, 0)
                        val keyX = if (restoreOnClick) KEY_BALL_X else KEY_WINDOW_X
                        val keyY = if (restoreOnClick) KEY_BALL_Y else KEY_WINDOW_Y
                        prefs.edit()
                            .putInt(keyX, params.x)
                            .putInt(keyY, params.y)
                            .apply()
                    }

                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    false
                }

                else -> true
            }
        }
    }

    private fun smallAction(context: Context, text: String, description: String, action: () -> Unit) = Button(context).apply {
        this.text = text; textSize = 20f; setTextColor(textColor(context)); setBackgroundColor(Color.TRANSPARENT)
        setPadding(0, 0, 0, 0); minWidth = dp(context, 42); minHeight = dp(context, 42); contentDescription = description
        setOnClickListener {
            android.util.Log.d("HermesPopupOverlay", "smallAction CLICK: $description")
            action()
        }
    }

    private fun actionBackground(context: Context, style: String): GradientDrawable = when (style.lowercase()) {
        "danger" -> rounded(blendColor(context, 0xFFD94A54.toInt()), 12)
        "secondary" -> rounded(blendColor(context, 0xFF606573.toInt()), 12)
        "success" -> rounded(blendColor(context, 0xFF3FA66B.toInt()), 12)
        else -> rounded(accentColor(context), 12)
    }

    private fun popupBackground(context: Context, color: Int, style: Int, radiusDp: Int): GradientDrawable = when (style.coerceIn(0, 2)) {
        1 -> GradientDrawable().apply { setColor(color); cornerRadius = radiusDp.toFloat(); setStroke(1, secondaryTextColor(context)) }
        2 -> GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp.toFloat()
            setStroke(dp(context, 1), secondaryTextColor(context), dp(context, 12).toFloat(), dp(context, 10).toFloat())
        }
        else -> rounded(color, radiusDp)
    }

    private fun floatingBallBackground(context: Context, color: Int, size: Int): GradientDrawable {
        val shape = ballShape(context)
        val radius = when (shape) {
            1 -> size / 3
            2 -> dp(context, 8)
            else -> size / 2
        }
        return frostedBall(color, radius)
    }

    private fun Int.adjustCard(): Int {
        val r = (Color.red(this) * 1.18f).roundToInt().coerceAtMost(255)
        val g = (Color.green(this) * 1.18f).roundToInt().coerceAtMost(255)
        val b = (Color.blue(this) * 1.18f).roundToInt().coerceAtMost(255)
        return Color.rgb(r, g, b)
    }

    private fun frostedBall(color: Int, radius: Int) = GradientDrawable().apply {
        val a = (Color.alpha(color).coerceIn(55, 210))
        setColor(Color.argb(a, Color.red(color), Color.green(color), Color.blue(color)))
        setStroke(1, Color.argb(95, 255, 255, 255))
        cornerRadius = radius.toFloat()
    }

    private fun accentColor(context: Context): Int {
        val bg = backgroundColor(context)
        return if (Color.luminance(bg) < 0.42f) Color.rgb(255, 170, 55) else Color.rgb(160, 82, 24)
    }

    private fun blendColor(context: Context, source: Int): Int {
        val bg = backgroundColor(context)
        val mix = 0.82f
        return Color.rgb(
            (Color.red(source) * mix + Color.red(bg) * (1f - mix)).roundToInt().coerceIn(0, 255),
            (Color.green(source) * mix + Color.green(bg) * (1f - mix)).roundToInt().coerceIn(0, 255),
            (Color.blue(source) * mix + Color.blue(bg) * (1f - mix)).roundToInt().coerceIn(0, 255),
        )
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp.toFloat(); setStroke(1, secondaryTextColorFrom(color))
    }

    private fun secondaryTextColorFrom(background: Int): Int = Color.argb(85, Color.red(Color.WHITE), Color.green(Color.WHITE), Color.blue(Color.WHITE))

    private fun removeOverlay(context: Context) {
        val oldView = overlayView
        val oldWindowManager = windowManager

        // Clear references FIRST.
        overlayView = null
        windowManager = null
        overlayParams = null

        if (oldView != null && oldWindowManager != null) {
            try {
                oldWindowManager.removeViewImmediate(oldView)
            } catch (_: Exception) {
                try {
                    oldWindowManager.removeView(oldView)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun <T> runOnMainSyncResult(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = java.util.concurrent.CountDownLatch(1); var result: T? = null
        mainHandler.post { try { result = block() } finally { latch.countDown() } }; latch.await(); return result as T
    }

    private fun runOnMainSync(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
        val latch = CountDownLatch(1)
        mainHandler.post { try { block() } finally { latch.countDown() } }
        latch.await()
    }

    private fun parseKind(value: String): Kind = when (value.lowercase()) {
        "form", "survey" -> Kind.FORM
        "panel", "control", "action" -> Kind.PANEL
        else -> Kind.NOTIFICATION
    }

    fun parseFields(element: com.google.gson.JsonElement): List<Field> = element.asJsonArray.mapNotNull { x ->
        val f = x.asJsonObject
        val id = f.get("id")?.asString?.trim().orEmpty()
        val type = f.get("type")?.asString?.trim()?.lowercase() ?: "text"
        val layout = type == "row" || type == "column" || type == "group" || type == "section"
        val label = f.get("label")?.asString?.trim().orEmpty()
        if (id.isBlank() || (!layout && label.isBlank())) null else Field(
            id=id, label=label, type=type, value=f.get("value")?.asString ?: "", placeholder=f.get("placeholder")?.asString ?: "",
            options=f.get("options")?.asJsonArray?.map{it.asString} ?: emptyList(), required=f.get("required")?.asBoolean ?: false,
            min=f.get("min")?.asDouble, max=f.get("max")?.asDouble, step=f.get("step")?.asDouble,
            onChangeActionId=f.get("on_change_action_id")?.asString,
            children=f.get("children")?.let(::parseFields) ?: emptyList()
        )
    }

    fun parseActions(element: com.google.gson.JsonElement): List<Action> = element.asJsonArray.mapNotNull { x ->
        val a = x.asJsonObject
        val id = a.get("id")?.asString?.trim().orEmpty()
        val label = a.get("label")?.asString?.trim().orEmpty()
        if (id.isBlank() || label.isBlank()) null else Action(
            id = id,
            label = label,
            style = a.get("style")?.asString ?: "primary",
            disabled = a.get("disabled")?.asBoolean ?: false,
            role = a.get("role")?.asString ?: "action",
            targetPageId = a.get("target_page_id")?.asString,
            event = a.get("event")?.asString,
            debounceMs = a.get("debounce_ms")?.asLong,
            state = a.get("state")?.asString ?: "idle",
        )
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private const val WRAP = WindowManager.LayoutParams.WRAP_CONTENT

    // ── User display settings ───────────────────────────────────────────────

    private val baseTextSizes =
        java.util.WeakHashMap<TextView, Float>()

    private val originalVerticalPadding =
        java.util.WeakHashMap<View, IntArray>()

    private val originalChildMargins =
        java.util.WeakHashMap<View, IntArray>()

    private fun applyDisplaySettings(
        context: Context,
        root: View
    ) {
        val prefs = context.getSharedPreferences(
            "hermes_overlay_prefs",
            Context.MODE_PRIVATE
        )

        val textScale = prefs.getFloat(
            HermesDisplaySettings.KEY_TEXT_SCALE,
            HermesDisplaySettings.DEFAULT_TEXT_SCALE
        ).coerceIn(0.8f, 1.4f)

        val lineSpacing = prefs.getFloat(
            HermesDisplaySettings.KEY_LINE_SPACING,
            HermesDisplaySettings.DEFAULT_LINE_SPACING
        ).coerceIn(0f, 0.8f)

        val letterSpacing = prefs.getFloat(
            HermesDisplaySettings.KEY_LETTER_SPACING,
            HermesDisplaySettings.DEFAULT_LETTER_SPACING
        ).coerceIn(-0.03f, 0.08f)

        val elementSpacing = prefs.getInt(
            HermesDisplaySettings.KEY_ELEMENT_SPACING,
            HermesDisplaySettings.DEFAULT_ELEMENT_SPACING
        ).coerceIn(0, 12)

        /*
         * “行距” = 控件与控件之间的纵向距离。
         *
         * 0%  -> 0dp
         * 20% -> 4dp
         * 40% -> 8dp
         * 80% -> 16dp
         */
        val rowGapDp = (lineSpacing * 20f).roundToInt()
        val rowGapPx = dp(context, rowGapDp)

        /*
         * “元素间距”继续控制原有上下 padding。
         */
        val verticalFactor =
            elementSpacing.toFloat() /
                HermesDisplaySettings.DEFAULT_ELEMENT_SPACING.toFloat()

        fun visit(view: View) {

            if (view is TextView) {
                val baseSize = baseTextSizes[view]
                    ?: (
                        view.textSize /
                            context.resources.displayMetrics.scaledDensity
                    ).also {
                        baseTextSizes[view] = it
                    }

                view.textSize = baseSize * textScale
                view.letterSpacing = letterSpacing

                /*
                 * 行距不再使用 TextView.setLineSpacing()。
                 * 这里保持 TextView 自身原有的内部行高。
                 */
            }

            val oldPadding = originalVerticalPadding[view]
                ?: intArrayOf(
                    view.paddingTop,
                    view.paddingBottom
                ).also {
                    originalVerticalPadding[view] = it
                }

            val newTop = (
                oldPadding[0] * verticalFactor
            ).roundToInt().coerceAtLeast(0)

            val newBottom = (
                oldPadding[1] * verticalFactor
            ).roundToInt().coerceAtLeast(0)

            if (
                view.paddingTop != newTop ||
                view.paddingBottom != newBottom
            ) {
                view.setPadding(
                    view.paddingLeft,
                    newTop,
                    view.paddingRight,
                    newBottom
                )
            }

            /*
             * 真正处理“行距”：
             * 只作用于竖向 LinearLayout 的直接子控件。
             *
             * 例如：
             *
             * TextView
             * EditText
             * Button
             *
             * 会按设置值逐行拉开。
             *
             * 横向布局不会被影响。
             */
            if (
                view is LinearLayout &&
                view.orientation == LinearLayout.VERTICAL
            ) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    val lp = child.layoutParams

                    val original = originalChildMargins[child]
                        ?: if (lp is ViewGroup.MarginLayoutParams) {
                            intArrayOf(
                                lp.leftMargin,
                                lp.topMargin,
                                lp.rightMargin,
                                lp.bottomMargin
                            )
                        } else {
                            intArrayOf(0, 0, 0, 0)
                        }.also {
                            originalChildMargins[child] = it
                        }

                    val targetLp =
                        if (lp is ViewGroup.MarginLayoutParams) {
                            lp
                        } else {
                            LinearLayout.LayoutParams(
                                lp.width,
                                lp.height
                            )
                        }

                    targetLp.leftMargin = original[0]
                    targetLp.topMargin = original[1]
                    targetLp.rightMargin = original[2]

                    val extraBottom =
                        if (i < view.childCount - 1) rowGapPx else 0

                    targetLp.bottomMargin =
                        original[3] + extraBottom

                    child.layoutParams = targetLp
                }
            }

            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    visit(view.getChildAt(i))
                }
            }
        }

        visit(root)
    }
}
