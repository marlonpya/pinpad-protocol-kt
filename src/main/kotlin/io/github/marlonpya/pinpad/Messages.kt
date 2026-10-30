package io.github.marlonpya.pinpad

import io.github.marlonpya.dukpt.hexToBytes
import io.github.marlonpya.dukpt.toHex

/**
 * Órdenes de la caja al PINpad. Viajan como texto ASCII con los campos
 * separados por [Control.FS].
 *
 * Es un protocolo propio del laboratorio, con la forma típica de los
 * protocolos semi-integrados: la caja manda el monto y el PINpad devuelve la
 * tarjeta leída con el PIN ya cifrado. La caja nunca ve el PIN.
 */
sealed interface PinpadCommand {

    data class Sale(val amountCents: Long, val currency: String, val stan: Int) : PinpadCommand {
        init {
            require(amountCents > 0) { "El monto debe ser positivo" }
            require(currency.length == 3 && currency.all { it.isDigit() }) { "Moneda ISO 4217 numérica de tres dígitos" }
        }
    }

    data object Status : PinpadCommand

    /**
     * Avanza el KSN sin leer tarjeta. Se usa antes de firmar un mensaje que no
     * viene de una venta nueva, como una reversa o un aviso: cada operación
     * criptográfica usa su propia llave.
     */
    data object NextKsn : PinpadCommand

    /**
     * Pide el MAC de [data] con la variante de MAC de la llave del KSN actual.
     * La caja arma el mensaje y el PINpad lo firma: la llave no sale del PINpad.
     */
    class Mac(data: ByteArray) : PinpadCommand {
        private val bytes = data.copyOf()
        val data: ByteArray get() = bytes.copyOf()
        override fun equals(other: Any?) = other is Mac && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
        override fun toString() = "Mac(${bytes.size} bytes)"
    }

    /** Solo existe en el laboratorio: le pide al simulador que falle a propósito. */
    data class InjectFault(val name: String, val value: String) : PinpadCommand

    fun encode(): ByteArray = when (this) {
        is Sale -> fields("SALE", amountCents.toString(), currency, stan.toString())
        Status -> fields("STATUS")
        NextKsn -> fields("NEXT_KSN")
        is Mac -> fields("MAC", data.toHex())
        is InjectFault -> fields("FAULT", name, value)
    }

    companion object {
        fun decode(payload: ByteArray): PinpadCommand {
            val f = split(payload)
            return when (f[0]) {
                "SALE" -> Sale(f[1].toLong(), f[2], f[3].toInt())
                "STATUS" -> Status
                "NEXT_KSN" -> NextKsn
                "MAC" -> Mac(f[1].hexToBytes())
                "FAULT" -> InjectFault(f[1], f.getOrElse(2) { "" })
                else -> throw ProtocolException("Orden desconocida")
            }
        }
    }
}

/** Respuestas del PINpad a la caja. */
sealed interface PinpadResponse {

    /**
     * Tarjeta leída. El PIN block ya viene cifrado con la llave DUKPT de esta
     * transacción y el KSN dice al host cuál es.
     *
     * La pista 2 viaja en claro por el enlace local porque `dukpt-kt` todavía
     * no tiene la variante de cifrado de datos. En un PINpad real va cifrada
     * (o el PINpad habla directo con el host y la caja no la ve nunca).
     */
    class CardRead(
        val entryMode: String,
        val track2: String,
        val cardSequence: String,
        val emv: ByteArray,
        val ksn: String,
        val pinBlock: ByteArray?,
    ) : PinpadResponse {
        val maskedPan: String
            get() = track2.takeWhile { it.isDigit() }.let { it.take(6) + "*".repeat((it.length - 10).coerceAtLeast(0)) + it.takeLast(4) }

        override fun toString() =
            "CardRead(pan=$maskedPan, modo=$entryMode, ksn=$ksn, pin=${if (pinBlock != null) "cifrado" else "sin PIN"}, emv=${emv.size} bytes)"
    }

    data class Failure(val reason: Reason) : PinpadResponse

    data class StatusInfo(val serial: String, val ksn: String) : PinpadResponse

    data class KsnInfo(val ksn: String) : PinpadResponse

    /** El MAC y el KSN de la llave con que se calculó. */
    data class MacResult(val ksn: String, val mac: String) : PinpadResponse

    data object Ok : PinpadResponse

    enum class Reason { CARD_REMOVED, USER_CANCEL, CARD_TIMEOUT, KEY_EXHAUSTED, UNSUPPORTED }

    fun encode(): ByteArray = when (this) {
        is CardRead -> fields("CARD", entryMode, track2, cardSequence, emv.toHex(), ksn, pinBlock?.toHex() ?: "")
        is Failure -> fields("FAIL", reason.name)
        is StatusInfo -> fields("STATUS", serial, ksn)
        is KsnInfo -> fields("KSN", ksn)
        is MacResult -> fields("MACRES", ksn, mac)
        Ok -> fields("OK")
    }

    companion object {
        fun decode(payload: ByteArray): PinpadResponse {
            val f = split(payload)
            return when (f[0]) {
                "CARD" -> CardRead(
                    entryMode = f[1],
                    track2 = f[2],
                    cardSequence = f[3],
                    emv = f[4].hexToBytes(),
                    ksn = f[5],
                    pinBlock = f.getOrElse(6) { "" }.takeIf { it.isNotEmpty() }?.hexToBytes(),
                )
                "FAIL" -> Failure(Reason.valueOf(f[1]))
                "STATUS" -> StatusInfo(f[1], f[2])
                "KSN" -> KsnInfo(f[1])
                "MACRES" -> MacResult(f[1], f[2])
                "OK" -> Ok
                else -> throw ProtocolException("Respuesta desconocida")
            }
        }
    }
}

/** La trama llegó bien pero su contenido no se entiende. */
class ProtocolException(message: String) : IllegalArgumentException(message)

private fun fields(vararg values: String): ByteArray =
    values.joinToString(Control.FS.toChar().toString()).toByteArray(Charsets.US_ASCII)

private fun split(payload: ByteArray): List<String> {
    val parts = String(payload, Charsets.US_ASCII).split(Control.FS.toChar())
    if (parts.isEmpty() || parts[0].isEmpty()) throw ProtocolException("Trama vacía")
    return parts
}
