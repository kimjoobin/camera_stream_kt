package com.example.camera_streaming_kt

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UDPSender(private val serverIP: String, private val serverPort: Int) {

    private val socket = DatagramSocket()

    fun send(data: ByteArray) {
        Thread {
            try {
                val address = InetAddress.getByName(serverIP)
                val socket = DatagramSocket()

                val packet = DatagramPacket(data, data.size, address, serverPort)
                socket.send(packet)

                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}