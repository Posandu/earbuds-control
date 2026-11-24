package dev.earbudscontrol

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.ComponentName
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.window.OnBackInvokedDispatcher
import android.widget.TextView
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Pop-up panel: battery rings, a segmented noise control switch, and a live listening-level meter. */
class PanelActivity : Activity() {
    private lateinit var card: View
    private lateinit var messageText: TextView
    private lateinit var batteryTexts: List<TextView>
    private lateinit var rings: List<RingView>
    private lateinit var thumb: View
    private lateinit var segments: List<Pair<Ambient, View>>
    private lateinit var dbValue: TextView
    private lateinit var dbStatus: TextView
    private lateinit var dbMeter: LevelMeterView

    private var selected: Ambient? = null
    private var worker: Worker? = null
    private var dismissing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.panel)

        val scrim = findViewById<View>(R.id.scrim)
        // Frosted background: blur everything behind this window. The theme flag alone isn't
        // honoured everywhere (e.g. One UI), so request it explicitly too.
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply { blurBehindRadius = (40 * resources.displayMetrics.density).toInt() }
        window.setBackgroundBlurRadius((40 * resources.displayMetrics.density).toInt())
        card = findViewById(R.id.card)
        val windowManager = getSystemService(WindowManager::class.java)
        val applyBlurState = { blurEnabled: Boolean ->
            scrim.setBackgroundColor(getColor(if (blurEnabled) R.color.scrim else R.color.scrim_no_blur))
            // Translucent card only makes sense over a blurred background; otherwise go solid.
            val cardColor = getColor(R.color.card)
            (card.background.mutate() as GradientDrawable).setColor(
                if (blurEnabled) cardColor else cardColor or 0xFF000000.toInt(),
            )
        }
        applyBlurState(windowManager.isCrossWindowBlurEnabled)
        windowManager.addCrossWindowBlurEnabledListener(mainExecutor) { applyBlurState(it) }
        scrim.setOnClickListener { dismiss() }

        card.setOnClickListener { /* swallow taps inside the card */ }
        messageText = findViewById(R.id.message)
        batteryTexts = listOf(R.id.battery_left, R.id.battery_case, R.id.battery_right).map { findViewById(it) }
        rings = listOf(R.id.ring_left, R.id.ring_case, R.id.ring_right).map { findViewById(it) }
        thumb = findViewById(R.id.segment_thumb)
        segments = listOf(
            Ambient.NOISE_CANCELING to findViewById(R.id.mode_nc),
            Ambient.NORMAL to findViewById(R.id.mode_off),
            Ambient.TRANSPARENCY to findViewById(R.id.mode_transparency),
        )
        dbValue = findViewById(R.id.db_value)
        dbStatus = findViewById(R.id.db_status)
        dbMeter = findViewById(R.id.db_meter)

        segments.forEach { (ambient, segment) ->
            segment.setOnClickListener {
                if (ambient == selected) return@setOnClickListener
                segment.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                select(ambient, animate = true)
                worker?.post { buds, status ->
                    buds.setAmbient(ambient, status.soundModes)
                    status.copy(ambient = ambient)
                }
            }
        }
        // Size and place the sliding thumb once the segments have been measured.
        findViewById<View>(R.id.segments).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val segmentWidth = segments.first().second.width
            val params = thumb.layoutParams
            val thumbWidth = segmentWidth - 2 * (params as android.view.ViewGroup.MarginLayoutParams).leftMargin
            if (params.width != thumbWidth) {
                params.width = thumbWidth
                thumb.layoutParams = params
            }
            select(selected, animate = false)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { dismiss() }
        }

        val cached = StatusCache.load(this)
        select(cached.ambient, animate = false)
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
        segments.forEach { (mode, view) -> view.isSelected = mode == ambient }
        val index = segments.indexOfFirst { it.first == ambient }
        thumb.visibility = if (index < 0) View.INVISIBLE else View.VISIBLE
        if (index < 0) return
        val target = segments[index].second.width * index.toFloat()
        if (animate) {
            ObjectAnimator.ofFloat(thumb, View.TRANSLATION_X, target).apply {
                duration = 320
                interpolator = OvershootInterpolator(0.9f)
                start()
            }
        } else {
            thumb.translationX = target
        }
    }

    // --- Rendering ----------------------------------------------------------------------------

    private fun showStatus(status: Status) {
        val levels = listOf(status.left, status.case, status.right)
        val charging = listOf(status.leftCharging, false, status.rightCharging)
        levels.forEachIndexed { i, level ->
            val percent = batteryPercent(level)
            batteryTexts[i].text = percent?.let { "$it%" + if (charging[i]) "⚡" else "" } ?: "–"
            rings[i].setLevel(percent)
        }
        if (status.ambient != selected) select(status.ambient, animate = true)
    }

    private fun showLevel(db: Int) {
        dbMeter.setLevel(db)
        dbValue.text = if (db > 0) db.toString() else "–"
        dbStatus.text = when {
            db <= 0 -> "Nothing playing"
            db < 75 -> "OK"
            db < 85 -> "Loud"
            else -> "Too loud"
        }
    }

    private fun showMessage(message: String) {
        messageText.text = message
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
                    runOnUiThread { showMessage("Connected"); showStatus(status) }
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
                if (running) runOnUiThread { showMessage(e.message ?: "Couldn't reach earbuds") }
            }
        }
    }
}
