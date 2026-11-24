package com.morph.app.engine

import java.io.DataInputStream
import java.io.File
import java.util.zip.CRC32

internal object PngIntegrity {
    fun verify(file: File) = DataInputStream(file.inputStream().buffered()).use { input ->
        require(input.readLong() == 0x89504e470d0a1a0auL.toLong()) { "Invalid PNG signature." }
        var header = false
        var data = false
        val buffer = ByteArray(65536)
        while (true) {
            var remaining = input.readInt()
            require(remaining >= 0)
            val type = ByteArray(4).also(input::readFully)
            val name = type.toString(Charsets.US_ASCII)
            require(header || (name == "IHDR" && remaining == 13))
            if (name == "IHDR") header = true
            if (name == "IDAT") data = true
            if (name == "IEND") require(remaining == 0 && data)
            val crc = CRC32().apply { update(type) }
            while(remaining>0) {
                val count = minOf(buffer.size,remaining)
                input.readFully(buffer,0,count)
                crc.update(buffer,0,count)
                remaining -= count
            }
            require(input.readInt() == crc.value.toInt()) { "The output PNG failed its chunk integrity check." }
            if (name == "IEND") { require(input.read() == -1); break }
        }
    }
}
