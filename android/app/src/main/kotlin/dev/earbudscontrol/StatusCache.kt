package dev.earbudscontrol

import android.content.Context

/** Last known state, so the tile can render instantly before the earbuds answer. */
object StatusCache {
    private const val PREFS = "status"

    data class Cached(val ambient: Ambient?, val left: Int, val right: Int)

    fun load(context: Context): Cached {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Cached(
            ambient = Ambient.of(prefs.getInt("ambient", -1)),
            left = prefs.getInt("left", -1),
            right = prefs.getInt("right", -1),
        )
    }

    fun save(context: Context, status: Status) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("ambient", status.ambient?.byte ?: -1)
            .putInt("left", status.left)
            .putInt("right", status.right)
            .apply()
    }
}
