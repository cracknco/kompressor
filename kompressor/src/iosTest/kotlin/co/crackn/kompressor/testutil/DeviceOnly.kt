package co.crackn.kompressor.testutil

import platform.Foundation.NSProcessInfo

/**
 * Returns `true` when running inside the iOS Simulator rather than on physical hardware.
 *
 * Detection uses the `SIMULATOR_DEVICE_NAME` environment variable that Xcode injects into
 * every simulator process. Physical devices never have this variable set.
 */
fun isSimulator(): Boolean =
    NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null

/**
 * Skips the calling test body when running on the iOS Simulator by throwing
 * [DeviceOnlyTestSkipped]. Use at the top of any test that requires real hardware
 * (e.g. HDR10 HEVC encoding, multi-channel AAC).
 *
 * On the simulator the test is reported as passed (with a log message) rather than failed,
 * because the feature under test is known to be unsupported on the sim — a failure would be
 * noise, not signal.
 */
fun assumeDevice(reason: String) {
    if (isSimulator()) {
        println("SKIPPED (simulator): $reason")
        throw DeviceOnlyTestSkipped(reason)
    }
}

/**
 * Sentinel exception thrown by [assumeDevice] to abort the test body on simulator.
 *
 * Kotlin/Native's test runner treats any thrown exception as a test failure. To avoid
 * false negatives, callers should wrap their test body with [runDeviceOnly] instead of
 * calling [assumeDevice] directly — [runDeviceOnly] catches this exception and logs the
 * skip cleanly.
 */
class DeviceOnlyTestSkipped(reason: String) : RuntimeException("Device-only test skipped: $reason")

/**
 * Runs [block] only on physical iOS hardware. On the simulator the test passes silently
 * with a log line explaining why it was skipped.
 *
 * ```kotlin
 * @Test
 * fun hdr10RoundTrip() = runDeviceOnly("HDR10 HEVC requires A10+ hardware") {
 *     runTest { /* … */ }
 * }
 * ```
 */
inline fun runDeviceOnly(reason: String, block: () -> Unit) {
    if (isSimulator()) {
        println("SKIPPED (simulator): $reason")
        return
    }
    block()
}
