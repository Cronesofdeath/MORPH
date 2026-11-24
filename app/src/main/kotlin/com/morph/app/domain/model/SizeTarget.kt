package com.morph.app.domain.model

import java.math.RoundingMode
import java.math.BigDecimal
import kotlin.math.ceil

enum class SizeUnit { B, KB, MB, GB }

object SizeUnitCalculator {
    private val decimal = Regex("""[+]?(?:[0-9]{1,19}(?:\.[0-9]{0,12})?|\.[0-9]{1,12})""")

    fun parse(value: String): BigDecimal? {
        val trimmed = value.trim()
        return if (decimal.matches(trimmed)) trimmed.toBigDecimalOrNull() else null
    }

    fun bytes(value: String, unit: SizeUnit, binary: Boolean): Long? = runCatching {
        val number = parse(value) ?: return null
        val multiplier = (if (binary) 1024 else 1000).toBigDecimal().pow(unit.ordinal)
        (number * multiplier).setScale(0, RoundingMode.DOWN).longValueExact().takeIf { it > 0 }
    }.getOrNull()

    fun allocate(total: Long, weights: List<Long>): List<Long> {
        require(weights.isNotEmpty() && total >= weights.size)
        val normalized = weights.map { it.coerceAtLeast(1).toBigInteger() }
        val denominator = normalized.reduce { sum, weight -> sum + weight }
        val available = (total - weights.size).toBigInteger()
        val fractions = normalized.map { (available * it).divideAndRemainder(denominator) }
        val shares = fractions.map { 1 + it[0].longValueExact() }.toMutableList()
        val spare = (total - shares.sum()).toInt()
        fractions.indices.sortedByDescending { fractions[it][1] }.take(spare).forEach { shares[it]++ }
        return shares
    }
}

data class SizeTarget(val bytes: Long, val exact: Boolean = false) {
    init { require(bytes > 0) }
}

data class ProOptions(
    val filename: String = "{name}_morph.{ext}",
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    val dpi: Int = 150,
    val keepMetadata: Boolean = false,
    val background: Int = -1,
    val videoCodec: String = "",
    val fps: Int? = null,
    val mute: Boolean = false,
    val trimStart: Double? = null,
    val trimEnd: Double? = null,
    val fastStart: Boolean = true,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val normalize: Boolean = false,
    val sheet: String = "",
    val delimiter: Char = ',',
    val pageSize: String = "A4",
    val landscape: Boolean = false,
    val margin: Int = 36,
    val pageRange: String = "",
    val compression: Int = 9,
    val password: String = "",
    val sha256: Boolean = true,
    val inputPassword: String = ""
)

fun ProOptions.permitsCorrection() = copy(filename = ProOptions().filename, sha256 = true) == ProOptions()

class TargetTooSmall(val minimum: Long) :
    IllegalArgumentException("Try a budget of ${java.text.NumberFormat.getIntegerInstance().format(minimum)} bytes with these settings.")

object TargetSizeConvergence {
    suspend fun highestQuality(limit: Long, low: Int = 5, high: Int = 100, attempt: suspend (Int) -> Long): Int? {
        var lower = low
        var upper = high
        var winner: Int? = null
        while (lower <= upper) {
            val quality = (lower + upper) / 2
            if (attempt(quality) <= limit) {
                winner = quality
                lower = quality + 1
            } else {
                upper = quality - 1
            }
        }
        return winner
    }

    data class BitBudget(val video: Int, val audio: Int) {
        val total: Int get() = video + audio
    }

    fun videoBudget(bytes: Long, seconds: Double, audio: Boolean): BitBudget {
        require(seconds.isFinite() && seconds > 0)
        val bitrate = ((bytes * 8.0 * .95) / seconds / 1000).toInt().coerceAtMost(100_000)
        val audioRate = when {
            !audio -> 0
            bitrate > 1500 -> 128
            bitrate > 500 -> 96
            bitrate > 180 -> 48
            else -> 24
        }
        if (bitrate - audioRate < 12) throw TargetTooSmall(ceil((12 + audioRate) * 1000 * seconds / (8 * .95)).toLong())
        return BitBudget(bitrate - audioRate, audioRate)
    }
}
