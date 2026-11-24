package dev.earbudscontrol

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import java.util.concurrent.atomic.AtomicBoolean

/** Quick settings tile: shows the current mode and battery; tap or long-press opens the panel. */
class AncTileService : TileService() {
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)

    override fun onStartListening() {
        val cached = StatusCache.load(this)
        render(cached.ambient, batterySubtitle(cached.left, cached.right))
        // Background refresh: skip it if the panel already holds the connection.
        if (hasBluetoothPermission(this)) runOnBuds(waitMs = 0) { buds -> buds.status() }
    }

    /** Tapping the tile opens the control panel pop-up. */
    override fun onClick() = openPanel()

    private fun runOnBuds(waitMs: Long, action: (Buds) -> Status) {
        if (!busy.compareAndSet(false, true)) return
        Thread {
            try {
                val buds = Buds.connectOrNull(this, waitMs)
                if (buds == null) {
                    if (waitMs > 0) throw BudsException("Earbuds busy, try again")
                    return@Thread
                }
                val status = buds.use(action)
                StatusCache.save(this, status)
                main.post { render(status.ambient, batterySubtitle(status.left, status.right)) }
            } catch (e: BudsException) {
                main.post { render(StatusCache.load(this).ambient, e.message, unavailable = true) }
            } finally {
                busy.set(false)
            }
        }.start()
    }

    private fun render(ambient: Ambient?, subtitle: String?, unavailable: Boolean = false) {
        val tile = qsTile ?: return
        tile.label = ambient?.label ?: "Noise Control"
        tile.subtitle = subtitle
        tile.icon = Icon.createWithResource(this, iconFor(ambient))
        tile.state = when {
            unavailable -> Tile.STATE_UNAVAILABLE
            ambient == null || ambient == Ambient.NORMAL -> Tile.STATE_INACTIVE
            else -> Tile.STATE_ACTIVE
        }
        tile.updateTile()
    }

    private fun openPanel() {
        val intent = Intent(this, PanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun iconFor(ambient: Ambient?): Int = when (ambient) {
            Ambient.TRANSPARENCY -> R.drawable.ic_mode_transparency
            Ambient.NORMAL -> R.drawable.ic_mode_off
            else -> R.drawable.ic_mode_nc
        }

        fun batterySubtitle(left: Int, right: Int): String? {
            val l = batteryPercent(left)
            val r = batteryPercent(right)
            if (l == null && r == null) return null
            return "L ${l?.let { "$it%" } ?: "–"} · R ${r?.let { "$it%" } ?: "–"}"
        }
    }
}
