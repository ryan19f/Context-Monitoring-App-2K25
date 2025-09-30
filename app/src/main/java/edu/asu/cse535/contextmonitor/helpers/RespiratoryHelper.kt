package edu.asu.cse535.contextmonitor.helpers

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

object RespiratoryHelper {
    /**
     * Computes respiratory rate (breaths per minute) from accelerometer magnitude changes.
     * Adapted from your helper with type tweaks and minor safety checks.
     */
    fun computeRespRateFromAccel(x: FloatArray, y: FloatArray, z: FloatArray, tNanos: LongArray): Int {
        if (x.isEmpty() || y.isEmpty() || z.isEmpty()) return 0
        val n = minOf(x.size, y.size, z.size)
        var prev = 10f
        var k = 0
        for (i in 1 until n) {
            val cur = sqrt(
                z[i].toDouble().pow(2.0) +
                        x[i].toDouble().pow(2.0) +
                        y[i].toDouble().pow(2.0)
            ).toFloat()
            if (abs(prev - cur) > 0.15f) {
                k++
            }
            prev = cur
        }
        val ret = k.toDouble() / 45.0
        return (ret * 30.0).toInt()
    }
}