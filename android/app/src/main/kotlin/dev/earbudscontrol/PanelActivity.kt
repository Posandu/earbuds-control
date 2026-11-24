package dev.earbudscontrol

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.service.quicksettings.TileService
import android.content.ComponentName
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Bottom panel: battery, the three noise control modes, and a live listening-level meter. */
class PanelActivity : Activity() {
    private lateinit var leftText: TextView
    private lateinit var rightText: TextView
    private lateinit var caseText: TextView
    private lateinit var messageText: TextView
    private lateinit var dbText: TextView
    private lateinit var dbBar: ProgressBar
    private lateinit var modeButtons: Map<Ambient, View>

    private var worker: Worker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.panel)
        findViewById<View>(R.id.scrim).setOnClickListener { finish() }
        findViewById<View>(R.id.card).setOnClickListener { /* swallow taps */ }

        leftText = findViewById(R.id.battery_left)
        rightText = findViewById(R.id.battery_right)
        caseText = findViewById(R.id.battery_case)
        messageText = findViewById(R.id.message)
        dbText = findViewById(R.id.db_value)
        dbBar = findViewById(R.id.db_bar)
        modeButtons = mapOf(
            Ambient.NOISE_CANCELING to findViewById(R.id.mode_nc),
            Ambient.TRANSPARENCY to findViewById(R.id.mode_transparency),
            Ambient.NORMAL to findViewById(R.id.mode_off),
        )
        modeButtons.forEach { (ambient, button) ->
            button.setOnClickListener {
                showMode(ambient)
                worker?.post { buds, status -> buds.setAmbient(ambient, status.soundModes); status.copy(ambient = ambient) }
            }
        }
        showMode(StatusCache.load(this).ambient)
    }

    override fun onStart() {
        super.onStart()
        if (hasBluetoothPermission(this)) {
            startWorker()
        } else {
            showMessage("Allow “Nearby devices” to talk to your earbuds.")
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }

    override fun onStop() {
        worker?.shutdown()
        worker = null
        TileService.requestListeningState(this, ComponentName(this, AncTileService::class.java))
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (hasBluetoothPermission(this)) startWorker() else showMessage("Without “Nearby devices” the app can't reach your earbuds.")
    }

    private fun startWorker() {
        if (worker != null) return
        showMessage("Connecting…")
        worker = Worker().also { it.start() }
    }

    private fun showStatus(status: Status) {
        leftText.text = batteryLabel(status.left, status.leftCharging)
        rightText.text = batteryLabel(status.right, status.rightCharging)
        caseText.text = batteryLabel(status.case, false)
        showMode(status.ambient)
    }

    private fun batteryLabel(level: Int, charging: Boolean): String =
        batteryPercent(level)?.let { "$it%" + if (charging) " ⚡" else "" } ?: "–"

    private fun showMode(ambient: Ambient?) {
        modeButtons.forEach { (mode, button) -> button.isSelected = mode == ambient }
    }

    private fun showLevel(db: Int) {
        dbText.text = if (db > 0) "$db dB" else "– dB"
        dbBar.progress = db
    }

    private fun showMessage(message: String?) {
        messageText.text = message
        messageText.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    /** Holds one connection while the panel is visible: runs mode changes and polls the dB level. */
    private inner class Worker : Thread("buds-panel") {
        private val actions = LinkedBlockingQueue<(Buds, Status) -> Status>()
        @Volatile private var running = true

        fun post(action: (Buds, Status) -> Status) {
            actions.put(action)
        }

        fun shutdown() {
            running = false
            interrupt()
        }

        override fun run() {
            try {
                Buds.connect(this@PanelActivity).use { buds ->
                    var status = buds.status()
                    StatusCache.save(this@PanelActivity, status)
                    runOnUiThread { showMessage(null); showStatus(status) }
                    while (running) {
                        val action = actions.poll(1, TimeUnit.SECONDS)
                        if (action != null) {
                            status = action(buds, status)
                            StatusCache.save(this@PanelActivity, status)
                            runOnUiThread { showStatus(status) }
                        }
                        val db = buds.volumeLevel()
                        runOnUiThread { showLevel(db) }
                    }
                }
            } catch (_: InterruptedException) {
                // panel closed
            } catch (e: BudsException) {
                if (running) runOnUiThread { showMessage(e.message) }
            }
        }
    }
}
