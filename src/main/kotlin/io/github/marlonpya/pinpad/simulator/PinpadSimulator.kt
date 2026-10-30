package io.github.marlonpya.pinpad.simulator

import io.github.marlonpya.dukpt.Dukpt
import io.github.marlonpya.dukpt.Ksn
import io.github.marlonpya.dukpt.KsnExhaustedException
import io.github.marlonpya.dukpt.PinBlock
import io.github.marlonpya.dukpt.hexToBytes
import io.github.marlonpya.dukpt.toHex
import io.github.marlonpya.iso8583.RetailMac
import io.github.marlonpya.pinpad.PinpadCommand
import io.github.marlonpya.pinpad.PinpadResponse
import io.github.marlonpya.pinpad.PinpadResponse.Reason
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/** Tarjeta de prueba: los números son los públicos de sandbox, no corresponden a nadie. */
data class TestCard(val pan: String, val expiry: String, val serviceCode: String, val aid: String) {
    val track2: String get() = "$pan=$expiry$serviceCode" + "0".repeat(37 - pan.length - 8)

    companion object {
        val VISA = TestCard("4111111111111111", "3012", "201", "A0000000031010")
        val MASTERCARD = TestCard("5555555555554444", "3012", "201", "A0000000041010")
    }
}

/** Lo que entrega el KIF al terminal: su IPEK y el KSN con el contador en cero. */
class InitialKey(ipek: ByteArray, val initialKsn: Ksn) {
    internal val ipek: ByteArray = ipek.copyOf()

    companion object {
        /**
         * Simula la ceremonia de inyección. En la vida real esto pasa en un
         * cuarto seguro, con la BDK dentro de un HSM; aquí la BDK es la de
         * prueba del estándar.
         */
        fun inject(bdk: ByteArray, ksn: Ksn): InitialKey = InitialKey(Dukpt.deriveIpek(bdk, ksn), ksn)

        /** BDK de prueba de ANSI X9.24-1. Nunca una real en un repositorio. */
        val TEST_BDK: ByteArray = "0123456789ABCDEFFEDCBA9876543210".hexToBytes()
        val TEST_KSN: Ksn = Ksn.parse("FFFF9876543210E00000")

        fun forTests(): InitialKey = inject(TEST_BDK, TEST_KSN)
    }
}

/**
 * La lógica de un PINpad sin el enlace: lee la "tarjeta", "captura" el PIN y
 * lo cifra con la llave DUKPT de la transacción.
 *
 * El criptograma EMV (9F26) es aleatorio: el simulador no tiene las llaves de
 * la tarjeta, así que no puede producir un ARQC real y no lo intenta. El
 * gateway del laboratorio no lo valida.
 */
class PinpadSimulator(
    initialKey: InitialKey = InitialKey.forTests(),
    val serial: String = "SIM-0001",
    val faults: Faults = Faults(),
    private val random: Random = Random.Default,
) {
    private val ipek = initialKey.ipek
    private var ksn: Ksn = initialKey.initialKsn
    private var atc = 0

    @Volatile
    var card: TestCard = TestCard.VISA

    /** El PIN que "digita" el cliente. */
    @Volatile
    var pin: String = "1234"

    @Synchronized
    fun handle(command: PinpadCommand): PinpadResponse = when (command) {
        is PinpadCommand.Sale -> sale(command)
        PinpadCommand.Status -> PinpadResponse.StatusInfo(serial, ksn.toHex())
        PinpadCommand.NextKsn -> advance()?.let { PinpadResponse.KsnInfo(it.toHex()) }
            ?: PinpadResponse.Failure(Reason.KEY_EXHAUSTED)
        is PinpadCommand.Mac -> mac(command.data)
        is PinpadCommand.InjectFault -> if (faults.apply(command.name, command.value, this)) {
            PinpadResponse.Ok
        } else {
            PinpadResponse.Failure(Reason.UNSUPPORTED)
        }
    }

    private fun sale(sale: PinpadCommand.Sale): PinpadResponse {
        faults.takeCardOutcome()?.let { return PinpadResponse.Failure(it) }
        advance() ?: return PinpadResponse.Failure(Reason.KEY_EXHAUSTED)
        val key = Dukpt.deriveTransactionKey(ipek, ksn)
        val pinBlock = PinBlock.encrypt(key, pin, card.pan)
        key.fill(0)
        atc++
        return PinpadResponse.CardRead(
            entryMode = "051",
            track2 = card.track2,
            cardSequence = "001",
            emv = emvData(sale),
            ksn = ksn.toHex(),
            pinBlock = pinBlock,
        )
    }

    private fun advance(): Ksn? = try {
        ksn.next().also { ksn = it }
    } catch (_: KsnExhaustedException) {
        null
    }

    private fun mac(data: ByteArray): PinpadResponse {
        if (ksn.counter == 0) return PinpadResponse.Failure(Reason.UNSUPPORTED)
        val key = Dukpt.macRequestKey(Dukpt.deriveTransactionKey(ipek, ksn))
        val mac = RetailMac.compute(key, data)
        key.fill(0)
        return PinpadResponse.MacResult(ksn.toHex(), mac.toHex())
    }

    private fun emvData(sale: PinpadCommand.Sale): ByteArray {
        val out = ByteArrayOutputStream()
        fun tlv(tag: String, value: ByteArray) {
            out.write(tag.hexToBytes())
            out.write(value.size)
            out.write(value)
        }
        tlv("9F02", sale.amountCents.toString().padStart(12, '0').hexToBytes())
        tlv("5F2A", ("0" + sale.currency).hexToBytes())
        tlv("9A", LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd")).hexToBytes())
        tlv("9C", byteArrayOf(0x00))
        tlv("84", card.aid.hexToBytes())
        tlv("95", ByteArray(5))
        tlv("9F36", byteArrayOf((atc ushr 8).toByte(), atc.toByte()))
        tlv("9F37", random.nextBytes(4))
        tlv("9F26", random.nextBytes(8))
        return out.toByteArray()
    }
}
