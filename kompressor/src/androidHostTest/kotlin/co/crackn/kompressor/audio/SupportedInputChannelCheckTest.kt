package co.crackn.kompressor.audio

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

/**
 * Host-side coverage for [checkSupportedInputChannelCount] — the upfront rejection of
 * multichannel (≥ 3 channel) inputs that Media3 1.10's built-in channel mixer cannot
 * handle. Extracting the rule to a pure function avoids the need for a 5.1 device fixture
 * and lets the regression gate run on every PR host CI job.
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
    fun nullChannelCount_isAccepted() {
        // A probe failure shouldn't block the pipeline — Media3 will surface its own error
        // if the file is genuinely unreadable.
        checkSupportedInputChannelCount(null) // does not throw
    }

    @Test
    fun threeChannelSource_rejectedWithTypedError() {
        val err = shouldThrow<AudioCompressionError.UnsupportedConfiguration> {
            checkSupportedInputChannelCount(3)
        }
        err.details shouldContain "3 channels"
    }

    @Test
    fun sixChannelSource_rejectedWithTypedError() {
        val err = shouldThrow<AudioCompressionError.UnsupportedConfiguration> {
            checkSupportedInputChannelCount(6) // 5.1
        }
        err.details shouldContain "6 channels"
    }

    @Test
    fun eightChannelSource_rejectedWithTypedError() {
        val err = shouldThrow<AudioCompressionError.UnsupportedConfiguration> {
            checkSupportedInputChannelCount(8) // 7.1
        }
        err.details shouldContain "8 channels"
    }
}
