package com.lagdaemon.creationremote.auth

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URLDecoder

/**
 * Minimal one-shot HTTP listener on 127.0.0.1, mirroring the desktop suite's
 * SuiteDesktopAuthSession loopback listener (RFC 8252 native-app OAuth
 * pattern) so this app can reuse the exact same server-side desktop OAuth
 * flow -- DesktopAuthRepository.isLoopbackRedirect only accepts loopback
 * addresses, so this is the supported approach on Android too, not a
 * custom URI scheme redirect.
 */
class LoopbackServer {
    private val serverSocket = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))

    val port: Int get() = serverSocket.localPort

    /** Blocks until one request arrives, then returns its query parameters. */
    fun awaitRedirect(): Map<String, String> {
        serverSocket.use { server ->
            server.accept().use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: ""
                // "GET /?code=...&state=... HTTP/1.1"
                val path = requestLine.split(" ").getOrElse(1) { "/" }
                val query = path.substringAfter("?", "")
                val params = query.split("&").filter { it.isNotBlank() }.associate {
                    val (key, value) = it.split("=", limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
                    URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
                }

                val body = "<html><body><h3>Signed in.</h3><p>You can close this tab and return to Creation Remote.</p></body></html>"
                val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                socket.getOutputStream().write(response.toByteArray())
                socket.getOutputStream().flush()

                return params
            }
        }
    }

    fun close() {
        if (!serverSocket.isClosed) serverSocket.close()
    }
}
