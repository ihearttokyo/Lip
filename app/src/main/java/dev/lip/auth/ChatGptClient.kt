package dev.lip.auth

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

data class Session(val email: String?, val canUsePlan: Boolean)
data class Model(val slug: String, val displayName: String)

/** Blocking calls belong on a worker thread. Authorization never uses an embedded browser. */
class ChatGptClient(context: Context) {
    private val store = SecureStore(context)

    fun session(): Session? = synchronized(credentialsLock) {
        record()?.takeIf { it.optString("access_token").isNotBlank() }?.let {
            Session(it.optString("email").takeIf(String::isNotBlank), hasPlan(it))
        }
    }

    fun beginSignIn(onUrl: (String) -> Unit): Session {
        val saved = synchronized(credentialsLock) {
            val value = record() ?: JSONObject()
            if (!value.has("ext_agent_host_id")) {
                value.put("ext_agent_host_id", "urn:uuid:${UUID.randomUUID()}")
                save(value)
            }
            value
        }
        val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val attempt = synchronized(signInLock) {
            if (pending != null) { listener.close(); throw AuthException("A ChatGPT sign-in is already pending.") }
            Pending(listener).also { pending = it }
        }
        val verifier = random()
        val state = random()
        val nonce = random()
        val existingClient = saved.optString("client_id").takeIf(String::isNotBlank)
        val redirect = "http://127.0.0.1:${listener.localPort}/auth/callback"
        val parameters = linkedMapOf(
            "client_id" to (existingClient ?: "dynamic_agent_client"),
            "ext_agent_host_id" to saved.getString("ext_agent_host_id"),
            "response_type" to "code", "redirect_uri" to redirect,
            "scope" to SCOPES, "resource" to RESOURCE,
            "state" to state, "nonce" to nonce,
            "code_challenge_method" to "S256", "code_challenge" to OAuthProtocol.challenge(verifier)
        )
        if (existingClient == null) parameters["agent_name_hint"] = "Lip"
        else {
            saved.optString("id_token").takeIf(String::isNotBlank)?.let { parameters["id_token_hint"] = it }
            saved.optString("email").takeIf(String::isNotBlank)?.let { parameters["login_hint"] = it }
            if (!hasPlan(saved)) parameters["prompt"] = "consent"
        }
        try {
            onUrl("$ISSUER/api/accounts/authorize?${form(parameters)}")
            val callback = waitForCallback(attempt, state, existingClient)
            if (callback.error != null) throw AuthException("ChatGPT authorization was declined or cancelled.")
            val issued = callback.clientId ?: throw AuthException("ChatGPT registration was incomplete.")
            // Retain an issued registration if code exchange must be restarted, without replacing an active session.
            synchronized(credentialsLock) {
                if (pending !== attempt) throw AuthException("ChatGPT sign-in was cancelled.")
                if (existingClient == null) save(saved.put("client_id", issued))
            }
            val tokens = jsonRequest("$ISSUER/api/accounts/oauth/token", form = mapOf(
                "grant_type" to "authorization_code", "client_id" to issued,
                "code" to callback.code!!, "code_verifier" to verifier,
                "redirect_uri" to redirect, "resource" to RESOURCE
            ))
            val identity = IdTokens.validate(tokens.getString("id_token"),
                jsonRequest("$ISSUER/.well-known/jwks.json").toString(), issued, nonce,
                saved.optString("subject").takeIf(String::isNotBlank))
            synchronized(credentialsLock) {
                if (pending !== attempt) throw AuthException("ChatGPT sign-in was cancelled.")
                saved.put("issuer", ISSUER).put("subject", identity.subject)
                    .put("email", identity.email ?: "").put("id_token", tokens.getString("id_token"))
                applyTokens(saved, tokens)
                save(saved)
                generation++
                return Session(identity.email, hasPlan(saved))
            }
        } catch (e: AuthException) { throw e }
        catch (_: Exception) { throw AuthException(if (pending !== attempt) "ChatGPT sign-in was cancelled." else "ChatGPT sign-in could not finish. Try again.") }
        finally {
            runCatching { listener.close() }
            synchronized(signInLock) { if (pending === attempt) pending = null }
        }
    }

    fun cancelSignIn() = synchronized(signInLock) {
        try { pending?.listener?.close() } finally { pending = null }
    }

    fun listModels(): List<Model> {
        try {
            val (token, epoch) = access()
            val models = jsonRequest("$RESOURCE/models", token = token).getJSONArray("models")
            val result = (0 until models.length()).map { models.getJSONObject(it) }
                .filter { it.optString("visibility") == "list" }
                .map { Model(it.getString("slug"), it.getString("display_name")) }
            checkGeneration(epoch)
            return result
        } catch (e: AuthException) { throw e }
        catch (_: Exception) { throw AuthException("ChatGPT model choices could not be loaded. Try again.") }
    }

    fun clean(text: String, model: String, style: String, dictionary: List<String>): String {
        if (text.isBlank() || style == "verbatim") return text
        if (style !in listOf("polished", "light")) throw AuthException("Choose Polished, Light, or Verbatim cleanup.")
        if (text.length > 32_768 || model.isBlank() || model.length > 200 || dictionary.size > 200 ||
            dictionary.any { it.length > 200 }) throw AuthException("Cleanup input exceeded its safe size limit.")
        val (token, epoch) = access()
        val input = JSONObject().put("transcript", text).put("style", style.take(80))
            .put("dictionary", JSONArray(dictionary))
        val body = JSONObject().put("model", model).put("store", false).put("stream", true)
            .put("instructions", cleanupPrompt())
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", input.toString())))
        val connection = connection("$RESOURCE/responses", token)
        try {
            send(connection, "application/json", body.toString())
            checkStatus(connection)
            if (!connection.contentType.orEmpty().substringBefore(';').equals("text/event-stream", ignoreCase = true))
                throw AuthException("ChatGPT did not return a cleanup stream.")
            val cleaned = connection.inputStream.bufferedReader(Charsets.UTF_8).use { ResponseStream.read(it) }
            checkGeneration(epoch)
            return cleaned
        } catch (e: AuthException) { throw e }
        catch (_: Exception) { throw AuthException("ChatGPT cleanup was interrupted. Your original text is preserved.") }
        finally { connection.disconnect() }
    }

    /** Local tokens are cleared even when remote revocation cannot be confirmed. */
    fun signOut(): Boolean {
        cancelSignIn()
        return synchronized(credentialsLock) {
            val value = record() ?: return@synchronized true
            var confirmed = value.optString("refresh_token").isBlank()
            if (!confirmed) {
                for (attempt in 0..1) {
                    try {
                        jsonRequest("$ISSUER/api/accounts/oauth/revoke", form = mapOf(
                            "token" to value.getString("refresh_token"), "token_type_hint" to "refresh_token",
                            "client_id" to value.getString("client_id")
                        ), allowEmpty = true)
                        confirmed = true
                        break
                    } catch (e: HttpFailure) { if (e.status < 500) break }
                    catch (_: AuthException) { }
                    if (attempt == 0) try { Thread.sleep(500) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                }
            }
            clearTokens(value)
            save(value)
            generation++
            confirmed
        }
    }

    private fun access(): Pair<String, Long> = synchronized(credentialsLock) {
        val value = record() ?: throw AuthException("Continue with ChatGPT to enable cleanup.")
        if (!hasPlan(value)) throw AuthException("Enable ChatGPT plan use by continuing with ChatGPT again.")
        if (value.optLong("expires_at") <= System.currentTimeMillis() + 60_000) {
            if (value.optString("refresh_token").isBlank()) throw AuthException("Continue with ChatGPT again to renew cleanup.")
            try {
                val tokens = jsonRequest("$ISSUER/api/accounts/oauth/token", form = mapOf(
                    "grant_type" to "refresh_token", "client_id" to value.getString("client_id"),
                    "refresh_token" to value.getString("refresh_token"), "resource" to RESOURCE
                ))
                applyTokens(value, tokens)
                save(value)
            } catch (e: HttpFailure) {
                if (e.code in TERMINAL_REFRESH_ERRORS) {
                    clearTokens(value); save(value); generation++
                    throw AuthException("ChatGPT session ended. Continue with ChatGPT again.")
                }
                throw e
            }
        }
        if (!hasPlan(value) || value.optString("access_token").isBlank()) throw AuthException("ChatGPT plan cleanup is not authorized.")
        value.getString("access_token") to generation
    }

    private fun applyTokens(value: JSONObject, tokens: JSONObject) {
        val token = tokens.optString("access_token")
        val refresh = tokens.optString("refresh_token")
        val expiry = tokens.optLong("expires_in", -1)
        if (!tokens.optString("token_type").equals("Bearer", true) || token.isBlank() || refresh.isBlank() || expiry !in 1..86_400)
            throw AuthException("ChatGPT returned incomplete credentials.")
        value.put("access_token", token).put("refresh_token", refresh)
            .put("scope", tokens.getString("scope"))
            .put("expires_at", System.currentTimeMillis() + expiry * 1_000)
        // The originally verified ID token remains an allowed, possibly expired reauthorization hint.
    }

    private fun clearTokens(value: JSONObject) {
        for (field in listOf("access_token", "refresh_token", "id_token", "expires_at")) value.remove(field)
    }
    private fun record(): JSONObject? = try { store.read("chatgpt.json")?.let { JSONObject(it) } }
        catch (_: Exception) { throw AuthException("Saved ChatGPT credentials could not be read. Existing data has been preserved.") }
    private fun save(value: JSONObject) = store.write("chatgpt.json", value.toString())
    private fun hasPlan(value: JSONObject) = "chatgpt.tokens.use.direct" in value.optString("scope").split(' ')
    private fun checkGeneration(epoch: Long) = synchronized(credentialsLock) {
        if (generation != epoch) throw AuthException("ChatGPT account changed. The previous cleanup was discarded.")
    }

    private fun waitForCallback(attempt: Pending, state: String, clientId: String?): OAuthCallback {
        val deadline = System.nanoTime() + 180_000_000_000L
        attempt.listener.soTimeout = 1_000
        while (pending === attempt && System.nanoTime() < deadline) {
            val socket = try { attempt.listener.accept() } catch (_: SocketTimeoutException) { continue }
            socket.use {
                it.soTimeout = 2_000
                var callback: OAuthCallback? = null
                try {
                    val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.US_ASCII))
                    val first = boundedLine(reader, 8_192, deadline).split(' ')
                    var headers = 0
                    while (true) {
                        val line = boundedLine(reader, 8_192, deadline)
                        headers += line.length
                        if (headers > 16_384) throw AuthException("Authorization request was too large.")
                        if (line.isEmpty()) break
                    }
                    if (first.size == 3 && first[0] == "GET" && first[2].startsWith("HTTP/1."))
                        callback = OAuthProtocol.callback(first[1], state, clientId)
                } catch (_: Exception) { }
                val message = if (callback != null) "Sign-in received. Return to Lip." else "This request does not match a pending Lip sign-in."
                val bytes = message.toByteArray(Charsets.UTF_8)
                try {
                    it.getOutputStream().write(("HTTP/1.1 ${if (callback != null) "200 OK" else "400 Bad Request"}\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII) + bytes)
                } catch (_: Exception) { }
                if (callback != null) return callback
            }
        }
        throw AuthException(if (pending !== attempt) "ChatGPT sign-in was cancelled." else "ChatGPT sign-in timed out. Try again.")
    }

    private fun boundedLine(reader: BufferedReader, maximum: Int, deadline: Long): String {
        val line = StringBuilder()
        while (true) {
            if (System.nanoTime() > deadline) throw AuthException("ChatGPT sign-in timed out.")
            val next = reader.read()
            if (next == -1) throw AuthException("Authorization request ended early.")
            if (next == '\n'.code) return line.toString().removeSuffix("\r")
            if (line.length >= maximum) throw AuthException("Authorization request was too large.")
            line.append(next.toChar())
        }
    }

    private fun jsonRequest(url: String, token: String? = null, form: Map<String, String>? = null, allowEmpty: Boolean = false): JSONObject {
        val connection = connection(url, token)
        try {
            if (form != null) send(connection, "application/x-www-form-urlencoded", form(form))
            checkStatus(connection)
            val body = connection.inputStream.use { readBounded(it) }
            return if (body.isBlank() && allowEmpty) JSONObject() else JSONObject(body)
        } catch (e: AuthException) { throw e }
        catch (_: Exception) { throw AuthException("ChatGPT connection failed. Existing credentials were preserved.") }
        finally { connection.disconnect() }
    }

    private fun connection(url: String, token: String?): HttpsURLConnection =
        (URL(url).openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json, text/event-stream")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
    private fun send(connection: HttpsURLConnection, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", contentType)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
    }
    private fun checkStatus(connection: HttpsURLConnection) {
        val status = connection.responseCode
        if (status !in 200..299) {
            val code = try {
                val body = connection.errorStream?.use { JSONObject(readBounded(it)) }
                val error = body?.opt("error")
                (if (error is String) error else (error as? JSONObject)?.optString("code"))
                    ?.takeIf { it.matches(Regex("[a-z_]{1,80}")) }
            } catch (_: Exception) { null }
            throw HttpFailure(status, code)
        }
    }
    private fun readBounded(input: InputStream): String {
        val bytes = input.readNBytes(1_048_577)
        if (bytes.size > 1_048_576) throw AuthException("ChatGPT response exceeded its safe size limit.")
        return String(bytes, Charsets.UTF_8)
    }
    private fun form(values: Map<String, String>) = values.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }
    private fun random() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    private class Pending(val listener: ServerSocket)
    private class HttpFailure(val status: Int, val code: String?) : AuthException(
        if (code == "subscription_sharing_usage_limit_exceeded") "ChatGPT usage limit reached. Manage usage in ChatGPT settings."
        else "ChatGPT request failed (HTTP $status). Existing credentials were preserved.")

    companion object {
        private const val ISSUER = "https://auth.openai.com"
        private const val RESOURCE = "https://api.openai.com/v1"
        private const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        // ponytail: one saved registration; add keyed registrations only with a real account picker.
        private val credentialsLock = Any()
        private val signInLock = Any()
        @Volatile private var pending: Pending? = null
        private var generation = 0L
        private val TERMINAL_REFRESH_ERRORS = setOf("invalid_grant", "invalid_refresh_token", "token_expired",
            "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")
    }
}
