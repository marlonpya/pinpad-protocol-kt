package io.github.marlonpya.pinpad

import io.github.marlonpya.dukpt.toHex
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FrameTest {

    @Test
    @DisplayName("La trama es STX, datos, ETX y el LRC de datos más ETX")
    fun encoding() {
        // "AB": 0x41 xor 0x42 xor 0x03 = 0x00
        assertEquals("024142" + "03" + "00", Frame.encode("AB".toByteArray()).toHex())
        // "A": 0x41 xor 0x03 = 0x42
        assertEquals("02410342", Frame.encode("A".toByteArray()).toHex())
    }

    @Test
    @DisplayName("Los datos no pueden contener STX ni ETX")
    fun forbiddenBytes() {
        assertThrows<IllegalArgumentException> { Frame.encode(byteArrayOf(0x41, 0x03)) }
    }

    @Test
    @DisplayName("Una trama se lee completa aunque venga con ruido antes")
    fun readsWithNoise() {
        val (a, b) = PipeLink.pair()
        a.write(byteArrayOf(0x7F, 0x00) + Frame.encode("HOLA".toByteArray()))
        val r = FrameIO(b).awaitFrame(500)
        assertIs<FrameResult.Received>(r)
        assertContentEquals("HOLA".toByteArray(), r.payload)
    }

    @Test
    @DisplayName("Un LRC alterado se detecta como trama dañada")
    fun corruptLrc() {
        val (a, b) = PipeLink.pair()
        val frame = Frame.encode("HOLA".toByteArray())
        frame[frame.size - 1] = (frame.last().toInt() xor 1).toByte()
        a.write(frame)
        assertEquals(FrameResult.Corrupt, FrameIO(b).awaitFrame(500))
    }

    @Test
    @DisplayName("Una trama cortada a la mitad termina en timeout")
    fun truncatedFrame() {
        val (a, b) = PipeLink.pair()
        a.write(Frame.encode("HOLA".toByteArray()).copyOf(3))
        assertEquals(FrameResult.Timeout, FrameIO(b).awaitFrame(200))
    }

    @Test
    @DisplayName("Las órdenes y respuestas se codifican y decodifican igual")
    fun messagesRoundTrip() {
        val sale = PinpadCommand.Sale(2550, "604", 7)
        assertEquals(sale, PinpadCommand.decode(sale.encode()))
        val fail = PinpadResponse.Failure(PinpadResponse.Reason.USER_CANCEL)
        assertEquals(fail, PinpadResponse.decode(fail.encode()))
        val card = PinpadResponse.CardRead("051", "4111111111111111=3012201", "001", byteArrayOf(1, 2), "FFFF9876543210E00001", null)
        val decoded = PinpadResponse.decode(card.encode())
        assertIs<PinpadResponse.CardRead>(decoded)
        assertEquals(null, decoded.pinBlock)
        assertEquals("411111******1111", decoded.maskedPan)
    }
}
