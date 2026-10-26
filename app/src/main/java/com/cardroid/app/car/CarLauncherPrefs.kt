package com.cardroid.app.car

import android.content.Context
import com.cardroid.app.helper.HelperProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pure (JVM-testable) list logic for the car launcher: favorites first (ordered, max [MAX_PINNED]), then the rest in the given order
 * (CarApps.load sorts by label), hidden apps removed. Keys are [CarApp.target] strings ("pkg/cls").
 */
object CarLauncherLogic {
    const val MAX_PINNED = 8

    fun <T> arrange(apps: List<T>, key: (T) -> String, pinned: List<String>, hidden: Set<String>): List<T> {
        val visible = apps.filter { key(it) !in hidden }
        val byKey = LinkedHashMap<String, T>().apply { visible.forEach { putIfAbsent(key(it), it) } }
        val favKeys = pinned.filter { it in byKey }.distinct().take(MAX_PINNED)
        val favSet = favKeys.toSet()
        return favKeys.map { byKey.getValue(it) } + visible.filter { key(it) !in favSet }
    }

    fun canPin(pinned: List<String>, key: String): Boolean = key in pinned || pinned.size < MAX_PINNED

    /** Pin/unpin [key]; pinning when [MAX_PINNED] favorites exist is a no-op. */
    fun togglePin(pinned: List<String>, key: String): List<String> = when {
        key in pinned -> pinned - key
        pinned.size >= MAX_PINNED -> pinned
        else -> pinned + key
    }

    /** Hiding an app also removes it from the favorites. */
    fun hide(pinned: List<String>, hidden: Set<String>, key: String): Pair<List<String>, Set<String>> = (pinned - key) to (hidden + key)

    /** Drops favorites/hidden entries of apps that are no longer installed. */
    fun prune(pinned: List<String>, hidden: Set<String>, installed: Set<String>): Pair<List<String>, Set<String>> =
        pinned.filter { it in installed }.distinct() to hidden.filter { it in installed }.toSet()

    fun <T> pages(items: List<T>, perPage: Int): List<List<T>> = if (perPage <= 0) emptyList() else items.chunked(perPage)

    /** Case-insensitive label match on any word start or substring; blank query matches everything. */
    fun <T> search(items: List<T>, label: (T) -> String, query: String): List<T> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return items
        return items.filter { label(it).lowercase().contains(q) }
    }

    /** Recents shown in the launcher: the car display's tasks minus CarDroid (the launcher) and the Pixel SecondaryDisplayLauncher. */
    fun recents(tasks: List<HelperProtocol.TaskEntry>, ownPackage: String): List<HelperProtocol.TaskEntry> =
        tasks.filter { it.pkg.isNotEmpty() && it.pkg != ownPackage && !it.component.contains("secondarydisplay", ignoreCase = true) }
            .distinctBy { it.taskId }
}

/** SharedPreferences "car_launcher": `favorites` (ordered, newline-joined targets) and `hidden` (string set). Observable via StateFlows. */
class CarLauncherPrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val _pinned = MutableStateFlow(readPinned())
    private val _hidden = MutableStateFlow(sp.getStringSet(KEY_HIDDEN, emptySet())?.toSet() ?: emptySet())

    private val _widgets = MutableStateFlow(sp.getString("widgets", "date,clock,favorites,connection").orEmpty()
        .split(',').filter { it in WIDGETS }.distinct())
    private val _columns = MutableStateFlow(sp.getInt("widget_columns", 2).coerceIn(1, 3))
    val widgets: StateFlow<List<String>> get() = _widgets
    val columns: StateFlow<Int> get() = _columns

    fun toggleWidget(id: String) {
        if (id !in WIDGETS) return
        setWidgets(if (id in _widgets.value) _widgets.value - id else _widgets.value + id)
    }
    fun moveWidget(id: String) {
        val list = _widgets.value.toMutableList()
        val index = list.indexOf(id)
        if (index <= 0) return
        list[index] = list[index - 1]; list[index - 1] = id
        setWidgets(list)
    }
    private fun setWidgets(list: List<String>) {
        _widgets.value = list
        sp.edit().putString("widgets", list.joinToString(",")).apply()
    }
    fun setColumns(count: Int) {
        _columns.value = count.coerceIn(1, 3)
        sp.edit().putInt("widget_columns", _columns.value).apply()
    }

    private val _panelApps = MutableStateFlow(sp.getString("panel_apps", "\n").orEmpty().split('\n').let { listOf(it.getOrElse(0) { "" }, it.getOrElse(1) { "" }) })
    val panelApps: StateFlow<List<String>> get() = _panelApps
    fun setPanelApp(index: Int, target: String) {
        if (index !in 0..1) return
        val updated = _panelApps.value.toMutableList().apply { set(index, target) }
        _panelApps.value = updated
        sp.edit().putString("panel_apps", updated.joinToString("\n")).apply()
    }

    val pinned: StateFlow<List<String>> get() = _pinned
    val hidden: StateFlow<Set<String>> get() = _hidden

    private fun readPinned(): List<String> = sp.getString(KEY_FAVORITES, "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun togglePin(key: String) = setPinned(CarLauncherLogic.togglePin(_pinned.value, key))

    fun hide(key: String) {
        val (p, h) = CarLauncherLogic.hide(_pinned.value, _hidden.value, key)
        setPinned(p); setHidden(h)
    }

    fun unhide(key: String) = setHidden(_hidden.value - key)

    fun prune(installed: Set<String>) {
        val (p, h) = CarLauncherLogic.prune(_pinned.value, _hidden.value, installed)
        if (p != _pinned.value) setPinned(p)
        if (h != _hidden.value) setHidden(h)
    }

    private fun setPinned(list: List<String>) { _pinned.value = list; sp.edit().putString(KEY_FAVORITES, list.joinToString("\n")).apply() }
    private fun setHidden(set: Set<String>) { _hidden.value = set; sp.edit().putStringSet(KEY_HIDDEN, HashSet(set)).apply() }

    companion object {
        val WIDGETS = listOf("date", "clock", "favorites", "connection")
        const val NAME = "car_launcher"
        const val KEY_FAVORITES = "favorites"
        const val KEY_HIDDEN = "hidden"
    }
}
