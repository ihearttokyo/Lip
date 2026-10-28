package dev.lip.auth

import java.io.Reader
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import java.util.Date
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import org.json.JSONObject

open class AuthException(message: String) : Exception(message)
data class OAuthCallback(val code: String?, val clientId: String?, val error: String?)
data class VerifiedIdentity(val subject: String, val email: String?)

internal object OAuthProtocol {
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(UTF_8)))

    fun callback(target: String, state: String, clientId: String?): OAuthCallback? {
        try {
            val uri = URI(target)
            if (uri.isAbsolute || uri.rawAuthority != null || uri.rawPath != "/auth/callback" || uri.rawFragment != null) return null
            val query = mutableMapOf<String, String>()
            for (part in (uri.rawQuery ?: "").split('&').filter { it.isNotEmpty() }) {
                val pair = part.split('=', limit = 2)
                val name = URLDecoder.decode(pair[0], "UTF-8")
                if (query.put(name, URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")) != null)
                    throw AuthException("Duplicate authorization parameter.")
            }
            val returnedState = query["state"] ?: return null
            if (!MessageDigest.isEqual(state.toByteArray(UTF_8), returnedState.toByteArray(UTF_8))) return null
            query["error"]?.let { return OAuthCallback(null, clientId, it) }
            val issued = query["client_id"] ?: clientId
            if (issued.isNullOrBlank() || issued == "dynamic_agent_client" || issued.length > 256 ||
                (clientId != null && clientId != issued)) throw AuthException("ChatGPT registration did not match this sign-in.")
            val code = query["code"]?.takeIf { it.isNotBlank() }
                ?: throw AuthException("ChatGPT registration was incomplete.")
            return OAuthCallback(code, issued, null)
        } catch (e: AuthException) { throw e }
        catch (_: Exception) { throw AuthException("Invalid authorization callback.") }
    }
}

internal object ResponseStream {
    fun read(reader: Reader, maxChars: Int = 262_144): String {
        val output = StringBuilder()
        val line = StringBuilder()
        val data = mutableListOf<String>()
        var event = ""
        var total = 0
        val deadline = System.nanoTime() + 90_000_000_000L
        fun consumeLine(): Boolean {
            val value = line.toString().removeSuffix("\r")
            line.setLength(0)
            if (value.startsWith("data:")) data += value.substring(5).removePrefix(" ")
            else if (value.startsWith("event:")) event = value.substring(6).trim()
            else if (value.isEmpty() && data.isNotEmpty()) {
                try {
                    val body = JSONObject(data.joinToString("\n"))
                    when (body.optString("type", event)) {
                        "response.output_text.delta" -> {
                            val delta = body.opt("delta") as? String ?: throw AuthException("Invalid cleanup response.")
                            output.append(delta)
                        }
                        "response.completed" -> {
                            val response = body.getJSONObject("response")
                            if (response.optString("status", "completed") != "completed")
                                throw AuthException("ChatGPT did not complete cleanup.")
                            val completedText = StringBuilder()
                            val items = response.getJSONArray("output")
                            for (index in 0 until items.length()) {
                                val content = items.getJSONObject(index).optJSONArray("content") ?: continue
                                for (partIndex in 0 until content.length()) {
                                    val part = content.getJSONObject(partIndex)
                                    when (part.optString("type")) {
                                        "refusal" -> throw AuthException("ChatGPT declined cleanup. Your original text is preserved.")
                                        "output_text" -> completedText.append(part.opt("text") as? String
                                            ?: throw AuthException("Invalid cleanup response."))
                                    }
                                }
                            }
                            if (completedText.toString() != output.toString())
                                throw AuthException("ChatGPT cleanup text did not match its completed response.")
                            if (output.isBlank()) throw AuthException("ChatGPT returned no cleaned text.")
                            return true
                        }
                        "response.failed", "response.incomplete", "response.refusal.delta", "response.refusal.done", "error" ->
                            throw AuthException("ChatGPT did not finish cleanup. Your original text is preserved.")
                    }
                } catch (e: AuthException) { throw e }
                catch (_: Exception) { throw AuthException("Invalid cleanup response.") }
                data.clear()
                event = ""
            }
            return false
        }
        while (true) {
            if (System.nanoTime() > deadline) throw AuthException("ChatGPT cleanup timed out. Your original text is preserved.")
            val next = reader.read()
            if (next == -1) break
            if (++total > maxChars) throw AuthException("Cleanup response exceeded the safe size limit.")
            if (next == '\n'.code) {
                if (consumeLine()) return output.toString()
            } else line.append(next.toChar())
        }
        throw AuthException("Cleanup was interrupted. Your original text is preserved.")
    }
}

internal object IdTokens {
    fun validate(token: String, jwks: String, clientId: String, nonce: String,
                 expectedSubject: String? = null, now: Date = Date()): VerifiedIdentity {
        try {
            val jwt = SignedJWT.parse(token)
            if (jwt.header.algorithm != JWSAlgorithm.RS256 || jwt.header.keyID.isNullOrBlank())
                throw AuthException("Unsupported ChatGPT identity signature.")
            val key = JWKSet.parse(jwks).getKeyByKeyId(jwt.header.keyID) as? RSAKey
                ?: throw AuthException("ChatGPT identity signing key was not found.")
            if (!jwt.verify(RSASSAVerifier(key.toRSAPublicKey()))) throw AuthException("ChatGPT identity signature was invalid.")
            val claims = jwt.jwtClaimsSet
            val issued = claims.issueTime ?: throw AuthException("ChatGPT identity had no issuance time.")
            val expiry = claims.expirationTime ?: throw AuthException("ChatGPT identity had no expiration.")
            val subject = claims.subject?.takeIf { it.isNotBlank() } ?: throw AuthException("ChatGPT identity had no subject.")
            val authorizedParty = claims.getStringClaim("azp")
            if (claims.issuer != "https://auth.openai.com" || clientId !in claims.audience ||
                (authorizedParty != null && authorizedParty != clientId) ||
                (claims.audience.size > 1 && authorizedParty == null) ||
                expiry.time <= now.time - 5_000 || issued.time > now.time + 5_000 || issued.time <= 0 ||
                expiry.before(issued) || (claims.notBeforeTime?.time ?: 0) > now.time + 5_000 ||
                !MessageDigest.isEqual((claims.getStringClaim("nonce") ?: "").toByteArray(UTF_8), nonce.toByteArray(UTF_8)) ||
                (expectedSubject != null && subject != expectedSubject))
                throw AuthException("ChatGPT identity did not match this sign-in.")
            return VerifiedIdentity(subject, claims.getStringClaim("email"))
        } catch (e: AuthException) { throw e }
        catch (_: Exception) { throw AuthException("ChatGPT identity could not be verified.") }
    }
}
