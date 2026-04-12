package co.crackn.kompressor

import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Extracts the CCW rotation angle (in whole degrees, 0 / 90 / 180 / 270) encoded
 * in the `a` and `b` components of a `CGAffineTransform` (`AVAssetTrack.preferredTransform`).
 *
 * A pure rotation matrix has `a = cos(θ)` and `b = sin(θ)`, so `atan2(b, a)`
 * recovers the angle. The result is normalised into `[0, 360)`.
 *
 * Split out so we can host-test it without constructing `CGAffineTransform` —
 * the platform call site in [IosKompressor] reads `a, b` from `preferredTransform`
 * and forwards here.
 */
internal fun computeRotationDegrees(a: Double, b: Double): Int {
    val radians = atan2(b, a)
    val degrees = (radians * DEGREES_PER_RADIAN).roundToInt()
    return ((degrees % FULL_CIRCLE) + FULL_CIRCLE) % FULL_CIRCLE
}

private const val FULL_CIRCLE = 360
private const val DEGREES_PER_RADIAN = 180.0 / kotlin.math.PI
