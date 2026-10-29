package de.letheapp.iris

import android.content.Context

/**
 * Remembers recently delivered push_ids. The server re-sends every push that wasn't
 * acknowledged on the next connect (a push written into a half-dead socket looks
 * "sent" to the server), so the same push can arrive twice - deliver it only once.
 */
object PushDedup {
    private const val PREFS = "iris_push_dedup"
    private const val KEY = "ids"
    private const val MAX = 300

    private var ids: LinkedHashSet<String>? = null

    private fun load(context: Context): LinkedHashSet<String> = ids ?: LinkedHashSet(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty().split(',').filter { it.isNotBlank() }
    ).also { ids = it }

    /** True if [pushId] is new (and records it), false if it was already delivered. */
    @Synchronized
    fun markNew(context: Context, pushId: String): Boolean {
        val set = load(context)
        if (!set.add(pushId)) return false
        while (set.size > MAX) set.remove(set.first())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, set.joinToString(",")).apply()
        return true
    }
}
