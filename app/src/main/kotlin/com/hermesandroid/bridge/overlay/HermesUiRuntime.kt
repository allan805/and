package com.hermesandroid.bridge.overlay

import android.content.Context
import com.google.gson.*
import java.util.concurrent.atomic.AtomicLong

/** Phase 1/3 protocol guard: validates resource limits, revisions and action/event metadata. */
object HermesUiRuntime {
    const val MAX_COMPONENTS = 80
    const val MAX_LIST_ITEMS = 100
    const val MAX_TEXT = 8000
    private const val MAX_ID_LENGTH = 128
    private val seq = AtomicLong(0)
    private val pageStates = setOf("idle", "loading", "ready", "busy", "success", "error", "disabled", "cancelled")
    private val actionStates = setOf("idle", "loading", "busy", "success", "error", "disabled")

    data class ActionState(val state: String, val progress: Float? = null, val message: String? = null)

    fun nextRevision(): Long = seq.incrementAndGet()

    fun validatePage(obj: JsonObject): Pair<Boolean, String?> {
        val pageId = obj.get("page_id")?.asString
        if (pageId != null && (pageId.isBlank() || pageId.length > MAX_ID_LENGTH || !validId(pageId))) return false to "invalid_page_id"
        val revision = obj.get("revision")?.asLong
        if (revision != null && revision < 0) return false to "invalid_revision"
        val status = obj.get("status")?.asString?.lowercase()
        if (status != null && status !in pageStates) return false to "invalid_page_state"
        val fields = obj.get("fields")?.takeIf { it.isJsonArray }?.asJsonArray
        if (fields != null && fields.size() > 30) return false to "too_many_fields"
        val actions = obj.get("actions")?.takeIf { it.isJsonArray }?.asJsonArray
        if (actions != null && actions.size() > 20) return false to "too_many_actions"
        actions?.forEach { item ->
            if (!item.isJsonObject) return false to "invalid_action"
            val id = item.asJsonObject.get("id")?.asString
            if (id != null && !validId(id)) return false to "invalid_action_id"
            val state = item.asJsonObject.get("state")?.asString?.lowercase()
            if (state != null && state !in actionStates) return false to "invalid_action_state"
        }
        val components = obj.get("components")?.takeIf { it.isJsonArray }?.asJsonArray
        if (components != null && components.size() > MAX_COMPONENTS) return false to "too_many_components"
        val lists = obj.get("lists")?.takeIf { it.isJsonArray }?.asJsonArray
        lists?.forEach { item ->
            val count = if (item.isJsonObject) item.asJsonObject.get("items")?.asJsonArray?.size() ?: 0 else 0
            if (count > MAX_LIST_ITEMS) return false to "too_many_list_items"
        }
        val message = obj.get("message")?.asString
        if (message != null && message.length > MAX_TEXT) return false to "text_too_long"
        obj.get("fields")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { item ->
            if (!item.isJsonObject) return false to "invalid_field"
            val id = item.asJsonObject.get("id")?.asString
            if (id != null && !validId(id)) return false to "invalid_field_id"
        }
        return true to null
    }

    fun validatePatch(obj: JsonObject): Pair<Boolean, String?> {
        val message = obj.get("message")?.asString
        if (message != null && message.length > MAX_TEXT) return false to "text_too_long"
        val fields = obj.get("fields")?.takeIf { it.isJsonArray }?.asJsonArray
        if (fields != null && fields.size() > 30) return false to "too_many_fields"
        val actions = obj.get("actions")?.takeIf { it.isJsonArray }?.asJsonArray
        if (actions != null && actions.size() > 20) return false to "too_many_actions"
        val components = obj.get("components")?.takeIf { it.isJsonArray }?.asJsonArray
        if (components != null && components.size() > MAX_COMPONENTS) return false to "too_many_components"
        val lists = obj.get("lists")?.takeIf { it.isJsonArray }?.asJsonArray
        lists?.forEach { item ->
            val count = if (item.isJsonObject) item.asJsonObject.get("items")?.asJsonArray?.size() ?: 0 else 0
            if (count > MAX_LIST_ITEMS) return false to "too_many_list_items"
        }
        return true to null
    }

    fun validateComponentPatch(obj: JsonObject?): Pair<Boolean, String?> {
        if (obj == null) return false to "patch must be an object"
        val text = obj.get("text")?.asString
        if (text != null && text.length > MAX_TEXT) return false to "text_too_long"
        return true to null
    }

    private fun validId(value: String): Boolean =
        value.length <= MAX_ID_LENGTH && value.isNotBlank() && value.all {
            it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == ':'
        }

    fun savePages(context: Context, pages: Collection<HermesPopupOverlay.Page>, expanded: Boolean) {
        val root = JsonObject().apply {
            addProperty("expanded", expanded)
            add("pages", JsonArray().also { a -> pages.forEach { a.add(serializePage(it)) } })
        }
        context.getSharedPreferences("hermes_ui_runtime", Context.MODE_PRIVATE).edit().putString("pages", root.toString()).apply()
    }

    fun loadPages(context: Context): Pair<List<HermesPopupOverlay.Page>, Boolean>? {
        val raw = context.getSharedPreferences("hermes_ui_runtime", Context.MODE_PRIVATE).getString("pages", null) ?: return null
        return runCatching {
            val o = JsonParser.parseString(raw).asJsonObject
            val pages = o.get("pages")?.asJsonArray?.mapNotNull { parsePage(it.asJsonObject) } ?: emptyList()
            pages to (o.get("expanded")?.asBoolean ?: true)
        }.getOrNull()
    }

    fun clear(context: Context) { context.getSharedPreferences("hermes_ui_runtime", Context.MODE_PRIVATE).edit().remove("pages").apply() }

    fun serializePage(p: HermesPopupOverlay.Page): JsonObject = JsonObject().apply {
        addProperty("page_id", p.id); addProperty("title", p.title); addProperty("message", p.message)
        addProperty("kind", p.kind.name.lowercase()); addProperty("auto_close_ms", p.autoCloseMs); addProperty("revision", p.revision)
        p.progress?.let { addProperty("progress", it) }; p.progressLabel?.let { addProperty("progress_label", it) }
        p.flowId?.let { addProperty("flow_id", it) }; p.stepIndex?.let { addProperty("step_index", it) }; p.stepCount?.let { addProperty("step_count", it) }
        p.backPageId?.let { addProperty("back_page_id", it) }; p.nextPageId?.let { addProperty("next_page_id", it) }; addProperty("status", p.status)
        add("fields", JsonArray().also { a -> p.fields.forEach { f -> a.add(JsonObject().apply { addProperty("id",f.id);addProperty("label",f.label);addProperty("type",f.type);addProperty("value",f.value);addProperty("placeholder",f.placeholder);addProperty("required",f.required);add("options",JsonArray().also{q->f.options.forEach(q::add)});f.min?.let{addProperty("min",it)};f.max?.let{addProperty("max",it)};f.step?.let{addProperty("step",it)};f.onChangeActionId?.let{addProperty("on_change_action_id",it)} }) } })
        add("actions", JsonArray().also { a -> p.actions.forEach { x -> a.add(JsonObject().apply { addProperty("id",x.id);addProperty("label",x.label);addProperty("style",x.style);addProperty("disabled",x.disabled);addProperty("role",x.role);x.targetPageId?.let{addProperty("target_page_id",it)};x.event?.let{addProperty("event",it)};x.debounceMs?.let{addProperty("debounce_ms",it)};addProperty("state",x.state) }) } })
        add("tabs", JsonArray().also { a -> p.tabs.forEach(a::add) }); addProperty("active_tab", p.activeTab)
        add("lists", JsonArray().also { a -> p.lists.forEach { l -> a.add(JsonObject().apply { addProperty("id",l.id);addProperty("title",l.title);add("items",JsonArray().also{q->l.items.forEach{q.add(JsonPrimitive(it))}}) }) } })
    }

    fun parsePage(o: JsonObject): HermesPopupOverlay.Page? = runCatching {
        val id = o.get("page_id")?.asString?.takeIf(String::isNotBlank) ?: return null
        val kind = when(o.get("kind")?.asString?.lowercase()) { "form","survey"->HermesPopupOverlay.Kind.FORM; "panel","control","action"->HermesPopupOverlay.Kind.PANEL; else->HermesPopupOverlay.Kind.NOTIFICATION }
        HermesPopupOverlay.Page(id, o.get("title")?.asString ?: "Hermes", o.get("message")?.asString ?: "", kind,
            o.get("fields")?.let(HermesPopupOverlay::parseFields) ?: emptyList(), o.get("actions")?.let(HermesPopupOverlay::parseActions) ?: emptyList(),
            o.get("auto_close_ms")?.asLong ?: 0L, o.get("revision")?.asLong ?: 0L, o.get("progress")?.asFloat, o.get("progress_label")?.asString,
            o.get("flow_id")?.asString,o.get("step_index")?.asInt,o.get("step_count")?.asInt,o.get("back_page_id")?.asString,o.get("next_page_id")?.asString,o.get("status")?.asString ?: "ready",
            o.get("lists")?.asJsonArray?.mapNotNull{ x->x.takeIf{it.isJsonObject}?.asJsonObject?.let{q->HermesPopupOverlay.ListBlock(q.get("id")?.asString ?: "list",q.get("title")?.asString ?: "",q.get("items")?.asJsonArray?.map{it.asString} ?: emptyList())}} ?: emptyList(),
            o.get("tabs")?.asJsonArray?.map { it.asString } ?: emptyList(), o.get("active_tab")?.asInt ?: 0)
    }.getOrNull()
}
