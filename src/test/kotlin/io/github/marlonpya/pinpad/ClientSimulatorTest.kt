package io.github.marlonpya.pinpad

import io.github.marlonpya.dukpt.Dukpt
import io.github.marlonpya.dukpt.Ksn
import io.github.marlonpya.dukpt.PinBlock
import io.github.marlonpya.dukpt.hexToBytes
import io.github.marlonpya.iso8583.RetailMac
import io.github.marlonpya.pinpad.simulator.InitialKey
import io.github.marlonpya.pinpad.simulator.PinpadSimulator
import io.github.marlonpya.pinpad.simulator.SimulatorEndpoint
import io.github.marlonpya.pinpad.simulator.TestCard
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ClientSimulatorTest {

    private val simulator = PinpadSimulator()
    private val links = PipeLink.pair()
    private val endpoint = SimulatorEndpoint(links.second, simulator, ackTimeoutMs = 200)
    private val thread = Thread(endpoint).apply { isDaemon = true; start() }
    private val client = PinpadClient(links.first, PinpadClient.Timeouts(ackMs = 200, responseMs = 1_000, maxSends = 3))

    @AfterEach
    fun tearDown() {
        endpoint.stop()
        thread.join(1_000)
    }

    private fun sale() = client.send(PinpadCommand.Sale(2550, "604", 1))

    @Test
    @DisplayName("Una venta devuelve la tarjeta y un PIN block que el host descifra con la BDK")
    fun happyPath() {
        val card = assertIs<PinpadResponse.CardRead>(sale())
        val pin = PinBlock.decrypt(
            Dukpt.transactionKey(InitialKey.TEST_BDK, Ksn.parse(card.ksn)),
            card.pinBlock!!,
            TestCard.VISA.pan,
        )
        assertEquals("1234", pin)
        assertFalse(card.toString().contains(TestCard.VISA.pan))
    }

    @Test
    @DisplayName("Cada venta usa un KSN nuevo")
    fun ksnAdvances() {
        val a = assertIs<PinpadResponse.CardRead>(sale())
        val b = assertIs<PinpadResponse.CardRead>(sale())
        assertNotEquals(a.ksn, b.ksn)
        assertEquals(Ksn.parse(a.ksn).next(), Ksn.parse(b.ksn))
    }

    @Test
    @DisplayName("Un NAK hace que el cliente reenvíe la orden y la venta sigue")
    fun nakIsRetried() {
        simulator.faults.nakRequests.set(2)
        assertIs<PinpadResponse.CardRead>(sale())
        assertEquals(0, simulator.faults.nakRequests.get())
    }

    @Test
    @DisplayName("Un ACK perdido se recupera reenviando la misma orden")
    fun lostAckIsRetried() {
        simulator.faults.dropAcks.set(1)
        assertIs<PinpadResponse.CardRead>(sale())
    }

    @Test
    @DisplayName("Si el PINpad nunca confirma, la orden se da por no entregada")
    fun notDelivered() {
        simulator.faults.nakRequests.set(10)
        assertThrows<PinpadException.NotDelivered> { sale() }
    }

    @Test
    @DisplayName("Una respuesta con LRC dañado se pide de nuevo con NAK")
    fun corruptResponseIsRequestedAgain() {
        simulator.faults.corruptResponses.set(1)
        assertIs<PinpadResponse.CardRead>(sale())
    }

    @Test
    @DisplayName("Si la orden llegó y la respuesta no, el estado es incierto")
    fun noResponse() {
        simulator.faults.responseDelayMs = 2_000
        assertThrows<PinpadException.NoResponse> { sale() }
    }

    @Test
    @DisplayName("Si el PINpad corta la conexión, el cliente lo reporta como desconexión")
    fun disconnect() {
        simulator.faults.disconnects.set(1)
        assertThrows<PinpadException.Disconnected> { sale() }
    }

    @Test
    @DisplayName("La orden FAULT configura el simulador desde la caja")
    fun faultCommand() {
        assertEquals(PinpadResponse.Ok, client.send(PinpadCommand.InjectFault("CARD", "USER_CANCEL")))
        assertEquals(PinpadResponse.Failure(PinpadResponse.Reason.USER_CANCEL), sale())
        assertIs<PinpadResponse.CardRead>(sale())
        val unknown = client.send(PinpadCommand.InjectFault("NO_EXISTE", "1"))
        assertEquals(PinpadResponse.Failure(PinpadResponse.Reason.UNSUPPORTED), unknown)
    }

    @Test
    @DisplayName("Con otro PIN digitado, el host descifra ese otro PIN")
    fun wrongPin() {
        client.send(PinpadCommand.InjectFault("PIN", "9999"))
        val card = assertIs<PinpadResponse.CardRead>(sale())
        val pin = PinBlock.decrypt(Dukpt.transactionKey(InitialKey.TEST_BDK, Ksn.parse(card.ksn)), card.pinBlock!!, TestCard.VISA.pan)
        assertEquals("9999", pin)
    }

    @Test
    @DisplayName("El PINpad firma con la llave del KSN actual y el host verifica con la BDK")
    fun macWithCurrentKey() {
        val card = assertIs<PinpadResponse.CardRead>(sale())
        val data = "0200 mensaje empaquetado".toByteArray()
        val result = assertIs<PinpadResponse.MacResult>(client.send(PinpadCommand.Mac(data)))
        assertEquals(card.ksn, result.ksn)
        val hostKey = Dukpt.macRequestKey(Dukpt.transactionKey(InitialKey.TEST_BDK, Ksn.parse(result.ksn)))
        assertTrue(RetailMac.verify(hostKey, data, result.mac.hexToBytes()))
    }

    @Test
    @DisplayName("Una reversa avanza el KSN antes de firmar: ninguna llave se usa en dos operaciones")
    fun nextKsnBeforeMac() {
        val card = assertIs<PinpadResponse.CardRead>(sale())
        val next = assertIs<PinpadResponse.KsnInfo>(client.send(PinpadCommand.NextKsn))
        assertEquals(Ksn.parse(card.ksn).next(), Ksn.parse(next.ksn))
        val result = assertIs<PinpadResponse.MacResult>(client.send(PinpadCommand.Mac(byteArrayOf(1, 2, 3))))
        assertEquals(next.ksn, result.ksn)
    }

    @Test
    @DisplayName("Sin ninguna transacción todavía no hay llave con qué firmar")
    fun macWithoutKey() {
        assertEquals(
            PinpadResponse.Failure(PinpadResponse.Reason.UNSUPPORTED),
            client.send(PinpadCommand.Mac(byteArrayOf(1))),
        )
    }

    @Test
    @DisplayName("El estado informa el serial y el KSN actual")
    fun status() {
        val status = assertIs<PinpadResponse.StatusInfo>(client.send(PinpadCommand.Status))
        assertEquals("SIM-0001", status.serial)
        assertTrue(status.ksn.startsWith("FFFF9876543210E"))
    }
}
