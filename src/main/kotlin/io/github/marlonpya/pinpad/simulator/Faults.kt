package io.github.marlonpya.pinpad.simulator

import io.github.marlonpya.pinpad.PinpadResponse.Reason
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Fallos que el simulador produce a propósito: el "panel de caos".
 *
 * Los contadores son "las próximas N veces": inyectar `NAK 1` hace que la
 * siguiente orden reciba NAK y la de después ya no. Así cada fallo se puede
 * provocar, observar y probar de forma determinista.
 */
class Faults {
    /** Órdenes que se reciben y no se confirman: el cliente debe reenviarlas. */
    val dropAcks = AtomicInteger()

    /** Órdenes que se rechazan con NAK. */
    val nakRequests = AtomicInteger()

    /** Respuestas que salen con el LRC dañado. */
    val corruptResponses = AtomicInteger()

    /** Veces que el simulador corta la conexión justo antes de responder. */
    val disconnects = AtomicInteger()

    @Volatile
    var responseDelayMs: Long = 0

    private val cardOutcome = AtomicReference<Reason?>(null)

    fun consume(counter: AtomicInteger): Boolean {
        while (true) {
            val n = counter.get()
            if (n <= 0) return false
            if (counter.compareAndSet(n, n - 1)) return true
        }
    }

    /** El resultado forzado de la próxima lectura de tarjeta, si lo hay. */
    fun takeCardOutcome(): Reason? = cardOutcome.getAndSet(null)

    fun reset() {
        dropAcks.set(0)
        nakRequests.set(0)
        corruptResponses.set(0)
        disconnects.set(0)
        responseDelayMs = 0
        cardOutcome.set(null)
    }

    /**
     * Aplica un fallo por nombre, que es como llega desde la orden FAULT.
     * Devuelve `false` si el nombre no existe.
     */
    fun apply(name: String, value: String, simulator: PinpadSimulator): Boolean {
        when (name) {
            "DROP_ACK" -> dropAcks.set(value.toInt())
            "NAK" -> nakRequests.set(value.toInt())
            "CORRUPT" -> corruptResponses.set(value.toInt())
            "DISCONNECT" -> disconnects.set(value.toInt())
            "DELAY" -> responseDelayMs = value.toLong()
            "CARD" -> cardOutcome.set(Reason.valueOf(value))
            "PIN" -> simulator.pin = value
            "CARD_BRAND" -> simulator.card = if (value == "MASTERCARD") TestCard.MASTERCARD else TestCard.VISA
            "RESET" -> {
                reset()
                simulator.pin = "1234"
                simulator.card = TestCard.VISA
            }
            else -> return false
        }
        return true
    }

    override fun toString() =
        "Faults(dropAcks=$dropAcks, nak=$nakRequests, corrupt=$corruptResponses, disconnects=$disconnects, delay=${responseDelayMs}ms)"
}
