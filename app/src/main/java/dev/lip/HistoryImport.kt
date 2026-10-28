package dev.lip

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Untrusted portable document, not an instruction to write/capture history. */
data class PortableHistoryRecord(
    val id: String, val createdAtUnixMs: Long, val original: String, val output: String,
    val language: String?, val model: String?, val modelDetails: String?, val legacyCloudCleanup: Boolean,
)
data class PortableHistoryDocument(val sourcePlatform: String, val record: PortableHistoryRecord)
enum class HistoryImportDisposition { NEW, IDENTICAL, CONFLICT, AMBIGUOUS }

/** Deliberately only the fixed v1 grammar. No JSONObject coercions, recursive
 * unknown trees, URI handling, writes or user-content diagnostics. */
object HistoryImport {
    fun classify(incoming: PortableHistoryRecord, existing: List<PortableHistoryRecord>): HistoryImportDisposition {
        val matches = existing.filter { it.id == incoming.id }
        return when {
            matches.isEmpty() -> HistoryImportDisposition.NEW
            matches.size > 1 -> HistoryImportDisposition.AMBIGUOUS
            matches.single() == incoming -> HistoryImportDisposition.IDENTICAL
            else -> HistoryImportDisposition.CONFLICT
        }
    }
    fun decode(bytes: ByteArray): PortableHistoryDocument {
        require(bytes.size <= HistoryTransfer.MAX_BYTES) { "History document exceeds import limit." }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return Reader(text).document()
    }
    /** Existing encrypted local bytes are not a trusted typed DTO. Android's
     * JSONObject getters coerce invalid primitives; import scans must not. */
    fun decodeLocal(bytes: ByteArray): PortableHistoryRecord {
        require(bytes.size <= HistoryTransfer.MAX_BYTES) { "Local history record exceeds import limit." }
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return Reader(text).localRecord()
    }
    private class Reader(private val text: String) {
        private var at = 0
        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid or unsupported history document; nothing imported.")
        private fun ws() { while (at < text.length && text[at] in " \t\r\n") at++ }
        private fun take(c: Char) { ws(); if (at >= text.length || text[at++] != c) invalid() }
        private fun peek(c: Char): Boolean { ws(); return at < text.length && text[at] == c }
        private fun literal(value: String) { ws(); if (!text.startsWith(value, at)) invalid(); at += value.length }
        private fun string(): String {
            take('"'); val result = StringBuilder()
            while (at < text.length) {
                val c = text[at++]
                when {
                    c == '"' -> return result.toString().also { HistoryTransfer.strictBytes(it) }
                    c == '\\' -> {
                        if (at >= text.length) invalid()
                        when (val escape = text[at++]) {
                            '"', '\\', '/' -> result.append(escape)
                            'b' -> result.append('\b'); 'f' -> result.append('\u000C')
                            'n' -> result.append('\n'); 'r' -> result.append('\r'); 't' -> result.append('\t')
                            'u' -> {
                                if (text.length - at < 4) invalid()
                                var code = 0
                                repeat(4) {
                                    val hex = text[at++]
                                    val digit = when(hex) { in '0'..'9' -> hex-'0'; in 'a'..'f' -> hex-'a'+10; in 'A'..'F' -> hex-'A'+10; else -> invalid() }
                                    code = code * 16 + digit
                                }
                                result.append(code.toChar())
                            }
                            else -> invalid()
                        }
                    }
                    c < ' ' -> invalid()
                    else -> result.append(c)
                }
            }
            invalid()
        }
        private fun nullableString(): String? = if (peek('n')) { literal("null"); null } else string()
        private fun integer(): Long {
            ws(); val start = at
            if (at >= text.length || text[at] !in '0'..'9') invalid()
            if (text[at] == '0') at++ else while (at < text.length && text[at] in '0'..'9') at++
            return text.substring(start, at).toLongOrNull() ?: invalid()
        }
        private fun bool(): Boolean = when { peek('t') -> { literal("true"); true }; peek('f') -> { literal("false"); false }; else -> invalid() }
        private fun fields(expected: Set<String>, required: Set<String> = expected, value: (String) -> Unit) {
            take('{'); val seen = HashSet<String>()
            if (!peek('}')) while (true) {
                val key = string()
                if (key !in expected || !seen.add(key)) invalid()
                take(':'); value(key)
                if (peek('}')) break
                take(',')
            }
            take('}'); if (!seen.containsAll(required)) invalid()
        }
        private fun record(): PortableHistoryRecord {
            var id = ""; var date = -1L; var original = ""; var output = ""
            var language: String? = null; var model: String? = null; var details: String? = null; var cloud = false
            fields(setOf("id","createdAtUnixMs","original","output","language","model","modelDetails","legacyCloudCleanup")) { key ->
                when (key) {
                    "id" -> id = string(); "createdAtUnixMs" -> date = integer()
                    "original" -> original = string(); "output" -> output = string()
                    "language" -> language = nullableString(); "model" -> model = nullableString()
                    "modelDetails" -> details = nullableString(); "legacyCloudCleanup" -> cloud = bool()
                }
            }
            if (id.isBlank() || HistoryTransfer.strictBytes(id).size > 256 || date !in 0..253402300799999L) invalid()
            for (value in listOf(language, model, details)) if (value != null && HistoryTransfer.strictBytes(value).size > 4096) invalid()
            return PortableHistoryRecord(id, date, original, output, language, model, details, cloud)
        }
        fun localRecord(): PortableHistoryRecord {
            take('[')
            var id=""; var date=-1L; var original="";var output="";var language:String?=null;var model:String?=null;var details:String?=null;var cloud=false
            fields(setOf("id","time","raw","clean","language","chatgpt","model","modelDetails"),
                setOf("id","time","raw","clean","language","chatgpt")) { key ->
                when(key) {"id"->id=string();"time"->date=integer();"raw"->original=string();"clean"->output=string()
                    "language"->language=nullableString();"model"->model=nullableString();"modelDetails"->details=nullableString();"chatgpt"->cloud=bool()}
            }
            take(']');ws();if(at!=text.length)invalid()
            if(id.isBlank()||HistoryTransfer.strictBytes(id).size>256||date !in 0..253402300799999L)invalid()
            for(value in listOf(language,model,details))if(value!=null&&HistoryTransfer.strictBytes(value).size>4096)invalid()
            return PortableHistoryRecord(id,date,original,output,language,model,details,cloud)
        }
        fun document(): PortableHistoryDocument {
            var schema = ""; var version = -1L; var source = ""; var record: PortableHistoryRecord? = null
            fields(setOf("schema","version","sourcePlatform","record")) { key ->
                when (key) { "schema" -> schema = string(); "version" -> version = integer(); "sourcePlatform" -> source = string(); "record" -> record = record() }
            }
            ws()
            if (at != text.length || schema != HistoryTransfer.SCHEMA || version != 1L || source !in setOf("android", "macos")) invalid()
            return PortableHistoryDocument(source, record ?: invalid())
        }
    }
}
