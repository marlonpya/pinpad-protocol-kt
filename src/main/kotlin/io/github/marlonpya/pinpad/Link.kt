package io.github.marlonpya.pinpad

import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * El medio físico entre la caja y el PINpad: serial, USB, Bluetooth o TCP.
 *
 * El protocolo solo necesita escribir bytes y leer uno con plazo, así que
 * cambiar de RS232 a TCP es cambiar la implementación de esta interfaz y nada
 * más. Es la razón por la que la trama y los reintentos se prueban sin
 * hardware.
 */
interface Link : Closeable {

    fun write(bytes: ByteArray)

    /** Un byte (0..255), o [TIMEOUT] si no llegó nada en el plazo. Lanza [LinkClosedException] si el otro lado cerró. */
    fun readByte(timeoutMs: Long): Int

    companion object {
        const val TIMEOUT = -1
    }
}

/** El otro extremo cerró la conexión o se desconectó el cable. */
class LinkClosedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Enlace TCP, que es como el emulador de Android habla con el simulador. */
class SocketLink(private val socket: Socket) : Link {

    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    init {
        socket.tcpNoDelay = true
    }

    override fun write(bytes: ByteArray) {
        try {
            output.write(bytes)
            output.flush()
        } catch (e: IOException) {
            throw LinkClosedException("No se pudo escribir en el enlace", e)
        }
    }

    override fun readByte(timeoutMs: Long): Int {
        socket.soTimeout = timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        return try {
            val b = input.read()
            if (b < 0) throw LinkClosedException("El otro extremo cerró la conexión")
            b
        } catch (_: SocketTimeoutException) {
            Link.TIMEOUT
        } catch (e: LinkClosedException) {
            throw e
        } catch (e: IOException) {
            throw LinkClosedException("Se perdió la conexión", e)
        }
    }

    override fun close() = socket.close()
}

/**
 * Par de enlaces conectados en memoria, para las pruebas y para correr el
 * simulador dentro de la misma app.
 */
class PipeLink private constructor(
    private val inbox: LinkedBlockingQueue<Int>,
    private val outbox: LinkedBlockingQueue<Int>,
) : Link {

    @Volatile
    private var closed = false

    override fun write(bytes: ByteArray) {
        if (closed) throw LinkClosedException("Enlace cerrado")
        for (b in bytes) outbox.put(b.toInt() and 0xFF)
    }

    override fun readByte(timeoutMs: Long): Int {
        if (closed) throw LinkClosedException("Enlace cerrado")
        val b = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: return Link.TIMEOUT
        if (b == CLOSED_MARK) {
            closed = true
            throw LinkClosedException("El otro extremo cerró la conexión")
        }
        return b
    }

    override fun close() {
        if (closed) return
        closed = true
        outbox.put(CLOSED_MARK)
    }

    companion object {
        private const val CLOSED_MARK = Int.MIN_VALUE

        fun pair(): Pair<PipeLink, PipeLink> {
            val a = LinkedBlockingQueue<Int>()
            val b = LinkedBlockingQueue<Int>()
            return PipeLink(a, b) to PipeLink(b, a)
        }
    }
}
