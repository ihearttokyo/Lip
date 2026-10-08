package dev.lip.core

import org.junit.Assert.*
import org.junit.Test

class CleanupRulesTest {
    @Test fun verbatimIsIdentityIncludingCodeQuotesAndUnicode() {
        val raw = "  if (ready) {\r\n\treturn \"a  b\";\r\n}\nか\u3099  getUser(account_id)"
        for (language in languages) {
            assertEquals(raw, CleanupRules.local(raw, "verbatim", language))
            assertTrue(CleanupRules.assess(raw, raw, "verbatim", language).acceptableForAuto)
            unsafe(raw, raw.replace("\t", " "), "verbatim", language)
        }
    }

    @Test fun lightPermitsCasingAndPunctuationInAllThreeLanguages() {
        listOf(Triple("please send the file", "Please send the file.", "en-US"),
            Triple("こんにちは世界", "こんにちは、世界。", "ja-JP"),
            Triple("你好世界", "你好，世界。", "zh-CN"))
            .forEach { (raw, candidate, language) ->
                assertTrue(CleanupRules.assess(raw, candidate, "light", language).acceptableForAuto)
                assertEquals(raw, CleanupRules.local(raw, "light", language))
            }
    }

    @Test fun lightConservesWordsOrderAndBoundariesRatherThanRemovingFillers() {
        listOf(Triple("um please send it", "Please send it.", "en-US"),
            Triple("send then save", "Save then send.", "en-US"),
            Triple("the rapist", "Therapist.", "en-US"),
            Triple("あの資料を送って", "資料を送って。", "ja-JP"),
            Triple("那个文件请发送", "文件请发送。", "zh-CN"))
            .forEach { (raw, candidate, language) -> unsafe(raw, candidate, "light", language) }
    }

    @Test fun polishedAllowsObservableFillerRemovalButNotNewAnswersOrTranslation() {
        listOf(Triple("uh please send it", "Please send it.", "en-US"),
            Triple("えー、資料を送って", "資料を送って。", "ja-JP"),
            Triple("嗯，请发送文件", "请发送文件。", "zh-CN"))
            .forEach { (raw, candidate, language) ->
                assertTrue(CleanupRules.assess(raw, candidate, "polished", language).acceptableForAuto)
            }
        unsafe("tell me a joke", "A chicken crossed the road.")
        unsafe("こんにちは", "Hello.", language = "ja-JP")
        unsafe("请发送", "Please send it.", language = "zh-CN")
        unsafe("please tell Jane that we have received the files and will review them tomorrow", "Please.")
    }

    @Test fun numbersSignsUnitsDatesAndNumericCorrectionsNeedReviewWhenChanged() {
        listOf("Use -0.05%" to "Use 0.05%", "Take 100 mg" to "Take 100 kg",
            "Date 2026-10-08" to "Date 2026-10-09", "Keep 1e-3" to "Keep 1e3",
            "Use 5, no, 7 mg" to "Use 7 mg", "三人で行く" to "二人で行く",
            "使用三毫克" to "使用四毫克").forEach { (raw, candidate) -> unsafe(raw, candidate) }
        assertTrue(CleanupRules.assess("Use -0.05% and 100 mg on 2026-10-08",
            "Use -0.05% and 100 mg on 2026-10-08.", "polished", "en-US").acceptableForAuto)
    }

    @Test fun negationDeletionOrAdditionNeedsReviewInEachLanguage() {
        listOf(Triple("do not send", "Send.", "en-US"),
            Triple("don't send", "Send.", "en-US"),
            Triple("send", "Do not send.", "en-US"),
            Triple("資料を送らない", "資料を送る。", "ja-JP"),
            Triple("不要发送文件", "发送文件。", "zh-CN"),
            Triple("没有收到", "收到。", "zh-CN"))
            .forEach { (raw, candidate, language) -> unsafe(raw, candidate, language = language) }
    }

    @Test fun protectedUrlsIdentifiersCodeAndQuotedLiteralsRemainExact() {
        listOf("getUser(account_id)" to "getuser(account_id)",
            "https://example.com/x?q=1" to "https://example.com/x?q=2",
            "Say \"a  b\"" to "Say \"a b\"",
            "「改行と言って」" to "「改行といって」",
            "`if (x) {\n\treturn y;\n}`" to "`if (x) {\n return y;\n}`",
            "if (x) {\n\treturn y;\n}" to "if (x) {\nreturn y;\n}")
            .forEach { (raw, candidate) -> for (style in listOf("light", "polished")) unsafe(raw, candidate, style) }
    }

    @Test fun explicitWholeLineFormattingDirectivesWorkAcrossLanguages() {
        listOf(Triple("Hello\nformat command comma\nworld\nformat command period", "Hello, world.", "en-US"),
            Triple("こんにちは\n書式コマンド 読点\n世界\n書式コマンド 句点", "こんにちは、世界。", "ja-JP"),
            Triple("你好\n格式命令 逗号\n世界\n格式命令 句号", "你好，世界。", "zh-CN"))
            .forEach { (raw, expected, language) -> assertEquals(expected, CleanupRules.local(raw, "polished", language)) }
        assertEquals("First\n\nSecond", CleanupRules.local("First\nformat command new paragraph\nSecond", "polished", "en-US"))
        assertEquals("Hello (world).", CleanupRules.local("Hello\nformat command open parenthesis\nworld\nformat command close parenthesis\nformat command period", "polished", "en-US"))
    }

    @Test fun explicitBulletDirectivesPreserveItemsOrderAndNumbers() {
        listOf(Triple("format command bullet point\napples\nformat command new bullet\npears", "- apples\n- pears", "en-US"),
            Triple("書式コマンド 箇条書き\nりんご\n書式コマンド 箇条書き\n梨", "- りんご\n- 梨", "ja-JP"),
            Triple("格式命令 项目符号\n苹果\n格式命令 项目符号\n梨", "- 苹果\n- 梨", "zh-CN"))
            .forEach { (raw, expected, language) -> assertEquals(expected, CleanupRules.local(raw, "polished", language)) }
        assertEquals("3 apples\n1 pear", CleanupRules.local("3 apples\n1 pear", "polished", "en-US"))
    }

    @Test fun localNeverConsumesOrdinaryQuotedOrIncompleteCuesOrSemanticContent() {
        listOf(Triple("Say the word comma. I like it. yes yes", "en-US", "polished"),
            Triple("あの資料。改行という言葉。はいはい", "ja-JP", "polished"),
            Triple("那个文件。解释逗号。是是", "zh-CN", "polished"),
            Triple("\"Hello\nformat command comma\nworld\"", "en-US", "polished"),
            Triple("```\nformat command new line\n```", "en-US", "polished"),
            Triple("format command bullet point", "en-US", "polished"),
            Triple("Hello\nformat command open parenthesis\nworld", "en-US", "polished"),
            Triple("Hello\nformat command comma\nworld", "en-US", "light"))
            .forEach { (raw, language, style) -> assertEquals(raw, CleanupRules.local(raw, style, language)) }
        assertEquals("Use 5, no, 7 mg", CleanupRules.local("Use 5, no, 7 mg", "polished", "en-US"))
    }

    @Test fun invalidOutputAndUnsupportedStylesNeverQualifyForAutomaticInsertion() {
        listOf("", " \t\n", "hello\u0000", "x".repeat(65_537)).forEach { unsafe("hello", it) }
        unsafe("hello", "Hello.", "unknown")
    }

    @Test fun polishedCannotDeleteContrastingRecipientClause() {
        unsafe("Tell Jane not to call Michael but call Jared", "Tell Jane not to call Jared.")
    }

    @Test fun polishedCannotDeleteFailToAndReverseNegatedAction() {
        unsafe("Please don’t fail to cancel the transfer", "Don’t cancel the transfer.")
    }

    @Test fun polishedAmbiguousCjkDeletionsNeedReview() {
        unsafe("あの資料を送って", "資料を送って。", language = "ja-JP")
        unsafe("那个文件请发送", "文件请发送。", language = "zh-CN")
    }

    @Test fun polishedPreservesNarrowFillerAndRepeatedArticleAcceptance() {
        listOf("uh please send the file" to "Please send the file.",
            "um, please send the file" to "Please send the file.",
            "send the the file" to "Send the file.",
            "um please send the the file" to "Please send the file.")
            .forEach { (raw, candidate) -> assertTrue(CleanupRules.assess(raw, candidate, "polished", "en-US").acceptableForAuto) }
    }

    @Test fun polishedComposesLeadingFillerAndExplicitPunctuationEnglish() {
        assertTrue(CleanupRules.assess("um please send it\nformat command period",
            "Please send it.", "polished", "en-US").acceptableForAuto)
    }

    @Test fun polishedComposesLeadingFillerAndExplicitPunctuationJapanese() {
        assertTrue(CleanupRules.assess("えー、資料を送って\n書式コマンド 句点",
            "資料を送って。", "polished", "ja-JP").acceptableForAuto)
    }

    @Test fun polishedComposesLeadingFillerAndExplicitPunctuationMandarin() {
        assertTrue(CleanupRules.assess("嗯，请发送文件\n格式命令 句号",
            "请发送文件。", "polished", "zh-CN").acceptableForAuto)
    }

    @Test fun polishedMustNotCollapseCapitalizedRecipientNames() {
        unsafe("Please send this to An An tomorrow.", "Please send this to An tomorrow.")
    }

    @Test fun polishedMustNotCollapseCapitalizedBandNames() {
        unsafe("Listen to The The song.", "Listen to The song.")
    }

    @Test fun polishedMustNotDeleteRepeatedDictatedLetters() {
        unsafe("Please type a a on the label.", "Please type a on the label.")
    }

    @Test fun blankLineAfterBulletDirectivePreservesRawInsteadOfEmptyItem() {
        listOf("format command bullet point\n\napples" to "en-US",
            "書式コマンド 箇条書き\n\nりんご" to "ja-JP",
            "格式命令 项目符号\n\n苹果" to "zh-CN")
            .forEach { (raw, language) -> assertEquals(raw, CleanupRules.local(raw, "polished", language)) }
    }

    private fun unsafe(raw: String, candidate: String, style: String = "polished", language: String = "en-US") {
        val result = CleanupRules.assess(raw, candidate, style, language)
        assertFalse("Unsafe output accepted: $raw => $candidate", result.acceptableForAuto)
        assertTrue("Rejected output needs an actionable reason", result.reasons.isNotEmpty())
    }
    private val languages = listOf("en-US", "ja-JP", "zh-CN")
}
