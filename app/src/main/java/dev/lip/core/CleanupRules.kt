package dev.lip.core

import java.text.Normalizer
import java.util.Locale

data class OutputAssessment(val acceptableForAuto: Boolean, val reasons: List<String>)

/** Conservative local formatting and observable output checks, not semantic equivalence. */
object CleanupRules {
    private val literals = Regex("```[\\s\\S]*?(?:```|\\z)|`[^`\\r\\n]+`|\"(?:\\\\.|[^\"\\\\])*(?:\"|\\z)|“[^”]*”|「[^」]*」|『[^』]*』|(?<![\\p{L}\\p{N}])'[^'\\r\\n]*'(?![\\p{L}\\p{N}])|(?:https?://|www\\.)[^\\s<>\"'「」『』“”]+", RegexOption.IGNORE_CASE)
    private val codeLines = Regex("(?m)^(?:(?:\\t| {4})[^\\r\\n]*|[^\\r\\n]*[{}=][^\\r\\n]*)$")
    private val identifiers = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
    private val numeric = Regex("[-+−＋－]?\\p{Nd}+(?:[.,:/-]\\p{Nd}+)*(?:[eE][-+]?\\p{Nd}+)?(?:[\\t ]*(?:[%％]|mg|kg|mL|ml|cm|mm|km|ms|Hz|GB|MB|USD|JPY|g|L|m|s|円|元|人|個|件|年|月|日|時|分|秒|ミリグラム|グラム|公斤|千克|毫克|克|毫升|升))?|[零〇一二三四五六七八九十百千万萬亿億两兩]+(?:人|個|件|年|月|日|毫克|公斤|千克|克)?|\\b(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|million|billion)\\b", RegexOption.IGNORE_CASE)
    private val negative = Regex("\\b(?:no|not|never|without|cannot|[a-z]+n['’]t)\\b|ない|ません|禁止|不可|不|没|沒|无|無|别|別|勿|未", RegexOption.IGNORE_CASE)
    private val lexical = Regex("[\\p{L}\\p{M}\\p{N}_$]+(?:['’][\\p{L}\\p{M}\\p{N}_$]+)*|[\\p{So}\\p{Sm}]")
    private val controls = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]")
    private val requestFiller = Regex("^(?:uh|um)(?:,[\\t ]*|[\\t ]+)(?=please\\b)", RegexOption.IGNORE_CASE)
    private val repeatedArticle = Regex("\\b(the)[\\t ]+\\1\\b(?=[\\t ]+\\p{L})")

    /** Only explicit whole-line directives are commands. Ordinary cues and semantic edits stay raw. */
    fun local(raw: String, style: String, language: String): String {
        if (style != "polished" || protected(raw).isNotEmpty()) return raw
        val cjk = language.startsWith("ja") || language.startsWith("zh")
        val prefix = when {
            language.startsWith("en") -> "format command "
            language.startsWith("ja") -> "書式コマンド "
            language.startsWith("zh") -> "格式命令 "
            else -> return raw
        }
        val cues = when {
            language.startsWith("ja") -> mapOf("読点" to "、", "句点" to "。", "括弧開く" to "（", "括弧閉じる" to "）", "改行" to "\n", "新段落" to "\n\n", "箇条書き" to "- ")
            language.startsWith("zh") -> mapOf("逗号" to "，", "句号" to "。", "左括号" to "（", "右括号" to "）", "换行" to "\n", "新段落" to "\n\n", "项目符号" to "- ")
            else -> mapOf("comma" to ",", "period" to ".", "open parenthesis" to "(", "close parenthesis" to ")", "new line" to "\n", "new paragraph" to "\n\n", "bullet point" to "- ", "new bullet" to "- ")
        }
        val lines = raw.lines()
        val commands = lines.map { if (it.startsWith(prefix)) cues[it.removePrefix(prefix)] else null }
        if (commands.all { it == null }) return raw
        val output = StringBuilder()
        var separator = ""
        var parentheses = 0
        for ((index, line) in lines.withIndex()) {
            val cue = commands[index]
            if (cue == null) {
                output.append(separator).append(line)
                separator = "\n"
            } else when (cue) {
                "- " -> {
                    if (lines.getOrNull(index + 1)?.let { it.isNotBlank() && !it.startsWith(prefix) } != true) return raw
                    if (output.isNotEmpty() && !output.endsWith("\n")) output.append('\n')
                    output.append(cue); separator = ""
                }
                "(", "（" -> {
                    if (!cjk && output.isNotEmpty() && !output.last().isWhitespace()) output.append(' ')
                    output.append(cue); parentheses++; separator = ""
                }
                ")", "）" -> {
                    if (--parentheses < 0) return raw
                    output.append(cue); separator = if (cjk) "" else " "
                }
                "\n", "\n\n" -> { output.append(cue); separator = "" }
                else -> { output.append(cue); separator = if (cjk) "" else " " }
            }
        }
        return if (parentheses == 0 && tokens(output.toString()).isNotEmpty()) output.toString() else raw
    }

    fun assess(raw: String, candidate: String, style: String, language: String): OutputAssessment {
        val reasons = mutableListOf<String>()
        if (candidate.isBlank() || candidate.length > 65_536 || controls.containsMatchIn(candidate)) reasons += "Invalid cleanup output"
        if (style !in listOf("verbatim", "light", "polished")) reasons += "Unsupported cleanup style"
        if (listOf("en", "ja", "zh").none { language.startsWith(it) }) reasons += "Unsupported cleanup language"
        if (style == "verbatim") {
            if (raw != candidate) reasons += "Verbatim text changed"
        } else {
            if (protected(raw) != protected(candidate)) reasons += "Code, URLs, identifiers or quoted text changed"
            if (numeric.findAll(raw).map { it.value }.toList() != numeric.findAll(candidate).map { it.value }.toList()) reasons += "Numbers, signs or units changed"
            if (negations(raw) != negations(candidate)) reasons += "Negation changed"
            val original = tokens(raw)
            val result = tokens(candidate)
            if (result.isEmpty() && original.isNotEmpty()) reasons += "Transcript content removed"
            if (style == "light" && original != result) reasons += "Light cleanup changed words or their order"
            if (style == "polished" && original != result) {
                val withoutFiller = removeRequestFiller(raw, language)
                val allowed = listOf(withoutFiller, repeatedArticle.replace(raw, "$1"),
                    repeatedArticle.replace(withoutFiller, "$1"), local(raw, style, language),
                    local(withoutFiller, style, language))
                // Only enumerated disfluencies or explicit directives permit deletions, never an arbitrary subsequence.
                if (allowed.none { tokens(it) == result }) reasons += "Cleanup changed words beyond narrow formatting; review required"
            }
        }
        return OutputAssessment(reasons.isEmpty(), reasons)
    }

    private fun protected(value: String): List<String> {
        val matches = literals.findAll(value).toList() + codeLines.findAll(value).toList() + identifiers.findAll(value).filter {
            val word = it.value
            word.any { c -> c == '_' || c == '$' || c.isDigit() } || word.any { c -> c.isLowerCase() } && word.drop(1).any { c -> c.isUpperCase() }
        }.toList()
        return matches.sortedBy { it.range.first }.map { it.value }
    }

    private fun negations(value: String) = negative.findAll(value).map { it.value.lowercase(Locale.ROOT).replace('’', '\'') }.toList()

    private fun removeRequestFiller(value: String, language: String): String = when {
        language.startsWith("en") -> requestFiller.replaceFirst(value, "")
        language.startsWith("ja") -> value.removePrefix("えー、")
        language.startsWith("zh") && value.startsWith("嗯，请") -> value.removePrefix("嗯，")
        else -> value
    }

    private fun tokens(value: String): List<String> = buildList {
        for (match in lexical.findAll(Normalizer.normalize(value, Normalizer.Form.NFC))) {
            val word = StringBuilder()
            fun flush() { if (word.isNotEmpty()) { add(word.toString()); word.setLength(0) } }
            for (point in match.value.lowercase(Locale.ROOT).replace('’', '\'').codePoints().toArray()) {
                when (Character.UnicodeScript.of(point)) {
                    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> { flush(); add(String(Character.toChars(point))) }
                    else -> word.appendCodePoint(point)
                }
            }
            flush()
        }
    }
}
