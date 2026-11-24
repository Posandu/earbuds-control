package dev.earbudscontrol

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Soundcore Liberty 4 NC (A3947) control protocol over RFCOMM. See ../README.md. */

enum class Ambient(val byte: Int, val label: String) {
    NOISE_CANCELING(0, "Noise Cancelling"),
    TRANSPARENCY(1, "Ambient sound"),
    NORMAL(2, "Off");

    fun next(): Ambient = when (this) {
        NOISE_CANCELING -> TRANSPARENCY
        TRANSPARENCY -> NORMAL
        NORMAL -> NOISE_CANCELING
    }

    companion object {
        fun of(byte: Int): Ambient? = entries.firstOrNull { it.byte == byte }
    }
}

data class Status(
    val left: Int,
    val right: Int,
    val case: Int,
    val leftCharging: Boolean,
    val rightCharging: Boolean,
    val ambient: Ambient?,
    val soundModes: ByteArray,
)

class BudsException(message: String) : Exception(message)

fun hasBluetoothPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

/** Battery level byte (0-5) as a percentage, or null when the bud is absent. */
fun batteryPercent(level: Int): Int? = if (level in 0..5) level * 20 else null

@SuppressLint("MissingPermission")
class Buds private constructor(private val socket: BluetoothSocket) : AutoCloseable {
    private val inbox = LinkedBlockingQueue<ByteArray>()
    private val closed = AtomicBoolean(false)

    /**
     * Called on the reader thread whenever the earbuds announce new sound modes on their own
     * (e.g. auto-switching when you take a bud out, or a change from the touch controls).
     */
    @Volatile var onSoundModesChanged: ((ByteArray) -> Unit)? = null

    init {
        Thread(::readLoop, "buds-reader").apply { isDaemon = true }.start()
    }

    fun status(): Status {
        val body = request(CMD_STATE)
        if (body.size <= OFF_CASE_BATTERY) throw BudsException("Unexpected reply from earbuds")
        val modes = body.copyOfRange(OFF_SOUND_MODES, OFF_SOUND_MODES + 7)
        return Status(
            left = body[OFF_BATTERY_LEFT].u(),
            right = body[OFF_BATTERY_RIGHT].u(),
            case = body[OFF_CASE_BATTERY].u(),
            leftCharging = body[OFF_CHARGING_LEFT].u() != 0,
            rightCharging = body[OFF_CHARGING_RIGHT].u() != 0,
            ambient = Ambient.of(modes[0].u()),
            soundModes = modes,
        )
    }

    /** Switch ambient mode, keeping every other sound mode setting as it is. */
    fun setAmbient(ambient: Ambient, currentSoundModes: ByteArray) {
        setSoundModes(SoundModes(currentSoundModes).withAmbient(ambient).bytes)
    }

    /** Send all 7 sound mode bytes (see [SoundModes]). */
    fun setSoundModes(modes: ByteArray) {
        request(CMD_SET_SOUND_MODES, modes)
    }

    /** Current listening level in dB, 0 when nothing is playing. */
    fun volumeLevel(): Int = request(CMD_VOLUME_LEVEL, timeoutMs = 600).firstOrNull()?.u() ?: 0

    private fun request(cmd: IntArray, body: ByteArray = ByteArray(0), timeoutMs: Long = 1000, retries: Int = 3): ByteArray {
        val packet = buildPacket(cmd, body)
        repeat(retries) {
            try {
                socket.outputStream.write(packet)
                socket.outputStream.flush()
            } catch (e: IOException) {
                throw BudsException("Lost connection to earbuds")
            }
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (true) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                if (remaining <= 0) break
                val frame = inbox.poll(remaining, TimeUnit.MILLISECONDS) ?: break
                if (frame[5].u() == cmd[0] && frame[6].u() == cmd[1]) {
                    return frame.copyOfRange(9, frame.size - 1)
                }
            }
        }
        throw BudsException("Earbuds didn't answer")
    }

    private fun readLoop() {
        val buffer = ByteArray(4096)
        var pending = ByteArray(0)
        try {
            while (true) {
                val read = socket.inputStream.read(buffer)
                if (read <= 0) return
                pending += buffer.copyOf(read)
                pending = extractFrames(pending)
            }
        } catch (_: IOException) {
            // socket closed
        }
    }

    /** Pull complete, checksum-valid frames off the front of [data]; return the leftover bytes. */
    private fun extractFrames(data: ByteArray): ByteArray {
        var rest = data
        while (true) {
            val start = rest.indexOfHeader()
            if (start < 0) return rest.takeLast(IN_HEADER.size - 1).toByteArray()
            rest = rest.copyOfRange(start, rest.size)
            if (rest.size < 9) return rest
            val length = rest[7].u() or (rest[8].u() shl 8)
            if (length < 10) {
                rest = rest.copyOfRange(1, rest.size)
                continue
            }
            if (rest.size < length) return rest
            val frame = rest.copyOfRange(0, length)
            rest = rest.copyOfRange(length, rest.size)
            if (checksum(frame, length - 1) != frame[length - 1].u()) continue
            if (frame[5].u() == PUSH_SOUND_MODES[0] && frame[6].u() == PUSH_SOUND_MODES[1] && length >= 10 + 7) {
                onSoundModesChanged?.invoke(frame.copyOfRange(9, 9 + 7))
            } else {
                inbox.put(frame)
            }
        }
    }

    private fun ByteArray.indexOfHeader(): Int {
        outer@ for (i in 0..size - IN_HEADER.size) {
            for (j in IN_HEADER.indices) if (this[i + j] != IN_HEADER[j]) continue@outer
            return i
        }
        return -1
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            socket.close()
        } catch (_: IOException) {
        }
        connectionLock.release()
    }

    companion object {
        private val SERVICE_UUID: UUID = UUID.fromString("0cf12d31-fac3-4553-bd80-d6832e7b3947")
        private const val NAME_HINT = "Liberty 4 NC"
        const val NOT_CONNECTED = "Not connected"

        private val OUT_HEADER = byteArrayOf(0x08, 0xEE.toByte(), 0x00, 0x00, 0x00)
        private val IN_HEADER = byteArrayOf(0x09, 0xFF.toByte(), 0x00, 0x00, 0x01)

        private val CMD_STATE = intArrayOf(0x01, 0x01)
        private val CMD_SET_SOUND_MODES = intArrayOf(0x06, 0x81)
        private val CMD_VOLUME_LEVEL = intArrayOf(0x20, 0x03)
        /** Sent by the earbuds, unprompted, when the sound modes change. */
        private val PUSH_SOUND_MODES = intArrayOf(0x06, 0x01)

        // Offsets into the 01 01 state reply body (firmware 08.61).
        private const val OFF_BATTERY_LEFT = 2
        private const val OFF_BATTERY_RIGHT = 3
        private const val OFF_CHARGING_LEFT = 4
        private const val OFF_CHARGING_RIGHT = 5
        private const val OFF_SOUND_MODES = 126
        private const val OFF_CASE_BATTERY = 139

        /**
         * The earbuds accept a single control connection, so the tile and the panel share this
         * lock instead of racing each other for it. Held from connect() until close().
         */
        private val connectionLock = Semaphore(1)

        /** Connect, waiting up to [waitMs] for another part of the app to finish; null if it didn't. */
        fun connectOrNull(context: Context, waitMs: Long): Buds? {
            if (!connectionLock.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) return null
            try {
                return Buds(openSocket(context))
            } catch (e: Throwable) {
                connectionLock.release()
                throw e
            }
        }

        fun connect(context: Context, waitMs: Long = 4000): Buds =
            connectOrNull(context, waitMs) ?: throw BudsException("Earbuds busy, try again")

        /**
         * Whether the earbuds are connected to this phone right now (false when they're in the
         * case or off), so callers can skip a doomed connection attempt. Null if unknown.
         */
        fun isLinked(context: Context): Boolean? {
            if (!hasBluetoothPermission(context)) return null
            val device = try {
                findDevice(context)
            } catch (_: BudsException) {
                return false
            }
            return try {
                // Hidden but long-standing API; fall back to "unknown" if it ever goes away.
                device.javaClass.getMethod("isConnected").invoke(device) as Boolean
            } catch (_: ReflectiveOperationException) {
                null
            } catch (_: RuntimeException) {
                null
            }
        }

        private fun findDevice(context: Context): android.bluetooth.BluetoothDevice {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw BudsException("No Bluetooth on this device")
            if (!adapter.isEnabled) throw BudsException("Bluetooth is off")
            return adapter.bondedDevices.firstOrNull { it.name?.contains(NAME_HINT, ignoreCase = true) == true }
                ?: throw BudsException("Liberty 4 NC isn't paired")
        }

        private fun openSocket(context: Context): BluetoothSocket {
            if (!hasBluetoothPermission(context)) throw BudsException("Bluetooth permission needed")
            if (isLinked(context) == false) throw BudsException(NOT_CONNECTED)
            val device = findDevice(context)
            repeat(2) { attempt ->
                val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                try {
                    socket.connect()
                    return socket
                } catch (e: IOException) {
                    try {
                        socket.close()
                    } catch (_: IOException) {
                    }
                    if (attempt == 0) Thread.sleep(400)
                }
            }
            throw BudsException("Can't reach earbuds. Are they connected to this phone?")
        }

        private fun buildPacket(cmd: IntArray, body: ByteArray): ByteArray {
            val length = body.size + 10
            val packet = OUT_HEADER + byteArrayOf(cmd[0].toByte(), cmd[1].toByte(), length.toByte(), (length shr 8).toByte()) + body
            return packet + checksum(packet, packet.size).toByte()
        }

        private fun checksum(bytes: ByteArray, count: Int): Int {
            var sum = 0
            for (i in 0 until count) sum += bytes[i].u()
            return sum and 0xFF
        }

        private fun Byte.u(): Int = toInt() and 0xFF
    }
}
