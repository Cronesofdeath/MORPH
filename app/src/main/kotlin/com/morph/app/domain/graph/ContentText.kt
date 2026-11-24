package com.morph.app.domain.graph

import com.morph.app.domain.model.Format
import com.morph.app.domain.model.Format.*
import java.io.File
import java.nio.charset.Charset

object ContentText {
    fun charset(bytes: ByteArray): Charset {
        if (bytes.size >= 2) {
            if (bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) return Charsets.UTF_16LE
            if (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) return Charsets.UTF_16BE
        }
        return if ('\uFFFD' in bytes.toString(Charsets.UTF_8).dropLast(1)) Charset.forName("windows-1252") else Charsets.UTF_8
    }

    fun decode(bytes: ByteArray) = bytes.toString(charset(bytes)).removePrefix("\uFEFF")
    fun reader(file: File): java.io.BufferedReader {
        val sample = file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            val count = input.read(buffer)
            buffer.copyOf(count.coerceAtLeast(0))
        }
        return file.bufferedReader(charset(sample)).apply {
            mark(1)
            if (read() != 0xfeff) reset()
        }
    }

    fun delimiter(text: String): Char? {
        val lines = text.lineSequence().filter { it.isNotBlank() }.take(12).toList()
        if (lines.size < 2) return null
        return listOf('\t', ',', ';').mapNotNull { sep ->
            val counts = lines.map { line ->
                var quoted = false
                line.count { ch ->
                    if (ch == '"') quoted = !quoted
                    ch == sep && !quoted
                }
            }
            val width = counts.first()
            if (width > 0 && counts.dropLast(1).all { it == width } && counts[1] == width) sep to width else null
        }.maxByOrNull { it.second }?.first
    }

    fun classify(text: String): Format? {
        if (text.isBlank() || text.any { it.code < 32 && it !in "\r\n\t" }) return null
        val trimmed = text.trim()
        if (Regex("""(?is)^(SELECT|CREATE TABLE|INSERT INTO|UPDATE)\s+.*""").matches(trimmed)) return SQL
        if (Regex("""(?m)^#{1,6}\s+\S""").containsMatchIn(trimmed)) return MD
        if (trimmed.startsWith("---\n") || Regex("""(?m)^[-\w.]+:\s+\S""").containsMatchIn(trimmed)) return YAML
        if (Regex("""(?m)^\s*[\w.-]+\s*=""").containsMatchIn(trimmed)) {
            return when {
                Regex("""(?m)^\s*[\w.-]+\s*=\s*(?:"|true\b|false\b|\d)""").containsMatchIn(trimmed) -> TOML
                trimmed.startsWith("[") -> INI
                else -> PROPERTIES
            }
        }
        delimiter(text)?.let { return if (it == '\t') TSV else CSV }
        if (trimmed.length % 4 == 0 && Regex("""[A-Za-z0-9+/]+={0,2}""").matches(trimmed)) {
            val decoded = runCatching { java.util.Base64.getDecoder().decode(trimmed) }.getOrNull()
            if (decoded != null && (trimmed.endsWith("=") || decoded.any { it == 0.toByte() })) return B64
        }
        return TXT
    }
}
