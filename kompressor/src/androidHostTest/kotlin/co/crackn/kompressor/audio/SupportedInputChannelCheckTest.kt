package co.crackn.kompressor.audio

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

/**
 * Host-side coverage for [checkSupportedInputChannelCount] — the upfront rejection of inputs
 * whose channel count exceeds what the mixer can handle (currently 7.1 / 8 channels). Pure
 * function so the regression gate runs on every PR host CI job without a multichannel device
 * fixture.
 */
class SupportedInputChannelCheckTest {

    @Test
    fun monoSource_isAccepted() {
        checkSupportedInputChannelCount(1) // does not throw
    }

    @Test
    fun stereoSource_isAccepted() {
        checkSupportedInputChannelCount(2) // does not throw
    }

    @Test
    fun threeChannelSource_isAccepted() {
        // 3-channel sources (e.g. LCR) are within the supported envelope; Media3's
        // constant-power matrix handles them down to mono / stereo.
        checkSupportedInputChannelCount(3) // does not throw
    }

    @Test
    fun fiveOneSource_isAccepted() {
        checkSupportedInputChannelCount(6) // 5.1 — does not throw
    }

    @Test
    fun sevenOneSource_isAccepted() {
        checkSupportedInputChannelCount(8) // 7.1 — does not throw
    }

    @Test
    fun nullChannelCount_isAccepted() {
        // A probe failure shouldn't block the pipeline — Media3 will surface its own error
        // if the file is genuinely unreadable.
        checkSupportedInputChannelCount(null) // does not throw
    }

    @Test
    fun sevenChannelSource_rejectedWithTypedError() {
        // 7-channel sources have neither a Media3 default matrix (createForConstantPower covers
        // 1..6 → {1,2}) nor a hand-rolled `surroundDownmixMatrix` (which is 8-channel only),
        // so they're rejected upfront alongside 9+-channel inputs.
        val err = shouldThrow<AudioCompressionError.UnsupportedConfiguration> {
            checkSupportedInputChannelCount(7)
        }
        err.details shouldContain "7 channels"
    }

    @Test
    fun nineChannelSource_rejectedWithTypedError() {
        // 9.1.x and beyond are not in our supported envelope (no AAC channel layout fits and
        // the mixer has no defined coefficients). Reject upfront with a typed error.
        val err = shouldThrow<AudioCompressionError.UnsupportedConfiguration> {
            checkSupportedInputChannelCount(9)
        }
        err.details shouldContain "9 channels"
    }

    @Test
    fun sixteenChannelSource_rejectedWithTypedError() {
        val err = shouldThrow<AudioCompressionError.UnsupportedConfiguration> {
            checkSupportedInputChannelCount(16)
        }
        err.details shouldContain "16 channels"
    }
}
