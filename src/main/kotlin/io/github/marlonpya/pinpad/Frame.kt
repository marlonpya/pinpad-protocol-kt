package io.github.marlonpya.pinpad

/**
 * Bytes de control del enlace. Son los de la tabla ASCII, los mismos que usan
 * desde hace décadas los protocolos de caja (ECR) con su PINpad por RS232.
 */
object Control {
    const val STX = 0x02
    const val ETX = 0x03
    const val ACK = 0x06
    const val NAK = 0x15

    /** Separador de campos dentro de la trama (File Separator). */
    const val FS = 0x1C
}

/**
 * Una trama: `STX` + datos + `ETX` + `LRC`.
 *
 * El LRC es el XOR de todos los bytes de datos y del `ETX` (no del `STX`). Es
 * un control de integridad del enlace, no de seguridad: detecta ruido en un
 * cable serial, no a un atacante. La integridad de la transacción la da el
 * MAC del mensaje ISO 8583.
 */
object Frame {

    const val MAX_PAYLOAD = 4096

    fun encode(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "Trama demasiado larga: ${payload.size} bytes" }
        require(payload.none { it.toInt() == Control.STX || it.toInt() == Control.ETX }) {
            "Los datos no pueden contener STX ni ETX"
        }
        return byteArrayOf(Control.STX.toByte()) + payload + Control.ETX.toByte() + lrc(payload)
    }

    fun lrc(payload: ByteArray): Byte {
        var x = Control.ETX
        for (b in payload) x = x xor (b.toInt() and 0xFF)
        return x.toByte()
    }
}

/** Lo que puede pasar al esperar una trama. */
sealed interface FrameResult {
    class Received(val payload: ByteArray) : FrameResult

    /** Llegó una trama con el LRC equivocado: se responde NAK y el otro lado la reenvía. */
    data object Corrupt : FrameResult

    data object Timeout : FrameResult
}

/** Lectura de tramas y bytes de control sobre un [Link], con plazos. */
class FrameIO(private val link: Link) {

    fun send(payload: ByteArray) = link.write(Frame.encode(payload))

    fun ack() = link.write(byteArrayOf(Control.ACK.toByte()))

    fun nak() = link.write(byteArrayOf(Control.NAK.toByte()))

    /**
     * Espera un ACK o un NAK. Cualquier otro byte es ruido y se descarta.
     * Devuelve `null` si se cumple el plazo.
     */
    fun awaitControl(timeoutMs: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            val b = link.readByte(remaining)
            if (b == Link.TIMEOUT) return null
            if (b == Control.ACK || b == Control.NAK) return b
        }
    }

    fun awaitFrame(timeoutMs: Long): FrameResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        fun next(): Int {
            val remaining = deadline - System.currentTimeMillis()
            return if (remaining <= 0) Link.TIMEOUT else link.readByte(remaining)
        }

        var b: Int
        do {
            b = next()
            if (b == Link.TIMEOUT) return FrameResult.Timeout
        } while (b != Control.STX)

        val payload = java.io.ByteArrayOutputStream()
        while (true) {
            b = next()
            if (b == Link.TIMEOUT) return FrameResult.Timeout
            if (b == Control.ETX) break
            if (b == Control.STX) payload.reset() // trama nueva: la anterior se cortó
            else payload.write(b)
            if (payload.size() > Frame.MAX_PAYLOAD) return FrameResult.Corrupt
        }
        val lrc = next()
        if (lrc == Link.TIMEOUT) return FrameResult.Timeout
        val data = payload.toByteArray()
        return if (Frame.lrc(data) == lrc.toByte()) FrameResult.Received(data) else FrameResult.Corrupt
    }
}
