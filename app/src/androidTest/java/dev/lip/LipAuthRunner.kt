package dev.lip

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import dev.lip.auth.ChatGptClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import kotlin.concurrent.thread

/** Test-only browser bridge. Auth URLs stay in memory; Lip retains its own encrypted credentials. */
class LipAuthRunner : Instrumentation() {
    private val lock = Any()
    @Volatile private var running = true
    private var state = "initializing"
    private var authorizationUrl: String? = null
    private var callbackPort: Int? = null
    private var signedIn = false
    private var eligible: Boolean? = null
    private var modelSlugs = emptyList<String>()
    private var catalog = "not_requested"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (arguments?.getString("isolated_emulator") != "true") {
            state = "isolation_confirmation_required"
            finish(Activity.RESULT_CANCELED, result())
        } else start()
    }

    override fun onStart() {
        var bridge: ServerSocket? = null
        var client: ChatGptClient? = null
        var worker: Thread? = null
        try {
            check(targetContext.packageName == "dev.lip.android")
            val accountClient = ChatGptClient(targetContext).also { client = it }
            val listener = ServerSocket(BRIDGE_PORT, 4, InetAddress.getByName("127.0.0.1"))
                .apply { soTimeout = 1_000 }.also { bridge = it }
            val deadline = System.nanoTime() + 240_000_000_000L
            worker = thread(isDaemon = true, name = "lip-auth-validation") {
                try {
                    val session = accountClient.beginSignIn { url ->
                        val auth = URI(url)
                        check(url.length <= 32_768 && url.all { it.code in 33..126 })
                        check(auth.scheme == "https" && auth.host == "auth.openai.com" && auth.port == -1 &&
                            auth.rawUserInfo == null && auth.rawPath == "/api/accounts/authorize" && auth.rawFragment == null)
                        val redirects = auth.rawQuery.split('&').map { it.split('=', limit = 2) }
                            .filter { it.size == 2 && it[0] == "redirect_uri" }
                        check(redirects.size == 1)
                        val callback = URI(URLDecoder.decode(redirects.single()[1], "UTF-8"))
                        check(callback.scheme == "http" && callback.host == "127.0.0.1" && callback.port in 1..65_535 &&
                            callback.rawPath == "/auth/callback" && callback.rawUserInfo == null &&
                            callback.rawQuery == null && callback.rawFragment == null)
                        synchronized(lock) {
                            check(running)
                            authorizationUrl = url; callbackPort = callback.port; state = "ready"
                        }
                        sendStatus(1, result())
                    }
                    synchronized(lock) {
                        authorizationUrl = null
                        if (running) {
                            signedIn = true; eligible = session.canUsePlan
                            state = if (session.canUsePlan) "connected" else "identity_only"
                        }
                    }
                    if (running && session.canUsePlan) {
                        try {
                            val models = accountClient.listModels().map { it.slug }
                                .filter { it.matches(Regex("[A-Za-z0-9._:/-]{1,200}")) }.take(64)
                            synchronized(lock) { modelSlugs = models; catalog = "complete" }
                        } catch (_: Exception) { synchronized(lock) { catalog = "unavailable" } }
                    }
                } catch (_: Exception) {
                    synchronized(lock) { authorizationUrl = null; if (running) state = "oauth_failed" }
                }
            }
            sendStatus(1, result())
            while (running && System.nanoTime() < deadline) {
                val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                socket.use { handle(it) }
            }
            if (running) synchronized(lock) { state = "expired" }
        } catch (_: Exception) { synchronized(lock) { state = "bridge_failed" } }
        finally {
            running = false
            synchronized(lock) { authorizationUrl = null }
            runCatching { bridge?.close() }
            runCatching { client?.cancelSignIn() }
            worker?.interrupt()
            try { worker?.join(3_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            finish(if (synchronized(lock) { signedIn }) Activity.RESULT_OK else Activity.RESULT_CANCELED, result())
        }
    }

    private fun handle(socket: Socket) {
        try {
            check(socket.inetAddress.isLoopbackAddress)
            socket.soTimeout = 2_000
            socket.sendBufferSize = 65_536
            val deadline = System.nanoTime() + 2_000_000_000L
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            val first = line(reader, deadline).split(' ')
            check(first.size == 3 && first[2] in listOf("HTTP/1.0", "HTTP/1.1"))
            val headers = mutableMapOf<String, String>()
            var chars = 0
            while (true) {
                val header = line(reader, deadline)
                chars += header.length + 2
                check(chars <= 16_384)
                if (header.isEmpty()) break
                val pair = header.split(':', limit = 2)
                check(pair.size == 2)
                val name = pair[0].lowercase(Locale.ROOT)
                check(name.matches(Regex("[a-z0-9-]{1,80}")) && headers.put(name, pair[1].trim()) == null)
            }
            check(headers["host"] in listOf("127.0.0.1:$BRIDGE_PORT", "localhost:$BRIDGE_PORT"))
            check(headers["transfer-encoding"] == null && (headers["content-length"] ?: "0") == "0")
            check(headers["origin"] == null || headers["origin"] in ORIGINS)
            headers["referer"]?.let { value ->
                val uri = URI(value)
                check(uri.scheme == "http" && uri.host in listOf("127.0.0.1", "localhost") && uri.port == BRIDGE_PORT)
            }
            check(headers["sec-fetch-site"] != "cross-site")
            when (first[0] to first[1]) {
                "GET" to "/status" -> reply(socket, "200 OK", status().toString(), "application/json")
                "GET" to "/start" -> {
                    check(headers["sec-fetch-dest"] == null || headers["sec-fetch-dest"] == "document")
                    check(headers["sec-fetch-mode"] == null || headers["sec-fetch-mode"] == "navigate")
                    val url = synchronized(lock) {
                        authorizationUrl?.also { authorizationUrl = null; state = "authorization_started" }
                    }
                    if (url == null) reply(socket, "410 Gone", "No pending browser authorization.")
                    else reply(socket, "302 Found", "", location = url)
                }
                "POST" to "/cancel" -> {
                    synchronized(lock) { state = "cancelled"; authorizationUrl = null; running = false }
                    reply(socket, "200 OK", "Bridge cancelled; existing Lip data is preserved.")
                }
                else -> reply(socket, "404 Not Found", "Unknown bridge request.")
            }
        } catch (_: Exception) { runCatching { reply(socket, "400 Bad Request", "Invalid bridge request.") } }
    }

    private fun line(reader: BufferedReader, deadline: Long): String {
        val value = StringBuilder()
        repeat(8_192) {
            check(System.nanoTime() < deadline)
            val next = reader.read()
            check(next != -1)
            if (next == '\n'.code) return value.toString().removeSuffix("\r")
            value.append(next.toChar())
        }
        error("Bridge request exceeded its safe limit")
    }

    private fun reply(socket: Socket, code: String, body: String, type: String = "text/plain", location: String? = null) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $code\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            "Connection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
            "Content-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\nX-Content-Type-Options: nosniff\r\n" +
            (location?.let { "Location: $it\r\n" } ?: "") + "\r\n"
        val packet = headers.toByteArray(Charsets.US_ASCII) + bytes
        check(packet.size <= socket.sendBufferSize)
        socket.getOutputStream().write(packet)
    }

    private fun status() = synchronized(lock) {
        JSONObject().put("state", state).put("bridge_port", BRIDGE_PORT).put("signed_in", signedIn)
            .put("catalog", catalog).put("model_slugs", JSONArray(modelSlugs)).also { value ->
                callbackPort?.let { value.put("callback_port", it) }
                eligible?.let { value.put("plan_eligible", it) }
            }
    }
    private fun result() = Bundle().apply { putString("lip_auth_status", status().toString()) }

    companion object {
        private const val BRIDGE_PORT = 18555
        private val ORIGINS = listOf("http://127.0.0.1:$BRIDGE_PORT", "http://localhost:$BRIDGE_PORT")
    }
}
