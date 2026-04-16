/*
 * Copyright 2025 crackn.co
 * SPDX-License-Identifier: Apache-2.0
 */

package co.crackn.kompressor.testutil

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Colour-science utilities scoped to the HDR10 round-trip test: PQ inverse EOTF, BT.2020 →
 * XYZ → L*a*b*, and CIEDE2000 ΔE00. Kept separate from [Hdr10Mp4Generator] so the generator
 * pulls in only the *encode* pipeline and the test pulls in only the *decode + measurement*
 * pipeline.
 *
 * All math is pure arithmetic (no Android APIs), so it runs host-side too if a unit test
 * wants to pin the canonical colour table on the JVM.
 */
object Hdr10ColorMath {

    /** A CIELAB tristimulus (D65) used as the distance metric for ΔE00. */
    data class Lab(val l: Double, val a: Double, val b: Double)

    /** Narrow-range 10-bit Y'CbCr → linear-light BT.2020 RGB (cd/m² normalised to 10 000 peak). */
    fun yuv10ToBt2020Linear(y10: Int, cb10: Int, cr10: Int): DoubleArray {
        val yP = (y10 - Y_OFFSET) / Y_SCALE
        val cbP = (cb10 - C_OFFSET) / C_SCALE
        val crP = (cr10 - C_OFFSET) / C_SCALE
        val rP = yP + BT2020_CR_DENOM * crP
        val bP = yP + BT2020_CB_DENOM * cbP
        val gP = (yP - BT2020_YR * rP - BT2020_YB * bP) / BT2020_YG
        return doubleArrayOf(pqEotf(rP), pqEotf(gP), pqEotf(bP))
    }

    /** Convert linear BT.2020 RGB (10 000 cd/m² peak) to CIE XYZ (D65, 10 000-normalised). */
    fun bt2020LinearToXyz(rgb: DoubleArray): DoubleArray {
        require(rgb.size == 3) { "expected 3 components, got ${rgb.size}" }
        val r = rgb[0]
        val g = rgb[1]
        val b = rgb[2]
        val x = BT2020_TO_XYZ_R0 * r + BT2020_TO_XYZ_G0 * g + BT2020_TO_XYZ_B0 * b
        val y = BT2020_TO_XYZ_R1 * r + BT2020_TO_XYZ_G1 * g + BT2020_TO_XYZ_B1 * b
        val z = BT2020_TO_XYZ_R2 * r + BT2020_TO_XYZ_G2 * g + BT2020_TO_XYZ_B2 * b
        return doubleArrayOf(x, y, z)
    }

    /** Convert XYZ (D65, Y normalised to 1.0 at white) to CIELAB. */
    fun xyzToLab(xyz: DoubleArray): Lab {
        require(xyz.size == 3) { "expected 3 components, got ${xyz.size}" }
        val fx = labF(xyz[0] / D65_XN)
        val fy = labF(xyz[1] / D65_YN)
        val fz = labF(xyz[2] / D65_ZN)
        return Lab(
            l = LAB_L_SCALE * fy - LAB_L_OFFSET,
            a = LAB_A_SCALE * (fx - fy),
            b = LAB_B_SCALE * (fy - fz),
        )
    }

    /**
     * CIEDE2000 ΔE00 between two CIELAB colours. Formula per Sharma, Wu & Dalal (2005):
     * "The CIEDE2000 Color-Difference Formula: Implementation Notes, Supplementary Test Data,
     * and Mathematical Observations."
     */
    @Suppress("LongMethod") // CIEDE2000 is intrinsically a long formula; splitting obscures it.
    fun deltaE2000(lab1: Lab, lab2: Lab): Double {
        val lBar = (lab1.l + lab2.l) / 2.0
        val c1 = sqrt(lab1.a * lab1.a + lab1.b * lab1.b)
        val c2 = sqrt(lab2.a * lab2.a + lab2.b * lab2.b)
        val cBar = (c1 + c2) / 2.0
        val g = 0.5 * (1.0 - sqrt(cBar.pow(DE_CBAR_POWER) /
            (cBar.pow(DE_CBAR_POWER) + DE_G_OFFSET.pow(DE_CBAR_POWER))))
        val a1p = lab1.a * (1.0 + g)
        val a2p = lab2.a * (1.0 + g)
        val c1p = sqrt(a1p * a1p + lab1.b * lab1.b)
        val c2p = sqrt(a2p * a2p + lab2.b * lab2.b)
        val cBarP = (c1p + c2p) / 2.0
        val h1p = atan2Deg(lab1.b, a1p)
        val h2p = atan2Deg(lab2.b, a2p)
        val dhp = when {
            c1p == 0.0 || c2p == 0.0 -> 0.0
            abs(h1p - h2p) <= HALF_CIRCLE_DEG -> h2p - h1p
            h2p <= h1p -> h2p - h1p + FULL_CIRCLE_DEG
            else -> h2p - h1p - FULL_CIRCLE_DEG
        }
        val hBarP = when {
            c1p == 0.0 || c2p == 0.0 -> h1p + h2p
            abs(h1p - h2p) <= HALF_CIRCLE_DEG -> (h1p + h2p) / 2.0
            h1p + h2p < FULL_CIRCLE_DEG -> (h1p + h2p + FULL_CIRCLE_DEG) / 2.0
            else -> (h1p + h2p - FULL_CIRCLE_DEG) / 2.0
        }
        val t = 1.0 - DE_T_C1 * cos(deg2rad(hBarP - DE_T_H1)) +
            DE_T_C2 * cos(deg2rad(2 * hBarP)) +
            DE_T_C3 * cos(deg2rad(3 * hBarP + DE_T_H3)) -
            DE_T_C4 * cos(deg2rad(4 * hBarP - DE_T_H4))
        val dLp = lab2.l - lab1.l
        val dCp = c2p - c1p
        val dHp = 2.0 * sqrt(c1p * c2p) * sin(deg2rad(dhp / 2.0))
        val lBarMinus50Sq = (lBar - DE_L_PIVOT).pow(2)
        val sL = 1.0 + (DE_SL_NUM * lBarMinus50Sq) / sqrt(DE_SL_DENOM_OFF + lBarMinus50Sq)
        val sC = 1.0 + DE_SC_SLOPE * cBarP
        val sH = 1.0 + DE_SH_SLOPE * cBarP * t
        val dTheta = DE_ROT_DEG * exp(-((hBarP - DE_ROT_H0) / DE_ROT_SIGMA).pow(2))
        val rC = 2.0 * sqrt(cBarP.pow(DE_CBAR_POWER) /
            (cBarP.pow(DE_CBAR_POWER) + DE_G_OFFSET.pow(DE_CBAR_POWER)))
        val rT = -sin(deg2rad(2.0 * dTheta)) * rC
        return sqrt(
            (dLp / sL).pow(2) + (dCp / sC).pow(2) + (dHp / sH).pow(2) +
                rT * (dCp / sC) * (dHp / sH),
        )
    }

    /** Convenience: Y'CbCr (10-bit narrow range) → CIELAB, collapsing the whole decode chain. */
    fun yuv10ToLab(y10: Int, cb10: Int, cr10: Int): Lab =
        xyzToLab(bt2020LinearToXyz(yuv10ToBt2020Linear(y10, cb10, cr10)))

    private fun labF(t: Double): Double =
        if (t > LAB_DELTA_CUBED) t.pow(1.0 / 3.0) else (t / LAB_KAPPA) + LAB_F_OFFSET

    private fun pqEotf(ep: Double): Double {
        val e = ep.coerceIn(0.0, 1.0)
        val eM2 = e.pow(1.0 / PQ_M2)
        val num = (eM2 - PQ_C1).coerceAtLeast(0.0)
        val den = (PQ_C2 - PQ_C3 * eM2).coerceAtLeast(DENOMINATOR_FLOOR)
        return (num / den).pow(1.0 / PQ_M1)
    }

    private fun atan2Deg(y: Double, x: Double): Double {
        val deg = Math.toDegrees(atan2(y, x))
        return if (deg < 0.0) deg + FULL_CIRCLE_DEG else deg
    }

    private fun deg2rad(d: Double) = d * (PI / HALF_CIRCLE_DEG)

    // --- BT.2020 narrow-range 10-bit quantiser (must mirror Hdr10Mp4Generator) ---------
    private const val Y_SCALE = 876.0
    private const val Y_OFFSET = 64.0
    private const val C_SCALE = 896.0
    private const val C_OFFSET = 512.0
    private const val BT2020_YR = 0.2627
    private const val BT2020_YG = 0.6780
    private const val BT2020_YB = 0.0593
    private const val BT2020_CB_DENOM = 1.8814
    private const val BT2020_CR_DENOM = 1.4746

    // --- BT.2020 RGB → XYZ matrix (D65, 10 000-normalised). ITU-R BT.2020 Annex 1 -----
    private const val BT2020_TO_XYZ_R0 = 0.636958
    private const val BT2020_TO_XYZ_G0 = 0.144617
    private const val BT2020_TO_XYZ_B0 = 0.168881
    private const val BT2020_TO_XYZ_R1 = 0.262700
    private const val BT2020_TO_XYZ_G1 = 0.677998
    private const val BT2020_TO_XYZ_B1 = 0.059302
    private const val BT2020_TO_XYZ_R2 = 0.000000
    private const val BT2020_TO_XYZ_G2 = 0.028073
    private const val BT2020_TO_XYZ_B2 = 1.060985

    // --- CIELAB constants --------------------------------------------------------------
    private const val D65_XN = 0.95047
    private const val D65_YN = 1.00000
    private const val D65_ZN = 1.08883
    private const val LAB_DELTA_CUBED = 0.008856 // (6/29)^3
    private const val LAB_KAPPA = 7.787
    private const val LAB_F_OFFSET = 16.0 / 116.0
    private const val LAB_L_SCALE = 116.0
    private const val LAB_L_OFFSET = 16.0
    private const val LAB_A_SCALE = 500.0
    private const val LAB_B_SCALE = 200.0

    // --- PQ inverse EOTF (ST.2084 / BT.2100) -------------------------------------------
    private const val PQ_M1 = 0.1593017578125
    private const val PQ_M2 = 78.84375
    private const val PQ_C1 = 0.8359375
    private const val PQ_C2 = 18.8515625
    private const val PQ_C3 = 18.6875
    private const val DENOMINATOR_FLOOR = 1e-12

    // --- CIEDE2000 constants (Sharma et al. 2005) --------------------------------------
    private const val DE_CBAR_POWER = 7.0
    private const val DE_G_OFFSET = 25.0
    private const val DE_L_PIVOT = 50.0
    private const val DE_SL_NUM = 0.015
    private const val DE_SL_DENOM_OFF = 20.0
    private const val DE_SC_SLOPE = 0.045
    private const val DE_SH_SLOPE = 0.015
    private const val DE_T_C1 = 0.17
    private const val DE_T_C2 = 0.24
    private const val DE_T_C3 = 0.32
    private const val DE_T_C4 = 0.20
    private const val DE_T_H1 = 30.0
    private const val DE_T_H3 = 6.0
    private const val DE_T_H4 = 63.0
    private const val DE_ROT_DEG = 30.0
    private const val DE_ROT_H0 = 275.0
    private const val DE_ROT_SIGMA = 25.0
    private const val HALF_CIRCLE_DEG = 180.0
    private const val FULL_CIRCLE_DEG = 360.0
}
