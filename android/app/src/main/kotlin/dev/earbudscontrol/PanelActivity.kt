package dev.earbudscontrol

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.window.OnBackInvokedDispatcher
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Pop-up panel: battery, noise control with per-mode options, and a live listening-level meter. */
private const val STATUS_REFRESH_TICKS = 4

class PanelActivity : Activity() {
    private lateinit var card: ViewGroup
    private lateinit var messageText: TextView
    private lateinit var batteryTexts: List<TextView>
    private lateinit var segments: List<Pair<Ambient, View>>
    private lateinit var dbValue: TextView
    private lateinit var dbStatus: TextView
    private lateinit var dbMeter: LevelMeterView

    private lateinit var ncModeRow: View
    private lateinit var strengthRow: View
    private lateinit var strength: PillSlider
    private lateinit var strengthValue: TextView
    private lateinit var transportRow: View
    private lateinit var transparencyRow: View
    private lateinit var ncModeChips: Map<NcMode, TextView>
    private lateinit var transportChips: Map<Transport, TextView>
    private lateinit var transparencyChips: Map<TransparencyMode, TextView>

    /** Sound modes as last sent to / read from the earbuds; null until connected. */
    private var modes: SoundModes? = null
    private var selected: Ambient? = null
    private var worker: Worker? = null
    private var dismissing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.panel)

        val scrim = findViewById<View>(R.id.scrim)
        card = findViewById(R.id.card)
        setUpBlur(scrim)
        scrim.setOnClickListener { dismiss() }
        card.setOnClickListener { /* swallow taps inside the card */ }

        messageText = findViewById(R.id.message)
        batteryTexts = listOf(R.id.battery_left, R.id.battery_case, R.id.battery_right).map { findViewById(it) }
        dbValue = findViewById(R.id.db_value)
        dbStatus = findViewById(R.id.db_status)
        dbMeter = findViewById(R.id.db_meter)

        setUpModeSwitch()
        setUpDetails()

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { dismiss() }
        }

        select(StatusCache.load(this).ambient, animate = false)
        renderDetails(animate = false)
        animateIn()
    }

    override fun onStart() {
        super.onStart()
        if (hasBluetoothPermission(this)) {
            startWorker()
        } else {
            showMessage("Allow “Nearby devices” to reach your earbuds")
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }

    override fun onStop() {
        worker?.shutdown()
        worker = null
        TileService.requestListeningState(this, ComponentName(this, AncTileService::class.java))
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    @Deprecated("Pre-Android 13 back handling")
    override fun onBackPressed() = dismiss()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (hasBluetoothPermission(this)) startWorker() else showMessage("Needs “Nearby devices” permission")
    }

    private fun startWorker() {
        if (worker != null) return
        showMessage("Connecting…")
        worker = Worker().also { it.start() }
    }

    // --- Controls -----------------------------------------------------------------------------

    /**
     * Frosted backdrop where the phone supports window blur. Many Samsung A-series phones ship
     * with it disabled in SurfaceFlinger (even One UI's own SemBlurInfo refuses there), so fall
     * back to a plain dim.
     */
    private fun setUpBlur(scrim: View) {
        val radius = (48 * resources.displayMetrics.density).toInt()
        val windowManager = getSystemService(WindowManager::class.java)
        val apply = { enabled: Boolean ->
            scrim.setBackgroundColor(getColor(if (enabled) R.color.scrim else R.color.scrim_no_blur))
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply { blurBehindRadius = radius }
        apply(windowManager.isCrossWindowBlurEnabled)
        windowManager.addCrossWindowBlurEnabledListener(mainExecutor) { apply(it) }
    }

    private fun setUpModeSwitch() {
        segments = listOf(
            Ambient.NOISE_CANCELING to findViewById(R.id.mode_nc),
            Ambient.NORMAL to findViewById(R.id.mode_off),
            Ambient.TRANSPARENCY to findViewById(R.id.mode_transparency),
        )
        segments.forEach { (ambient, segment) ->
            segment.setOnClickListener { change { it.withAmbient(ambient) } }
        }
    }

    private fun setUpDetails() {
        ncModeRow = findViewById(R.id.nc_mode_row)
        strengthRow = findViewById(R.id.strength_row)
        strength = findViewById(R.id.strength)
        strengthValue = findViewById(R.id.strength_value)
        transportRow = findViewById(R.id.transport_row)
        transparencyRow = findViewById(R.id.transparency_row)

        ncModeChips = chips(ncModeRow, NcMode.entries, NcMode::label) { mode -> change { it.withNcMode(mode) } }
        transportChips = chips(transportRow, Transport.entries, Transport::label) { t -> change { it.withTransport(t) } }
        transparencyChips = chips(transparencyRow, TransparencyMode.entries, TransparencyMode::label) { mode ->
            change { it.withTransparency(mode) }
        }

        strength.onChanging = { strengthValue.text = strengthLabel(it) }
        strength.onChanged = { level -> change(haptic = false) { it.withManualLevel(level) } }
    }

    private fun <T> chips(row: View, items: List<T>, label: (T) -> String, onPick: (T) -> Unit): Map<T, TextView> {
        row as LinearLayout
        return items.associateWith { item ->
            (layoutInflater.inflate(R.layout.chip, row, false) as TextView).also { chip ->
                chip.text = label(item)
                chip.setOnClickListener { onPick(item) }
                row.addView(chip)
            }
        }
    }

    /** Apply a sound mode change: update the UI right away, then send it to the earbuds. */
    private fun change(haptic: Boolean = true, transform: (SoundModes) -> SoundModes) {
        val current = modes ?: return
        val next = transform(current)
        if (next.bytes.contentEquals(current.bytes)) return
        if (haptic) card.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        showModes(next, animate = true)
        worker?.post { buds, status ->
            val updated = transform(SoundModes(status.soundModes))
            buds.setSoundModes(updated.bytes)
            status.copy(ambient = updated.ambient, soundModes = updated.bytes)
        }
    }

    // --- Animations ---------------------------------------------------------------------------

    private fun animateIn() {
        card.alpha = 0f
        card.scaleX = 0.86f
        card.scaleY = 0.86f
        card.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(380)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    private fun dismiss() {
        if (dismissing) return
        dismissing = true
        card.animate()
            .alpha(0f).scaleX(0.9f).scaleY(0.9f)
            .setDuration(180)
            .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction {
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
            .start()
    }

    private fun select(ambient: Ambient?, animate: Boolean) {
        selected = ambient
        segments.forEach { (mode, view) ->
            view.isSelected = mode == ambient
            if (animate && mode == ambient) {
                // Little press-and-pop on the newly selected button.
                val icon = (view as ViewGroup).getChildAt(0)
                icon.scaleX = 0.82f
                icon.scaleY = 0.82f
                icon.animate().scaleX(1f).scaleY(1f).setDuration(360)
                    .setInterpolator(OvershootInterpolator(2.2f)).start()
            }
        }
    }

    // --- Rendering ----------------------------------------------------------------------------

    private fun showStatus(status: Status) {
        val levels = listOf(status.left, status.case, status.right)
        val charging = listOf(status.leftCharging, false, status.rightCharging)
        levels.forEachIndexed { i, level ->
            val percent = batteryPercent(level)
            batteryTexts[i].text = percent?.let { "$it%" + if (charging[i]) "⚡" else "" } ?: "–"
            // Icon and text turn red when that part is running low.
            val color = getColor(if (percent != null && percent <= 20) R.color.battery_low else R.color.text_secondary)
            batteryTexts[i].setTextColor(color)
            batteryTexts[i].compoundDrawableTintList = android.content.res.ColorStateList.valueOf(color)
        }
        showModes(SoundModes(status.soundModes), animate = modes != null)
    }

    private fun showModes(next: SoundModes, animate: Boolean) {
        modes = next
        if (next.ambient != selected) select(next.ambient, animate)
        renderDetails(animate)
    }

    private fun renderDetails(animate: Boolean) {
        if (animate) TransitionManager.beginDelayedTransition(card, AutoTransition().setDuration(220))
        val m = modes
        val nc = m?.ambient == Ambient.NOISE_CANCELING
        ncModeRow.visibility = if (nc) View.VISIBLE else View.GONE
        strengthRow.visibility = if (nc && m?.ncMode == NcMode.MANUAL) View.VISIBLE else View.GONE
        transportRow.visibility = if (nc && m?.ncMode == NcMode.TRANSPORT) View.VISIBLE else View.GONE
        transparencyRow.visibility = if (m?.ambient == Ambient.TRANSPARENCY) View.VISIBLE else View.GONE
        if (m == null) return

        ncModeChips.forEach { (mode, chip) -> chip.isSelected = mode == m.ncMode }
        transportChips.forEach { (t, chip) -> chip.isSelected = t == m.transport }
        transparencyChips.forEach { (mode, chip) -> chip.isSelected = mode == m.transparency }
        strength.setValue(m.manualLevel, animate)
        strengthValue.text = strengthLabel(m.manualLevel)
    }

    private fun strengthLabel(level: Int) = level.toString()

    private fun showLevel(db: Int) {
        dbMeter.setLevel(db)
        dbValue.text = if (db > 0) "$db dB" else "–"
        dbStatus.text = when {
            db <= 0 -> "Not playing"
            db < 75 -> "OK"
            db < 85 -> "Loud"
            else -> "Too loud"
        }
    }

    /** Status line under the name; hidden once connected. */
    private fun showMessage(message: String?) {
        messageText.text = message
        messageText.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    /** Holds one connection while the panel is visible: runs mode changes and polls the dB level. */
    private fun Status.sameAs(other: Status) =
        left == other.left && right == other.right && case == other.case &&
            leftCharging == other.leftCharging && rightCharging == other.rightCharging &&
            soundModes.contentEquals(other.soundModes)

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
                    // Live updates pushed by the earbuds: fold them into our state on this thread.
                    buds.onSoundModesChanged = { pushed ->
                        post { _, current ->
                            val modes = SoundModes(pushed)
                            runOnUiThread { showModes(modes, animate = true) }
                            current.copy(ambient = modes.ambient, soundModes = pushed)
                        }
                    }
                    var tick = 0
                    while (running) {
                        val action = actions.poll(1, TimeUnit.SECONDS)
                        if (action != null) {
                            status = action(buds, status)
                            StatusCache.save(this@PanelActivity, status)
                        }
                        // Full re-read every few seconds catches battery, charging and anything
                        // the earbuds changed without telling us.
                        if (++tick % STATUS_REFRESH_TICKS == 0 && actions.isEmpty()) {
                            val fresh = buds.status()
                            if (!fresh.sameAs(status)) {
                                status = fresh
                                StatusCache.save(this@PanelActivity, status)
                                runOnUiThread { showStatus(fresh) }
                            }
                        }
                        val db = buds.volumeLevel()
                        runOnUiThread { showLevel(db) }
                    }
                }
            } catch (_: InterruptedException) {
                // panel closed
            } catch (e: BudsException) {
                if (running) runOnUiThread { showMessage(e.message ?: "Couldn't reach earbuds") }
            }
        }
    }
}
