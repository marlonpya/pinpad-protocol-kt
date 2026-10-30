package io.github.marlonpya.pinpad

/**
 * Lado caja del protocolo: manda una orden y espera la respuesta, con los
 * reintentos del enlace.
 *
 * Las reglas, en orden:
 *  1. Se manda la trama y se espera ACK. Con NAK o sin respuesta en
 *     [Timeouts.ackMs], se reenvía la misma trama, hasta [Timeouts.maxSends].
 *  2. Con ACK, el PINpad ya tiene la orden. Se espera la respuesta hasta
 *     [Timeouts.responseMs], que es largo porque incluye que el cliente
 *     inserte la tarjeta y digite el PIN.
 *  3. Si la respuesta llega con el LRC mal, se responde NAK y el PINpad la
 *     reenvía. Si llega bien, ACK.
 *
 * Lo importante es qué excepción sale en cada caso, porque deciden cosas
 * distintas: [PinpadException.NotDelivered] significa que el PINpad nunca
 * recibió la orden; [PinpadException.NoResponse], que la recibió y no sabemos
 * qué hizo.
 */
class PinpadClient(link: Link, private val timeouts: Timeouts = Timeouts()) {

    data class Timeouts(
        val ackMs: Long = 1_500,
        val responseMs: Long = 30_000,
        val maxSends: Int = 3,
    )

    private val io = FrameIO(link)

    @Synchronized
    fun send(command: PinpadCommand): PinpadResponse {
        try {
            deliver(command.encode())
            return receive()
        } catch (e: LinkClosedException) {
            throw PinpadException.Disconnected(e)
        }
    }

    private fun deliver(payload: ByteArray) {
        repeat(timeouts.maxSends) {
            io.send(payload)
            if (io.awaitControl(timeouts.ackMs) == Control.ACK) return
        }
        throw PinpadException.NotDelivered(timeouts.maxSends)
    }

    private fun receive(): PinpadResponse {
        var corrupt = 0
        val deadline = System.currentTimeMillis() + timeouts.responseMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw PinpadException.NoResponse(timeouts.responseMs)
            when (val r = io.awaitFrame(remaining)) {
                is FrameResult.Received -> {
                    io.ack()
                    return PinpadResponse.decode(r.payload)
                }
                FrameResult.Corrupt -> {
                    if (++corrupt >= timeouts.maxSends) throw PinpadException.Corrupted(corrupt)
                    io.nak()
                }
                FrameResult.Timeout -> throw PinpadException.NoResponse(timeouts.responseMs)
            }
        }
    }
}

sealed class PinpadException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** El PINpad no confirmó la orden: es seguro reintentarla desde cero. */
    class NotDelivered(sends: Int) : PinpadException("El PINpad no confirmó la orden después de $sends envíos")

    /** El PINpad confirmó la orden pero no respondió a tiempo. */
    class NoResponse(timeoutMs: Long) : PinpadException("El PINpad no respondió en $timeoutMs ms")

    /** La respuesta llegó dañada demasiadas veces seguidas. */
    class Corrupted(times: Int) : PinpadException("La respuesta llegó dañada $times veces")

    class Disconnected(cause: Throwable) : PinpadException("Se perdió el enlace con el PINpad", cause)
}
