package com.ghostgramlabs.speakalert.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class Radix2FftTest {
    @Test fun `forward agrees with a direct complex DFT for different plan sizes`() {
        for (size in listOf(1, 2, 4, 8, 32, 64)) {
            val random = Random(size)
            val real = FloatArray(size) { random.nextFloat() * 2 - 1 }
            val imaginary = FloatArray(size) { random.nextFloat() * 2 - 1 }
            val originalReal = real.copyOf()
            val originalImaginary = imaginary.copyOf()
            Radix2Fft(size).forward(real, imaginary)
            for (bin in 0 until size) {
                var expectedReal = 0.0
                var expectedImaginary = 0.0
                for (sample in 0 until size) {
                    val angle = -2.0 * PI * bin * sample / size
                    expectedReal += originalReal[sample] * cos(angle) - originalImaginary[sample] * sin(angle)
                    expectedImaginary += originalReal[sample] * sin(angle) + originalImaginary[sample] * cos(angle)
                }
                assertEquals(expectedReal, real[bin].toDouble(), 0.00003 * size)
                assertEquals(expectedImaginary, imaginary[bin].toDouble(), 0.00003 * size)
            }
        }
    }

    @Test fun `voice sized complex signal survives forward and inverse without touching array tails`() {
        val random = Random(9)
        val real = FloatArray(1027) { random.nextFloat() * 100 - 50 }
        val imaginary = FloatArray(1027) { random.nextFloat() * 100 - 50 }
        val originalReal = real.copyOf()
        val originalImaginary = imaginary.copyOf()
        val plan = Radix2Fft(1024)
        plan.forward(real, imaginary)
        plan.inverse(real, imaginary)
        for (i in 0 until 1024) {
            assertEquals(originalReal[i], real[i], 0.0001f)
            assertEquals(originalImaginary[i], imaginary[i], 0.0001f)
        }
        for (i in 1024 until real.size) {
            assertEquals(originalReal[i], real[i], 0f)
            assertEquals(originalImaginary[i], imaginary[i], 0f)
        }
    }

    @Test fun `one immutable plan handles independent simultaneous transforms`() {
        val plan = Radix2Fft(1024)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 12).map { seed -> pool.submit<Boolean> {
                val random = Random(seed)
                val real = FloatArray(1024) { random.nextFloat() }
                val expected = real.copyOf()
                val imaginary = FloatArray(1024)
                repeat(4) { plan.forward(real, imaginary); plan.inverse(real, imaginary) }
                real.indices.all { kotlin.math.abs(real[it] - expected[it]) < 0.00001f && kotlin.math.abs(imaginary[it]) < 0.00001f }
            } }
            tasks.forEach { assertTrue(it.get()) }
        } finally { pool.shutdownNow() }
    }

    @Test fun `invalid sizes and aliased or short arrays are rejected`() {
        for (size in listOf(0, -1, 3, 12)) {
            assertThrows(IllegalArgumentException::class.java) { Radix2Fft(size) }
        }
        val plan = Radix2Fft(8)
        val aliased = FloatArray(8)
        assertThrows(IllegalArgumentException::class.java) { plan.forward(aliased, aliased) }
        assertThrows(IllegalArgumentException::class.java) { plan.inverse(FloatArray(7), FloatArray(8)) }
    }
}
