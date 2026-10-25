package com.kiva.infrastructure.media

import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/**
 * The blurhash of a photo (blurha.sh): a short text the frontend turns into a blurred placeholder while the photo
 * loads. Our own small implementation of the published algorithm, so no library is needed; its tests pin the encoding.
 * Fed the 32-pixel-wide version of the photo, which is plenty: a blurhash keeps only a few colors anyway.
 */
object Blurhash {

    const val COMPONENTS_X = 4
    const val COMPONENTS_Y = 3

    private const val CHARACTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"

    fun encode(image: BufferedImage, componentsX: Int = COMPONENTS_X, componentsY: Int = COMPONENTS_Y): String {
        require(componentsX in 1..9 && componentsY in 1..9) { "Blurhash components are 1 to 9, were $componentsX x $componentsY" }
        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        image.getRGB(0, 0, width, height, pixels, 0, width)
        val linear = Array(3) { channel -> DoubleArray(pixels.size) { srgbToLinear((pixels[it] shr (16 - 8 * channel)) and 0xFF) } }

        val factors = Array(componentsX * componentsY) { index ->
            val i = index % componentsX
            val j = index / componentsX
            val normalisation = if (i == 0 && j == 0) 1.0 else 2.0
            val sums = DoubleArray(3)
            for (y in 0 until height) {
                val cosY = cos(PI * j * y / height)
                for (x in 0 until width) {
                    val basis = normalisation * cos(PI * i * x / width) * cosY
                    val p = y * width + x
                    for (c in 0..2) sums[c] += basis * linear[c][p]
                }
            }
            DoubleArray(3) { sums[it] / (width * height) }
        }

        val dc = factors[0]
        val ac = factors.drop(1)
        val hash = StringBuilder()
        hash.append(base83((componentsX - 1) + (componentsY - 1) * 9, 1))
        val maximumValue: Double
        if (ac.isNotEmpty()) {
            val actualMaximum = ac.maxOf { factor -> factor.maxOf { abs(it) } }
            val quantisedMaximum = floor(max(0.0, min(82.0, floor(actualMaximum * 166 - 0.5)))).toInt()
            maximumValue = (quantisedMaximum + 1) / 166.0
            hash.append(base83(quantisedMaximum, 1))
        } else {
            maximumValue = 1.0
            hash.append(base83(0, 1))
        }
        hash.append(base83(encodeDc(dc), 4))
        ac.forEach { hash.append(base83(encodeAc(it, maximumValue), 2)) }
        return hash.toString()
    }

    private fun encodeDc(value: DoubleArray): Int = (linearToSrgb(value[0]) shl 16) + (linearToSrgb(value[1]) shl 8) + linearToSrgb(value[2])

    private fun encodeAc(value: DoubleArray, maximumValue: Double): Int {
        fun quantise(v: Double) = floor(max(0.0, min(18.0, floor(signPow(v / maximumValue, 0.5) * 9 + 9.5)))).toInt()
        return quantise(value[0]) * 19 * 19 + quantise(value[1]) * 19 + quantise(value[2])
    }

    private fun signPow(value: Double, exponent: Double) = sign(value) * abs(value).pow(exponent)

    private fun srgbToLinear(value: Int): Double {
        val v = value / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun linearToSrgb(value: Double): Int {
        val v = value.coerceIn(0.0, 1.0)
        return if (v <= 0.0031308) (v * 12.92 * 255 + 0.5).toInt() else ((1.055 * v.pow(1 / 2.4) - 0.055) * 255 + 0.5).toInt()
    }

    private fun base83(value: Int, length: Int): String = buildString(length) {
        for (i in 1..length) {
            val digit = (value / 83.0.pow(length - i).toInt()) % 83
            append(CHARACTERS[digit])
        }
    }
}
