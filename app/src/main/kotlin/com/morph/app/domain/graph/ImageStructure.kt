package com.morph.app.domain.graph

import com.morph.app.domain.model.Format
import java.io.File
import java.io.RandomAccessFile

object ImageStructure {
    fun animated(file: File, format: Format): Boolean {
        if (format !in setOf(Format.GIF,Format.PNG,Format.WEBP)) return false
        return runCatching { RandomAccessFile(file,"r").use { io ->
            fun skip(n: Long) {
                require(n >= 0 && n <= io.length() - io.filePointer)
                io.seek(io.filePointer+n)
            }
            fun blocks() {
                while (true) {
                    val length = io.readUnsignedByte()
                    if (length == 0) return
                    skip(length.toLong())
                }
            }
            when (format) {
                Format.GIF -> {
                    io.seek(10)
                    val packed = io.readUnsignedByte()
                    skip(2)
                    if (packed and 128 != 0) skip(3L shl ((packed and 7)+1))
                    var images = 0
                    while (io.filePointer < io.length()) {
                        when(io.readUnsignedByte()) {
                            0x21 -> { skip(1); blocks() }
                            0x2c -> {
                                skip(8)
                                val flags = io.readUnsignedByte()
                                if (flags and 128 != 0) skip(3L shl ((flags and 7)+1))
                                skip(1)
                                blocks()
                                if (++images > 1) return@use true
                            }
                            else -> break
                        }
                    }
                    false
                }
                Format.PNG -> {
                    io.seek(8)
                    while (io.filePointer+8 <= io.length()) {
                        val n = io.readInt().toLong() and 0xffffffffL
                        when (io.readInt()) {
                            0x6163544c -> return@use n == 8L && io.readInt() > 1
                            0x49444154 -> break
                            else -> skip(n+4)
                        }
                    }
                    false
                }
                else -> {
                    io.seek(12)
                    io.readInt() == 0x56503858 && Integer.reverseBytes(io.readInt()) == 10 && io.readUnsignedByte() and 2 != 0
                }
            }
        } }.getOrDefault(false)
    }

    fun multipage(file: File, format: Format): Boolean {
        if (format != Format.TIFF) return false
        return runCatching { RandomAccessFile(file,"r").use { io ->
            val little = io.readUnsignedShort() == 0x4949
            fun int() = io.readInt().let { if (little) Integer.reverseBytes(it) else it }.toLong() and 0xffffffffL
            io.seek(4)
            io.seek(int())
            val count = io.readShort().let { if (little) java.lang.Short.reverseBytes(it) else it }.toInt() and 65535
            io.seek(io.filePointer+count*12L)
            int() in 8 until io.length()
        } }.getOrDefault(false)
    }
}
