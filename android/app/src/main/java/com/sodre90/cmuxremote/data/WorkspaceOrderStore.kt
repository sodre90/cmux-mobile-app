package com.sodre90.cmuxremote.data

import android.content.Context

/**
 * Persists the phone-local display preferences for the sessions list -- the
 * custom drag order and the "Waiting first" sort toggle. Both are display
 * preferences only: not synced to the bridge, not visible from any other
 * device, and unrelated to cmux's own sidebar order on the Mac. Workspace ids
 * that later disappear from the saved order are simply dropped by
 * [SessionsLogic.applyCustomOrder]; nothing here needs to track that.
 */
class WorkspaceOrderStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Workspace ids in their last-saved custom order, oldest-set-first. */
    fun load(): List<String> =
        prefs.getString(KEY_ORDER, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() } ?: emptyList()

    fun save(order: List<String>) {
        prefs.edit().putString(KEY_ORDER, order.joinToString(SEPARATOR)).apply()
    }

    fun loadSortByAttention(): Boolean = prefs.getBoolean(KEY_SORT_BY_ATTENTION, false)

    fun saveSortByAttention(sortByAttention: Boolean) {
        prefs.edit().putBoolean(KEY_SORT_BY_ATTENTION, sortByAttention).apply()
    }

    private companion object {
        const val PREFS_NAME = "cmux_workspace_prefs"
        const val KEY_ORDER = "order"
        const val KEY_SORT_BY_ATTENTION = "sort_by_attention"
        const val SEPARATOR = ","
    }
}
