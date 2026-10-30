package io.github.marlonpya.pinpad.simulator

import java.net.ServerSocket
import io.github.marlonpya.pinpad.SocketLink

/**
 * Simulador de PINpad por TCP, para que la app Android (o cualquier caja) le
 * hable como a un terminal real.
 *
 * Desde el emulador de Android el host es `10.0.2.2`, así que la app se
 * conecta a `10.0.2.2:9100`.
 *
 *     ./gradlew run                 # puerto 9100
 *     ./gradlew run --args="9200"   # otro puerto
 */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: 9100
    val simulator = PinpadSimulator()
    ServerSocket(port).use { server ->
        println("Simulador de PINpad ${simulator.serial} escuchando en el puerto $port")
        println("Llave de prueba inyectada; KSN inicial ${InitialKey.TEST_KSN}")
        while (true) {
            val socket = server.accept()
            println("Caja conectada desde ${socket.remoteSocketAddress}")
            // Una caja a la vez, como un PINpad real conectado por cable.
            SimulatorEndpoint(SocketLink(socket), simulator).run()
            println("Caja desconectada")
        }
    }
}
