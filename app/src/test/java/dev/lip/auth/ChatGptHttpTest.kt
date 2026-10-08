package dev.lip.auth

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.io.InputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Real loopback HTTP; every credential and response is a synthetic fixture. */
class ChatGptHttpTest {
    @Test fun refreshRotatesCredentialsBeforeLoadingOrderedVisibleModels() {
        Fixture(record(expired = true)).use { fixture ->
            fixture.reply(200, JSONObject().put("access_token", "fixture-rotated-access")
                .put("refresh_token", "fixture-rotated-refresh").put("token_type", "Bearer")
                .put("expires_in", 3600).put("scope", "chatgpt.tokens.use.direct").toString())
            fixture.reply(200, """{"models":[{"slug":"b","display_name":"Second","visibility":"list"},{"slug":"hidden","display_name":"Hidden","visibility":"hide"},{"slug":"a","display_name":"First","visibility":"list"}]}""")
            assertEquals(listOf(Model("b", "Second"), Model("a", "First")), fixture.client.listModels())
            val saved = JSONObject(fixture.saved!!)
            assertEquals("fixture-rotated-access", saved.getString("access_token"))
            assertEquals("fixture-rotated-refresh", saved.getString("refresh_token"))
            assertTrue(saved.getLong("expires_at") > System.currentTimeMillis() + 60_000)
            assertEquals("fixture-client", saved.getString("client_id"))
            assertEquals(2, fixture.requests.size)
            val refresh = fixture.requests[0]
            assertEquals("POST", refresh.method)
            assertEquals("/api/accounts/oauth/token", refresh.path)
            assertEquals("application/x-www-form-urlencoded", refresh.contentType)
            assertNull(refresh.authorization)
            assertEquals(mapOf("grant_type" to "refresh_token", "client_id" to "fixture-client",
                "refresh_token" to "fixture-refresh", "resource" to "https://api.openai.com/v1"), form(refresh.body))
            val models = fixture.requests[1]
            assertEquals("GET", models.method)
            assertEquals("/v1/models", models.path)
            assertEquals("Bearer fixture-rotated-access", models.authorization)
            assertEquals("", models.body)
        }
    }

    @Test fun terminalRefreshClearsTokensButTransientAndClientErrorsPreserveThem() {
        for ((status, code) in listOf(400 to "invalid_grant", 400 to "invalid_refresh_token",
            503 to "subscription_sharing_usage_unavailable", 400 to "invalid_client")) {
            Fixture(record(expired = true)).use { fixture ->
                val previous = fixture.saved
                fixture.reply(status, JSONObject().put("error", code).toString())
                rejects { fixture.client.listModels() }
                assertEquals(1, fixture.requests.size)
                if (code in listOf("invalid_grant", "invalid_refresh_token")) assertTokensCleared(fixture)
                else assertEquals(previous, fixture.saved)
            }
        }
    }

    @Test fun revocationRetriesAreBoundedAndLocalTokensAreAlwaysCleared() {
        for (statuses in listOf(listOf(503, 200), listOf(503, 503), listOf(400))) {
            Fixture(record()).use { fixture ->
                statuses.forEach { fixture.reply(it, if (it == 200) "" else "{}") }
                assertEquals(statuses.last() == 200, fixture.client.signOut())
                assertEquals(statuses.size, fixture.requests.size)
                fixture.requests.forEach { request ->
                    assertEquals("POST", request.method)
                    assertEquals("/api/accounts/oauth/revoke", request.path)
                    assertEquals(mapOf("token" to "fixture-refresh", "token_type_hint" to "refresh_token",
                        "client_id" to "fixture-client"), form(request.body))
                }
                assertTokensCleared(fixture)
            }
        }
    }

    @Test fun cleanupPostsThePublicResponsesContractAndConsumesCompletedUnicodeSse() {
        Fixture(record()).use { fixture ->
            val text = "你好\nが 🙂"
            fixture.reply(200, delta(text) + completed(text), "text/event-stream; charset=utf-8")
            assertEquals(text, fixture.client.clean("你好\nか\u3099 🙂", "fixture-model", "polished", listOf("Fortissimo", "東京")))
            assertEquals(1, fixture.requests.size)
            val request = fixture.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/v1/responses", request.path)
            assertEquals("Bearer fixture-access", request.authorization)
            assertEquals("application/json", request.contentType)
            val body = JSONObject(request.body)
            assertEquals("fixture-model", body.getString("model"))
            assertFalse(body.getBoolean("store"))
            assertTrue(body.getBoolean("stream"))
            assertEquals(cleanupPrompt(), body.getString("instructions"))
            val message = body.getJSONArray("input").getJSONObject(0)
            assertEquals("user", message.getString("role"))
            val input = JSONObject(message.getString("content"))
            assertEquals("你好\nか\u3099 🙂", input.getString("transcript"))
            assertEquals("polished", input.getString("style"))
            val dictionary = input.getJSONArray("dictionary")
            assertEquals(listOf("Fortissimo", "東京"), (0 until dictionary.length()).map(dictionary::getString))
        }
    }

    @Test fun cleanupRejectsWrongMimeIncompleteStreamsRefusalsAndRedirectsWithoutRetry() {
        val text = "Partial text"
        val complete = delta(text) + completed(text)
        val refusal = event("response.refusal.delta", JSONObject().put("delta", "Declined"))
        val replies = listOf(
            Reply(200, complete, "application/json"),
            Reply(200, delta(text), "text/event-stream"),
            Reply(200, delta(text) + event("response.failed"), "text/event-stream"),
            Reply(200, delta(text) + refusal + completed(text), "text/event-stream"),
            Reply(200, delta(text) + completed("Different text"), "text/event-stream"),
            Reply(429, """{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}"""),
            Reply(503, """{"detail":"Temporary admission failure"}"""),
            Reply(302, "", location = "/redirect-must-not-be-followed"),
        )
        for (reply in replies) Fixture(record()).use { fixture ->
            val previous = fixture.saved
            fixture.replies.add(reply)
            fixture.reply(200, complete, "text/event-stream")
            rejects { fixture.client.clean(text, "fixture-model", "light", emptyList()) }
            assertEquals(1, fixture.requests.size)
            assertEquals(previous, fixture.saved)
        }
    }

    private fun assertTokensCleared(fixture: Fixture) {
        val saved = JSONObject(fixture.saved!!)
        for (field in listOf("access_token", "refresh_token", "id_token", "expires_at")) assertFalse(saved.has(field))
        assertEquals("fixture-client", saved.getString("client_id"))
        assertEquals("fixture-subject", saved.getString("subject"))
        assertEquals("urn:uuid:fixture-host", saved.getString("ext_agent_host_id"))
    }

    private fun record(expired: Boolean = false) = JSONObject().put("client_id", "fixture-client")
        .put("subject", "fixture-subject").put("ext_agent_host_id", "urn:uuid:fixture-host")
        .put("access_token", "fixture-access").put("refresh_token", "fixture-refresh").put("id_token", "fixture-id")
        .put("scope", "openid chatgpt.tokens.use.direct").put("expires_at", if (expired) 0 else System.currentTimeMillis() + 3_600_000)

    private fun delta(text: String) = event("response.output_text.delta", JSONObject().put("delta", text))
    private fun completed(text: String) = event("response.completed", JSONObject().put("response",
        JSONObject().put("status", "completed").put("output", JSONArray().put(JSONObject().put("type", "message")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))))))
    private fun event(type: String, body: JSONObject = JSONObject()) = "data: ${body.put("type", type)}\n\n"
    private fun form(body: String) = body.split('&').associate { part ->
        val pair = part.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejected HTTP cleanup or refresh") } catch (_: AuthException) { }
    }

    private data class Request(val method: String, val path: String, val authorization: String?, val contentType: String?, val body: String)
    private data class Reply(val status: Int, val body: String, val mime: String = "application/json", val location: String? = null)

    private class Fixture(initial: JSONObject) : AutoCloseable {
        @Volatile var saved: String? = initial.toString()
        val requests = CopyOnWriteArrayList<Request>()
        val replies = ConcurrentLinkedQueue<Reply>()
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        @Volatile private var failure: Exception? = null
        val client = ChatGptClient({ saved }, { saved = it }, { official ->
            assertTrue(official.toString() in setOf("https://auth.openai.com/api/accounts/oauth/token",
                "https://auth.openai.com/api/accounts/oauth/revoke", "https://auth.openai.com/.well-known/jwks.json",
                "https://api.openai.com/v1/models", "https://api.openai.com/v1/responses"))
            URL("http://127.0.0.1:${server.localPort}${official.path}").openConnection() as HttpURLConnection
        })
        private val worker = thread(isDaemon = true, name = "lip-http-fixture") {
            try { while (!server.isClosed) server.accept().use(::respond) }
            catch (error: Exception) { if (!server.isClosed) failure = error }
        }
        private fun respond(socket: Socket) {
            socket.soTimeout = 5_000
            val input = socket.getInputStream().buffered()
            val first = line(input).split(' ')
            require(first.size == 3)
            val headers = mutableMapOf<String, String>()
            var headerChars = 0
            while (true) {
                val header = line(input)
                headerChars += header.length + 2
                require(headerChars <= 16_384)
                if (header.isEmpty()) break
                val pair = header.split(':', limit = 2)
                require(pair.size == 2)
                headers[pair[0].lowercase()] = pair[1].trim()
            }
            val length = headers["content-length"]?.toInt() ?: 0
            require(length in 0..1_048_576)
            val body = input.readNBytes(length)
            require(body.size == length)
            requests.add(Request(first[0], first[1], headers["authorization"], headers["content-type"], String(body, Charsets.UTF_8)))
            val reply = replies.poll() ?: Reply(500, "{}")
            val bytes = reply.body.toByteArray(Charsets.UTF_8)
            val responseHeaders = "HTTP/1.1 ${reply.status} Fixture\r\nContent-Type: ${reply.mime}\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n" +
                (reply.location?.let { "Location: $it\r\n" } ?: "") + "\r\n"
            try {
                val output = socket.getOutputStream()
                output.write(responseHeaders.toByteArray(Charsets.US_ASCII))
                var position = 0
                while (position < bytes.size) {
                    val count = minOf(3, bytes.size - position)
                    output.write(bytes, position, count); output.flush()
                    position += count
                }
            } catch (_: IOException) { /* Rejected MIME/refusals may close the stream before its tail. */ }
        }
        private fun line(input: InputStream): String {
            val value = StringBuilder()
            repeat(8_192) {
                val next = input.read()
                require(next != -1)
                if (next == '\n'.code) return value.toString().removeSuffix("\r")
                value.append(next.toChar())
            }
            error("Fixture request line exceeded its safe limit")
        }
        fun reply(status: Int, body: String, mime: String = "application/json") { replies.add(Reply(status, body, mime)) }
        override fun close() {
            server.close(); worker.join(5_000)
            check(!worker.isAlive) { "Fixture server did not stop" }
            failure?.let { throw AssertionError("Fixture server failed", it) }
        }
    }
}
