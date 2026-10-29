package com.shilapi.xcertplay.voyah

import android.content.Context

/** Physical pixels match the OEM display; orders are stable IDs, never enum ordinals. */
object VoyahComfortLayout {
    const val SIZE_PX = 109
    const val PREFS = "voyah_vehicle"
    val ids = listOf("light", "wheel", "heat_l", "vent_l", "mass_l", "aroma", "mass_r", "vent_r", "heat_r")
    val settingsKeys = listOf("wheel", "heat_l", "vent_l", "vent_r", "heat_r")
    val titles = mapOf("light" to "Свет", "wheel" to "Подогрев руля", "heat_l" to "Подогрев водителя",
        "vent_l" to "Вентиляция водителя", "mass_l" to "Массаж водителя", "aroma" to "Ароматизация",
        "mass_r" to "Массаж пассажира", "vent_r" to "Вентиляция пассажира", "heat_r" to "Подогрев пассажира")
    enum class Mode(val title: String) { BOTTOM("Снизу"), LEFT("Слева"), RIGHT("Справа"), BOTH("Слева и справа") }
    enum class Edge(val title: String) { BOTTOM("Нижний ряд"), LEFT("Левая колонка"), RIGHT("Правая колонка") }
    data class Insets(val left: Int = 0, val right: Int = 0, val bottom: Int = 0)
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("comfort_bar_enabled", false)
    fun mode(c: Context) = Mode.entries.firstOrNull { it.name == prefs(c).getString("comfort_layout", null) } ?: Mode.BOTTOM
    fun availableIds(c: Context) = availableIds(VoyahEquipment.hasHeatedWheel(c))
    fun availableIds(heatedWheel: Boolean) = ids.filter { heatedWheel || it != "wheel" }
    fun settingsKeys(c: Context): List<String> {
        val available = availableIds(c)
        return settingsKeys.filter { it in available }
    }
    fun settingsKey(c: Context): String {
        val choices = settingsKeys(c)
        return prefs(c).getString("comfort_settings_key", "wheel").takeIf { it in choices } ?: choices.first()
    }
    fun sanitizeOrder(raw: String?, defaults: List<String>): List<String> =
        if (raw == null) defaults else raw.split(',').filter { it in ids }.distinct()
    fun order(c: Context, edge: Edge): List<String> {
        val defaults = when (edge) {
            Edge.BOTTOM -> ids.filter { it == "light" || prefs(c).getBoolean("comfort_$it", true) }
            Edge.LEFT -> listOf("light", "wheel", "heat_l", "vent_l", "mass_l")
            Edge.RIGHT -> listOf("aroma", "mass_r", "vent_r", "heat_r")
        }
        val available = availableIds(c)
        return sanitizeOrder(prefs(c).getString("comfort_order_${edge.name}", null), defaults).filter { it in available }
    }
    fun edges(mode: Mode) = when (mode) {
        Mode.BOTTOM -> listOf(Edge.BOTTOM)
        Mode.LEFT -> listOf(Edge.LEFT)
        Mode.RIGHT -> listOf(Edge.RIGHT)
        Mode.BOTH -> listOf(Edge.LEFT, Edge.RIGHT)
    }
    /** Retain one accessible settings entry even after a change of mode or an empty selection. */
    fun visible(c: Context): Map<Edge, List<String>> {
        return resolve(enabled(c), mode(c), Edge.entries.associateWith { order(c,it) }, settingsKey(c), availableIds(c))
    }
    fun resolve(enabled: Boolean, mode: Mode, orders: Map<Edge,List<String>>, settingsKey: String,
        availableIds: List<String> = ids): Map<Edge,List<String>> {
        if (!enabled) return emptyMap()
        val available = ids.filter { it in availableIds }
        val result = edges(mode).associateWith { edge -> orders[edge].orEmpty().filter { it in available }.distinct() }.toMutableMap()
        val keys = settingsKeys.filter { it in available }
        val key = settingsKey.takeIf { it in keys } ?: keys.firstOrNull() ?: return emptyMap()
        if (result.values.none { key in it }) {
            val first = result.keys.first()
            result[first] = result.getValue(first) + key
        }
        return result.filterValues { it.isNotEmpty() }
    }
    fun insets(c: Context) = insetsFor(visible(c))
    fun insetsFor(panels: Map<Edge,List<String>>) = Insets(
        if (!panels[Edge.LEFT].isNullOrEmpty()) SIZE_PX else 0,
        if (!panels[Edge.RIGHT].isNullOrEmpty()) SIZE_PX else 0,
        if (!panels[Edge.BOTTOM].isNullOrEmpty()) SIZE_PX else 0)
    fun setEnabled(c: Context, enabled: Boolean) { prefs(c).edit().putBoolean("comfort_bar_enabled", enabled).apply() }
    fun setMode(c: Context, mode: Mode) { prefs(c).edit().putString("comfort_layout", mode.name).apply() }
    fun setSettingsKey(c: Context, id: String) {
        require(id in settingsKeys(c))
        prefs(c).edit().putString("comfort_settings_key", id).apply()
    }
    fun setOrder(c: Context, edge: Edge, order: List<String>) {
        prefs(c).edit().putString("comfort_order_${edge.name}", order.filter { it in ids }.distinct().joinToString(",")).apply()
    }
}
