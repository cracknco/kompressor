package co.crackn.kompressor

import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Extracts the CCW rotation angle (in whole degrees, normalised to `[0, 360)`)
 * encoded in the `a` and `b` components of a `CGAffineTransform`
 * (`AVAssetTrack.preferredTransform`).
 *
 * A pure rotation matrix has `a = cos(θ)` and `b = sin(θ)`, so `atan2(b, a)`
 * recovers the angle. Video tracks in the wild almost always use a multiple of
 * 90°, but since this is a video rotation — not a camera-sensor one — callers
 * shouldn't rely on that; any integer in `[0, 360)` is a valid result (e.g. a
 * slightly skewed export could yield `89` or `91`).
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
