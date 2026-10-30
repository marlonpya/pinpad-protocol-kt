package io.github.marlonpya.pinpad.simulator

import io.github.marlonpya.pinpad.Control
import io.github.marlonpya.pinpad.Frame
import io.github.marlonpya.pinpad.FrameIO
import io.github.marlonpya.pinpad.FrameResult
import io.github.marlonpya.pinpad.Link
import io.github.marlonpya.pinpad.LinkClosedException
import io.github.marlonpya.pinpad.PinpadCommand
import io.github.marlonpya.pinpad.PinpadResponse
import io.github.marlonpya.pinpad.ProtocolException

/**
 * Lado PINpad del protocolo: atiende órdenes sobre un [Link] hasta que se
 * cierre, aplicando los [Faults] configurados en el simulador.
 */
class SimulatorEndpoint(
    private val link: Link,
    private val simulator: PinpadSimulator,
    private val ackTimeoutMs: Long = 1_500,
    private val maxSends: Int = 3,
) : Runnable {

    private val io = FrameIO(link)
    private val faults get() = simulator.faults

    @Volatile
    private var running = true

    override fun run() {
        try {
            while (running) serveOne()
        } catch (_: LinkClosedException) {
            // La caja cerró o el cable se desconectó: termina la sesión.
        } finally {
            link.close()
        }
    }

    fun stop() {
        running = false
        link.close()
    }

    private fun serveOne() {
        val payload = when (val r = io.awaitFrame(IDLE_POLL_MS)) {
            FrameResult.Timeout -> return
            FrameResult.Corrupt -> return io.nak()
            is FrameResult.Received -> r.payload
        }
        if (faults.consume(faults.dropAcks)) return
        if (faults.consume(faults.nakRequests)) return io.nak()
        io.ack()

        val response = try {
            simulator.handle(PinpadCommand.decode(payload))
        } catch (_: ProtocolException) {
            PinpadResponse.Failure(PinpadResponse.Reason.UNSUPPORTED)
        }

        if (faults.responseDelayMs > 0) Thread.sleep(faults.responseDelayMs)
        if (faults.consume(faults.disconnects)) {
            running = false
            link.close()
            return
        }
        reply(response.encode())
    }

    private fun reply(payload: ByteArray) {
        repeat(maxSends) {
            val frame = Frame.encode(payload)
            if (faults.consume(faults.corruptResponses)) {
                frame[frame.size - 1] = (frame.last().toInt() xor 0xFF).toByte()
            }
            link.write(frame)
            if (io.awaitControl(ackTimeoutMs) == Control.ACK) return
        }
    }

    private companion object {
        const val IDLE_POLL_MS = 500L
    }
}
