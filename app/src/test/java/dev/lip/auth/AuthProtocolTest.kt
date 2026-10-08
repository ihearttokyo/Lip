package dev.lip.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Date

class AuthProtocolTest {
    @Test fun deletionMustVerifyEveryAtomicFileVariantIsGone() {
        val directory = Files.createTempDirectory("lip-deletion-test").toFile()
        val base = File(directory, "history")
        try {
            for (suffix in listOf("", ".bak", ".new")) {
                val remaining = File(base.path + suffix)
                remaining.writeText("encrypted fixture")
                rejects { SecureStore.verifyDeleted(base) }
                assertTrue(remaining.delete())
            }
            SecureStore.verifyDeleted(base)
        } finally {
            listOf("", ".bak", ".new").forEach { File(base.path + it).delete() }
            directory.delete()
        }
    }

    @Test fun pkceUsesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            OAuthProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun callbackIsBoundToStatePathAndIssuedClient() {
        assertNull(OAuthProtocol.callback("/favicon.ico?state=s", "s", null))
        assertNull(OAuthProtocol.callback("/auth/callback?state=wrong&code=c&client_id=oaiapp_1", "s", null))
        assertEquals(OAuthCallback("c", "oaiapp_1", null),
            OAuthProtocol.callback("/auth/callback?state=s&code=c&client_id=oaiapp_1", "s", null))
        assertEquals("oaiapp_1", OAuthProtocol.callback("/auth/callback?state=s&code=c", "s", "oaiapp_1")!!.clientId)
        rejects { OAuthProtocol.callback("/auth/callback?state=s&code=c", "s", null) }
        rejects { OAuthProtocol.callback("/auth/callback?state=s&code=c&client_id=dynamic_agent_client", "s", null) }
        rejects { OAuthProtocol.callback("/auth/callback?state=s&code=c&client_id=oaiapp_2", "s", "oaiapp_1") }
        rejects { OAuthProtocol.callback("/auth/callback?state=s&state=s&code=c&client_id=oaiapp_1", "s", null) }
        rejects { OAuthProtocol.callback("/auth/callback?state=s&code=c&code=d&client_id=oaiapp_1", "s", null) }
        rejects { OAuthProtocol.callback("/auth/callback?state=s&%73tate=s&code=c&client_id=oaiapp_1", "s", null) }
        assertNull(OAuthProtocol.callback("http://evil.invalid/auth/callback?state=s&code=c&client_id=oaiapp_1", "s", null))
        assertEquals("access_denied", OAuthProtocol.callback("/auth/callback?state=s&error=access_denied", "s", null)!!.error)
    }

    @Test fun streamRequiresCompletedAndRejectsFailedOrTruncatedOutput() {
        val delta = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"こんにちは\\n世界\"}\n\n"
        val completed = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[" +
            "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"こんにちは\\n世界\"}]}]}}\n\n"
        assertEquals("こんにちは\n世界", ResponseStream.read(StringReader(delta + completed)))
        assertEquals("こんにちは\n世界", ResponseStream.read(StringReader((delta + completed).replace("\n", "\r\n"))))
        assertEquals("x", ResponseStream.read(StringReader(": comment\n\ndata: {\"type\":\"response.output_text.delta\",\n" +
            "data: \"delta\":\"x\"}\n\n" + completed.replace("こんにちは\\n世界", "x"))))
        rejects { ResponseStream.read(StringReader(delta)) }
        rejects { ResponseStream.read(StringReader(delta + "data: [DONE]\n\n")) }
        rejects { ResponseStream.read(StringReader(delta + "data: {\"type\":\"response.failed\"}\n\n")) }
        rejects { ResponseStream.read(StringReader(delta + "data: {\"type\":\"response.incomplete\"}\n\n")) }
        rejects { ResponseStream.read(StringReader(delta + "data: {\"type\":\"error\"}\n\n")) }
        rejects { ResponseStream.read(StringReader(delta + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"incomplete\"}}\n\n")) }
        rejects { ResponseStream.read(StringReader(delta + completed), maxChars = 20) }
    }

    @Test fun completedTextWithRefusalIsNotUsableCleanup() {
        val delta = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Partial text\"}\n\n"
        val completed = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[" +
            "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Partial text\"}," +
            "{\"type\":\"refusal\",\"refusal\":\"Cannot complete cleanup\"}]}]}}\n\n"
        rejects { ResponseStream.read(StringReader(delta + completed)) }
    }

    @Test fun completedTextMustMatchStreamedText() {
        val delta = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Streamed text\"}\n\n"
        val completed = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[" +
            "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Different text\"}]}]}}\n\n"
        rejects { ResponseStream.read(StringReader(delta + completed)) }
        assertEquals("Streamed text", ResponseStream.read(StringReader(delta + completed.replace("Different text", "Streamed text"))))
    }

    @Test fun refusalEventsRejectPreviouslyStreamedText() {
        val delta = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Partial text\"}\n\n"
        val completed = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[" +
            "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Partial text\"}]}]}}\n\n"
        for (type in listOf("response.refusal.delta", "response.refusal.done"))
            rejects { ResponseStream.read(StringReader(delta + "data: {\"type\":\"$type\",\"delta\":\"Declined\"}\n\n" + completed)) }
    }

    @Test fun completedResponseWithoutTerminalOutputIsRejected() {
        val delta = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Unverified text\"}\n\n"
        val completed = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
        rejects { ResponseStream.read(StringReader(delta + completed)) }
    }

    @Test fun signedIdentityChecksNonceAudienceExpiryAndReturningAccount() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val key = RSAKey.Builder(pair.public as RSAPublicKey)
            .privateKey(pair.private as RSAPrivateKey).keyID("test").build()
        val jwks = JWKSet(key.toPublicJWK()).toString()
        val now = Date(1_800_000_000_000L)
        fun token(nonce: String = "nonce", audience: String = "oaiapp_1", expiry: Long = now.time + 60_000,
                  subject: String = "subject", issuer: String = "https://auth.openai.com",
                  issuedAt: Long? = now.time - 10_000, notBefore: Long? = null,
                  multipleAudience: Boolean = false, authorizedParty: String? = null,
                  algorithm: JWSAlgorithm = JWSAlgorithm.RS256, keyId: String = "test"): String {
            val claims = JWTClaimsSet.Builder().issuer(issuer)
                .audience(if (multipleAudience) listOf(audience, "other") else listOf(audience)).subject(subject)
                .issueTime(issuedAt?.let(::Date)).notBeforeTime(notBefore?.let(::Date)).expirationTime(Date(expiry))
                .claim("azp", authorizedParty).claim("nonce", nonce).claim("email", "test@example.com").build()
            return SignedJWT(JWSHeader.Builder(algorithm).keyID(keyId).build(), claims)
                .apply { sign(RSASSASigner(key)) }.serialize()
        }
        assertEquals("subject", IdTokens.validate(token(), jwks, "oaiapp_1", "nonce", "subject", now).subject)
        rejects { IdTokens.validate(token(nonce = "wrong"), jwks, "oaiapp_1", "nonce", null, now) }
        rejects { IdTokens.validate(token(audience = "other"), jwks, "oaiapp_1", "nonce", null, now) }
        rejects { IdTokens.validate(token(expiry = now.time - 10_000), jwks, "oaiapp_1", "nonce", null, now) }
        rejects { IdTokens.validate(token(subject = "other"), jwks, "oaiapp_1", "nonce", "subject", now) }
        rejects { IdTokens.validate(token(issuer = "https://evil.invalid"), jwks, "oaiapp_1", "nonce", null, now) }
        rejects { IdTokens.validate(token(issuedAt = null), jwks, "oaiapp_1", "nonce", null, now) }
        rejects { IdTokens.validate(token(issuedAt = now.time + 10_000), jwks, "oaiapp_1", "nonce", null, now) }
        rejects { IdTokens.validate(token(notBefore = now.time + 10_000), jwks, "oaiapp_1", "nonce", null, now) }
        rejects { IdTokens.validate(token(multipleAudience = true), jwks, "oaiapp_1", "nonce", null, now) }
        assertEquals("subject", IdTokens.validate(token(multipleAudience = true, authorizedParty = "oaiapp_1"),
            jwks, "oaiapp_1", "nonce", null, now).subject)
        rejects { IdTokens.validate(token(algorithm = JWSAlgorithm.RS512), jwks, "oaiapp_1", "nonce", null, now) }
        rejects { IdTokens.validate(token(keyId = "unknown"), jwks, "oaiapp_1", "nonce", null, now) }
        rejects { IdTokens.validate(token().dropLast(12) + "AAAAAAAAAAAA", jwks, "oaiapp_1", "nonce", null, now) }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejected authorization or incomplete response") }
        catch (_: AuthException) { }
    }
}
