package com.morph.app.engine

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Decode RFC 4648 input with optional ASCII whitespace and omitted final padding. */
internal suspend fun decodeBase64(input: InputStream, output: OutputStream) {
    val context = currentCoroutineContext()
    val buffer = ByteArray(65_536)
    var bits = 0
    var count = 0
    var padding = 0
    while (true) {
        context.ensureActive()
        val length = input.read(buffer)
        if (length < 0) break
        for (index in 0 until length) {
            val char = buffer[index].toInt().toChar()
            if (char == ' ' || char == '\t' || char == '\r' || char == '\n') continue
            if (char == '=') {
                val expected = 4 - count
                padding++
                require(count >= 2 && padding <= expected) { "Invalid Base64 padding" }
                continue
            }
            require(padding == 0) { "Unexpected data after Base64 padding" }
            val value = when (char) {
                in 'A'..'Z' -> char - 'A'
                in 'a'..'z' -> char - 'a' + 26
                in '0'..'9' -> char - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> error("Invalid character in Base64 input")
            }
            bits = (bits shl 6) or value
            if (++count == 4) {
                output.write(bits ushr 16)
                output.write(bits ushr 8)
                output.write(bits)
                count = 0
                bits = 0
            }
        }
    }
    require(count != 1 && (padding == 0 || count + padding == 4)) { "Incomplete Base64 group" }
    if (count == 2) {
        require(bits and 0x0F == 0) { "Invalid Base64 trailing bits" }
        output.write(bits ushr 4)
    } else if (count == 3) {
        require(bits and 0x03 == 0) { "Invalid Base64 trailing bits" }
        output.write(bits ushr 10)
        output.write(bits ushr 2)
    }
}
