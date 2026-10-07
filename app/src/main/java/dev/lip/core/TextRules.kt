package dev.lip.core

import java.text.Normalizer

object TextRules {
    fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFC)
        .replace(Regex("[\\t ]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .trim()
}
