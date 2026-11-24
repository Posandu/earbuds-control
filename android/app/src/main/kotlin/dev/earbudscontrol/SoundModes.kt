package dev.earbudscontrol

/**
 * The 7 sound mode bytes of the Liberty 4 NC:
 *   0 ambient mode       (0 noise cancelling, 1 transparency, 2 off)
 *   1 NC strength        (manual level << 4 | adaptive level)
 *   2 transparency mode  (0 full, 1 vocal)
 *   3 NC mode            (0 manual, 1 adaptive, 2 transport)
 *   4 wind noise reduction
 *   5 environment detection
 *   6 transport          (0 plane, 1 train, 2 bus, 3 car)
 * Immutable: every `with*` returns a modified copy.
 */
class SoundModes(source: ByteArray) {
    val bytes: ByteArray = source.copyOf(7)

    val ambient: Ambient? get() = Ambient.of(u(0))
    val ncMode: NcMode? get() = NcMode.entries.firstOrNull { it.byte == u(3) }
    val manualLevel: Int get() = (u(1) shr 4).coerceIn(1, 5)
    val transparency: TransparencyMode? get() = TransparencyMode.entries.firstOrNull { it.byte == u(2) }
    val transport: Transport? get() = Transport.entries.firstOrNull { it.byte == u(6) }

    fun withAmbient(value: Ambient) = with(0, value.byte)
    fun withNcMode(value: NcMode) = with(3, value.byte)
    fun withManualLevel(level: Int) = with(1, (level.coerceIn(1, 5) shl 4) or (u(1) and 0x0F))
    fun withTransparency(value: TransparencyMode) = with(2, value.byte)
    fun withTransport(value: Transport) = with(6, value.byte)

    private fun u(index: Int) = bytes[index].toInt() and 0xFF

    private fun with(index: Int, value: Int): SoundModes {
        val copy = bytes.copyOf()
        copy[index] = value.toByte()
        return SoundModes(copy)
    }
}

enum class NcMode(val byte: Int, val label: String) {
    ADAPTIVE(1, "Adaptive"),
    MANUAL(0, "Manual"),
    TRANSPORT(2, "Transport"),
}

enum class TransparencyMode(val byte: Int, val label: String) {
    FULL(0, "Full"),
    VOCAL(1, "Vocal"),
}

enum class Transport(val byte: Int, val label: String) {
    PLANE(0, "Plane"),
    TRAIN(1, "Train"),
    BUS(2, "Bus"),
    CAR(3, "Car"),
}
